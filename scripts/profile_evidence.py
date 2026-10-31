"""Fail-closed validation of synthetic kernel profiling evidence (stdlib only)."""
import hashlib
import itertools
import json
import math
from pathlib import Path, PurePosixPath
import re


PATTERN = "io.ominicat.benchmarks.*Benchmark.*"
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
HISTORICAL_CASES = [
    {"benchmark": "io.ominicat.benchmarks." + name, "params": {"stages": stages}}
    for name in ("PipelineBenchmark.dispatch", "SessionBenchmark.roundTrip")
    for stages in ("1", "4", "16")
]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    def invalid(value):
        raise ValueError(f"{path.name}: nonfinite JSON number {value}")
    return json.loads(path.read_text(), parse_constant=invalid)


def number(value, name, *, positive=False, integer=False):
    require(type(value) in (int, float) and math.isfinite(value), f"{name}: expected finite number")
    require(value > 0 if positive else value >= 0, f"{name}: invalid numeric range")
    if integer:
        require(type(value) is int, f"{name}: expected integer")
    return value


def text(value, name):
    require(isinstance(value, str) and bool(value.strip()), f"{name}: expected nonempty text")
    return value


def case_key(case):
    require(isinstance(case, dict), "benchmark case must be an object")
    benchmark = text(case.get("benchmark"), "benchmark")
    params = case.get("params", {})
    require(isinstance(params, dict) and all(isinstance(k, str) and isinstance(v, str)
                                           for k, v in params.items()), "benchmark params must be strings")
    return benchmark, tuple(sorted(params.items()))


def parse_jmh_plan(output):
    """Expand JMH -lp's discovered methods and parameter products, not a static suite."""
    methods = []
    current = None
    for line in output.splitlines():
        stripped = line.strip()
        if not stripped or stripped == "Benchmarks:":
            continue
        parameter = re.fullmatch(r'param "([^"]+)" = \{(.+)\}', stripped)
        if parameter:
            require(current is not None, "JMH plan parameter has no benchmark")
            name, values = parameter.groups()
            require(name not in current[1], "duplicate JMH plan parameter")
            current[1][name] = [value.strip() for value in values.split(",")]
            require(all(current[1][name]), "empty JMH parameter value")
        else:
            require(re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", stripped),
                    "unrecognized JMH -lp output")
            current = (stripped, {})
            methods.append(current)
    cases = []
    for method, params in methods:
        for values in itertools.product(*params.values()):
            cases.append({"benchmark": method, "params": dict(zip(params, values))})
    require(cases and len({case_key(case) for case in cases}) == len(cases), "empty or duplicate JMH plan")
    return cases


def validate_source(source):
    require(isinstance(source, dict) and source.get("algorithm") == "SHA-256", "invalid source fingerprint")
    files = source.get("files")
    require(isinstance(files, list) and files, "empty source fingerprint")
    seen = set()
    for entry in files:
        require(isinstance(entry, dict), "invalid source file entry")
        path = text(entry.get("path"), "source path")
        parsed = PurePosixPath(path)
        require(not parsed.is_absolute() and ".." not in parsed.parts and "\\" not in path
                and path not in seen, "unsafe or duplicate source path")
        seen.add(path)
        require(isinstance(entry.get("sha256"), str) and SHA256.fullmatch(entry["sha256"]), "invalid source file SHA-256")
    digest = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    require(source.get("aggregate") == digest, "source fingerprint aggregate mismatch")
    return digest


def prepare_run(folder, mode, artifact):
    require(mode in ("quick", "full"), "unknown profiling mode")
    source = read_json(folder / "source-sha256.json")
    manifest = {
        "schemaVersion": 1, "mode": mode, "benchmarkPattern": PATTERN,
        "benchmarkArtifact": artifact.name,
        "benchmarkArtifactSha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
        "sourceSha256": validate_source(source),
        "expectedCases": parse_jmh_plan((folder / "jmh-plan.txt").read_text()),
        "expectedJmh": {"forks": 2 if mode == "quick" else 3, "warmupIterations": 3 if mode == "quick" else 5,
                        "measurementIterations": 5 if mode == "quick" else 8, "threads": 1},
        "expectedLoad": {"sessions": 16, "stages": 4, "inflightPerSession": 32, "warmupFrames": 100000,
                         "measuredFrames": 25000000 if mode == "quick" else 50000000, "maxHeapBytes": 536870912},
    }
    (folder / "run-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def validate_evidence(folder, *, strict=False, current_source=None, artifact=None):
    manifest_path = folder / "run-manifest.json"
    manifest = read_json(manifest_path) if manifest_path.exists() else None
    require(not strict or manifest is not None, "strict mode requires run-manifest.json")
    source_path = folder / "source-sha256.json"
    source = read_json(source_path) if source_path.exists() else None
    require(source is not None, "source-sha256.json is required")
    source_hash = validate_source(source)
    if current_source is not None:
        require(source == current_source, "runtime sources changed while profiling; rerun against a stable revision")
    if manifest is not None:
        require(isinstance(manifest, dict) and manifest.get("schemaVersion") == 1, "invalid run manifest")
        require(manifest.get("mode") in ("quick", "full"), "invalid run manifest mode")
        require(manifest.get("sourceSha256") == source_hash, "run/source SHA-256 mismatch")
        name = manifest.get("benchmarkArtifact")
        require(isinstance(name, str) and name == Path(name).name and name.endswith(".jar")
                and "/" not in name and "\\" not in name, "invalid benchmark artifact basename")
        jar_hash = manifest.get("benchmarkArtifactSha256")
        require(isinstance(jar_hash, str) and SHA256.fullmatch(jar_hash), "invalid benchmark artifact SHA-256")
        local_artifact = artifact if artifact is not None else folder / name
        if artifact is not None or local_artifact.exists():
            require(local_artifact.is_file() and hashlib.sha256(local_artifact.read_bytes()).hexdigest() == jar_hash,
                    "benchmark artifact SHA-256 mismatch")
        expected = manifest.get("expectedCases")
        require(isinstance(expected, list) and expected, "empty expected JMH cases")
        expected_jmh = manifest.get("expectedJmh")
        expected_load = manifest.get("expectedLoad")
        require(isinstance(expected_jmh, dict) and isinstance(expected_load, dict), "missing run settings")
        for key in ("forks", "warmupIterations", "measurementIterations", "threads"):
            number(expected_jmh.get(key), "manifest " + key, positive=True, integer=True)
        for key in ("sessions", "stages", "inflightPerSession", "measuredFrames", "maxHeapBytes"):
            number(expected_load.get(key), "manifest " + key, positive=True, integer=True)
        number(expected_load.get("warmupFrames"), "manifest warmupFrames", integer=True)
    else:
        # Published pre-manifest runs contain this suite. New profiles always use strict mode.
        expected, expected_jmh, expected_load = HISTORICAL_CASES, None, None
    expected_keys = {case_key(case) for case in expected}
    require(len(expected_keys) == len(expected), "duplicate expected JMH case")
    jmh = read_json(folder / "jmh.json")
    require(isinstance(jmh, list) and jmh, "empty JMH results")
    actual_keys = [case_key(result) for result in jmh]
    require(len(set(actual_keys)) == len(actual_keys), "duplicate JMH result")
    require(set(actual_keys) == expected_keys, "JMH results do not match the complete expected benchmark/parameter matrix")
    versions = set()
    for result in jmh:
        for key in ("forks", "warmupIterations", "measurementIterations", "threads"):
            number(result.get(key), "JMH " + key, positive=True, integer=True)
            if expected_jmh:
                require(result[key] == expected_jmh[key], "JMH run setting mismatch: " + key)
        versions.add(text(result.get("jdkVersion"), "JMH jdkVersion"))
        for metric_name, metric in (("primaryMetric", result.get("primaryMetric")),
                                    ("gc.alloc.rate.norm", result.get("secondaryMetrics", {}).get("gc.alloc.rate.norm"))):
            require(isinstance(metric, dict), "missing JMH metric " + metric_name)
            number(metric.get("score"), metric_name + " score", positive=metric_name == "primaryMetric")
            number(metric.get("scoreError"), metric_name + " scoreError")
            text(metric.get("scoreUnit"), metric_name + " scoreUnit")
            raw = metric.get("rawData")
            require(isinstance(raw, list) and len(raw) == result["forks"], "incomplete JMH forks")
            for fork in raw:
                require(isinstance(fork, list) and len(fork) == result["measurementIterations"], "incomplete JMH iterations")
                for sample in fork:
                    number(sample, metric_name + " raw sample")
    load = read_json(folder / "load.json")
    require(isinstance(load, dict) and load.get("schemaVersion") == 1, "invalid load schema")
    for key in ("recordedAt", "workload", "latencyScope"):
        text(load.get(key), "load " + key)
    for key in ("sessions", "stages", "inflightPerSession", "measuredFrames", "processedIncludingWarmup"):
        number(load.get(key), "load " + key, positive=True, integer=True)
    for key in ("warmupFrames", "rejected"):
        number(load.get(key), "load " + key, integer=True)
    require(load["rejected"] == 0 and load["processedIncludingWarmup"] == load["measuredFrames"] + load["warmupFrames"],
            "load processed/rejected counters do not match the complete workload")
    elapsed = number(load.get("elapsedSeconds"), "load elapsedSeconds", positive=True)
    throughput = number(load.get("throughputFramesPerSecond"), "load throughput", positive=True)
    require(math.isclose(throughput, load["measuredFrames"] / elapsed, rel_tol=1e-6, abs_tol=0.001), "load throughput/counter mismatch")
    latency = load.get("latencyMicros")
    require(isinstance(latency, dict), "missing load latency")
    values = [number(latency.get(key), "load latency " + key) for key in ("p50", "p95", "p99", "max")]
    require(values == sorted(values), "load latency percentiles are not ordered")
    environment = load.get("environment")
    require(isinstance(environment, dict), "missing load environment")
    for key in ("javaVersion", "javaVendor", "os", "arch"):
        text(environment.get(key), "environment " + key)
    for key in ("processors", "maxHeapBytes"):
        number(environment.get(key), "environment " + key, positive=True, integer=True)
    require(versions == {environment["javaVersion"]}, "JMH/load JVM version mismatch")
    if expected_load:
        for key, value in expected_load.items():
            actual = environment.get(key) if key == "maxHeapBytes" else load.get(key)
            require(actual == value, "load run setting mismatch: " + key)
    return jmh, load, source, "strict manifest" if manifest is not None else "historical compatibility"

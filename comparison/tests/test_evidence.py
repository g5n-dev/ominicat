"""Adversarial fixtures for complete, comparable evidence; no benchmark runs.

Only standard-library unittest is required. Collector tests launch tiny owned
Python helpers and replace its OS memory probe with a deterministic sampler;
they test process/error lifecycle, never claim real RSS or framework throughput.
"""
from copy import deepcopy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock


COMPARISON = Path(__file__).resolve().parents[1]


def load_module(name, filename, imports=None):
    # Other repository suites also own a report.py. Load these exact shipped
    # sources without changing import search paths or replacing their modules.
    spec = importlib.util.spec_from_file_location(name, COMPARISON / filename)
    module = importlib.util.module_from_spec(spec)
    with mock.patch.dict(sys.modules, imports or {}):
        spec.loader.exec_module(module)
    return module


evidence = load_module("comparison_evidence_fixture", "evidence.py")
report = load_module("comparison_report_fixture", "report.py", {"evidence": evidence})


def suite_fixture(forks=5):
    settings = {"engines": list(evidence.ENGINES), "stages": [1, 4, 16], "forks": forks,
                "warmupSeconds": 3, "measureSeconds": 5, "maxSamples": 2_000_000,
                "versions": {"ominicat": "0.1.0-SNAPSHOT", "pipecat": "1.15.0", "camel": "4.22.2"},
                "sessions": 1, "inflight": 1, "mode": "queued-w1"}
    order = []
    for fork in range(1, forks + 1):
        for stage_index, stage in enumerate(settings["stages"]):
            offset = (fork - 1 + stage_index) % 3
            engines = settings["engines"][offset:] + settings["engines"][:offset]
            order.extend({"engine": engine, "stages": stage, "fork": fork} for engine in engines)
    files = {name: evidence.digest(("synthetic source " + name).encode()) for name in (
        "pom.xml", "ominicat-core/src/main/java/io/ominicat/core/Frame.java",
        "comparison/benchmarks/pom.xml", "comparison/benchmarks/src/main/java/io/ominicat/comparison/BenchmarkMain.java",
        "comparison/pipecat/benchmark.py", "comparison/run.py", "comparison/evidence.py",
        "comparison/protocol.md", "comparison/requirements.lock")}
    manifest = {"schemaVersion": 1, "protocol": "model-free-framework-path-v1", "recordedAt": "2026-10-31T00:00:00Z", "settings": settings,
                "caseOrder": order, "sourceFingerprint": {"files": files, "aggregate": evidence.digest(evidence.canonical(files))},
                "javaArtifactSha256": evidence.digest(b"synthetic measured jar"),
                "environment": {"os": "Darwin", "osRelease": "synthetic", "arch": "arm64", "logicalProcessors": 4,
                    "collectorPython": "3.12.12", "psutil": "7.2.2", "javaVersion": 'openjdk version "17.0.21"',
                    "pythonVersion": "Python 3.12.12", "context": "Synthetic evidence fixture; never benchmark results"},
                "commands": {"java": ["${JAVA17}", "-Xms128m", "-Xmx512m", "-jar", "comparison/benchmarks/target/benchmark.jar"],
                             "pipecat": ["${BENCH_PYTHON}", "-u", "comparison/pipecat/benchmark.py"]}}
    manifest_hash = evidence.digest(evidence.canonical(manifest))
    results = []
    for case in order:
        python = case["engine"] == "pipecat"
        environment = {"runtime": "CPython" if python else "Java", "version": "3.12.12" if python else "17.0.21",
                       "backend": "asyncio" if python else "platform", "sampleBufferBytes": 16 * 1024 * 1024}
        if python:
            environment["eventLoop"] = "_UnixSelectorEventLoop"
        else:
            environment.update({"vendor": "Synthetic JDK", "os": "Mac OS X", "architecture": "aarch64",
                                "availableProcessors": 4, "maxHeapBytes": 512 * 1024 * 1024})
        result = {"schemaVersion": 1, "engine": case["engine"], "version": settings["versions"][case["engine"]],
                  "mode": "queued-w1", "sessions": 1, "inflight": 1, "stages": case["stages"],
                  "warmupSeconds": 3.001, "warmupFrames": 300, "measuredFrames": 500,
                  "elapsedSeconds": 5.001, "throughputFramesPerSecond": 500 / 5.001,
                  "latencyMicros": {"p50": 10, "p95": 20, "p99": 30, "max": 40}, "processCpuSeconds": 1.2,
                  "checks": {key: True for key in evidence.CHECKS}, "environment": environment,
                  "sampleCapacity": 2_000_000, "harnessBufferBytes": 16 * 1024 * 1024}
        if python:
            result["adapterSha256"] = files["comparison/pipecat/benchmark.py"]
        raw = json.dumps(result, sort_keys=True).encode() + b"\n"
        results.append({**case, "manifestSha256": manifest_hash,
                        "file": f"raw/{case['engine']}-s{case['stages']:02}-f{case['fork']:02}.json",
                        "sha256": evidence.digest(raw), "result": result,
                        "resources": {"peakRssBytes": 40_000_000, "measuredPeakRssBytes": 30_000_000,
                                      "rssSamples": 200, "measuredRssSamples": 100, "sampleIntervalSeconds": .05}})
    return {"schemaVersion": 1, "status": "complete", "manifest": manifest,
            "manifestSha256": manifest_hash, "results": results}


def rebind_manifest(suite):
    suite["manifestSha256"] = evidence.digest(evidence.canonical(suite["manifest"]))
    for item in suite["results"]:
        item["manifestSha256"] = suite["manifestSha256"]


def write_suite(folder, suite):
    (folder / "raw").mkdir()
    for item in suite["results"]:
        raw = json.dumps(item["result"], sort_keys=True).encode() + b"\n"
        item["sha256"] = evidence.digest(raw)
        (folder / item["file"]).write_bytes(raw)
    (folder / "manifest.json").write_text(json.dumps(suite["manifest"]))
    (folder / "suite.json").write_text(json.dumps(suite))


class EvidenceTest(unittest.TestCase):
    def test_complete_45_cell_fixture_is_accepted_without_rewriting_it(self):
        suite = suite_fixture()
        self.assertEqual(len(suite["results"]), 45)
        before = deepcopy(suite)
        self.assertIs(evidence.validate_suite(suite), suite)
        self.assertEqual(suite, before)
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            write_suite(folder, suite)
            self.assertEqual(evidence.load_suite(folder), suite)

    def test_missing_duplicate_and_unknown_observations_never_rank(self):
        for defect in ("missing", "duplicate", "unknown-engine", "unknown-stage", "unknown-fork"):
            with self.subTest(defect=defect):
                suite = suite_fixture()
                if defect == "missing":
                    suite["results"].pop()
                elif defect == "duplicate":
                    suite["results"][-1] = deepcopy(suite["results"][0])
                else:
                    key, value = {"unknown-engine": ("engine", "other"), "unknown-stage": ("stages", 8),
                                  "unknown-fork": ("fork", 6)}[defect]
                    suite["results"][0][key] = value
                with self.assertRaises(ValueError):
                    evidence.validate_suite(suite)

    def test_planned_matrix_cannot_hide_missing_or_duplicate_cells(self):
        for duplicate in (False, True):
            with self.subTest(duplicate=duplicate):
                suite = suite_fixture()
                if duplicate:
                    suite["manifest"]["caseOrder"][-1] = deepcopy(suite["manifest"]["caseOrder"][0])
                else:
                    suite["manifest"]["caseOrder"].pop()
                rebind_manifest(suite)
                with self.assertRaises(ValueError):
                    evidence.validate_suite(suite)

    def test_framework_versions_and_measurement_manifests_cannot_mix(self):
        for defect in ("result-version", "declared-version", "row-manifest", "suite-manifest"):
            with self.subTest(defect=defect):
                suite = suite_fixture()
                if defect == "result-version":
                    suite["results"][0]["result"]["version"] = "unmeasured-version"
                elif defect == "declared-version":
                    suite["manifest"]["settings"]["versions"]["pipecat"] = "0.0.0"
                    rebind_manifest(suite)
                elif defect == "row-manifest":
                    suite["results"][0]["manifestSha256"] = "0" * 64
                else:
                    suite["manifestSha256"] = "0" * 64
                with self.assertRaises(ValueError):
                    evidence.validate_suite(suite)

    def test_source_and_artifact_bindings_are_required_and_consistent(self):
        for defect in ("no-files", "aggregate", "file-hash", "jar-hash", "protocol"):
            with self.subTest(defect=defect):
                suite = suite_fixture()
                manifest = suite["manifest"]
                if defect == "no-files":
                    manifest["sourceFingerprint"]["files"] = {}
                elif defect == "aggregate":
                    manifest["sourceFingerprint"]["aggregate"] = "0" * 64
                elif defect == "file-hash":
                    manifest["sourceFingerprint"]["files"]["comparison/run.py"] = "not-a-sha256"
                    manifest["sourceFingerprint"]["aggregate"] = evidence.digest(evidence.canonical(manifest["sourceFingerprint"]["files"]))
                elif defect == "jar-hash":
                    manifest["javaArtifactSha256"] = "short"
                else:
                    manifest["protocol"] = "different-protocol"
                rebind_manifest(suite)
                with self.assertRaises(ValueError):
                    evidence.validate_suite(suite)

    def test_nan_infinity_and_invalid_counts_are_rejected(self):
        for key in ("warmupSeconds", "elapsedSeconds", "throughputFramesPerSecond", "processCpuSeconds"):
            for value in (float("nan"), float("inf"), -1, True):
                with self.subTest(key=key, value=value):
                    suite = suite_fixture()
                    suite["results"][0]["result"][key] = value
                    with self.assertRaises(ValueError):
                        evidence.validate_suite(suite)
        for key in ("warmupFrames", "measuredFrames"):
            for value in (0, -1, 1.5, True, float("nan")):
                with self.subTest(key=key, value=value):
                    suite = suite_fixture()
                    suite["results"][0]["result"][key] = value
                    with self.assertRaises(ValueError):
                        evidence.validate_suite(suite)

    def test_short_duration_wrong_throughput_and_sample_overflow_are_rejected(self):
        for key, value in (("warmupSeconds", 2.99), ("elapsedSeconds", 4.99),
                           ("throughputFramesPerSecond", 100_000), ("measuredFrames", 2_000_001)):
            with self.subTest(key=key):
                suite = suite_fixture()
                suite["results"][0]["result"][key] = value
                with self.assertRaises(ValueError):
                    evidence.validate_suite(suite)

    def test_unordered_nonfinite_or_missing_latency_is_rejected(self):
        for key, value in (("p50", 41), ("p95", float("nan")), ("p99", float("inf")), ("max", 0)):
            with self.subTest(key=key):
                suite = suite_fixture()
                suite["results"][0]["result"]["latencyMicros"][key] = value
                with self.assertRaises(ValueError):
                    evidence.validate_suite(suite)
        suite = suite_fixture()
        del suite["results"][0]["result"]["latencyMicros"]["p99"]
        with self.assertRaises(ValueError):
            evidence.validate_suite(suite)

    def test_every_output_and_cleanup_check_requires_literal_true(self):
        for key in evidence.CHECKS:
            for value in (False, 1, "true", None):
                with self.subTest(key=key, value=value):
                    suite = suite_fixture()
                    suite["results"][0]["result"]["checks"][key] = value
                    with self.assertRaises(ValueError):
                        evidence.validate_suite(suite)

    def test_missing_and_impossible_resource_observations_are_rejected(self):
        for key in ("peakRssBytes", "measuredPeakRssBytes", "rssSamples", "measuredRssSamples", "sampleIntervalSeconds"):
            for missing in (True, False):
                with self.subTest(key=key, missing=missing):
                    suite = suite_fixture()
                    if missing:
                        del suite["results"][0]["resources"][key]
                    else:
                        suite["results"][0]["resources"][key] = 0
                    with self.assertRaises(ValueError):
                        evidence.validate_suite(suite)
        suite = suite_fixture()
        suite["results"][0]["resources"]["measuredPeakRssBytes"] = 50_000_000
        with self.assertRaises(ValueError):
            evidence.validate_suite(suite)

    def test_result_profile_integer_fields_cannot_be_json_booleans(self):
        for key in ("schemaVersion", "sessions", "inflight", "stages"):
            with self.subTest(key=key):
                suite = suite_fixture()
                suite["results"][0]["result"][key] = True
                with self.assertRaises(ValueError):
                    evidence.validate_suite(suite)

    def test_measured_rss_samples_are_a_subset_of_whole_process_samples(self):
        suite = suite_fixture()
        suite["results"][0]["resources"]["measuredRssSamples"] = 201
        with self.assertRaises(ValueError):
            evidence.validate_suite(suite)

    def test_raw_bytes_cannot_change_even_when_json_value_is_identical(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            suite = suite_fixture()
            write_suite(folder, suite)
            raw = folder / suite["results"][0]["file"]
            raw.write_bytes(raw.read_bytes() + b" \n")
            with self.assertRaises(ValueError):
                evidence.load_suite(folder)

    def test_updating_byte_hash_cannot_hide_raw_and_embedded_result_disagreement(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            suite = suite_fixture()
            write_suite(folder, suite)
            raw = folder / suite["results"][0]["file"]
            modified = deepcopy(suite["results"][0]["result"])
            modified["measuredFrames"] += 1
            raw.write_bytes(json.dumps(modified).encode())
            suite["results"][0]["sha256"] = evidence.digest(raw.read_bytes())
            (folder / "suite.json").write_text(json.dumps(suite))
            with self.assertRaises(ValueError):
                evidence.load_suite(folder)

    def test_extra_or_escaped_raw_files_cannot_be_ignored(self):
        for defect in ("extra", "escape"):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary)
                suite = suite_fixture()
                write_suite(folder, suite)
                if defect == "extra":
                    (folder / "raw/unaccounted.json").write_text("{}")
                else:
                    suite["results"][0]["file"] = "../outside.json"
                    (folder / "suite.json").write_text(json.dumps(suite))
                with self.assertRaises(ValueError):
                    evidence.load_suite(folder)

    def test_two_forks_cannot_alias_one_raw_file_via_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            suite = suite_fixture()
            write_suite(folder, suite)
            first = suite["results"][0]
            another = next(item for item in suite["results"] if item["engine"] == first["engine"]
                           and item["stages"] == first["stages"] and item["fork"] != first["fork"])
            alias = folder / another["file"]
            alias.unlink()
            alias.symlink_to(Path(first["file"]).name)
            with self.assertRaises(ValueError):
                evidence.load_suite(folder)

    def test_two_forks_cannot_reference_the_same_raw_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            suite = suite_fixture()
            write_suite(folder, suite)
            first = suite["results"][0]
            another = next(item for item in suite["results"] if item["engine"] == first["engine"]
                           and item["stages"] == first["stages"] and item["fork"] != first["fork"])
            (folder / another["file"]).unlink()
            another["file"] = first["file"]
            (folder / "suite.json").write_text(json.dumps(suite))
            with self.assertRaises(ValueError):
                evidence.load_suite(folder)

    def test_published_manifest_file_cannot_disagree_with_validated_inline_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            suite = suite_fixture()
            write_suite(folder, suite)
            (folder / "manifest.json").write_text(json.dumps({"protocol": "other", "caseOrder": []}))
            with self.assertRaises(ValueError):
                evidence.load_suite(folder)


class ReportTest(unittest.TestCase):
    def test_full_report_retains_all_five_forks_instead_of_the_best_observation(self):
        suite = suite_fixture()
        for item in suite["results"]:
            result = item["result"]
            result["measuredFrames"] = item["fork"] * 500
            result["throughputFramesPerSecond"] = result["measuredFrames"] / result["elapsedSeconds"]
        evidence.validate_suite(suite)
        summary = report.summarize(suite)
        self.assertEqual(len(summary["cells"]), 9)
        for cell in summary["cells"]:
            values = cell["metrics"]["throughput"]
            self.assertEqual(len(values["forkValues"]), 5)
            self.assertAlmostEqual(values["median"], 1500 / 5.001)
            self.assertAlmostEqual(values["min"], 500 / 5.001)
            self.assertAlmostEqual(values["max"], 2500 / 5.001)
        self.assertIn("not confidence intervals", summary["intervalMeaning"])

    def test_smoke_or_selected_engine_profiles_fail_before_publishing_or_rendering(self):
        for defect in ("forks", "engines", "stages", "warmup", "measure", "more-forks", "long-warmup", "long-measure"):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source, destination = root / "collected", root / "published"
                source.mkdir()
                suite = suite_fixture(forks=6 if defect == "more-forks" else 5)
                settings = suite["manifest"]["settings"]
                if defect == "forks":
                    settings["forks"] = 1
                elif defect == "engines":
                    settings["engines"] = ["ominicat"]
                elif defect == "stages":
                    settings["stages"] = [4]
                elif defect == "warmup":
                    settings["warmupSeconds"] = .1
                elif defect == "measure":
                    settings["measureSeconds"] = .2
                elif defect == "long-warmup":
                    settings["warmupSeconds"] = 4
                    for item in suite["results"]:
                        item["result"]["warmupSeconds"] = 4.001
                elif defect == "long-measure":
                    settings["measureSeconds"] = 6
                    for item in suite["results"]:
                        result = item["result"]
                        result["elapsedSeconds"] = 6.001
                        result["throughputFramesPerSecond"] = result["measuredFrames"] / 6.001
                permitted = lambda item: (item["fork"] <= settings["forks"]
                    and item["engine"] in settings["engines"] and item["stages"] in settings["stages"])
                suite["results"] = [item for item in suite["results"] if permitted(item)]
                suite["manifest"]["caseOrder"] = [item for item in suite["manifest"]["caseOrder"] if permitted(item)]
                rebind_manifest(suite)
                write_suite(source, suite)
                self.assertEqual(evidence.load_suite(source), suite, "collection may record an explicitly declared smoke profile")
                before = {p.relative_to(source): p.read_bytes() for p in source.rglob("*") if p.is_file()}
                with mock.patch.object(sys, "argv", ["report.py", str(source), str(destination)]), mock.patch.object(report, "render") as render:
                    with self.assertRaises(ValueError):
                        report.main()
                render.assert_not_called()
                self.assertFalse(destination.exists(), "a smoke profile must not create successful publication artifacts")
                self.assertEqual({p.relative_to(source): p.read_bytes() for p in source.rglob("*") if p.is_file()}, before)


def collector_fixture():
    """Import the real collector with only its OS-specific memory probe faked."""
    class NoSuchProcess(Exception):
        pass

    probe = types.SimpleNamespace(__version__="synthetic-memory-probe", NoSuchProcess=NoSuchProcess,
                                 Process=lambda pid: types.SimpleNamespace(memory_info=lambda: types.SimpleNamespace(rss=123_456)))
    return load_module("comparison_collector_fixture", "run.py", {"psutil": probe, "evidence": evidence})


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.collector = collector_fixture()
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)

    def test_nonzero_child_never_becomes_success_even_if_it_wrote_valid_json(self):
        output = self.folder / "adapter.json"
        script = ("import json,pathlib,sys,time; "
                  "pathlib.Path(sys.argv[1]).write_text(json.dumps({'looksSuccessful':True})); "
                  "print(json.dumps({'event':'phase','phase':'measure'}),flush=True); "
                  "time.sleep(.18); sys.exit(7)")
        with self.assertRaises(ValueError):
            self.collector.collect([sys.executable, "-u", "-c", script, str(output)], output, 3)
        self.assertTrue(output.exists(), "the control must demonstrate that a plausible file cannot override a failed exit")
        self.assertFalse((self.folder / "suite.json").exists())

    def test_successful_helper_requires_measurement_resource_samples(self):
        output = self.folder / "adapter.json"
        script = "import time; print('setup without a measurement phase',flush=True); time.sleep(.15)"
        with self.assertRaises(ValueError):
            self.collector.collect([sys.executable, "-u", "-c", script], output, 3)

    def test_stale_output_is_rejected_before_launching_any_child(self):
        output = self.folder / "adapter.json"
        output.write_text("{}")
        with mock.patch.object(self.collector.subprocess, "Popen") as launch:
            with self.assertRaises(ValueError):
                self.collector.collect([sys.executable, "-c", "raise SystemExit(0)"], output, 3)
        launch.assert_not_called()

    def test_deadline_kills_and_reaps_the_owned_helper(self):
        output = self.folder / "adapter.json"
        real_popen = subprocess.Popen
        processes = []

        def launch(*args, **kwargs):
            process = real_popen(*args, **kwargs)
            processes.append(process)
            return process

        script = "import time; print('{\"event\":\"phase\",\"phase\":\"measure\"}',flush=True); time.sleep(30)"
        with mock.patch.object(self.collector.subprocess, "Popen", side_effect=launch):
            with self.assertRaises(TimeoutError):
                self.collector.collect([sys.executable, "-u", "-c", script], output, .12)
        self.assertEqual(len(processes), 1)
        self.assertIsNotNone(processes[0].poll())
        with self.assertRaises(ProcessLookupError):
            os.kill(processes[0].pid, 0)


if __name__ == "__main__":
    unittest.main()

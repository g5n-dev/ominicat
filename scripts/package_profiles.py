#!/usr/bin/env python3
"""Package an explicit, privacy-checked profile allowlist; never run benchmarks.

CI supplies GITHUB_SHA and (optionally) the GitHub run environment. Local callers
can supply --commit. Inputs are copied unchanged; suspicious content is rejected,
not silently redacted. A package is evidence, never a cross-run performance gate.
"""

import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.parse import urlsplit


FILES = (
    "report.md", "profiles.md", "cpu-flamegraph.svg", "allocation-flamegraph.svg",
    "cpu-hotspots.svg", "allocation-hotspots.svg", "gc-pauses.svg",
    "jmh.json", "jmh.log", "load.json", "source-sha256.json",
    "jfr-hotspots.json", "jfr-events.json.gz", "gc.log", "jfr-summary.txt",
    "run-manifest.json",
)
EVENTS = frozenset((
    "jdk.ExecutionSample", "jdk.ObjectAllocationSample", "jdk.GarbageCollection",
    "jdk.JavaMonitorEnter", "jdk.VirtualThreadPinned",
))
PRIVACY_RULES = (
    ("personal home path", re.compile(r"/(?i:Users)/[^/\s\"'<>]+|/home/[^/\s\"'<>]+|/root/|(?i:[A-Z]:[\\/]+Users[\\/])")),
    ("bearer credential", re.compile(r"\bBearer\s+\S+", re.I)),
    ("authorization credential", re.compile(r"\bAuthorization[\"']?\s*[:=]\s*[\"']?Basic\s+\S+", re.I)),
    ("credential assignment", re.compile(
        r"[\"']?\b(?:[A-Z_]*(?:TOKEN|API[_-]?KEY|PASSWORD|PASSWD|CLIENT[_-]?SECRET)|"
        r"AWS_SECRET_ACCESS_KEY)[\"']?\s*[:=]\s*[\"']?[^\s\"',;}]+", re.I)),
    ("credential format", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|sk-[A-Za-z0-9_-]{20,})\b")),
    ("private key", re.compile(r"-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----")),
)


def fail(message):
    raise ValueError(message)


def privacy_check(name, content):
    text = content.decode("utf-8")
    normalized = re.sub(r"\\u([0-9a-fA-F]{4})", lambda match: chr(int(match[1], 16)), text).replace("\\/", "/")
    for description, pattern in PRIVACY_RULES:
        if pattern.search(normalized):
            # Do not echo the matching path or secret into a CI log.
            fail(f"{name}: refused {description}; sanitize the source explicitly")
    return text


def parse_json(name, text):
    def invalid_constant(_):
        fail(f"{name}: non-finite JSON constant")
    def finite_float(value):
        parsed = float(value)
        if not math.isfinite(parsed):
            invalid_constant(value)
        return parsed
    try:
        return json.loads(text, parse_constant=invalid_constant, parse_float=finite_float)
    except json.JSONDecodeError:
        fail(f"{name}: invalid JSON")


def utc_time(value):
    if not isinstance(value, str):
        fail("load.json: recordedAt must be an ISO UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        fail("load.json: recordedAt must be an ISO UTC timestamp")
    if parsed.tzinfo is None:
        fail("load.json: recordedAt must include its timezone")
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def github_run_url(explicit):
    value = explicit
    if value is None and os.environ.get("GITHUB_RUN_ID"):
        server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
        repository = os.environ.get("GITHUB_REPOSITORY", "")
        value = f"{server.rstrip('/')}/{repository}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    if value:
        parsed = urlsplit(value)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment
                or not re.fullmatch(r"/[^/\s]+/[^/\s]+/actions/runs/[0-9]+", parsed.path)):
            fail("GitHub run URL must be an HTTPS actions/runs URL without credentials")
    return value or None


def number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        fail("profile metrics must be finite numbers")
    return f"{value:,.3f}"


def cell(value):
    return str(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def pause_ms(value):
    match = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", value or "")
    if not match or not any(match.groups()):
        fail("selected GC event has an invalid sumOfPauses duration")
    hours, minutes, seconds = (float(part or 0) for part in match.groups())
    result = (hours * 3600 + minutes * 60 + seconds) * 1000
    if not math.isfinite(result):
        fail("selected GC event has a non-finite sumOfPauses duration")
    return result


def summary(metadata, jmh, load, events):
    lines = [
        "## Synthetic kernel profile", "",
        f"Java **{cell(metadata['javaVersion'])}**; commit `{metadata['gitCommit']}`.", "",
        "| JMH benchmark | Stages | Score | Unit | Allocation B/op |",
        "|---|---:|---:|---|---:|",
    ]
    if not isinstance(jmh, list) or not jmh:
        fail("jmh.json: expected nonempty benchmark results")
    for key in ("sessions", "stages", "inflightPerSession", "rejected"):
        value = load[key]
        if isinstance(value, bool) or not isinstance(value, int) or value < (0 if key == "rejected" else 1):
            fail(f"load.json: invalid {key}")
    for key in ("throughputFramesPerSecond", "elapsedSeconds"):
        number(load[key])
        if load[key] <= 0:
            fail(f"load.json: {key} must be positive")
    for result in jmh:
        metric = result["primaryMetric"]
        allocation = result.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
        name = result["benchmark"].removeprefix("io.ominicat.benchmarks.")
        number(metric["score"])
        if allocation is not None:
            number(allocation)
        if metric["score"] < 0 or (allocation is not None and allocation < 0):
            fail("jmh.json: benchmark scores and allocations must be nonnegative")
        lines.append(f"| {cell(name)} | {cell(result.get('params', {}).get('stages', '—'))} | "
                     f"{number(metric['score'])} | {cell(metric['scoreUnit'])} | "
                     f"{number(allocation) if allocation is not None else 'n/a'} |")
    latency = load["latencyMicros"]
    values = [latency[key] for key in ("p50", "p95", "p99", "max")]
    for value in values:
        number(value)
    if values != sorted(values) or values[0] < 0:
        fail("load.json: latency percentiles must be ordered and nonnegative")
    pauses = [pause_ms(event["values"]["sumOfPauses"]) for event in events
              if event["type"] == "jdk.GarbageCollection"]
    lines += [
        "", "JMH average-time scores are means, not latency percentiles.", "",
        f"Closed-loop load: **{number(load['throughputFramesPerSecond'])} frames/s**; "
        f"p50/p95/p99 **{number(latency['p50'])}/{number(latency['p95'])}/{number(latency['p99'])} µs**; "
        f"{load['sessions']} sessions × {load['stages']} stages × {load['inflightPerSession']} in flight; "
        f"{number(load['elapsedSeconds'])} s measured; {load['rejected']} rejected.", "",
        f"Whole-recording GC: **{len(pauses)}** collections; pause sum **{number(sum(pauses))} ms**; "
        f"maximum collection pause sum **{number(max(pauses, default=0))} ms**.", "",
        "Scope: synthetic in-memory kernel, no model or network. Load latency includes ingress waiting "
        "and excludes generator permit waiting; JFR includes warmup and the harness. "
        "Shared-runner results are evidence, not a capacity guarantee or cross-run performance gate.", "",
    ]
    if metadata["githubRunUrl"]:
        lines += [f"[GitHub run]({metadata['githubRunUrl']})", ""]
    return "\n".join(lines)


def package(args):
    source = args.source.resolve(strict=True)
    if not source.is_dir():
        fail("source must be a profile directory")
    if args.output.is_symlink():
        fail("output must not be a symlink")
    destination = args.output.resolve()
    if source == destination:
        fail("source and output must be different directories")
    if destination.exists() and (not destination.is_dir() or any(destination.iterdir())):
        fail("output must be absent or an empty directory")
    if args.summary:
        summary_path = args.summary.resolve()
        if summary_path.is_relative_to(source) or summary_path.is_relative_to(destination):
            fail("summary must be outside the source and output directories")
    commit = args.commit
    if not commit or not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", commit):
        fail("set GITHUB_SHA or --commit to the full measured git commit")

    contents, texts = {}, {}
    for name in FILES:
        path = source / name
        if path.is_symlink() or not path.is_file():
            fail(f"{name}: required regular file is missing (symlinks are refused)")
        data = path.read_bytes()
        if not data:
            fail(f"{name}: required file is empty")
        plain = gzip.decompress(data) if name.endswith(".gz") else data
        text = privacy_check(name, plain)
        if not text.strip():
            fail(f"{name}: required content is empty")
        contents[name], texts[name] = data, text

    load = parse_json("load.json", texts["load.json"])
    jmh = parse_json("jmh.json", texts["jmh.json"])
    source_hash = parse_json("source-sha256.json", texts["source-sha256.json"])["aggregate"]
    manifest = parse_json("run-manifest.json", texts["run-manifest.json"])
    parse_json("jfr-hotspots.json", texts["jfr-hotspots.json"])
    events = parse_json("jfr-events.json.gz", texts["jfr-events.json.gz"])["recording"]["events"]
    if not isinstance(events, list) or not events or any(event["type"] not in EVENTS for event in events):
        fail("selected JFR events must be nonempty and contain only approved event categories")
    if not isinstance(source_hash, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", source_hash):
        fail("source-sha256.json: invalid aggregate SHA-256")
    artifact_hash = manifest["benchmarkArtifactSha256"]
    if not isinstance(artifact_hash, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", artifact_hash):
        fail("run-manifest.json: invalid benchmark artifact SHA-256")
    if (manifest["schemaVersion"] != 1 or manifest["mode"] not in ("quick", "full")
            or manifest["benchmarkArtifact"] != "ominicat-benchmarks.jar"
            or manifest["sourceSha256"] != source_hash):
        fail("run-manifest.json: invalid manifest or source fingerprint mismatch")
    java_version = load["environment"]["javaVersion"]
    if not isinstance(java_version, str) or not re.fullmatch(r"[A-Za-z0-9.+_-]{1,120}", java_version):
        fail("load.json: invalid Java version")
    metadata = {
        "schemaVersion": 1,
        "gitCommit": commit.lower(),
        "sourceSha256": source_hash.lower(),
        "benchmarkArtifactSha256": artifact_hash.lower(),
        "profileMode": manifest["mode"],
        "javaVersion": java_version,
        "workerBackend": load["environment"].get("workerBackend"),
        "githubRunUrl": github_run_url(args.run_url),
        "collectedAtUtc": utc_time(load["recordedAt"]),
        "packagedAtUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "synthetic": True,
        "crossRunPerformanceGate": False,
        "scope": "In-memory kernel only; excludes model and network; not a capacity guarantee.",
    }
    markdown = summary(metadata, jmh, load, events)
    contents["metadata.json"] = (json.dumps(metadata, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    contents["SHA256SUMS"] = "".join(
        f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(contents.items())
    ).encode("ascii")

    # Stage beside the output so failures never leave a partial public package.
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".profile-package-", dir=destination.parent) as temporary:
        staged = Path(temporary) / "package"
        staged.mkdir()
        for name, data in contents.items():
            (staged / name).write_bytes(data)
        staged.rename(destination)
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as stream:
            stream.write(markdown + "\n")
    print(f"Packaged {len(FILES)} allowlisted files plus metadata.json and SHA256SUMS.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--summary", type=Path, help="append Markdown to a GitHub step summary")
    parser.add_argument("--commit", default=os.environ.get("GITHUB_SHA"), help="measured git commit (default: GITHUB_SHA)")
    parser.add_argument("--run-url", help="optional GitHub Actions run URL (default: GitHub run environment)")
    args = parser.parse_args()
    try:
        package(args)
    except OSError as error:
        print(f"Profile packaging failed: file operation failed ({type(error).__name__})", file=sys.stderr)
        return 1
    except (ValueError, KeyError, TypeError, EOFError, AttributeError, OverflowError) as error:
        # Input errors never print source file contents or credential values.
        print(f"Profile packaging failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

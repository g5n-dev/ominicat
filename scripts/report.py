#!/usr/bin/env python3
"""Turn raw local JMH/load/JFR results into an evidence-linked Markdown report."""
import json
import math
import hashlib
import argparse
from collections import Counter
from pathlib import Path
import sys

from profile_evidence import prepare_run, validate_evidence


def shown(value, digits=3):
    return f"{value:,.{digits}f}" if isinstance(value, (int, float)) and math.isfinite(value) else "n/a"


def fingerprint():
    roots = [Path("ominicat-core/src/main/java"), Path("ominicat-benchmarks/src/main/java")]
    paths = sorted(path for root in roots for path in root.rglob("*.java"))
    paths += [Path("pom.xml"), Path("ominicat-core/pom.xml"), Path("ominicat-benchmarks/pom.xml")]
    files = [{"path": path.as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in paths]
    aggregate = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    return {"algorithm": "SHA-256", "aggregate": aggregate, "files": files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", nargs="?", default="reports/latest", type=Path)
    parser.add_argument("--fingerprint", action="store_true")
    parser.add_argument("--prepare-run", choices=("quick", "full"))
    parser.add_argument("--artifact", type=Path, help="verify the actual measured JAR when available")
    parser.add_argument("--strict", action="store_true", help="require a dynamic run manifest; never fall back to historical mode")
    parser.add_argument("--check-source", action="store_true", help="require current runtime sources to match the pre-run fingerprint")
    parser.add_argument("--validate", action="store_true", help="validate evidence without changing report files")
    args = parser.parse_args()
    if args.fingerprint:
        destination = args.folder
        destination.mkdir(parents=True, exist_ok=True)
        (destination / "source-sha256.json").write_text(json.dumps(fingerprint(), indent=2) + "\n")
        return
    folder = args.folder
    if args.prepare_run:
        if args.artifact is None:
            parser.error("--prepare-run requires --artifact")
        prepare_run(folder, args.prepare_run, args.artifact)
        return
    jmh, load, provenance, validation_mode = validate_evidence(
        folder, strict=args.strict, current_source=fingerprint() if args.check_source else None,
        artifact=args.artifact)
    print(f"Validated {len(jmh)} JMH cases and complete load counters ({validation_mode} mode).")
    if args.validate:
        return
    # Keep original local logs out of publication while removing repository/user paths.
    private = folder.parent / "local" / folder.name
    for name in ["jmh.log", "build.log", "load.log", "jfr-config.log"]:
        log = folder / name
        if log.exists():
            original = log.read_text()
            cleaned = original.replace(str(Path.cwd()), "[repository]")
            if cleaned != original:
                private.mkdir(parents=True, exist_ok=True)
                (private / name).write_text(original)
                log.write_text(cleaned)
    environment = load["environment"]
    lines = [
        "# Local kernel performance evidence", "",
        f"Recorded: {load['recordedAt']}. These measurements cover the in-memory Java kernel only. They do not measure model inference, speech accuracy, networking, WebRTC, or end-to-end agent latency.", "",
        "## Environment", "",
        f"- Java: {environment['javaVendor']} {environment['javaVersion']}",
        f"- Platform: {environment['os']} / {environment['arch']}; {environment['processors']} available processors",
        f"- Maximum heap: {shown(environment['maxHeapBytes'] / (1024 * 1024), 0)} MiB",
        "- The machine was shared with development tasks. Results are an initial baseline, not a capacity guarantee.", "",
        "## JMH dispatch and scheduling", "",
        "Each dispatch stage transforms a changing numeric payload. Blackhole consumes the transformed result. Dispatch includes frame allocation; the session test includes one outstanding frame, bounded ingress, worker scheduling, and output queue handoff.", "",
        "| Benchmark | Stages | Score | JMH error (99.9% CI) | Unit | Allocation B/op |", "|---|---:|---:|---:|---|---:|",
    ]
    if provenance:
        lines[4:4] = [f"Runtime/source fingerprint: `{provenance['aggregate']}`. [Per-file SHA-256 manifest](source-sha256.json).", ""]
    lines[4:4] = [f"Evidence validation: {validation_mode} mode; complete benchmark/parameter matrix and load counters checked.", ""]
    if (folder / "run-manifest.json").exists():
        lines[4:4] = ["[Run manifest and measured benchmark JAR SHA-256](run-manifest.json).", ""]
    for result in jmh:
        metric = result["primaryMetric"]
        allocation = result.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
        name = result["benchmark"].rsplit(".", 2)[-2:]
        lines.append(f"| {'.'.join(name)} | {result.get('params', {}).get('stages', '—')} | {shown(metric['score'])} | ± {shown(metric.get('scoreError'))} | {metric['scoreUnit']} | {shown(allocation)} |")
    if jmh:
        first = jmh[0]
        lines += ["", f"Configuration: {first['forks']} fork(s), {first['warmupIterations']} warmup iterations × {first['warmupTime']}, {first['measurementIterations']} measurement iterations × {first['measurementTime']}; {first['threads']} JMH thread(s). GC allocation profiler enabled."]
    latency = load["latencyMicros"]
    lines += ["", "## Concurrent bounded-inflight load", "",
        f"{load['sessions']} sessions × {load['stages']} stages, at most {load['inflightPerSession']} outstanding frames per session. {load['warmupFrames']:,} warmup frames followed by {load['measuredFrames']:,} measured frames; {load['rejected']} rejected frames.", "",
        "| Accepted throughput | p50 | p95 | p99 | Maximum |", "|---:|---:|---:|---:|---:|",
        f"| {shown(load['throughputFramesPerSecond'], 0)} frames/s | {shown(latency['p50'])} µs | {shown(latency['p95'])} µs | {shown(latency['p99'])} µs | {shown(latency['max'])} µs |", "",
        "Latency is measured from immediately before submission to the output callback, including ingress queue waiting. Waiting for the generator's in-flight permit is excluded. This is a closed-loop test; it does not correct for coordinated omission or predict latency under an independently arriving overload. No frames are discarded from the percentile calculation. Percentiles use the nearest-rank method over the exact measured sample array.", "",
        "## Profiles", ""]
    events_path = folder / "jfr-events.json"
    if events_path.exists():
        events = json.loads(events_path.read_text()).get("recording", {}).get("events", [])
        counts = {}
        for event in events:
            counts[event["type"]] = counts.get(event["type"], 0) + 1
        for kind in ["jdk.ExecutionSample", "jdk.ObjectAllocationSample", "jdk.GarbageCollection", "jdk.JavaMonitorEnter", "jdk.VirtualThreadPinned"]:
            if kind == "jdk.VirtualThreadPinned" and int(environment["javaVersion"].split(".")[0]) < 21:
                lines.append("- `jdk.VirtualThreadPinned`: not available on this runtime.")
            else:
                lines.append(f"- `{kind}`: {counts.get(kind, 0)} recorded events.")
        leaves = Counter()
        allocations = Counter()
        for event in events:
            values = event.get("values", {})
            if event["type"] == "jdk.ExecutionSample":
                frames = (values.get("stackTrace") or {}).get("frames", [])
                if frames:
                    method = frames[0].get("method", {})
                    leaves[method.get("type", {}).get("name", "unknown").replace("/", ".") + "." + method.get("name", "unknown")] += 1
            elif event["type"] == "jdk.ObjectAllocationSample":
                allocations[values.get("objectClass", {}).get("name", "unknown").replace("/", ".")] += values.get("weight", 0)
        summary = {"eventCounts": counts, "executionSampleLeaves": dict(leaves.most_common(12)),
                   "sampledAllocationWeightBytes": dict(allocations.most_common(12))}
        (folder / "jfr-hotspots.json").write_text(json.dumps(summary, indent=2) + "\n")
        lines += ["", "CPU sampled leaf methods (whole load process, including warmup and percentile sorting):", "",
                  "| Method | Samples |", "|---|---:|"]
        for method, count in leaves.most_common(8):
            lines.append(f"| `{method}` | {count} |")
        lines += ["", "Sampled allocation weights (estimates, not exact per-type allocation totals):", "",
                  "| Type | Estimated MiB |", "|---|---:|"]
        for kind, weight in allocations.most_common(8):
            lines.append(f"| `{kind}` | {shown(weight / 1024 / 1024)} |")
        lines += ["", "Event counts are observations from this recording. Zero recorded pinning events does not prove the absence of pinning in other workloads; JFR thresholds and sampling apply."]
    lines += ["", "Raw evidence: [JMH JSON](jmh.json), [JMH output](jmh.log), [load JSON](load.json), [GC log](gc.log), [JFR summary](jfr-summary.txt), [compact JFR hotspots](jfr-hotspots.json). The binary JFR recording (`kernel.jfr`) and uncompressed selected event export are generated locally and are excluded from source control.", "",
        "Reproduce from the repository root with Java 17+: `scripts/profile.sh quick reports/latest`. Use `full` for longer warmup and measurement. Open the JFR recording in JDK Mission Control for stack, allocation, lock, and GC analysis.", "",
        "Compare revisions on the same idle machine, JVM, heap, stage count, and profiler settings. Broader claims require longer runs, repetitions, arrival-rate load tests, and real provider and transport measurements.", ""]
    (folder / "report.md").write_text("\n".join(lines))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"Profile evidence validation failed: {error}", file=sys.stderr)
        sys.exit(1)

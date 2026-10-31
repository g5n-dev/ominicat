#!/usr/bin/env python3
"""Separate, measure-only cProfile diagnosis of the pinned Pipecat adapter.

Use the comparison CPython 3.12 venv to run this script, or pass --python with
that interpreter. This adds instrumentation and is never a headline benchmark.
Raw profiler data is restricted to reports/local; exported summaries omit raw
filenames/callers and contain only normalized code locations and aggregate times.
"""

from __future__ import annotations

import argparse
import asyncio
import cProfile
import csv
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import platform
import posixpath
import pstats
import re
import subprocess
import sys
import sysconfig


ROOT = Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "comparison/pipecat/benchmark.py"
SCOPE = (
    "Separate instrumented cProfile diagnosis of the synthetic queued-W1 path; "
    "not a headline timing, native CPU stack sample, retained-heap measure or "
    "cross-language allocation/B-op comparison. Current event-loop thread only. "
    "Imports, startup, warmup and teardown are excluded. Python and exposed C "
    "call entries are attributed; native implementation internals are not resolved. "
    "Cumulative call times overlap and must not be summed as an exclusive breakdown."
)


class ProfileError(RuntimeError):
    """A diagnostic failed or could not be safely summarized."""


class PhaseCapture:
    """Enable after the flushed measure marker; disable before teardown marker."""

    def __init__(self, profiler, forward):
        self.profiler = profiler
        self.forward = forward
        self.phases = []
        self.enabled = False

    def __call__(self, phase):
        expected = ("warmup", "measure", "teardown")
        if len(self.phases) >= len(expected) or phase != expected[len(self.phases)]:
            raise ProfileError("unexpected profiling phase sequence")
        self.phases.append(phase)
        if phase == "teardown":
            self.profiler.disable()
            self.enabled = False
        self.forward(phase)
        if phase == "measure":
            self.profiler.enable()
            self.enabled = True

    def stop(self):
        if self.enabled:
            self.profiler.disable()
            self.enabled = False

    def complete(self):
        if self.phases != ["warmup", "measure", "teardown"] or self.enabled:
            raise ProfileError("measure-only capture was not completed")


def _opaque_location(value):
    return "external/" + hashlib.sha256(value.encode("utf-8", errors="surrogatepass")).hexdigest()[:16]


def _normal(value):
    return posixpath.normpath(value.replace("\\", "/"))


def _relative_to(value, root):
    normalized = _normal(root).rstrip("/")
    # Windows paths are case-insensitive; do not depend on the current host OS.
    left, right = (value.casefold(), normalized.casefold()) if re.match(r"^[a-zA-Z]:/", value) else (value, normalized)
    return value[len(normalized) + 1:] if left.startswith(right + "/") else None


def _safe_relative(value):
    return (bool(value) and len(value) <= 600 and not value.startswith("/")
            and all(part not in ("", ".", "..") for part in value.split("/"))
            and not any(ord(char) < 32 or ord(char) == 127 for char in value)
            and not re.search(r"[\\:<>|]", value))


def sanitize_file(filename, *, root=ROOT, stdlib_roots=None):
    """Normalize known code roots without probing any profiled path on disk.

    Unknown absolute/relative filenames receive opaque labels rather than a
    potentially personal basename. Only comparison/, site-packages/ and known
    stdlib names are retained. Raw caller maps are never exported.
    """
    if not isinstance(filename, str):
        raise ProfileError("invalid profiler filename")
    if filename == "~":
        return "[native]"
    if any(ord(char) < 32 or ord(char) == 127 for char in filename):
        return _opaque_location(filename)
    if filename.startswith("<") and filename.endswith(">") and not re.search(r"[/\\:]", filename):
        return filename[:200]
    value = _normal(filename)
    for marker in ("site-packages/", "dist-packages/"):
        position = value.find(marker)
        if position >= 0 and (position == 0 or value[position - 1] == "/"):
            tail = value[position + len(marker):]
            return "site-packages/" + tail if _safe_relative(tail) else _opaque_location(filename)
    relative = _relative_to(value, str(root))
    if relative is not None and relative.startswith("comparison/") and _safe_relative(relative):
        return relative
    if value.startswith("comparison/") and _safe_relative(value):
        return value
    roots = stdlib_roots if stdlib_roots is not None else {
        sysconfig.get_path("stdlib"), sysconfig.get_path("platstdlib")}
    for stdlib in roots:
        if not stdlib:
            continue
        aliases = {str(stdlib)}
        # Resolve only trusted sysconfig roots, never a filename from the
        # profile. Homebrew's opt symlink and Cellar code paths may differ.
        if Path(stdlib).is_absolute():
            try:
                aliases.add(str(Path(stdlib).resolve()))
            except (OSError, RuntimeError):
                pass
        for alias in aliases:
            relative = _relative_to(value, alias)
            if relative is not None and _safe_relative(relative):
                return "stdlib/" + relative
    return _opaque_location(filename)


def sanitize_symbol(symbol):
    if not isinstance(symbol, str):
        raise ProfileError("invalid profiler symbol")
    if re.search(r"[/\\]", symbol) or any(ord(char) < 32 or ord(char) == 127 for char in symbol):
        return "symbol-" + hashlib.sha256(symbol.encode("utf-8", errors="surrogatepass")).hexdigest()[:16]
    return re.sub(r"0x[0-9a-fA-F]+", "<address>", symbol)[:240]


def summarize(stats, *, top=50, root=ROOT, stdlib_roots=None):
    """Create JSON-safe, bounded self/cumulative rankings from pstats data."""
    if not 1 <= top <= 200:
        raise ProfileError("top rows must be in [1, 200]")
    if not math.isfinite(stats.total_tt) or stats.total_tt <= 0:
        raise ProfileError("profiler contains no positive finite measured time")
    rows = []
    for (filename, line, function), (primitive, total, own, cumulative, _callers) in stats.stats.items():
        if (not isinstance(line, int) or line < 0 or not isinstance(primitive, int) or primitive < 0
                or not isinstance(total, int) or total < primitive
                or any(not math.isfinite(value) or value < 0 for value in (own, cumulative))):
            raise ProfileError("invalid profiler aggregate")
        rows.append({"file": sanitize_file(filename, root=root, stdlib_roots=stdlib_roots), "line": line,
                     "function": sanitize_symbol(function), "primitiveCalls": primitive, "totalCalls": total,
                     "selfSeconds": own, "cumulativeSeconds": cumulative,
                     "selfSharePercent": own * 100 / stats.total_tt})
    identity = lambda row: (row["file"], row["line"], row["function"])
    return {
        "totalCalls": stats.total_calls, "primitiveCalls": stats.prim_calls,
        "totalSelfSeconds": stats.total_tt, "functionCount": len(rows),
        "topSelf": sorted(rows, key=lambda row: (-row["selfSeconds"], identity(row)))[:top],
        "topCumulative": sorted(rows, key=lambda row: (-row["cumulativeSeconds"], identity(row)))[:top],
    }


def local_output(path):
    root = ROOT / "reports/local"
    target = path.absolute()
    if not target.is_relative_to(root) or not target.resolve().is_relative_to(root.resolve()):
        raise ProfileError("raw profile output must be under reports/local")
    if target.is_symlink() or (target.exists() and (not target.is_dir() or any(target.iterdir()))):
        raise ProfileError("profile output must be an absent or empty directory")
    return target


def _csv_cell(value):
    return "'" + value if isinstance(value, str) and value.startswith(("=", "+", "-", "@")) else value


def write_summaries(summary, output):
    (output / "summary.json").write_text(json.dumps(summary, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    columns = ("ranking", "rank", "file", "line", "function", "primitiveCalls", "totalCalls",
               "selfSeconds", "cumulativeSeconds", "selfSharePercent")
    with (output / "summary.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        for name in ("topSelf", "topCumulative"):
            for rank, row in enumerate(summary[name], 1):
                writer.writerow({key: _csv_cell(value) for key, value in {"ranking": name, "rank": rank, **row}.items()})
    lines = ["# Pipecat instrumented Python diagnosis", "", summary["scope"], "",
             "Capture starts after the flushed measure marker and stops before the teardown marker. "
             "The measured client loop, numeric oracle, native queues/output callback and small phase-return "
             "coordination are included. Profiling changes execution cost; this run is excluded from the "
             "five-process headline comparison. Self time excludes subcalls; cumulative time includes them.", "",
             "Raw profiler data is generated locally and must not be published. The safe "
             "[JSON](summary.json) and [CSV](summary.csv) retain normalized code locations only.", "",
             "| Rank | Function | Self seconds | Cumulative seconds |", "| --- | --- | ---: | ---: |"]
    for rank, row in enumerate(summary["topSelf"][:15], 1):
        label = f"{row['file']}:{row['line']} ({row['function']})".replace("`", "'").replace("|", "\\|")
        lines.append(f"| {rank} | `{label}` | {row['selfSeconds']:.6f} | {row['cumulativeSeconds']:.6f} |")
    lines += ["", "Definitions follow the [CPython cProfile documentation](https://docs.python.org/3.12/library/profile.html).",
              "These instrumented call statistics do not explain Java native CPU stacks or establish a common allocation axis.", ""]
    (output / "profile.md").write_text("\n".join(lines), encoding="utf-8")


def collect(args):
    output = local_output(args.output)
    output.mkdir(parents=True, exist_ok=True)
    before = hashlib.sha256(ADAPTER.read_bytes()).hexdigest()
    spec = importlib.util.spec_from_file_location("ominicat_diagnostic_pipecat", ADAPTER)
    adapter = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = adapter
    spec.loader.exec_module(adapter)
    profiler = cProfile.Profile()
    capture = PhaseCapture(profiler, adapter.phase_event)
    adapter.phase_event = capture
    benchmark_args = adapter.arguments([
        "--stages", str(args.stages), "--warmup-seconds", str(args.warmup_seconds),
        "--measure-seconds", str(args.measure_seconds), "--max-samples", "2000000",
        "--output", str(output / "instrumented-result.json")])
    try:
        result = asyncio.run(adapter.execute(benchmark_args))
        capture.complete()
    finally:
        capture.stop()
        if "measure" in capture.phases:
            profiler.dump_stats(str(output / "python.prof"))
    after = hashlib.sha256(ADAPTER.read_bytes()).hexdigest()
    if before != after or result["adapterSha256"] != before:
        raise ProfileError("adapter source changed during the diagnostic")
    stats = pstats.Stats(profiler, stream=io.StringIO())
    summary = {
        "schemaVersion": 1, "engine": "pipecat", "version": result["version"], "profileType": "cProfile",
        "recordedAt": datetime.now(timezone.utc).isoformat(), "scope": SCOPE,
        "instrumented": True, "headlineEligible": False,
        "capture": {"start": "after flushed measure phase marker", "stop": "before teardown phase marker",
                    "phases": capture.phases, "startupIncluded": False, "warmupIncluded": False,
                    "teardownIncluded": False},
        "workload": {"mode": "queued-w1", "stages": args.stages, "warmupSecondsRequested": args.warmup_seconds,
                     "measureSecondsRequested": args.measure_seconds, "measuredFrames": result["measuredFrames"],
                     "instrumentedElapsedSeconds": result["elapsedSeconds"]},
        "runtime": {"implementation": platform.python_implementation(), "version": platform.python_version(),
                    "backend": "asyncio"},
        "adapterSha256": before, "profilerSourceSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        **summarize(stats, top=args.top),
    }
    result.update({"instrumented": True, "headlineEligible": False, "profileType": "cProfile", "scope": SCOPE})
    (output / "instrumented-result.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    write_summaries(summary, output)
    print(json.dumps({"event": "profile_complete", "instrumented": True, "headlineEligible": False,
                      "adapterSha256": before, "functionCount": summary["functionCount"]}), flush=True)


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", type=Path, help="run in this existing CPython 3.12 venv; never installs packages")
    parser.add_argument("--output", type=Path, required=True, help="new/empty directory under reports/local")
    parser.add_argument("--stages", type=int, choices=(1, 4, 16), default=4)
    parser.add_argument("--warmup-seconds", type=float, default=3.0)
    parser.add_argument("--measure-seconds", type=float, default=5.0)
    parser.add_argument("--top", type=int, default=50)
    args = parser.parse_args(argv)
    if not 1 <= args.top <= 200 or any(not math.isfinite(value) or not 0 < value <= 600
                                      for value in (args.warmup_seconds, args.measure_seconds)):
        parser.error("finite phase durations in (0, 600] and top in [1, 200] are required")
    return args


def main(argv=None):
    args = arguments(argv)
    try:
        if args.python and os.path.abspath(args.python) != os.path.abspath(sys.executable):
            command = [str(args.python), str(Path(__file__).absolute()), "--output", str(args.output),
                       "--stages", str(args.stages), "--warmup-seconds", str(args.warmup_seconds),
                       "--measure-seconds", str(args.measure_seconds), "--top", str(args.top)]
            return subprocess.run(command, check=False).returncode
        collect(args)
    except Exception as error:
        message = str(error) if isinstance(error, ProfileError) else "instrumented diagnostic failed"
        print(json.dumps({"event": "profile_failed", "errorType": type(error).__name__, "message": message}),
              file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

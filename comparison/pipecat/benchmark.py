#!/usr/bin/env python3
"""Protocol-1 native queued Pipecat adapter; synthetic values, no providers.

Run in a fresh CPython 3.12 process for every repetition. The ordinary asyncio
loop and Pipecat's default per-processor queues remain enabled. W=1 is a harness
admission rule, not a capacity limit on Pipecat's internal queues.
"""

from __future__ import annotations

import argparse
from array import array
import asyncio
import hashlib
from importlib import metadata
import json
import math
import os
from pathlib import Path
import platform
import sys
import tempfile
import time


VERSION = "1.15.0"
MASK = (1 << 64) - 1
STAGE_KEY = 0x9E3779B97F4A7C15
BUFFER_SLOTS = 2_097_152  # Exactly 16 MiB with an 8-byte unsigned item.
MAX_SAMPLES = 2_000_000  # The protocol's usable limit, excluding padding.
PHASE_TIMEOUT_MARGIN = 15.0
LIFECYCLE_TIMEOUT = 10.0


class BenchmarkError(RuntimeError):
    """A failed protocol invariant; no successful result may be published."""


def rotate_left(value: int, distance: int) -> int:
    distance &= 63
    value &= MASK
    return ((value << distance) | (value >> ((64 - distance) & 63))) & MASK


def combined_oracle(stages: int) -> tuple[int, int]:
    """Return one rotation and XOR mask; the terminal oracle is O(1)."""
    combined = 0
    for index in range(stages):
        combined = rotate_left(combined ^ ((STAGE_KEY + index) & MASK), 7)
    return (stages * 7) & 63, combined


def make_numeric_frame(base):
    """Keep numeric payload slots immutable while retaining native Frame state.

    Pipecat's non-frozen Frame owns mutable lifecycle metadata, so a frozen
    dataclass cannot inherit from it. These five slots instead reject ordinary
    reassignment/deletion, without replacing the framework's Frame constructor.
    Every application stage constructs a new native typed frame.
    """
    fields = frozenset(("sequence", "value", "started_ns", "measurement_index", "phase"))

    class NumericFrame(base):
        __slots__ = ("sequence", "value", "started_ns", "measurement_index", "phase")

        def __init__(self, sequence, value, started_ns, measurement_index, phase):
            super().__init__()
            self.sequence = sequence
            self.value = value
            self.started_ns = started_ns
            self.measurement_index = measurement_index
            self.phase = phase

        def __setattr__(self, name, value):
            if name in fields and hasattr(self, name):
                raise AttributeError("numeric payload is immutable")
            super().__setattr__(name, value)

        def __delattr__(self, name):
            if name in fields:
                raise AttributeError("numeric payload is immutable")
            super().__delattr__(name)

    return NumericFrame


def exact_quantiles(samples: array, count: int) -> dict[str, float]:
    """Exact nearest ranks in-place, without materializing millions of PyLongs.

    Multi-select uses the existing buffer and small partition stacks. All work
    happens after the measured phase and complete pipeline teardown.
    """
    if count < 1 or count > len(samples):
        raise BenchmarkError("invalid exact sample count")
    ranks = {label: math.ceil(count * percentile / 100) - 1
             for label, percentile in (("p50", 50), ("p95", 95), ("p99", 99), ("max", 100))}
    todo = [(0, count - 1, set(ranks.values()))]
    while todo:
        low, high, targets = todo.pop()
        if low >= high:
            continue
        pivot = sorted((samples[low], samples[(low + high) // 2], samples[high]))[1]
        left, cursor, right = low, low, high
        while cursor <= right:
            value = samples[cursor]
            if value < pivot:
                samples[left], samples[cursor] = value, samples[left]
                left += 1
                cursor += 1
            elif value > pivot:
                samples[cursor], samples[right] = samples[right], value
                right -= 1
            else:
                cursor += 1
        below = {rank for rank in targets if rank < left}
        above = {rank for rank in targets if rank > right}
        if below:
            todo.append((low, left - 1, below))
        if above:
            todo.append((right + 1, high, above))
    return {label: samples[rank] / 1000.0 for label, rank in ranks.items()}


class Ledger:
    """One outstanding frame, reusable acknowledgment, full terminal oracle."""

    def __init__(self, samples: array, stages: int, max_samples: int):
        self.samples = samples
        self.max_samples = max_samples
        self.rotation, self.combined = combined_oracle(stages)
        self.pending = None
        self.ack = asyncio.Event()
        self.failure = None
        self.sequence = 0
        self.warmup_frames = 0
        self.measured_frames = 0

    def fail(self, message):
        if self.failure is None:
            self.failure = BenchmarkError(message)
        self.ack.set()

    def healthy(self):
        if self.failure is not None:
            raise self.failure

    def accept(self, frame):
        pending = self.pending
        if pending is None:
            self.fail("unexpected or duplicate terminal data")
            return
        expected = rotate_left(self.sequence, self.rotation) ^ self.combined
        if (frame.sequence != self.sequence or frame.sequence != pending.sequence
                or frame.value != expected or frame.started_ns != pending.started_ns
                or frame.phase != pending.phase or frame.measurement_index != pending.measurement_index):
            self.fail("terminal nonce, order, payload or phase mismatch")
            return
        if frame.phase == 1 and (frame.measurement_index != self.measured_frames
                                or self.measured_frames >= self.max_samples):
            self.fail("measurement sample capacity or index mismatch")
            return
        if frame.phase not in (0, 1):
            self.fail("unexpected measurement phase")
            return
        # The stop timestamp follows full validation and precedes storage/ack.
        stopped = time.perf_counter_ns()
        if frame.phase == 1:
            self.samples[frame.measurement_index] = stopped - frame.started_ns
            self.measured_frames += 1
        else:
            self.warmup_frames += 1
        self.sequence += 1
        self.pending = None
        self.ack.set()


def phase_event(phase):
    print(json.dumps({"event": "phase", "phase": phase}, separators=(",", ":")), flush=True)


async def run_phase(worker, numeric_frame, ledger, duration, measured):
    phase_event("measure" if measured else "warmup")
    cpu_start = time.process_time_ns()
    started = time.perf_counter_ns()
    deadline = started + int(duration * 1_000_000_000)
    while time.perf_counter_ns() < deadline:
        ledger.healthy()
        if ledger.pending is not None:
            raise BenchmarkError("W=1 admission invariant violated")
        index = ledger.measured_frames if measured else -1
        if measured and index >= ledger.max_samples:
            raise BenchmarkError("exact sample buffer capacity exceeded")
        ledger.ack.clear()
        # Input allocation is inside latency; queue admission and caller ack are
        # also inside the wall throughput loop. No per-item timeout is added.
        input_started = time.perf_counter_ns()
        frame = numeric_frame(ledger.sequence, ledger.sequence, input_started, index, int(measured))
        ledger.pending = frame
        await worker.queue_frame(frame)
        await ledger.ack.wait()
        ledger.healthy()
    elapsed = (time.perf_counter_ns() - started) / 1_000_000_000
    cpu = (time.process_time_ns() - cpu_start) / 1_000_000_000
    if ledger.pending is not None:
        raise BenchmarkError("phase did not drain")
    return elapsed, cpu


async def execute(args):
    # Imports/setup are outside warmup and measurement. No provider is created.
    if platform.python_implementation() != "CPython" or sys.version_info[:2] != (3, 12):
        raise BenchmarkError("protocol 1 requires CPython 3.12")
    if metadata.version("pipecat-ai") != VERSION:
        raise BenchmarkError("the pinned pipecat-ai version is required")
    from loguru import logger
    logger.remove()
    logger.add(sys.stderr, level="ERROR")
    from pipecat.frames.frames import DataFrame, EndFrame, StartFrame
    from pipecat.pipeline.pipeline import Pipeline
    from pipecat.pipeline.worker import PipelineParams, PipelineWorker
    from pipecat.processors.frame_processor import FrameDirection, FrameProcessor
    from pipecat.workers.runner import WorkerRunner

    samples = array("Q", [0]) * BUFFER_SLOTS
    if samples.itemsize != 8 or len(samples) * samples.itemsize != 16 * 1024 * 1024:
        raise BenchmarkError("exact buffer must occupy 16 MiB")
    for index in range(0, BUFFER_SLOTS, 512):
        samples[index] = 0  # Explicit page pre-touch before warmup.
    ledger = Ledger(samples, args.stages, args.max_samples)
    numeric_frame = make_numeric_frame(DataFrame)

    class TransformStage(FrameProcessor):
        def __init__(self, index):
            super().__init__(name=f"numeric-{index}", enable_direct_mode=False)
            self.index = index
            self.count = self.starts = self.ends = self.cleanups = 0

        async def process_frame(self, frame, direction):
            await super().process_frame(frame, direction)
            if isinstance(frame, numeric_frame):
                if direction is not FrameDirection.DOWNSTREAM:
                    ledger.fail("unexpected upstream numeric frame")
                    return
                self.count += 1
                frame = numeric_frame(frame.sequence, rotate_left(frame.value ^ ((STAGE_KEY + self.index) & MASK), 7),
                                      frame.started_ns, frame.measurement_index, frame.phase)
            elif isinstance(frame, StartFrame):
                self.starts += 1
            elif isinstance(frame, EndFrame):
                self.ends += 1
            await self.push_frame(frame, direction)  # Including all controls.

        async def cleanup(self):
            self.cleanups += 1
            await super().cleanup()

    stages = [TransformStage(index) for index in range(args.stages)]
    worker = PipelineWorker(Pipeline(stages), name="comparison-pipeline", enable_rtvi=False,
                            enable_turn_tracking=False, enable_tracing=False, idle_timeout_secs=None,
                            cancel_on_idle_timeout=False, check_dangling_tasks=True,
                            cancel_timeout_secs=2.0, start_timeout_secs=5.0, setup_timeout_secs=5.0,
                            params=PipelineParams(enable_metrics=False, enable_usage_metrics=False,
                                                  enable_heartbeats=False, send_initial_empty_metrics=False))
    worker.set_reached_downstream_filter((numeric_frame,))
    ready = asyncio.Event()

    @worker.event_handler("on_pipeline_started")
    async def started(_worker, _frame):
        ready.set()

    @worker.event_handler("on_frame_reached_downstream")
    async def terminal(_worker, frame):
        ledger.accept(frame)

    @worker.event_handler("on_pipeline_error")
    async def failed(_worker, _frame):
        ledger.fail("Pipecat reported a pipeline error")

    @worker.event_handler("on_pipeline_timeout")
    async def timed_out(_worker, _frame):
        ledger.fail("Pipecat reported a lifecycle timeout")
        ready.set()

    runner = WorkerRunner(name="comparison-runner", handle_sigint=False, handle_sigterm=False,
                          force_gc=False, check_dangling_tasks=True)
    baseline = set(asyncio.all_tasks())
    run_task = None
    completed = False
    try:
        await runner.add_workers(worker)
        run_task = asyncio.create_task(runner.run(), name="comparison-runner")
        await asyncio.wait_for(ready.wait(), LIFECYCLE_TIMEOUT)
        ledger.healthy()
        warmup_elapsed, _ = await asyncio.wait_for(
            run_phase(worker, numeric_frame, ledger, args.warmup_seconds, False),
            args.warmup_seconds + PHASE_TIMEOUT_MARGIN)
        elapsed, cpu = await asyncio.wait_for(
            run_phase(worker, numeric_frame, ledger, args.measure_seconds, True),
            args.measure_seconds + PHASE_TIMEOUT_MARGIN)
        phase_event("teardown")
        await worker.stop_when_done()
        await asyncio.wait_for(asyncio.shield(run_task), LIFECYCLE_TIMEOUT)
        ledger.healthy()
        total = ledger.warmup_frames + ledger.measured_frames
        if (not worker.has_finished() or ledger.pending is not None or ledger.measured_frames < 1
                or any(stage.count != total or stage.starts != 1 or stage.ends != 1 or stage.cleanups != 1
                       for stage in stages)):
            raise BenchmarkError("output conservation or lifecycle cleanup mismatch")
        await asyncio.sleep(0)
        if any(not task.done() for task in asyncio.all_tasks() - baseline):
            raise BenchmarkError("owned asyncio work remains after teardown")
        completed = True
    finally:
        if not completed:
            # Cancellation is independently bounded. The parent process runner
            # must also enforce its outer process deadline for library defects.
            try:
                await asyncio.wait_for(runner.cancel(reason="benchmark failure"), LIFECYCLE_TIMEOUT)
                if run_task is not None:
                    await asyncio.wait_for(asyncio.shield(run_task), LIFECYCLE_TIMEOUT)
            except (Exception, asyncio.CancelledError):
                if run_task is not None:
                    run_task.cancel()
            pending = [task for task in asyncio.all_tasks() - baseline if not task.done()]
            for task in pending:
                task.cancel()
            if pending:
                await asyncio.wait_for(asyncio.gather(*pending, return_exceptions=True), LIFECYCLE_TIMEOUT)
            ledger.pending = None
    return {
        "schemaVersion": 1, "engine": "pipecat", "version": VERSION, "mode": "queued-w1",
        "stages": args.stages, "sessions": 1, "inflight": 1,
        "warmupSeconds": warmup_elapsed, "warmupFrames": ledger.warmup_frames,
        "measuredFrames": ledger.measured_frames, "elapsedSeconds": elapsed,
        "throughputFramesPerSecond": ledger.measured_frames / elapsed,
        "latencyMicros": exact_quantiles(samples, ledger.measured_frames), "processCpuSeconds": cpu,
        "checks": {"allOutputsValidated": True, "exactOutputCount": True, "noRejections": True,
                   "drained": True, "cleanupComplete": True},
        "environment": {"runtime": "CPython", "version": platform.python_version(),
                        "backend": "asyncio", "eventLoop": type(asyncio.get_running_loop()).__name__,
                        "sampleBufferBytes": len(samples) * samples.itemsize},
        "adapterSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    }


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stages", type=int, choices=(1, 4, 16), required=True)
    parser.add_argument("--warmup-seconds", type=float, default=3.0)
    parser.add_argument("--measure-seconds", type=float, default=5.0)
    parser.add_argument("--max-samples", type=int, default=MAX_SAMPLES)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if any(not math.isfinite(value) or not 0 < value <= 600
           for value in (args.warmup_seconds, args.measure_seconds)):
        parser.error("phase durations must be finite and in (0, 600]")
    if not 1 <= args.max_samples <= MAX_SAMPLES:
        parser.error("max-samples must be in [1, 2000000]")
    if args.output.exists():
        parser.error("output must not already exist")
    return args


def main(argv=None):
    args = arguments(argv)
    try:
        result = asyncio.run(execute(args))
        content = json.dumps(result, allow_nan=False, indent=2) + "\n"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=args.output.parent,
                                         prefix=".pipecat-", suffix=".json", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(content)
        try:
            os.replace(temporary, args.output)
        finally:
            temporary.unlink(missing_ok=True)
        print(json.dumps(result, allow_nan=False, separators=(",", ":")), flush=True)
    except Exception as error:
        # Third-party tracebacks can include personal paths. Keep the public
        # failure line small; a failed fork has no successful output file.
        message = str(error) if isinstance(error, BenchmarkError) else "adapter execution failed"
        print(json.dumps({"event": "failure", "errorType": type(error).__name__, "message": message}),
              file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

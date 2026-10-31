# Model-free framework-path comparison, protocol 1

This optional benchmark compares native queued execution paths, not feature parity or production capacity. It is outside the production reactor and adds no runtime dependency to OminiCat.

## Main matrix

- Engines: current OminiCat, Pipecat 1.15.0, Apache Camel 4.22.2 LTS.
- Java: 17 platform workers; Python: the recorded CPython 3.12 interpreter with ordinary asyncio, no uvloop/multiprocess.
- One process, one pipeline/session and **one outstanding data frame (W=1)**.
- Application stages: 1, 4, 16. Framework source/sink/worker costs remain included.
- 5 fresh processes per cell, engine order rotated deterministically between repetitions. Each process warms for 3 seconds, fully drains, then measures for 5 seconds.
- No models, HTTP, JSON, media codecs, tracing or per-frame logging inside timing. Startup/teardown are excluded.

## Payload and output oracle

Each application stage receives an immutable numeric value with `sequence`, `value`, input monotonic timestamp and measurement index/phase. Start value equals sequence. Stage i (zero based) produces a new value with `value = rotateLeft64(value XOR (0x9e3779b97f4a7c15 + i), 7)`, preserving sequence/timestamp/phase. Python masks all arithmetic to 64 bits. No media bytes are copied in this profile.

Every terminal data output must match the expected sequence, order and transformed value. Expected value uses a precomputed combined XOR mask and rotation, so the oracle does not replay the whole stage chain. Missing, duplicate, incorrect or unexpected data, rejection, timeout, cleanup failure or leaked work makes the process fail. Framework control frames are forwarded correctly but excluded from data counts.

OminiCat uses PipelineSession; Camel uses SEDA with size=1, one consumer, no discard and a terminal sink; Pipecat uses the current PipelineWorker/WorkerRunner and default queued FrameProcessor mode. Pipecat's optional RTVI, turn tracking, metrics, usage, heartbeats and tracing are explicitly off. A harness W=1 gate is not evidence that Pipecat's internal unbounded queues provide the same backpressure contract.

## Timing and resources

Start each latency observation immediately before constructing/submitting the input numeric item; stop in the terminal sink **after output validation**, before caller acknowledgment. This includes input/stage allocations, native admission, queueing, processing and terminal validation. Wall throughput is all validated measured outputs divided by the whole measured client loop elapsed time, including the caller acknowledgment. It is not saturated capacity or exactly the reciprocal of terminal latency.

Keep at most 2,000,000 exact latency samples in a fixed 2,097,152-element / 16 MiB buffer allocated and pre-touched before warmup. Exceeding capacity is an explicit failed run. Record exact nearest-rank p50/p95/p99/max after timing. Full output oracle and timer/sample overhead remain in the measured path. Record process CPU time across the measured phase.

The parent runner samples OS process RSS at 50 ms intervals, separately retaining whole-process and measurement-phase peaks. RSS includes interpreter/JVM, imported libraries and the common harness buffer; it is neither retained heap nor framework-only allocation. Sampling may miss brief peaks. Java uses -Xms128m -Xmx512m; Python has no equivalent managed-heap switch. Cross-language differences include runtime, scheduler and object-model costs.

Profiler runs are separate from headline timings. Java JFR allocation/CPU/GC and Python profiler observations must not be put on a shared B/op allocation axis.

## Adapter interface / result schema

Adapters accept `--stages N --warmup-seconds S --measure-seconds S --max-samples 2000000 --output FILE`. The Java adapter additionally accepts `--engine ominicat|camel`. Print flushed JSON-line phase events (`event: phase`, `phase: warmup|measure|teardown`); the parent uses these only for resource sampling. Write the final successful JSON to FILE and print it once.

Required result fields:

- `schemaVersion: 1`, `engine`, `version`, `mode: queued-w1`, `stages`, `sessions: 1`, `inflight: 1`;
- `warmupSeconds` (actual), `warmupFrames`, `measuredFrames`, `elapsedSeconds`, `throughputFramesPerSecond`;
- `latencyMicros: {p50,p95,p99,max}`, `processCpuSeconds`;
- `checks: {allOutputsValidated:true, exactOutputCount:true, noRejections:true, drained:true, cleanupComplete:true}`;
- `environment` with runtime/version/backend, no unrelated process inventory/environment values.

Failures must exit nonzero without a successful result. Do not silently drop failed forks or retain a best run. The report shows all five repetitions, median and observed min-max range; a range is not a confidence interval.

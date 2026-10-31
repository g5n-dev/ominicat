# Local kernel performance evidence

Recorded: 2026-10-31T06:13:49.096290Z. These measurements cover the in-memory Java kernel only. They do not measure model inference, speech accuracy, networking, WebRTC, or end-to-end agent latency.

Runtime/source fingerprint: `7f3b929c919da480b5014b1bd01b9891c1b1aa034787fe8a5facfcb320978017`. [Per-file SHA-256 manifest](source-sha256.json).

## Environment

- Java: Homebrew 21.0.13.0.3
- Platform: Mac OS X / aarch64; 10 available processors
- Maximum heap: 512 MiB
- The host was shared with development work and a concurrent rate-limited correctness soak (64 steady clients plus 4 churn clients). This is not a server capacity test or a dedicated-host benchmark.

## JMH dispatch and scheduling

Each dispatch stage transforms a changing numeric payload. Blackhole consumes the transformed result. Dispatch includes frame allocation; the session test includes one outstanding frame, bounded ingress, worker scheduling, and output queue handoff.

| Benchmark | Stages | Score | JMH error (99.9% CI) | Unit | Allocation B/op |
|---|---:|---:|---:|---|---:|
| PipelineBenchmark.dispatch | 1 | 113,224,138.704 | ± 1,559,095.817 | ops/s | 112.000 |
| PipelineBenchmark.dispatch | 4 | 40,009,652.744 | ± 263,268.497 | ops/s | 328.000 |
| PipelineBenchmark.dispatch | 16 | 11,615,086.320 | ± 225,706.073 | ops/s | 1,192.001 |
| SessionBenchmark.roundTrip | 1 | 8.128 | ± 0.261 | us/op | 272.032 |
| SessionBenchmark.roundTrip | 4 | 8.229 | ± 0.189 | us/op | 512.014 |
| SessionBenchmark.roundTrip | 16 | 8.575 | ± 0.202 | us/op | 1,472.031 |

Configuration: 2 fork(s), 3 warmup iterations × 1 s, 5 measurement iterations × 1 s; 1 JMH thread(s). GC allocation profiler enabled.

## Concurrent bounded-inflight load

16 sessions × 4 stages, at most 32 outstanding frames per session. 100,000 warmup frames followed by 25,000,000 measured frames; 0 rejected frames.

| Accepted throughput | p50 | p95 | p99 | Maximum |
|---:|---:|---:|---:|---:|
| 3,345,082 frames/s | 32.375 µs | 69.459 µs | 272.458 µs | 31,027.834 µs |

Latency is measured from immediately before submission to the output callback, including ingress queue waiting. Waiting for the generator's in-flight permit is excluded. This is a closed-loop test; it does not correct for coordinated omission or predict latency under an independently arriving overload. No frames are discarded from the percentile calculation. Percentiles use the nearest-rank method over the exact measured sample array.

## Profiles

- `jdk.ExecutionSample`: 791 recorded events.
- `jdk.ObjectAllocationSample`: 2267 recorded events.
- `jdk.GarbageCollection`: 30 recorded events.
- `jdk.JavaMonitorEnter`: 0 recorded events.
- `jdk.VirtualThreadPinned`: 0 recorded events.

CPU sampled leaf methods (whole load process, including warmup and percentile sorting):

| Method | Samples |
|---|---:|
| `io.ominicat.core.Pipeline.dispatchSession` | 166 |
| `java.lang.System$2.unparkVirtualThread` | 122 |
| `io.ominicat.core.PipelineSession.valid` | 106 |
| `jdk.internal.misc.Unsafe.getAndBitwiseAndInt` | 87 |
| `java.util.concurrent.CountDownLatch$Sync.tryReleaseShared` | 81 |
| `java.util.DualPivotQuicksort.sort` | 58 |
| `java.lang.VirtualThread.parkNanos` | 29 |
| `java.util.concurrent.locks.ReentrantLock$Sync.lock` | 26 |

Sampled allocation weights (estimates, not exact per-type allocation totals):

| Type | Estimated MiB |
|---|---:|
| `io.ominicat.core.Pipeline$$Lambda.0x000000d80100cac8` | 2,902.082 |
| `io.ominicat.core.SynchronousSink` | 2,139.987 |
| `io.ominicat.benchmarks.CoreLoad$Probe` | 1,192.092 |
| `io.ominicat.core.PipelineSession$Pending` | 783.803 |
| `io.ominicat.core.PipelineSession$$Lambda.0x000000d80100b958` | 575.023 |
| `[J` | 211.192 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ExclusiveNode` | 202.383 |
| `java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask` | 68.579 |

Event counts are observations from this recording. Zero recorded pinning events does not prove the absence of pinning in other workloads; JFR thresholds and sampling apply.

Raw evidence: [JMH JSON](jmh.json), [JMH output](jmh-output.txt), [load JSON](load.json), [GC log](gc.txt), [JFR summary](jfr-summary.txt), [compact JFR hotspots](jfr-hotspots.json). The binary JFR recording (`kernel.jfr`) and uncompressed selected event export are generated locally and are excluded from source control.

Reproduce from the repository root with Java 21+: `scripts/profile.sh quick reports/latest`. Use `full` for longer warmup and measurement. Open the JFR recording in JDK Mission Control for stack, allocation, lock, and GC analysis.

Compare revisions on the same idle machine, JVM, heap, stage count, and profiler settings. Broader claims require longer runs, repetitions, arrival-rate load tests, and real provider and transport measurements.

[Historical comparison and observed allocation changes](comparison.md). [Measurement and artifact context](measurement-context.json). [CPU/allocation flame graphs and GC summary](profiles.md).

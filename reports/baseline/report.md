# Local kernel performance evidence

Recorded: 2026-10-31T05:07:24.283691Z. These measurements cover the in-memory Java kernel only. They do not measure model inference, speech accuracy, networking, WebRTC, or end-to-end agent latency.

Runtime/source fingerprint: `7e06420ee8e1911f75afc30a4fc687fecdf3c468dc7e805d73ced416c6e030aa`. [Per-file SHA-256 manifest](source-sha256.json).

## Environment

- Java: Homebrew 21.0.13.0.3
- Platform: Mac OS X / aarch64; 10 available processors
- Maximum heap: 512 MiB
- The machine was shared with development tasks. Results are an initial baseline, not a capacity guarantee.

## JMH dispatch and scheduling

Each dispatch stage transforms a changing numeric payload. Blackhole consumes the transformed result. Dispatch includes frame allocation; the session test includes one outstanding frame, bounded ingress, worker scheduling, and output queue handoff.

| Benchmark | Stages | Score | JMH error (99.9% CI) | Unit | Allocation B/op |
|---|---:|---:|---:|---|---:|
| PipelineBenchmark.dispatch | 1 | 234,421,494.592 | ± 5,062,946.652 | ops/s | 64.000 |
| PipelineBenchmark.dispatch | 4 | 79,935,482.684 | ± 1,869,628.437 | ops/s | 136.000 |
| PipelineBenchmark.dispatch | 16 | 25,120,225.657 | ± 1,270,195.551 | ops/s | 424.000 |
| SessionBenchmark.roundTrip | 1 | 8.320 | ± 0.516 | us/op | 215.893 |
| SessionBenchmark.roundTrip | 4 | 8.114 | ± 0.268 | us/op | 287.893 |
| SessionBenchmark.roundTrip | 16 | 8.805 | ± 0.298 | us/op | 575.897 |

Configuration: 2 fork(s), 3 warmup iterations × 1 s, 5 measurement iterations × 1 s; 1 JMH thread(s). GC allocation profiler enabled.

## Concurrent bounded-inflight load

16 sessions × 4 stages, at most 32 outstanding frames per session. 100,000 warmup frames followed by 25,000,000 measured frames; 0 rejected frames.

| Accepted throughput | p50 | p95 | p99 | Maximum |
|---:|---:|---:|---:|---:|
| 3,391,487 frames/s | 32.334 µs | 72.958 µs | 257.958 µs | 20,786.459 µs |

Latency is measured from immediately before submission to the output callback, including ingress queue waiting. Waiting for the generator's in-flight permit is excluded. This is a closed-loop test; it does not correct for coordinated omission or predict latency under an independently arriving overload. No frames are discarded from the percentile calculation. Percentiles use the nearest-rank method over the exact measured sample array.

## Profiles

- `jdk.ExecutionSample`: 889 recorded events.
- `jdk.ObjectAllocationSample`: 2191 recorded events.
- `jdk.GarbageCollection`: 12 recorded events.
- `jdk.JavaMonitorEnter`: 0 recorded events.
- `jdk.VirtualThreadPinned`: 0 recorded events.

CPU sampled leaf methods (whole load process, including warmup and percentile sorting):

| Method | Samples |
|---|---:|
| `java.util.concurrent.locks.LockSupport.unpark` | 515 |
| `jdk.internal.misc.Unsafe.getAndBitwiseAndInt` | 143 |
| `java.util.DualPivotQuicksort.sort` | 67 |
| `java.lang.VirtualThread.parkNanos` | 51 |
| `io.ominicat.core.PipelineSession.run` | 16 |
| `java.util.concurrent.CountDownLatch$Sync.tryReleaseShared` | 16 |
| `io.ominicat.benchmarks.CoreLoad.lambda$runPhase$4` | 11 |
| `java.lang.System$2.unparkVirtualThread` | 10 |

Sampled allocation weights (estimates, not exact per-type allocation totals):

| Type | Estimated MiB |
|---|---:|
| `io.ominicat.benchmarks.CoreLoad$Probe` | 1,224.031 |
| `io.ominicat.core.PipelineSession$Pending` | 732.665 |
| `io.ominicat.core.PipelineSession$$Lambda.0x000000e80100bba8` | 528.602 |
| `[J` | 211.193 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ExclusiveNode` | 93.189 |
| `java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask` | 57.338 |
| `java.util.concurrent.ForkJoinTask$AdaptedRunnableAction` | 44.071 |
| `java.lang.VirtualThread$$Lambda.0x000000e80103e8c8` | 36.146 |

Event counts are observations from this recording. Zero recorded pinning events does not prove the absence of pinning in other workloads; JFR thresholds and sampling apply.

Raw evidence: [JMH JSON](jmh.json), [JMH output](jmh.log), [load JSON](load.json), [GC log](gc.log), [JFR summary](jfr-summary.txt), [compact JFR hotspots](jfr-hotspots.json). The binary JFR recording (`kernel.jfr`) and uncompressed selected event export are generated locally and are excluded from source control.

Reproduce from the repository root with Java 21+: `scripts/profile.sh quick reports/latest`. Use `full` for longer warmup and measurement. Open the JFR recording in JDK Mission Control for stack, allocation, lock, and GC analysis.

Compare revisions on the same idle machine, JVM, heap, stage count, and profiler settings. Broader claims require longer runs, repetitions, arrival-rate load tests, and real provider and transport measurements.

Additional evidence: [CPU/allocation flame graphs and GC analysis](profiles.md), [real WebSocket echo result](websocket.json), and [test/coverage verification](../verification.md).

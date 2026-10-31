# Local kernel performance evidence

Recorded: 2026-10-31T20:11:05.862261985Z. These measurements cover the in-memory Java kernel only. They do not measure model inference, speech accuracy, networking, WebRTC, or end-to-end agent latency.

[Run manifest and measured benchmark JAR SHA-256](run-manifest.json).

Evidence validation: strict manifest mode; complete benchmark/parameter matrix and load counters checked.

Runtime/source fingerprint: `521ea6ea3ef70373aebd78c194f1fe464867dc734d244b11debc4416eeb8f809`. [Per-file SHA-256 manifest](source-sha256.json).

## Environment

- Java: Eclipse Adoptium 17.0.21
- Platform: Linux / amd64; 4 available processors
- Maximum heap: 512 MiB
- The machine was shared with development tasks. Results are an initial baseline, not a capacity guarantee.

## JMH dispatch and scheduling

Each dispatch stage transforms a changing numeric payload. Blackhole consumes the transformed result. Dispatch includes frame allocation; the session test includes one outstanding frame, bounded ingress, worker scheduling, and output queue handoff.

| Benchmark | Stages | Score | JMH error (99.9% CI) | Unit | Allocation B/op |
|---|---:|---:|---:|---|---:|
| PipelineBenchmark.dispatch | 1 | 59,090,636.005 | ± 1,174,249.131 | ops/s | 112.000 |
| PipelineBenchmark.dispatch | 4 | 22,189,603.035 | ± 239,393.999 | ops/s | 328.000 |
| PipelineBenchmark.dispatch | 16 | 5,514,447.752 | ± 21,234.574 | ops/s | 1,192.001 |
| SessionBenchmark.roundTrip | 1 | 23.226 | ± 1.595 | us/op | 248.042 |
| SessionBenchmark.roundTrip | 4 | 22.363 | ± 1.472 | us/op | 488.033 |
| SessionBenchmark.roundTrip | 16 | 23.365 | ± 1.441 | us/op | 1,448.037 |

Configuration: 3 fork(s), 5 warmup iterations × 2 s, 8 measurement iterations × 2 s; 1 JMH thread(s). GC allocation profiler enabled.

## Concurrent bounded-inflight load

16 sessions × 4 stages, at most 32 outstanding frames per session. 100,000 warmup frames followed by 50,000,000 measured frames; 0 rejected frames.

| Accepted throughput | p50 | p95 | p99 | Maximum |
|---:|---:|---:|---:|---:|
| 3,872,063 frames/s | 69.127 µs | 145.855 µs | 417.647 µs | 11,367.096 µs |

Latency is measured from immediately before submission to the output callback, including ingress queue waiting. Waiting for the generator's in-flight permit is excluded. This is a closed-loop test; it does not correct for coordinated omission or predict latency under an independently arriving overload. No frames are discarded from the percentile calculation. Percentiles use the nearest-rank method over the exact measured sample array.

## Profiles

- `jdk.ExecutionSample`: 1200 recorded events.
- `jdk.ObjectAllocationSample`: 3918 recorded events.
- `jdk.GarbageCollection`: 344 recorded events.
- `jdk.JavaMonitorEnter`: 0 recorded events.
- `jdk.VirtualThreadPinned`: not available on this runtime.

CPU sampled leaf methods (whole load process, including warmup and percentile sorting):

| Method | Samples |
|---|---:|
| `java.util.concurrent.locks.LockSupport.unpark` | 637 |
| `java.util.DualPivotQuicksort.sort` | 361 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire` | 81 |
| `io.ominicat.core.Pipeline.lambda$dispatchSession$2` | 24 |
| `io.ominicat.core.PipelineSession.run` | 21 |
| `java.lang.invoke.DirectMethodHandle.allocateInstance` | 13 |
| `java.util.concurrent.locks.ReentrantLock.lock` | 13 |
| `io.ominicat.core.PipelineSession.take` | 8 |

Sampled allocation weights (estimates, not exact per-type allocation totals):

| Type | Estimated MiB |
|---|---:|
| `io.ominicat.core.Pipeline$$Lambda$101+0x00007b312c014000.320929598` | 5,611.436 |
| `io.ominicat.core.SynchronousSink` | 4,846.964 |
| `io.ominicat.benchmarks.CoreLoad$Probe` | 2,337.570 |
| `io.ominicat.core.PipelineSession$Pending` | 1,457.075 |
| `io.ominicat.core.PipelineSession$$Lambda$98+0x00007b312c00fa10.342683394` | 1,323.162 |
| `[J` | 381.605 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode` | 73.290 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode` | 56.938 |

Event counts are observations from this recording. Zero recorded pinning events does not prove the absence of pinning in other workloads; JFR thresholds and sampling apply.

Raw evidence: [JMH JSON](jmh.json), [JMH output](jmh.log), [load JSON](load.json), [GC log](gc.log), [JFR summary](jfr-summary.txt), [compact JFR hotspots](jfr-hotspots.json). The binary JFR recording (`kernel.jfr`) and uncompressed selected event export are generated locally and are excluded from source control.

Reproduce from the repository root with Java 17+: `scripts/profile.sh quick reports/latest`. Use `full` for longer warmup and measurement. Open the JFR recording in JDK Mission Control for stack, allocation, lock, and GC analysis.

Compare revisions on the same idle machine, JVM, heap, stage count, and profiler settings. Broader claims require longer runs, repetitions, arrival-rate load tests, and real provider and transport measurements.

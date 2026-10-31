# Local kernel performance evidence

Recorded: 2026-10-31T07:21:53.715391Z. These measurements cover the in-memory Java kernel only. They do not measure model inference, speech accuracy, networking, WebRTC, or end-to-end agent latency.

Runtime/source fingerprint: `83c64c086b23e3513e81e1bf571a52329e0dab5e51d17b529782368c9b7f49f9`. [Per-file SHA-256 manifest](source-sha256.json).

## Environment

- Java: Homebrew 17.0.21.0.3
- Platform: Mac OS X / aarch64; 10 available processors
- Maximum heap: 512 MiB
- The host was shared with development work and a concurrent 120-second paced/native Java 17 compatibility validation. This is not a dedicated-host benchmark, controlled A/B experiment, or server/provider capacity test.

- Worker backend: **platform**, named daemon workers on Java 17; built classes use major version **61**. [Observed worker/executor probe](worker-backend.json).

## JMH dispatch and scheduling

Each dispatch stage transforms a changing numeric payload. Blackhole consumes the transformed result. Dispatch includes frame allocation; the session test includes one outstanding frame, bounded ingress, worker scheduling, and output queue handoff.

| Benchmark | Stages | Score | JMH error (99.9% CI) | Unit | Allocation B/op |
|---|---:|---:|---:|---|---:|
| PipelineBenchmark.dispatch | 1 | 111,304,843.502 | ± 3,796,904.383 | ops/s | 112.000 |
| PipelineBenchmark.dispatch | 4 | 40,536,488.832 | ± 1,222,608.090 | ops/s | 328.000 |
| PipelineBenchmark.dispatch | 16 | 11,447,645.104 | ± 79,358.318 | ops/s | 1,192.001 |
| SessionBenchmark.roundTrip | 1 | 6.862 | ± 0.093 | us/op | 248.036 |
| SessionBenchmark.roundTrip | 4 | 7.104 | ± 0.939 | us/op | 488.028 |
| SessionBenchmark.roundTrip | 16 | 7.062 | ± 0.315 | us/op | 1,448.021 |

Configuration: 2 fork(s), 3 warmup iterations × 1 s, 5 measurement iterations × 1 s; 1 JMH thread(s). GC allocation profiler enabled.

## Concurrent bounded-inflight load

16 sessions × 4 stages, at most 32 outstanding frames per session. 100,000 warmup frames followed by 25,000,000 measured frames; 0 rejected frames.

| Accepted throughput | p50 | p95 | p99 | Maximum |
|---:|---:|---:|---:|---:|
| 2,706,282 frames/s | 57.250 µs | 256.875 µs | 1,436.041 µs | 108,764.167 µs |

Latency is measured from immediately before submission to the output callback, including ingress queue waiting. Waiting for the generator's in-flight permit is excluded. This is a closed-loop test; it does not correct for coordinated omission or predict latency under an independently arriving overload. No frames are discarded from the percentile calculation. Percentiles use the nearest-rank method over the exact measured sample array.

## Profiles

- `jdk.ExecutionSample`: 524 recorded events.
- `jdk.ObjectAllocationSample`: 2814 recorded events.
- `jdk.GarbageCollection`: 33 recorded events.
- `jdk.JavaMonitorEnter`: 0 recorded events.
- `jdk.VirtualThreadPinned`: not available on this runtime.

CPU sampled leaf methods (whole load process, including warmup and percentile sorting):

| Method | Samples |
|---|---:|
| `java.util.concurrent.locks.LockSupport.unpark` | 272 |
| `java.util.DualPivotQuicksort.sort` | 71 |
| `java.util.concurrent.CountDownLatch$Sync.tryReleaseShared` | 53 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire` | 46 |
| `io.ominicat.core.Pipeline.lambda$dispatchSession$2` | 30 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.signalNextIfShared` | 21 |
| `io.ominicat.core.PipelineSession.run` | 10 |
| `io.ominicat.benchmarks.CoreLoad.lambda$runPhase$4` | 9 |

Sampled allocation weights (estimates, not exact per-type allocation totals):

| Type | Estimated MiB |
|---|---:|
| `io.ominicat.core.Pipeline$$Lambda$93+0x000000700100fd60.2065602256` | 2,778.293 |
| `io.ominicat.core.SynchronousSink` | 2,467.815 |
| `io.ominicat.benchmarks.CoreLoad$Probe` | 1,022.432 |
| `io.ominicat.core.PipelineSession$Pending` | 843.538 |
| `io.ominicat.core.PipelineSession$$Lambda$86+0x000000700100f6e8.1407567661` | 600.434 |
| `[J` | 209.197 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ExclusiveNode` | 156.540 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode` | 38.244 |

Event counts are observations from this recording. Zero recorded pinning events does not prove the absence of pinning in other workloads; JFR thresholds and sampling apply.

Raw evidence: [JMH JSON](jmh.json), [JMH output](jmh-output.txt), [load JSON](load.json), [GC log](gc.txt), [JFR summary](jfr-summary.txt), [compact JFR hotspots](jfr-hotspots.json). The binary JFR recording (`kernel.jfr`) and uncompressed selected event export are generated locally and are excluded from source control.

Reproduce from the repository root with Java 17+: `scripts/profile.sh quick reports/latest`. Use `full` for longer warmup and measurement. Open the JFR recording in JDK Mission Control for stack, allocation, lock, and GC analysis.

Compare revisions on the same idle machine, JVM, heap, stage count, and profiler settings. Broader claims require longer runs, repetitions, arrival-rate load tests, and real provider and transport measurements.

[CPU/allocation flame graphs and GC summary](profiles.md) · [Measurement and artifact context](measurement-context.json) · [Profile cleanup verification](tooling-verification.json).

This Java 17 measurement is separate from historical Java 21 reports. It does not isolate the cost of runtime version, worker backend, or other changes. The synchronous JMH cases do not separately benchmark callback retirement or live model services.

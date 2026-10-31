# CPU, allocation and GC profile

Generated from the selected JFR events for the bounded kernel load. Samples include the load harness, warmup and exact-percentile sorting; they are not a model or network profile. The host was shared with development work and a concurrent 120-second paced/native Java 17 compatibility validation. This is not a dedicated-host benchmark, controlled A/B experiment, or server/provider capacity test.

The original recording was re-exported with `jfr print --stack-depth 64`, recovering CPU stacks up to 46 frames and allocation stacks up to 39 frames. All 3,371 event records match the earlier export after projecting stacks back to its default five frames; timestamps, sampled weights and measurement results are unchanged. [Export provenance](measurement-context.json) preserves the original recording and prior export fingerprints. The raw JFR stays local.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

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
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await` | 2 |
| `java.util.concurrent.locks.LockSupport.park` | 2 |
| `java.lang.invoke.DirectMethodHandle.allocateInstance` | 2 |
| `java.util.DualPivotQuicksort.mixedInsertionSort` | 2 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. The harness retains an exact latency array; its `long[]` allocation is intentionally visible. Use JMH `gc.alloc.rate.norm` for per-operation allocation estimates.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.core.Pipeline$$Lambda$93+0x000000700100fd60.2065602256` | 2,778.29 |
| `io.ominicat.core.SynchronousSink` | 2,467.82 |
| `io.ominicat.benchmarks.CoreLoad$Probe` | 1,022.43 |
| `io.ominicat.core.PipelineSession$Pending` | 843.54 |
| `io.ominicat.core.PipelineSession$$Lambda$86+0x000000700100f6e8.1407567661` | 600.43 |
| `[J` | 209.20 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ExclusiveNode` | 156.54 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode` | 38.24 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode` | 28.13 |
| `jdk.internal.org.objectweb.asm.SymbolTable$Entry` | 1.00 |
| `java.lang.InterruptedException` | 0.33 |
| `[B` | 0.25 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 33.
- Recorded GC pause sum: 33.959 ms; longest collection pause sum: 1.715 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the measured-load start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [measurement report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

[GC log](gc.txt).

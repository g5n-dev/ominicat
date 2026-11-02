# CPU, allocation and GC profile

Independent instrumented Java 17 JMH diagnostic JVM, including startup, setup, two 1-second warmups, two 1-second measurements, harness and teardown. No phase filtering. CPU sampling requests 10ms; allocation sampling is throttled to 50 events/second; recording and export retain up to 64 frames. This is not main timing evidence, per-operation allocation, retained heap, method duration, p99 latency, RSS or production capacity.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `io.ominicat.core.Pipeline.dispatch` | 94 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.acceptOutput` | 88 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.invoke` | 59 |
| `io.ominicat.core.Pipeline$DirectInvocation.accept` | 58 |
| `io.ominicat.core.Pipeline.process` | 36 |
| `io.ominicat.benchmarks.DirectStateProbe.invoke` | 6 |
| `sun.security.provider.SHA2.implCompress0` | 2 |
| `java.lang.invoke.VarHandleByteArrayAsInts$ArrayHandle.get` | 2 |
| `java.lang.invoke.InvokerBytecodeGenerator.lookupPregenerated` | 1 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.expected` | 1 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.lambda$new$4` | 1 |
| `java.lang.Math.multiplyExact` | 1 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. Different fixed-duration loops can perform different operation counts. This probe has no exact latency array. Use formal paired JMH B/op for per-operation allocation.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.benchmarks.DirectStateProbe$Value` | 11,312.77 |
| `io.ominicat.core.Pipeline$DirectInvocation` | 11,080.41 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 19.04 |
| `[B` | 2.04 |
| `java.util.HashMap` | 0.41 |
| `java.lang.Object` | 0.39 |
| `java.lang.invoke.MethodType$ConcurrentWeakInternSet$WeakEntry` | 0.20 |
| `java.util.zip.ZipFile$InflaterCleanupAction` | 0.15 |
| `java.util.concurrent.CopyOnWriteArrayList$COWIterator` | 0.00 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 149.
- Recorded GC pause sum: 98.784 ms; longest collection pause sum: 6.333 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

# CPU, allocation and GC profile

Independent instrumented Java 17 JMH diagnostic JVM, including startup, setup, two 1-second warmups, two 1-second measurements, harness and teardown. No phase filtering. CPU sampling requests 10ms; allocation sampling is throttled to 50 events/second; recording and export retain up to 64 frames. This is not main timing evidence, per-operation allocation, retained heap, method duration, p99 latency, RSS or production capacity.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `io.ominicat.core.Pipeline$DirectInvocation.dispatchNext` | 194 |
| `io.ominicat.core.Pipeline.process` | 45 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.invoke` | 24 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.expected` | 24 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness$$Lambda$125+0x00007feba4094c70.857831642.accept` | 22 |
| `io.ominicat.benchmarks.DirectStateProbe.invoke` | 8 |
| `jdk.internal.loader.BuiltinClassLoader.loadClassOrNull` | 1 |
| `java.util.zip.ZipFile$Source.readAt` | 1 |
| `java.lang.invoke.LambdaForm$NamedFunction.returnType` | 1 |
| `java.nio.DirectByteBuffer.get` | 1 |
| `java.lang.StringBuilder.<init>` | 1 |
| `sun.util.resources.Bundles.loadBundleOf` | 1 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. Different fixed-duration loops can perform different operation counts. This probe has no exact latency array. Use formal paired JMH B/op for per-operation allocation.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.benchmarks.DirectStateProbe$Value` | 12,537.08 |
| `io.ominicat.core.Pipeline$DirectInvocation` | 7,008.58 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 18.99 |
| `[B` | 4.71 |
| `[J` | 0.37 |
| `java.util.ArrayList$Itr` | 0.26 |
| `java.util.ArrayList` | 0.19 |
| `java.lang.Class` | 0.19 |
| `java.lang.invoke.MethodType` | 0.00 |
| `java.util.concurrent.CopyOnWriteArrayList$COWIterator` | 0.00 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 130.
- Recorded GC pause sum: 95.603 ms; longest collection pause sum: 6.042 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

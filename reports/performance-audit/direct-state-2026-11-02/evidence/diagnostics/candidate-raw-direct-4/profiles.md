# CPU, allocation and GC profile

Independent instrumented Java 17 JMH diagnostic JVM, including startup, setup, two 1-second warmups, two 1-second measurements, harness and teardown. No phase filtering. CPU sampling requests 10ms; allocation sampling is throttled to 50 events/second; recording and export retain up to 64 frames. This is not main timing evidence, per-operation allocation, retained heap, method duration, p99 latency, RSS or production capacity.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `io.ominicat.benchmarks.DirectStateProbe$Harness.acceptOutput` | 161 |
| `io.ominicat.core.Pipeline$DirectStage.invoke` | 69 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.invoke` | 66 |
| `io.ominicat.core.Pipeline.dispatch` | 25 |
| `io.ominicat.core.Pipeline$DirectInvocation.dispatchNext` | 8 |
| `io.ominicat.benchmarks.DirectStateProbe.invoke` | 6 |
| `sun.security.provider.SHA2.implCompress0` | 2 |
| `java.lang.Math.multiplyExact` | 2 |
| `java.lang.String.newStringUTF8NoRepl` | 1 |
| `java.lang.invoke.DirectMethodHandle.shouldBeInitialized` | 1 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.lambda$new$4` | 1 |
| `org.openjdk.jmh.infra.Blackhole.consumeFull` | 1 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. Different fixed-duration loops can perform different operation counts. This probe has no exact latency array. Use formal paired JMH B/op for per-operation allocation.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.benchmarks.DirectStateProbe$Value` | 11,067.44 |
| `io.ominicat.core.Pipeline$DirectInvocation` | 10,171.00 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 18.88 |
| `[B` | 1.98 |
| `[Ljava.lang.Object;` | 0.42 |
| `java.util.TreeMap$Entry` | 0.40 |
| `java.util.concurrent.ConcurrentHashMap$Node` | 0.20 |
| `[Ljdk.internal.org.objectweb.asm.SymbolTable$Entry;` | 0.20 |
| `java.util.zip.CRC32` | 0.16 |
| `java.lang.invoke.MethodType` | 0.00 |
| `java.util.concurrent.CopyOnWriteArrayList$COWIterator` | 0.00 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 141.
- Recorded GC pause sum: 91.619 ms; longest collection pause sum: 5.689 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

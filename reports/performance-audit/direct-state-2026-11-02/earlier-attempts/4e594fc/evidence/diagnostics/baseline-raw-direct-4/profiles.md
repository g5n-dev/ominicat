# CPU, allocation and GC profile

Independent instrumented Java 17 JMH diagnostic JVM, including startup, setup, two 1-second warmups, two 1-second measurements, harness and teardown. No phase filtering. CPU sampling requests 10ms; allocation sampling is throttled to 50 events/second; recording and export retain up to 64 frames. This is not main timing evidence, per-operation allocation, retained heap, method duration, p99 latency, RSS or production capacity.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `io.ominicat.core.Pipeline.lambda$dispatch$1` | 130 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.acceptOutput` | 125 |
| `io.ominicat.core.Pipeline.process` | 42 |
| `java.lang.invoke.DirectMethodHandle.allocateInstance` | 12 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.invoke` | 11 |
| `io.ominicat.benchmarks.DirectStateProbe.invoke` | 6 |
| `java.util.StringTokenizer.scanToken` | 1 |
| `java.lang.Character.compareTo` | 1 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. Different fixed-duration loops can perform different operation counts. This probe has no exact latency array. Use formal paired JMH B/op for per-operation allocation.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.benchmarks.DirectStateProbe$Value` | 8,628.95 |
| `io.ominicat.core.Pipeline$$Lambda$131+0x00007f3c78095978.1682136925` | 7,201.56 |
| `io.ominicat.core.SynchronousSink` | 4,200.31 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 19.03 |
| `[B` | 3.75 |
| `jdk.internal.org.objectweb.asm.SymbolTable$Entry` | 0.37 |
| `[Ljava.lang.Object;` | 0.37 |
| `[C` | 0.37 |
| `java.lang.Object` | 0.23 |
| `[Ljava.nio.file.LinkOption;` | 0.18 |
| `java.lang.String` | 0.18 |
| `[Ljdk.internal.org.objectweb.asm.SymbolTable$Entry;` | 0.18 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 134.
- Recorded GC pause sum: 91.279 ms; longest collection pause sum: 4.717 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

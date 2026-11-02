# CPU, allocation and GC profile

Independent instrumented Java 17 JMH diagnostic JVM, including startup, setup, two 1-second warmups, two 1-second measurements, harness and teardown. No phase filtering. CPU sampling requests 10ms; allocation sampling is throttled to 50 events/second; recording and export retain up to 64 frames. This is not main timing evidence, per-operation allocation, retained heap, method duration, p99 latency, RSS or production capacity.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `io.ominicat.benchmarks.DirectStateProbe$Harness.acceptOutput` | 98 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.lambda$new$4` | 48 |
| `java.lang.Long.getChars` | 43 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.invoke` | 42 |
| `jdk.internal.misc.Unsafe.allocateUninitializedArray` | 37 |
| `io.ominicat.core.FrameEnvelope.<init>` | 31 |
| `io.ominicat.core.FrameEnvelope.derive` | 22 |
| `java.lang.invoke.DirectMethodHandle.allocateInstance` | 7 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.checkEnvelope` | 6 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness$1.process` | 4 |
| `io.ominicat.core.SynchronousSink.<init>` | 3 |
| `io.ominicat.core.EnvelopeProcessor.process` | 3 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. Different fixed-duration loops can perform different operation counts. This probe has no exact latency array. Use formal paired JMH B/op for per-operation allocation.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.core.SynchronousSink` | 5,376.32 |
| `io.ominicat.core.FrameEnvelope` | 2,506.17 |
| `io.ominicat.benchmarks.DirectStateProbe$Value` | 2,384.31 |
| `io.ominicat.core.Pipeline$$Lambda$133+0x00007f0fa4096030.284879437` | 1,952.30 |
| `java.lang.String` | 1,444.97 |
| `io.ominicat.core.EnvelopeProcessor$$Lambda$134+0x00007f0fa4096258.1201448536` | 1,224.12 |
| `[B` | 343.26 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 19.05 |
| `java.util.jar.JarFile$JarFileEntry` | 0.40 |
| `[Ljava.lang.Object;` | 0.39 |
| `joptsimple.OptionSpecBuilder` | 0.35 |
| `jdk.internal.org.objectweb.asm.SymbolTable$Entry` | 0.20 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 101.
- Recorded GC pause sum: 71.240 ms; longest collection pause sum: 3.653 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

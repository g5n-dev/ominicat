# CPU, allocation and GC profile

Independent instrumented Java 17 JMH diagnostic JVM, including startup, setup, two 1-second warmups, two 1-second measurements, harness and teardown. No phase filtering. CPU sampling requests 10ms; allocation sampling is throttled to 50 events/second; recording and export retain up to 64 frames. This is not main timing evidence, per-operation allocation, retained heap, method duration, p99 latency, RSS or production capacity.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `io.ominicat.benchmarks.DirectStateProbe$Harness.invoke` | 61 |
| `io.ominicat.core.FrameEnvelope.<init>` | 58 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.acceptOutput` | 51 |
| `jdk.internal.misc.Unsafe.allocateUninitializedArray` | 45 |
| `io.ominicat.core.FrameEnvelope.derive` | 44 |
| `java.lang.Long.getChars` | 35 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.expected` | 19 |
| `io.ominicat.core.Pipeline.dispatch` | 12 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.enterStage` | 11 |
| `io.ominicat.core.EnvelopeProcessor.lambda$process$2` | 3 |
| `sun.security.provider.SHA2.implCompress0` | 2 |
| `io.ominicat.benchmarks.DirectStateProbe.invoke` | 2 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. Different fixed-duration loops can perform different operation counts. This probe has no exact latency array. Use formal paired JMH B/op for per-operation allocation.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.core.Pipeline$DirectInvocation` | 3,460.96 |
| `io.ominicat.benchmarks.DirectStateProbe$Value` | 3,440.66 |
| `io.ominicat.core.FrameEnvelope` | 3,018.65 |
| `io.ominicat.core.EnvelopeProcessor$$Lambda$133+0x00007ffa38096278.2119816728` | 2,419.25 |
| `[B` | 1,854.48 |
| `java.lang.String` | 318.73 |
| `io.ominicat.core.SynchronousSink` | 67.92 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 19.04 |
| `java.lang.invoke.MethodType$ConcurrentWeakInternSet$WeakEntry` | 0.81 |
| `[C` | 0.41 |
| `jdk.internal.org.objectweb.asm.SymbolTable$Entry` | 0.20 |
| `jdk.internal.org.objectweb.asm.ByteVector` | 0.18 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 97.
- Recorded GC pause sum: 68.792 ms; longest collection pause sum: 5.312 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

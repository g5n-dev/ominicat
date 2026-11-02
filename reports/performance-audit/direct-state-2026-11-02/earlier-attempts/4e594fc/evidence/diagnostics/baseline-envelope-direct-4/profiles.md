# CPU, allocation and GC profile

Independent instrumented Java 17 JMH diagnostic JVM, including startup, setup, two 1-second warmups, two 1-second measurements, harness and teardown. No phase filtering. CPU sampling requests 10ms; allocation sampling is throttled to 50 events/second; recording and export retain up to 64 frames. This is not main timing evidence, per-operation allocation, retained heap, method duration, p99 latency, RSS or production capacity.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `java.lang.StringLatin1.indexOfNonWhitespace` | 80 |
| `io.ominicat.core.FrameEnvelope.<init>` | 74 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.lambda$new$4` | 52 |
| `java.lang.Long.getChars` | 46 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.invoke` | 31 |
| `jdk.internal.misc.Unsafe.allocateUninitializedArray` | 19 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.acceptOutput` | 11 |
| `java.lang.Record.<init>` | 6 |
| `java.lang.CharacterDataLatin1.getProperties` | 5 |
| `java.security.SecureClassLoader$CodeSourceKey.equals` | 1 |
| `sun.nio.fs.UnixPath.encode` | 1 |
| `jdk.internal.jimage.ImageLocation.verify` | 1 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. Different fixed-duration loops can perform different operation counts. This probe has no exact latency array. Use formal paired JMH B/op for per-operation allocation.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.core.FrameEnvelope` | 2,451.95 |
| `io.ominicat.benchmarks.DirectStateProbe$Value` | 2,300.22 |
| `io.ominicat.core.SynchronousSink` | 2,285.89 |
| `io.ominicat.core.Pipeline$$Lambda$133+0x00007f9f58096030.284879437` | 1,420.90 |
| `[B` | 1,289.70 |
| `java.lang.String` | 677.24 |
| `io.ominicat.core.EnvelopeProcessor$$Lambda$134+0x00007f9f58096258.1201448536` | 175.80 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 18.99 |
| `jdk.internal.org.objectweb.asm.SymbolTable$Entry` | 0.41 |
| `[J` | 0.37 |
| `java.util.ArrayList$Itr` | 0.26 |
| `java.util.HashMap` | 0.19 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 72.
- Recorded GC pause sum: 67.168 ms; longest collection pause sum: 5.282 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

# CPU, allocation and GC profile

Separate instrumented Java 17 JMH diagnostic JVM: whole recording includes JVM/framework startup, setup, two 1-second warmup iterations, two 1-second measurement iterations, harness and teardown. No phase filtering. CPU execution sampling requests a 10ms period; allocation uses profile.jfc defaults. Recording and exported stacks retain up to 64 frames. These are not headline timings, framework-exclusive CPU, retained heap, exact object counts, p99 latency or RSS measurements.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `org.apache.camel.support.MessageSupport.copyFromWithNewBody` | 133 |
| `org.apache.camel.support.FlatMap.<init>` | 52 |
| `org.apache.camel.support.AbstractExchange.getProperty` | 17 |
| `io.ominicat.mechanisms.ExchangeCopyFixture.invoke` | 13 |
| `io.ominicat.mechanisms.ExchangeCopyBenchmark.copy` | 5 |
| `java.util.Objects.equals` | 4 |
| `java.lang.invoke.VarHandleByteArrayAsInts$ArrayHandle.get` | 3 |
| `sun.security.provider.SHA2.implCompress0` | 2 |
| `org.apache.camel.ExchangePropertyKey.asExchangePropertyKey` | 2 |
| `jdk.internal.loader.BuiltinClassLoader.isSealed` | 1 |
| `java.lang.invoke.LambdaForm$BasicType.basicTypeSlots` | 1 |
| `java.lang.String.isLatin1` | 1 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. This JMH mechanism experiment has no exact latency array. Use the separate uninstrumented JMH measurements for per-operation allocation; no such estimate is inferred from these samples.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `[Ljava.lang.Object;` | 15,433.51 |
| `org.apache.camel.support.DefaultMessage` | 7,706.23 |
| `java.util.EnumMap` | 3,560.18 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 19.34 |
| `[B` | 10.36 |
| `org.apache.camel.support.DefaultExchange` | 2.04 |
| `org.apache.camel.support.CopyOnWriteHeadersMap` | 0.84 |
| `jdk.internal.org.objectweb.asm.SymbolTable$Entry` | 0.69 |
| `jdk.internal.org.objectweb.asm.ByteVector` | 0.43 |
| `jdk.internal.org.objectweb.asm.MethodWriter` | 0.41 |
| `[C` | 0.39 |
| `java.util.zip.ZipFile$ZipFileInflaterInputStream` | 0.38 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 178.
- Recorded GC pause sum: 141.635 ms; longest collection pause sum: 5.735 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

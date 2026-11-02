# CPU, allocation and GC profile

Independent instrumented Java 17 JMH diagnostic JVM, including startup, setup, two 1-second warmups, two 1-second measurements, harness and teardown. No phase filtering. CPU sampling requests 10ms; allocation sampling is throttled to 50 events/second; recording and export retain up to 64 frames. This is not main timing evidence, per-operation allocation, retained heap, method duration, p99 latency, RSS or production capacity.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `io.ominicat.core.Pipeline.lambda$dispatch$1` | 132 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.expected` | 97 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness.invoke` | 66 |
| `io.ominicat.core.Pipeline.process` | 24 |
| `io.ominicat.benchmarks.DirectStateProbe$Harness$$Lambda$125+0x00007fe1c0094a28.1669521372.accept` | 15 |
| `io.ominicat.benchmarks.DirectStateProbe.invoke` | 4 |
| `io.ominicat.benchmarks.jmh_generated.DirectStateProbe_invoke_jmhTest.invoke_avgt_jmhStub` | 1 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. Different fixed-duration loops can perform different operation counts. This probe has no exact latency array. Use formal paired JMH B/op for per-operation allocation.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.benchmarks.DirectStateProbe$Value` | 11,653.19 |
| `io.ominicat.core.Pipeline$$Lambda$131+0x00007fe1c0095978.1682136925` | 8,168.08 |
| `io.ominicat.core.SynchronousSink` | 3,045.57 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 19.07 |
| `[B` | 4.27 |
| `java.lang.NoSuchFieldException` | 0.39 |
| `java.lang.String` | 0.34 |
| `jdk.internal.org.objectweb.asm.SymbolTable$Entry` | 0.22 |
| `[Ljava.lang.Object;` | 0.20 |
| `java.net.URL` | 0.17 |
| `java.util.concurrent.ConcurrentHashMap$ForwardingNode` | 0.17 |
| `java.lang.invoke.MethodType` | 0.00 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 151.
- Recorded GC pause sum: 92.861 ms; longest collection pause sum: 5.206 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

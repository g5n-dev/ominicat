# CPU, allocation and GC profile

Separate instrumented Java 17 JMH diagnostic JVM: whole recording includes JVM/framework startup, setup, two 1-second warmup iterations, two 1-second measurement iterations, harness and teardown. No phase filtering. CPU execution sampling requests a 10ms period; allocation uses profile.jfc defaults. Recording and exported stacks retain up to 64 frames. These are not headline timings, framework-exclusive CPU, retained heap, exact object counts, p99 latency or RSS measurements.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `io.ominicat.core.Pipeline.lambda$dispatch$1` | 177 |
| `io.ominicat.core.Pipeline.process` | 48 |
| `java.lang.invoke.DirectMethodHandle.allocateInstance` | 28 |
| `io.ominicat.mechanisms.PathBenchmark.roundTrip` | 23 |
| `org.openjdk.jmh.infra.Blackhole.consumeFull` | 2 |
| `java.util.zip.ZipCoder$UTF8ZipCoder.checkedHash` | 1 |
| `java.lang.invoke.InvokerBytecodeGenerator.emitReferenceCast` | 1 |
| `java.lang.String.regionMatches` | 1 |
| `java.lang.invoke.MethodHandleNatives.canBeCalledVirtual` | 1 |
| `java.util.zip.ZipFile$CleanableResource.releaseInflater` | 1 |
| `java.util.stream.AbstractPipeline.evaluate` | 1 |
| `io.ominicat.mechanisms.PathHarness$Oracle.stage` | 1 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. This JMH mechanism experiment has no exact latency array. Use the separate uninstrumented JMH measurements for per-operation allocation; no such estimate is inferred from these samples.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.mechanisms.PathHarness$Value` | 9,449.46 |
| `io.ominicat.core.Pipeline$$Lambda$133+0x00007f65bc0deeb0.818152665` | 7,021.30 |
| `io.ominicat.core.SynchronousSink` | 2,540.98 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 19.34 |
| `[B` | 4.65 |
| `io.ominicat.mechanisms.PathHarness$OminiCatBackend$$Lambda$132+0x00007f65bc0dec88.490283198` | 3.99 |
| `[I` | 1.43 |
| `jdk.internal.org.objectweb.asm.MethodWriter` | 0.56 |
| `java.util.concurrent.ConcurrentHashMap$Node` | 0.40 |
| `java.net.URL` | 0.40 |
| `[C` | 0.39 |
| `jdk.internal.org.objectweb.asm.ByteVector` | 0.28 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 127.
- Recorded GC pause sum: 101.715 ms; longest collection pause sum: 4.957 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

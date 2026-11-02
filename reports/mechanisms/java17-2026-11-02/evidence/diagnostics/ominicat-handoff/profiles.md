# CPU, allocation and GC profile

Separate instrumented Java 17 JMH diagnostic JVM: whole recording includes JVM/framework startup, setup, two 1-second warmup iterations, two 1-second measurement iterations, harness and teardown. No phase filtering. CPU execution sampling requests a 10ms period; allocation uses profile.jfc defaults. Recording and exported stacks retain up to 64 frames. These are not headline timings, framework-exclusive CPU, retained heap, exact object counts, p99 latency or RSS measurements.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `jdk.internal.loader.BuiltinClassLoader.loadClass` | 1 |
| `jdk.internal.misc.ScopedMemoryAccess.copyMemoryInternal` | 1 |
| `java.util.StringTokenizer.scanToken` | 1 |
| `java.lang.invoke.VarHandleByteArrayAsInts$ArrayHandle.get` | 1 |
| `java.lang.invoke.InvokerBytecodeGenerator.resolveInvokerMember` | 1 |
| `io.ominicat.mechanisms.PathHarness.run` | 1 |
| `java.lang.Integer.valueOf` | 1 |
| `java.lang.AbstractStringBuilder.append` | 1 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. This JMH mechanism experiment has no exact latency array. Use the separate uninstrumented JMH measurements for per-operation allocation; no such estimate is inferred from these samples.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.mechanisms.PathHarness$Value` | 25.54 |
| `io.ominicat.core.Pipeline$$Lambda$134+0x00007f803c0df2e8.492584403` | 21.76 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 19.44 |
| `io.ominicat.core.SynchronousSink` | 15.29 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode` | 10.02 |
| `io.ominicat.mechanisms.PathHarness$Reply` | 5.99 |
| `[B` | 4.99 |
| `io.ominicat.mechanisms.PathHarness$OminiCatBackend$$Lambda$133+0x00007f803c0df0c0.750408753` | 4.42 |
| `java.lang.String` | 0.93 |
| `java.util.zip.ZipFile$InflaterCleanupAction` | 0.50 |
| `java.security.CodeSource` | 0.40 |
| `[C` | 0.39 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 1.
- Recorded GC pause sum: 3.965 ms; longest collection pause sum: 3.965 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

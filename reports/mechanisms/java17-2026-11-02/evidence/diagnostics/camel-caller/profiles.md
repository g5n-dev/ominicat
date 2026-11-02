# CPU, allocation and GC profile

Separate instrumented Java 17 JMH diagnostic JVM: whole recording includes JVM/framework startup, setup, two 1-second warmup iterations, two 1-second measurement iterations, harness and teardown. No phase filtering. CPU execution sampling requests a 10ms period; allocation uses profile.jfc defaults. Recording and exported stacks retain up to 64 frames. These are not headline timings, framework-exclusive CPU, retained heap, exact object counts, p99 latency or RSS measurements.

![CPU sampled leaf methods](cpu-hotspots.svg)

## Complete supplied CPU stacks

![CPU sampled stacks](cpu-flamegraph.svg)

Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.

## CPU leaf samples

| Method | Samples |
|---|---:|
| `org.apache.camel.impl.engine.CamelInternalProcessor.process` | 40 |
| `org.apache.camel.support.DefaultExchange.getIn` | 29 |
| `org.apache.camel.support.EventHelper.notifyExchangeAsyncProcessingStartedEvent` | 23 |
| `org.apache.camel.impl.engine.DefaultReactiveExecutor$Worker.executeReactiveWork` | 20 |
| `java.util.concurrent.atomic.LongAdder.add` | 19 |
| `org.apache.camel.impl.engine.AdviceIterator.runAfterTasks` | 19 |
| `java.util.concurrent.ConcurrentHashMap.get` | 16 |
| `java.lang.ThreadLocal$ThreadLocalMap.getEntry` | 14 |
| `org.apache.camel.impl.engine.DefaultReactiveExecutor$Worker.doRun` | 12 |
| `org.apache.camel.impl.engine.DefaultUnitOfWorkFactory.createUnitOfWork` | 10 |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.signalNext` | 7 |
| `java.lang.StringLatin1.hashCode` | 7 |

## Sampled allocation weights

Weights estimate sampled allocation distribution, not retained heap or exact object counts. This JMH mechanism experiment has no exact latency array. Use the separate uninstrumented JMH measurements for per-operation allocation; no such estimate is inferred from these samples.

![Sampled allocation weights by class](allocation-hotspots.svg)

| Class | Sampled weight (MiB) |
|---|---:|
| `io.ominicat.mechanisms.PathHarness$Value` | 885.53 |
| `org.apache.camel.support.DefaultMessage` | 612.04 |
| `[Ljava.lang.Object;` | 565.42 |
| `org.apache.camel.support.ResetableClock` | 525.19 |
| `org.apache.camel.impl.engine.DefaultAsyncProcessorAwaitManager$$Lambda$239+0x00007f9b281bc440.1587638878` | 502.37 |
| `org.apache.camel.support.DefaultExchange` | 404.64 |
| `java.util.EnumMap` | 400.17 |
| `org.apache.camel.impl.engine.CamelInternalProcessor$AsyncAfterTask` | 339.01 |
| `java.util.concurrent.CountDownLatch$Sync` | 135.34 |
| `org.apache.camel.processor.errorhandler.RedeliveryErrorHandler$SimpleTask` | 128.59 |
| `org.apache.camel.processor.Pipeline$PipelineTask` | 27.13 |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 19.33 |

## Complete supplied allocation stacks

![Allocation sampled stacks](allocation-flamegraph.svg)

## GC and contention

![GC collection pause timeline](gc-pauses.svg)

- Garbage collections recorded: 33.
- Recorded GC pause sum: 56.626 ms; longest collection pause sum: 5.392 ms.
- Monitor contention events: 0; virtual-thread pinning events: not available on this runtime.
- GC positions are relative to the first timestamped selected event, not the JMH measurement start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.
- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.

Evidence: [selected events, gzip JSON](jfr-events.json.gz), [diagnostic report](report.md).
Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.

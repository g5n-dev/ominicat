# Performance design and Linux evidence

OminiCat controls dispatch overhead, queued work and ownership boundaries through a small Java 17+ kernel. Public performance figures use **Ubuntu 24.04 and CentOS Stream 9 user-space CI recordings**. The [complete evidence](../reports/comparison/README.md) contains every process result, source/runtime/dependency manifest, plot and checksum.

## Published Linux baseline

Each environment ran three native queued implementations: OminiCat, Pipecat 1.15.0 and Apache Camel 4.22.2 LTS. Each completed 45 fresh processes across 1/4/16 application stages and five processes per cell. Together they validated **17,406,385 measured outputs**. The four-stage medians are:

| Environment | Framework | Frames/s · W=1 | Terminal p99 µs | Phase peak RSS MiB |
| --- | --- | ---: | ---: | ---: |
| Ubuntu 24.04 native | OminiCat | 49,084 | 17.30 | 144.1 |
| Ubuntu 24.04 native | Pipecat | 5,300 | 220.35 | 93.5 |
| Ubuntu 24.04 native | Apache Camel | 42,756 | 20.41 | 188.7 |
| CentOS Stream 9 user space | OminiCat | 66,567 | 12.44 | 145.3 |
| CentOS Stream 9 user space | Pipecat | 10,019 | 130.88 | 88.0 |
| CentOS Stream 9 user space | Apache Camel | 59,527 | 13.78 | 188.9 |

Ubuntu used an AMD EPYC 9V74 with four available logical processors, Temurin 17.0.21, CPython 3.12.15 and glibc 2.39. CentOS used an AMD EPYC 9V45 with four available logical processors, distribution OpenJDK 17.0.20.1, CPython 3.12.15 and glibc 2.34. CentOS is a digest-pinned official container on an Ubuntu runner and shares its kernel. These are independent hosts: compare frameworks within each job, not operating systems across jobs.

[![Ubuntu native queued-path comparison](assets/previews/ubuntu-comparison.png)](../reports/comparison/linux-ubuntu24-java17-2026-10-31/overview.svg)

[Ubuntu figures and raw results](../reports/comparison/linux-ubuntu24-java17-2026-10-31/README.md) · [CentOS figures and raw results](../reports/comparison/linux-centos9-java17-2026-10-31/README.md) · [Recorded CI run](https://github.com/g5n-dev/ominicat/actions/runs/41222330745) · [Publication and archive provenance](../reports/comparison/linux-ci-41222330745.json).

## How the implementation controls cost

[![Typed direct calls and session execution](assets/previews/execution-path.png)](assets/execution-path.svg)

| Mechanism and source | Work avoided or controlled | Costs and boundaries that remain |
| --- | --- | --- |
| [Typed dispatch](../ominicat-core/src/main/java/io/ominicat/core/Pipeline.java) | Synchronous stages invoke the next processor on the same call stack; no per-stage wire codec, queue or worker handoff is introduced. Direct embedding can avoid session scheduling. | [Scoped sinks](../ominicat-core/src/main/java/io/ominicat/core/SynchronousSink.java) enforce invocation thread/lifetime. Sinks and captured continuations allocate; direct callers manage serialized access and cleanup. |
| [Session ownership](../ominicat-core/src/main/java/io/ominicat/core/PipelineSession.java) | One owner worker serializes linear stage execution, reset and reverse cleanup. External callbacks enqueue a continuation at its next stage. | The FIFO uses `ReentrantLock`, `Condition` and `ArrayDeque`. Queue entries and worker handoffs have costs. Async continuation order follows callback arrival. |
| [Independent work limits](../ominicat-core/src/main/java/io/ominicat/core/PipelineSession.java) | Pending entries and active asynchronous handles each have a count limit using the configured capacity. Saturation is explicit. | The two counts do not share a combined reservation pool and are not a total-memory limit. Payload size, current work and plugin state must be budgeted separately. |
| [Bounded parallel composition](../ominicat-core/src/main/java/io/ominicat/core/ParallelProcessor.java) | Explicit branch workers and per-branch output buffers bound concurrency and retained frame references; results are joined in declaration order after every branch succeeds. | A shared deadline covers branch scheduling/wait, not all downstream delivery. Buffered frame references are not payload-byte/RSS limits; already-emitted sink effects cannot be rolled back. |
| [Container processing boundary](../ominicat-server/src/main/java/io/ominicat/server/OminiCatServer.java) | Normal processor work runs on session workers, separate from Netty NIO event loops. Pending encoded payload bytes and writability are checked before network writes. | Decoding and connection/stage assembly remain in I/O callbacks. Output encoding, Netty tasks, sockets and native buffers have their own costs. Write completion is not client consumption. |
| [Worker capability discovery](../ominicat-core/src/main/java/io/ominicat/core/WorkerThreads.java) | Java 17 platform workers and Java 21+ virtual workers share the Java 17-compatible API; discovery happens outside frame dispatch. | Virtual workers do not make CPU computation inherently faster. Branch executors keep explicit limits on either backend. The published comparison used Java 17 platform workers. |
| [Media snapshots](../ominicat-core/src/main/java/io/ominicat/core/Frame.java) | Constructed immutable frame instances can pass between stages by reference without sharing mutable payload storage. | Audio/image construction copies bytes; getters create read-only views. JSON/Base64, new media construction and optional envelope identities add costs. The numeric profile does not measure those costs. |

These are source-backed mechanisms, not measured causal speedup ratios. The peer comparison retains runtime, frame-object, scheduler and harness differences. It measures the whole declared path; it does not isolate the contribution of each design choice. [Architecture, limits and lifecycle diagrams](architecture.md).

## What the comparison measures

The [protocol](../comparison/protocol.md) fixes immutable numeric work and an independent output oracle. One pipeline/session admits **one outstanding frame**. Every application stage creates the same numeric transformation. OminiCat uses `PipelineSession`, Pipecat its native queued processors, and Camel single-consumer SEDA. Native lifecycle and metadata differences remain included; this is not feature or backpressure equivalence.

Every process warms for three seconds and measures for five seconds. Framework order rotates between processes. Throughput covers the complete validated client loop. Per-input terminal latency starts before construction/submission and stops after output validation, before caller acknowledgment. It therefore has a different boundary from whole-loop throughput.

Five-process medians and observed min–max ranges are retained; ranges are not confidence intervals. W=1 rate is not saturated capacity or production sizing. Models, codecs, network exchanges and audio quality are outside this profile. The collector validates every output, nonce, count, sequence, transformed value, timer binding and cleanup result. Missing, duplicated, modified, failed or mixed-source observations prevent publication.

An exact, pre-touched 16 MiB sample buffer holds at most 2,000,000 observations. Overflow fails collection rather than silently sampling or truncating. The parent samples process RSS every 50 ms and reports whole-process and measurement-phase peaks separately. RSS includes the runtime, dependencies and common buffer, is not B/op or retained heap, and can miss brief peaks. Java uses `-Xms128m -Xmx512m`; Python has no corresponding managed-heap limit. OS-reported host memory, CPU affinity and readable cgroup limits are recorded separately.

## CPU, allocation and GC

The [Ubuntu diagnostic report](../reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/README.md) and [CentOS diagnostic report](../reports/comparison/linux-centos9-java17-2026-10-31/profiles/README.md) were captured in **additional instrumented processes**. Their timings do not enter the 90-process matrix.

Java JFR covers process startup, warmup, the measured loop, harness work and teardown. It requests 64-frame capture/export and 1 ms execution sampling. The renderer preserves supplied stacks and reports sampled weights and GC pause timelines. Ubuntu's OminiCat capture contains 41 CPU samples, 445 allocation samples and three collections; the GC pause sum is 4.876 ms and its largest per-collection pause sum is 2.580 ms. CPU samples are sparse observations, not method durations. Allocation weights are estimates, not retained heap or exact bytes per operation.

Pipecat cProfile captures only the measured event-loop phase, after warmup and before teardown. Its deterministic call self times include exposed waits/system calls and do not resolve native internals. They are not Java CPU samples or a cross-language B/op axis. Zero sampled events do not prove absence in another workload or below sampling thresholds.

To inspect the whole exported stacks, use [Ubuntu CPU](../reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/cpu-flamegraph.svg), [Ubuntu allocation](../reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/allocation-flamegraph.svg) and the per-framework diagnostic indices above. Numeric observations, original archive hashes, tool hashes and exact publication changes are recorded in the [publication provenance](../reports/comparison/linux-ci-41222330745.json).

## Run the Linux workflow

The [comparison workflow](../.github/workflows/framework-comparison.yml) runs on relevant pull requests and protected-main pushes, and supports manual dispatch. Ubuntu uses a native runner; CentOS uses its native RPM Python/JDK inside the official digest-pinned image. Each job completes all 45 cells, separate diagnostics and allowlisted/checksummed publication before uploading. The job summary includes four-stage medians and artifact instructions. Artifacts expire after 30 days; the committed baseline remains available.

Actual distribution and architecture are checked, and context is bound to the measured Git revision and kernel. Manifests include actual JDK/Python versions, CPU model, libc, dependency inventory, source hashes, affinity and readable cgroup limits. Shared hosted runners have no exclusive CPU isolation. CI collects evidence without imposing fixed throughput thresholds or automatically replacing recorded headline values.

## Reproduce on Linux

Use an actual Java 17 JDK, CPython 3.12 and the native dependencies listed in the [comparison guide](../comparison/README.md#linux-ci). Run builds and measurements serially on the same host:

```sh
./mvnw -B -ntp -pl ominicat-core -am install -DskipTests
./mvnw -B -ntp -f comparison/benchmarks/pom.xml clean verify
python3.12 -m venv reports/local/framework-env
reports/local/framework-env/bin/python -m pip install -r comparison/requirements.lock
reports/local/framework-env/bin/python comparison/run.py \
  --java "$JAVA_HOME/bin/java" --python reports/local/framework-env/bin/python \
  --output reports/local/framework-run
reports/local/framework-env/bin/python comparison/report.py \
  reports/local/framework-run reports/local/framework-publish
```

The [same guide](../comparison/README.md#separate-diagnostic-profiles) gives separate JFR/cProfile and allowlisted packaging commands. Use new output folders; collection refuses stale results. Raw JFR, profiler dumps, environments and private logs stay local.

## Other probes and comparisons

`scripts/profile.sh quick reports/local/profile` runs synchronous JMH, its allocation profiler, bounded concurrent load and JFR. `full` uses longer forks/warmup/measurement. Use a Linux recording with matching source, host, JDK, worker backend, heap, stage count and payload before publishing B/op or an optimization result. The queued peer matrix above does not supply a Linux synchronous-dispatch or concurrent-saturation headline.

For a separate loopback transport probe, start the default container and run:

```sh
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
# In another terminal:
java scripts/WebSocketLoad.java ws://127.0.0.1:8080/ws 32 5000
```

This warms each connection for 100 frames and times from `sendText` to matching response. It has a different workload and timing scope from numeric kernel dispatch. Slow peers, TLS, large payloads, independent arrivals and actual provider chains need their own probes.

The separate [weekly profile workflow](../.github/workflows/performance.yml) runs Linux profiling on Java 17 and 21 and retains allowlisted artifacts for 30 days. Its success means collection and validation completed. Proposed experiments and their acceptance boundaries are in the [iteration plan](iteration-plan.md).

For changes, retain raw observations and report uncertainty alongside throughput, latency and allocation. A matching before/after experiment is needed to attribute an improvement; the existing framework comparison is not an optimization ablation or a model-latency claim.

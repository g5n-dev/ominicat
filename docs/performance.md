# Performance methodology

OminiCat is designed for high throughput, low dispatch overhead and bounded work under load. The current [Java 17 measurements](../reports/java17/kernel/report.md) expose throughput, scheduling latency and allocation together; [CPU/allocation flame graphs and GC](../reports/java17/kernel/profiles.md) link those results to execution costs.

## Recorded Java 17 performance

| Probe | Result | Scope |
| --- | ---: | --- |
| Four-stage synchronous dispatch | **40.54M ops/s**, **328 B/op** | One JMH thread; local numeric frames |
| Four-stage session round trip | **7.104 µs/op** | Average; one outstanding frame, including queue/worker/output handoff |
| 16-session bounded load | **2.706M frames/s**, p99 **1.436 ms** | Four stages, max 32 outstanding frames/session; 25M measured frames; zero rejections |

Java 17.0.21, Apple M2 Pro / macOS arm64, 512 MiB heap, platform workers. JMH uses two forks, three 1-second warmup iterations and five 1-second measurement iterations. The full report retains uncertainty, maximum latency and [raw data](../reports/java17/kernel/jmh.json). The shared development host also ran paced validation, so these results describe the recorded workload and environment.

## Model-free framework comparison

The [three-framework report](../reports/comparison/java17-2026-10-31/README.md) compares current OminiCat, Pipecat 1.15.0 and Apache Camel 4.22.2 LTS using a separately defined [queued-path protocol](../comparison/protocol.md). It preserves each native execution architecture, fixes application work and W=1 admission, and uses five fresh processes per engine/stage cell (45 total). The [collector](../comparison/run.py) checks every output and captures same-OS RSS; the publisher rejects partial or mixed-source results.

Four-stage medians are 140,089 frames/s and terminal p99 9.79 µs for OminiCat, 107,210 frames/s and 12.54 µs for Camel, and 3,906 frames/s and 455.29 µs for Pipecat. Pipecat has the lowest process RSS in this profile. These are native public API paths with runtime/object/scheduler differences, not an isolated proof of framework superiority. The W=1 rate is not saturation capacity. Process RSS includes the runtime and common pre-touched buffer and cannot be equated to Java allocation B/op. Direct synchronous JMH and the bounded 16-session load above retain their separate scopes.

[Figures, ranges and all raw processes](../reports/comparison/java17-2026-10-31/README.md) · [Separate diagnostic profiles](../reports/comparison/java17-2026-10-31/profiles/README.md) · [Local reproduction](../comparison/README.md). The manual [comparison workflow](../.github/workflows/framework-comparison.yml) collects its own hosted-runner evidence and does not automatically replace this local recording.

## Performance design

| Mechanism | Execution and resource behavior |
| --- | --- |
| Direct typed-frame pipeline | Synchronous stages call the next processor directly; the kernel introduces no JSON boundary between stages. `Pipeline.process` supports embedding without session queue/scheduling overhead. |
| Single session owner | A session worker serializes linear stages, reset hooks and cleanup; explicit parallel branches use bounded branch workers. Async continuations rejoin the session ingress queue. Separate sessions can advance concurrently. |
| Explicit work limits | Ingress capacity, live async work, branch parallelism and buffered outputs are bounded; producers receive explicit rejection on saturation. |
| Transport isolation | The container runs processor work on session workers while Netty handles network events. Socket output has a byte budget and a writability boundary; admission remains retained through worker cleanup. |
| Runtime worker selection | Java 17 uses platform workers; Java 21+ uses virtual workers. Reflection occurs during factory discovery, outside frame dispatch. |
| Observable costs | JMH GC profiling reports B/op; JFR exposes CPU, allocation and GC; metrics and sustained probes report queue/output pressure and resource drain. |

The [Java 17 mixed-media resource report](../reports/java17/http-cancellation/README.md) separately checks text, audio, transcripts, images and envelopes over real local WebSockets. Its paced workload and [resource timeline](../reports/java17/http-cancellation/soak-resources.svg) have a different timing scope from kernel benchmarks. Choose thread/session limits for the selected JDK and profile the actual processing chain.

The [historical Java 21 baseline](../reports/baseline/report.md) and [20-minute deep run](../reports/deep/README.md) retain their original source and artifact hashes. Their [historical comparison](../reports/deep/kernel/comparison.md) records invocation-guard allocation and throughput costs. Compare matching revisions, worker backends and workloads when evaluating a change.

## Reproduce

```bash
./mvnw -B -Pcoverage,sbom clean verify
scripts/profile.sh quick reports/local/run
# Longer forks/warmup/measurements:
scripts/profile.sh full reports/local/longer-run
```

`quick` uses two JMH forks, three 1-second warmup iterations and five 1-second measurement iterations. `full` uses three forks, five 2-second warmup iterations and eight 2-second measurement iterations. Both fix the heap at 512 MiB and include the JMH GC profiler. Profiles are deliberately outside the default test lifecycle so noisy performance numbers do not become flaky CI gates.

Choose a new, empty output directory for each run. The script refuses to overwrite earlier evidence, stops on JMH failures, and validates the discovered benchmark/parameter matrix, finite metrics, complete load counters, source fingerprint and measured JAR hash before generating a report.

| Probe | Measures | Does not establish |
|---|---|---|
| `PipelineBenchmark` | Changing numeric-frame transformations through 1/4/16 local stages, consumed by Blackhole | Model, serialization, media payload copying or network throughput |
| `SessionBenchmark` | One queued input through a session worker and output handoff | Open-loop overload behavior or concurrent-session capacity |
| `CoreLoad` | Queue-inclusive latency and accepted throughput across 16 sessions, 4 stages, max 32 outstanding frames per session | Arrival-rate SLA; the generator waits for in-flight capacity |
| `WebSocketLoad.java` | Real JSON text echo over loopback, one outstanding message per connection | Speech/model latency, remote-network behavior or production sizing |

Core load uses 100,000 warmup frames followed by 25 million measured frames in quick mode (50 million in full mode). It stores exact latency samples and uses nearest-rank percentiles. Waiting for a generator permit is excluded; queue waiting after submission is included. The latency-array allocation belongs to the measurement harness and is visible in allocation profiles.

## CPU, memory, GC and contention

JFR captures sampled CPU stacks, allocation weights, collection pauses and monitor contention; virtual-thread pinning is available only on runtimes that provide that event. [The Java 17 summary](../reports/java17/kernel/profiles.md) includes readable CPU/allocation distributions, full exported flame graphs and a GC pause timeline. Allocation weights are estimates, not retained heap. JMH `gc.alloc.rate.norm` estimates allocated bytes per operation. Zero recorded pinning or contention events does not prove absence in another workload or below recording thresholds.

The capture and JSON export explicitly request 64 stack frames; `jfr print` otherwise exports only five by default. The renderer preserves every supplied stack frame and reports recording truncation flags separately. The Java 17 baseline was restored from its original local JFR: all 3,371 events and their original first five frames match the earlier export. Only stack visibility was restored; benchmark results were not rerun or replaced. Older historical reports retain their original export depth.

The profiling script disables environment/system-property/process-inventory events and exports selected diagnostic event categories. Raw JFR remains local and is excluded from Git. Public evidence includes compact data and sanitized selected events. Real application recordings may contain sensitive data and should stay private.

The profiling script generates figures before moving any uncompressed selected export into local storage. To regenerate figures from the run's retained `jfr-events.json.gz`, run:

```bash
python3 scripts/summarize_profiles.py reports/local/run
```

## Scheduled evidence collection

The [Performance evidence workflow](../.github/workflows/performance.yml) runs every Monday at 01:23 UTC and can also be dispatched manually. Java 17 and 21 each run the full profile independently. Every successful run generates the measurement report, CPU/allocation distributions and flame graphs, GC pause timeline and summary, selected compressed events and source/run manifests. A diagnostic allowlist packages these files with the measured JAR hash, run metadata and SHA-256 checksums; raw JFR, uncompressed event exports and build logs are excluded. Artifacts expire after 30 days.

Collection or validation failure fails the job. A failure step preserves bounded diagnostic log tails only after privacy checks; unsafe logs are withheld. Hosted-runner throughput has no fixed pass threshold and does not automatically replace the README's recorded baseline. Compare matching revisions on a controlled host before accepting a performance claim. The [iteration plan](iteration-plan.md) separates evidence gaps from proposed runtime changes.

## Transport probe

Start the default server in another terminal:

```bash
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
java scripts/WebSocketLoad.java ws://127.0.0.1:8080/ws 32 5000
```

Each connection warms up for 100 frames. Measured latency starts before `sendText` and ends after the matching response arrives. Errors abort the probe instead of disappearing from the percentile calculation. This is a closed-loop local probe; slow peers, network jitter, TLS, large media frames and independent arrival-rate overload need dedicated workloads.

## Compare changes

Use the same idle host, JVM, heap, source revision, stage count and workload. Compare uncertainty and allocation alongside throughput. Keep raw evidence and note profiler overhead. Reports include a source fingerprint; the build uses a clean package to avoid stale incremental classes. Extend the workload when a change affects an unmeasured path such as async streaming or large audio buffers.

The published comparison supports only the explicitly tested execution profiles. Synthetic results do not establish universal framework superiority, predict model inference latency or establish a production session limit. Continuous load, fault injection and real provider trials are separate acceptance work.

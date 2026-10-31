# Performance methodology

OminiCat includes separate measurements for synchronous dispatch, session scheduling, sustained bounded concurrency and WebSocket transport. Read the [actual baseline](../reports/baseline/report.md), [CPU/allocation/GC analysis](../reports/baseline/profiles.md), [transport result](../reports/baseline/websocket.json) and [verification summary](../reports/verification.md).

## Reproduce

```bash
./mvnw -B -Pcoverage,sbom clean verify
scripts/profile.sh quick reports/local/run
# Longer forks/warmup/measurements:
scripts/profile.sh full reports/local/longer-run
```

`quick` uses two JMH forks, three 1-second warmup iterations and five 1-second measurement iterations. `full` uses three forks, five 2-second warmup iterations and eight 2-second measurement iterations. Both fix the heap at 512 MiB and include the JMH GC profiler. Profiles are deliberately outside the default test lifecycle so noisy performance numbers do not become flaky CI gates.

| Probe | Measures | Does not establish |
|---|---|---|
| `PipelineBenchmark` | Changing numeric-frame transformations through 1/4/16 local stages, consumed by Blackhole | Model, serialization, media payload copying or network throughput |
| `SessionBenchmark` | One queued input through a virtual worker and output handoff | Open-loop overload behavior or concurrent-session capacity |
| `CoreLoad` | Queue-inclusive latency and accepted throughput across 16 sessions, 4 stages, max 32 outstanding frames per session | Arrival-rate SLA; the generator waits for in-flight capacity |
| `WebSocketLoad.java` | Real JSON text echo over loopback, one outstanding message per connection | Speech/model latency, remote-network behavior or production sizing |

Core load uses 100,000 warmup frames followed by 25 million measured frames in quick mode (50 million in full mode). It stores exact latency samples and uses nearest-rank percentiles. Waiting for a generator permit is excluded; queue waiting after submission is included. The latency-array allocation belongs to the measurement harness and is visible in allocation profiles.

## CPU, memory, GC and contention

JFR captures sampled CPU stacks, allocation weights, collection pauses, monitor contention and virtual-thread pinning. [The summary](../reports/baseline/profiles.md) includes sampled flame graphs. Allocation weights are estimates, not retained heap. JMH `gc.alloc.rate.norm` estimates allocated bytes per operation. Zero recorded pinning or contention events does not prove absence in another workload or below recording thresholds.

The profiling script disables environment/system-property/process-inventory events and exports selected diagnostic event categories. Raw JFR remains local and is excluded from Git. Public evidence includes compact data and sanitized selected events. Real application recordings may contain sensitive data and should stay private.

To summarize a local run with full selected events, copy its selected JSON from `reports/local/<run-name>/jfr-events.json` into that run's folder temporarily, then run:

```bash
python3 scripts/summarize_profiles.py reports/local/run
```

## Transport probe

Start the default server in another terminal:

```bash
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
java scripts/WebSocketLoad.java ws://127.0.0.1:8080/ws 32 5000
```

Each connection warms up for 100 frames. Measured latency starts before `sendText` and ends after the matching response arrives. Errors abort the probe instead of disappearing from the percentile calculation. This is a closed-loop local probe; slow peers, network jitter, TLS, large media frames and independent arrival-rate overload need dedicated workloads.

## Compare changes

Use the same idle host, JVM, heap, source revision, stage count and workload. Compare uncertainty and allocation alongside throughput. Keep raw evidence and note profiler overhead. Reports include a source fingerprint; the build uses a clean package to avoid stale incremental classes. Extend the workload when a change affects an unmeasured path such as async streaming or large audio buffers.

No synthetic result here demonstrates superiority over another framework, predicts model inference latency or establishes a production session limit. Continuous load, fault injection and real provider trials are separate acceptance work.

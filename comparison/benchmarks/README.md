<!-- ominicat-language-nav -->
[简体中文](../../docs/zh-CN/comparison/benchmarks/README.md) · **English**
<!-- /ominicat-language-nav -->

# Java queued adapters

An optional standalone project implementing [comparison protocol 1](../protocol.md). It is outside the production Maven reactor. Dependencies are current OminiCat core and Apache Camel **4.22.2 LTS** ([official Java 17 support](https://camel.apache.org/releases/release-4.22.2/)).

Build the current core locally, then build this project serially from the repository root:

```sh
./mvnw -B -pl ominicat-core -am install -DskipTests
./mvnw -B -f comparison/benchmarks/pom.xml clean verify
java -Xms128m -Xmx512m -jar comparison/benchmarks/target/benchmark.jar \
  --engine ominicat --stages 4 --warmup-seconds 3 --measure-seconds 5 \
  --max-samples 2000000 --output reports/local/comparison/ominicat-4.json
# Use --engine camel for the same payload, oracle, and whole-loop harness.
```

Measurements require an actual Java 17 JVM. The output path must not exist. The process emits flushed warmup/measure/teardown phase JSON lines and writes successful result JSON only after drain and cleanup. Failures exit nonzero. The parent runner owns fresh processes, repetition/order, RSS sampling, environment/source/artifact manifests and aggregation.

OminiCat uses `trySubmit` into a capacity-one session and an `ArrayBlockingQueue(1)` for terminal acknowledgment. Camel uses native [SEDA](https://camel.apache.org/components/4.22.x/seda-component.html) with one consumer, capacity one, no discard, no virtual-thread-per-task mode, immediate full-queue failure, and `waitForTaskToComplete=Always`. `ProducerTemplate.requestBody` includes its normal Exchange/Message creation, correlated copy and request/reply costs. Its return is independently checked against the terminal oracle. These are public API path costs, not identical internals or feature parity.

Every stage creates a new shared immutable numeric record. Both terminal sinks validate sequence, phase, index, transformed value and output count before stopping latency, then acknowledge the caller. Input construction is inside latency; acknowledgment is outside terminal latency and inside whole-loop wall throughput. A fixed **16 MiB** primitive buffer is pre-touched before warmup; at most 2,000,000 exact measured samples are accepted. Percentiles are nearest rank and sorted after timing. CPU time covers the measured process phase; heap/GC activity can include earlier warmup objects. Startup, setup validation, drain, shutdown, sorting and JSON are outside timing.

Setup verifies a real different stage/terminal worker. Between phases, all work must drain. Shutdown must complete and the observed worker must terminate within the bounded watchdogs. Tests cover both engines at all three stage counts, output arithmetic, malformed or duplicate output, sample overflow and invalid options. The W=1 result describes queued round-trip execution; it is not saturated capacity, model latency or production session capacity.

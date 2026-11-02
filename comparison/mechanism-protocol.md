<!-- ominicat-language-nav -->
[简体中文](../docs/zh-CN/comparison/mechanism-protocol.md) · **English**
<!-- /ominicat-language-nav -->

# Controlled Java mechanism experiments

This profile tests local mechanisms behind performance explanations. It is separate from the native queued comparison in [protocol 1](protocol.md). It changes no production runtime and makes no model, network, media-capacity or feature-parity claim.

## Questions and controls

The path experiment has four cells: OminiCat or Camel, each called on the benchmark thread or through the same external handoff. Both execute four immutable numeric transformations and the same output oracle. OminiCat uses the real `Pipeline.process` API. Camel 4.22.2 uses a real `direct:` InOut route with `synchronous=true`, `block=false` and `failIfNoConsumers=true`. Camel JMX, message history and stream caching are disabled in both of its cells.

The handoff is one shared implementation: one capacity-one `ArrayBlockingQueue` for input, one persistent platform worker, and one capacity-one acknowledgment queue. At most one request is outstanding. Only the handoff variable changes within a framework. Every stage and terminal checks its expected execution thread. Output validation checks sequence, value, count and ordering. Success acknowledgment is published only after the synchronous backend has returned: a backend that emits a correct value and then throws must still fail.

This experiment does not use `PipelineSession` or SEDA as its handoff. Within a framework, its difference measures this whole external queue/worker/ack package, including its allocations and waiting. Between frameworks, it compares their public synchronous paths under the same handoff. It does not isolate a single instruction, lock or universal routing cost.

## Correlated Exchange copy

Two further cells use one prebuilt source Exchange: return the source identity, or invoke `ExchangeHelper.createCorrelatedCopy(source, false, true)`. These are the exact flags used by the pinned SEDA request/reply path. Source construction and lazy source/message-ID initialization occur before timing. Both variants consume and validate the same immutable body and prepared metadata; the copy must remain a distinct Exchange with the correct correlation and message identity. The fixture records its source configuration, rather than presenting it as every possible Exchange shape.

This component experiment excludes source-Exchange construction, SEDA queueing, completion-latch waiting, result-copy-back and route execution. Its cost cannot be added to path differences to explain the old SEDA comparison: JIT, object escape, caching, allocation and GC interact.

## Measurement protocol

- Java 17 on Ubuntu 24.04, one host/JVM/settings and one verified probe JAR; `-Xms256m -Xmx256m`.
- JMH 1.37 AverageTime in `ns/op`, one benchmark thread. Each path operation includes input construction, transforms, result checks, synchronous return and, where selected, complete handoff/acknowledgment. Copy operations instead use the prepared source described above.
- Six cells run in forward order and then reverse order: twelve fresh benchmark JVMs, two independent forks per cell. Every fork warms for three one-second iterations and measures five one-second iterations.
- Record all raw iteration data, the two fork means and observed iteration ranges. Ranges are observations, not confidence intervals. Hosted-runner noise is not a performance threshold or a production capacity estimate.
- JMH GC profiling supplies `B/op` over participating JVM threads; the handoff worker lives throughout the trial. Allocation includes the harness, framework and runtime, not framework-exclusive bytes or retained memory.
- A separate internal profiler normalizes whole-process CPU nanoseconds by JMH's all-operation count over the profiler iteration interval. That interval includes iteration-hook work as well as worker, JVM, GC and harness work; it is not wall latency or attribution to one method.
- No per-operation latency array or additional clock is inserted. This experiment reports means, not p99. It does not sample the JMH launcher and call that benchmark-process RSS.

## Correctness and failure boundaries

Tests must reject wrong, duplicate or absent outputs, the wrong execution thread, saturation, post-output exceptions and missing acknowledgments. Close drains owned work, stops the Camel context and joins the external worker. No retry, dropped case, partial matrix or best-fork selection can produce a successful report. Fresh-fork setup verifies the actual executable JAR origin against its expected SHA-256.

The collector rejects changed sources/artifacts, unexpected settings or cases, missing iterations, invalid/nonfinite metrics and unreviewed diagnostics. It records source, dependency and executable hashes, host/JVM scope, order and timer boundaries. Raw files remain byte-for-byte preserved.

## Separate diagnostics and publication

Independent, explicitly instrumented runs record CPU/allocation stacks and GC events. They use JMH without another fork so the selected JVM is the one being recorded; those timings are ineligible for the main comparison. Sampling includes initialization, warmup, the harness and teardown. Missing samples do not establish absence, and allocation sample weights are not exact `B/op`.

Only the selected-event diagnostic allowlist and its derived figures are published. Raw JFR, configurations and unreviewed tool output remain outside the publish directory. Source/runtime and file hashes bind the complete measurement and diagnostic collections. The existing native queued results and archives remain unchanged.

## Reproduction

```sh
./mvnw -B -ntp -pl ominicat-core -am install -DskipTests
./mvnw -B -ntp -f comparison/mechanisms/pom.xml verify
python3 comparison/mechanism_evidence.py collect --root . --output reports/local/mechanism-capture/evidence/measurements
python3 comparison/mechanism_profiles.py collect --root . --output reports/local/mechanism-capture
python3 comparison/mechanism_evidence.py check reports/local/mechanism-capture/evidence/measurements
python3 comparison/mechanism_profiles.py check reports/local/mechanism-capture/evidence
```

Use the explicitly configured Java 17 `JAVA_HOME`. The dedicated Linux workflow runs these steps sequentially. Compilation and correctness fixtures also run on Java 17, 21 and 25; measurement results remain tied to Java 17.

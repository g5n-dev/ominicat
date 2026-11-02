<!-- ominicat-language-nav -->
[简体中文](../docs/zh-CN/comparison/direct-state-protocol.md) · **English**
<!-- /ominicat-language-nav -->

# Direct invocation state: paired structural experiment

This preregistered protocol defines a bounded structural experiment. Its recorded outcome belongs in the source-bound result report. This is a causal comparison within OminiCat, not another framework ranking. No benefit is accepted before the complete Linux experiment and lifecycle checks. A negative or inconclusive result remains publishable evidence; it does not authorize shipping a worse runtime.

## One controlled change

The baseline direct dispatch evaluates a captured continuation and wraps it in a fresh synchronous sink. The experiment preserves these recorded candidate variants, identified by their source and JAR hashes:

- The first variant combines continuation and lifetime into one four-field private static invocation, with inline dispatch. It failed the envelope time target.
- The second retains that representation with separate construction/cleanup and forwarding helpers. Its envelope block range was inconclusive under the same target.
- The third binds fixed processor/successor references once during pipeline construction. Its immutable `DirectStage` nodes hold only processor and successor; each fresh invocation holds only owner thread, next stage and final output. It stores no input payload, cursor or output in the shared plan.

Each variant still invokes processors in order, allows filtering and multiple outputs, checks thread/lifetime before inspecting an emitted frame, and clears all invocation graph references in `finally`. It never pools or reactivates a retired handle. A null successor means an active terminal output; only a null owner marks retirement.

Only `Pipeline.java` may differ among production kernel sources. Public entry-point bodies, session/async dispatch, generation fences, shared synchronous sinks and media constructors remain unchanged. Variant three changes private constructor/field structure: a non-async pipeline owns N immutable stage nodes and one N-entry reference array, including session-only pipelines; async pipelines do not build the plan. This bounded setup cost must be measured alongside hot-path benefits. Processor instances and state are not copied or globally shared by this plan.

Reducing source allocation expressions does not itself establish heap bytes or speed: inlining and escape analysis may eliminate or reshape allocations. Actual JARs use the same executable probe within each comparison; different runners and candidate variants are not pooled.

## Fixed behavior profile

Seven scenarios run through public APIs, with every stage and terminal checked:

| Scenario | Work and boundary |
| --- | --- |
| `raw-direct-1` | One numeric stage; direct public call and synchronous return. |
| `raw-direct-4` | Four numeric stages; the preregistered primary allocation case. |
| `raw-direct-16` | Sixteen numeric stages; sensitivity to stage count. |
| `envelope-direct-4` | Four real envelope adapters; deterministic identifier and clock suppliers, exact metadata/causation checks. Their work is timed. |
| `raw-session-4` | Unchanged real session path, capacity one and one request outstanding. Includes terminal acknowledgment and waiting for the successful processed counter; this control is reported separately, never used to correct other results. |
| `audio-direct-4` | Four pass-through stages over 64 prebuilt immutable 640-byte, 16 kHz mono PCM frames; exact frame identity and stage ordering. |
| `image-direct-4` | Four pass-through stages over 64 prebuilt immutable 64 KiB synthetic image payloads; exact frame identity and stage ordering. |

Numeric inputs and transformed frames are created inside timing, with a rotating independent result oracle. Media creation, byte-snapshot validation and read-only checks happen during setup; their constructors and codecs are not timed. Reusing prebuilt media does not measure audio/image creation, decoding or production media capacity. Setup, artifact verification and resource cleanup are outside the benchmark method. Session checking and waiting costs are part of its own measured control.

## Pairing and provenance

Use Ubuntu 24.04 and an explicitly selected Java 17 JVM, one benchmark thread and a fixed 256 MiB heap. Build baseline and candidate core JARs, then one probe JAR. Every fresh JVM uses its selected core first on the classpath and exactly the same probe bytes. Setup verifies the actual source JARs for public kernel classes, pipeline nested classes and the probe against mandatory SHA-256 properties. Paths are not printed in public evidence.

The fixed plan has three ABBA blocks. Within each scenario, run baseline, candidate, candidate, baseline sequentially, each as a fresh single-fork JMH JVM. Reverse scenario order in the middle block. The complete plan is 84 JVMs, six forks per side/scenario. Each fork uses three one-second warmup iterations and five one-second measured iterations, reporting AverageTime in `ns/op` and GC-profiler allocation in `B/op`. Preserve all 30 measured iterations per side/scenario, six fork means and source/artifact/environment fingerprints.

For each block, compare the arithmetic mean of its two candidate fork means against the arithmetic mean of its two baseline fork means. Report all three paired ratios, their observed range and arithmetic mean. Forks and blocks are the replication units; iterations within a fork are not independent repetitions. Observed ranges are not confidence intervals. Shared-runner variation and three blocks cannot establish a universal performance guarantee.

## Preregistered acceptance and counterexamples

The observation targets are at least 10% lower allocation in every primary-case block, no more than 5% greater time in any changed direct-case block, and no allocation increase exceeding 1% in those direct cases. The unchanged session control cannot rescue a failed target or be used for post-hoc noise correction. The evidence collector archives technical success independently of these targets: consistent observations are `observed-targets-met`; point estimates that meet targets with inconsistent block ranges are `uncertain`; otherwise `targets-not-met`. These labels are experimental decisions, not CI performance threshold gates or claims of statistical significance. An allocation baseline of zero with a zero candidate has ratio 1; a positive candidate over zero retains a null ratio and fails the target, without inventing Infinity or treating a valid observation as a technical failure.

Shipping also requires Java 17 coverage/SBOM verification, Java 17/21/25 compatibility and 512 runtime stress seeds. Behavioral fixtures must retain filtering, ordered multiple emissions, nested direct calls, null/error behavior, wrong-thread rejection and post-return rejection. A retired handle held by a plugin must not retain the pipeline, captured processor state, output state, input payload or owning thread. An output followed by an exception remains a failed call. Cancellation and generation tests continue to protect unchanged session/async behavior.

If the declared evidence is incomplete, mixed, changed, nonfinite or misbound, collection fails instead of omitting cells. If the optimization is unsupported or uncertain, keep the evidence and leave production behavior unchanged. A result here cannot explain a percentage of the old Camel SEDA difference: that comparison also has different APIs, metadata and acknowledgment machinery, and Camel already has a synchronous route behind its entrance queue.

## New-pipeline initialization costs

A separate `DirectPlanStartupProbe.invoke` experiment reports `build-4` and `first-direct-4`. Both create a new real four-stage builder and pipeline, using four cached distinct processors and one prebuilt input/terminal oracle. Build-only consumes the resulting pipeline, checking the configured graph during setup and last-instance teardown. First-direct also executes and validates every timed first call, including synchronous return. Processor/input construction is outside timing; builder, pipeline, plan and selected first-call work are inside.

These are warmed-JVM costs for a new pipeline, not OS/JVM cold-start latency. Two scenarios use one ABBA block, eight fresh JVMs total, two forks per side/scenario, with the same three one-second warmups and five one-second measurements. The strict 21-file archive binds the actual complete main manifest, runtime/probe hashes and host. It has no performance target and cannot override the seven hot-path targets. Report its time/B/op and fork ranges separately; do not derive production capacity or subtract timings across different probe oracles.

## Independent diagnostics

Record baseline and candidate `raw-direct-4` and `envelope-direct-4` in four separate instrumented JVMs. Use JMH without an additional fork, two one-second warmups and two one-second measurements. Request 10 ms execution sampling, allocation sampling throttled to 50 events per second, and 64-frame recording/export stacks. The 128 MiB selected-event JSON bound remains strict; oversized, partial or missing evidence fails rather than being truncated.

CPU/allocation/GC figures include startup, setup, warmup, harness and teardown. They are not additional main timing samples. Total samples, estimated allocation weights and GC counts are not normalized by operations; faster loops can perform more work within the same duration. Use formal JMH `B/op` for per-operation allocation. Publish only selected allowlisted events and derived diagnostics; never raw JFR, private configurations, credentials or unreviewed tool logs.

## Reproduction

```sh
# In each clean baseline/candidate checkout, with the same Java 17 JAVA_HOME:
./mvnw -B -ntp -pl ominicat-core -am package -DskipTests
# Build the one shared probe in the candidate checkout:
./mvnw -B -ntp -pl ominicat-benchmarks -am package -DskipTests
python3 comparison/direct_state_evidence.py collect --baseline BASELINE_CHECKOUT --candidate . --output reports/local/direct-state/evidence/measurements
python3 comparison/direct_plan_startup.py collect --baseline BASELINE_CHECKOUT --candidate . --measurements reports/local/direct-state/evidence/measurements --output reports/local/direct-state/startup-evidence
python3 comparison/direct_state_profiles.py collect --baseline BASELINE_CHECKOUT --candidate . --output reports/local/direct-state
python3 comparison/direct_state_evidence.py check reports/local/direct-state/evidence/measurements
python3 comparison/direct_state_profiles.py check reports/local/direct-state/evidence
```

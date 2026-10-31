# Iteration plan

This is a living queue for small, evidence-led improvements to OminiCat's high-performance Java 17+ multimodal microkernel. Completed work links to its pull request and validation. A new failure can take priority over the queue. Reassess assumptions at the start of each increment rather than treating proposals as confirmed defects.

## Execution loop

1. Read repository instructions, current source, open pull requests and recent CI results. Resume matching unfinished work and avoid modifying another task's files.
2. Select one ready item; state its trigger, bounded scope and acceptance criteria before implementation. If every item needs missing external information, record the exact dependency and stop without speculative changes.
3. Implement and verify the increment. Use local synthetic protocol fixtures by default. Preserve diagnostic evidence and failure output; incomplete measurements must fail rather than produce a successful report.
4. Update this queue with evidence and the next useful action, then open or update a focused pull request containing the implementation and plan changes. Let all five required checks pass at its current head, resolve conversations and merge through protected `main`. Do not weaken the gates to complete a run.
5. Confirm the merged result and carry remaining work into the next increment. For experimental optimization, a measured negative result is a valid outcome; retain the conclusion without shipping a worse runtime.

Scheduled profiling is a separate evidence collection job. It runs weekly on Java 17 and 21, generates CPU/allocation flame graphs and GC summaries, and retains an allowlisted, checksummed artifact for 30 days. Its success means collection and validation completed, not that performance improved. CI runner variation must not update headline performance claims automatically. See [performance methodology](performance.md).

## Ready queue

| Order | Increment | Evidence and scope | Acceptance |
| --- | --- | --- | --- |
| 1 | Async continuation and envelope cost | Existing JMH covers synchronous numeric dispatch and session round trips. Add bounded external-callback benchmarks with raw-frame and envelope variants; keep runtime contracts unchanged. | Validate nonce, causation and frame output; one request in flight; executor/session resources drained on teardown. Record Java 17 warmup, at least two forks, latency, B/op, source hash and timer boundaries. Include executor, identifier and clock overhead in the stated scope. Compile and verify on 17/21/25. |
| 2 | Media snapshot and dispatch cost | Numeric-frame results exclude the defensive copy in audio/image construction. Benchmark construction separately from dispatch of a prebuilt immutable frame. Cover 640/3,840-byte PCM blocks and 64 KiB/1 MiB image bytes. | Prepare reusable source data outside timing, prevent constant folding, retain snapshot/read-only regressions. Publish time/throughput, B/op and GC with exact payload sizes. Make no codec, model or real-time media capacity claim. |
| 3 | Reduce synchronous invocation allocation, if justified | The recorded four-stage Java 17 dispatch allocates 328 B/op. JFR points to invocation guards and captured lambdas; sampling alone does not establish the benefit of a change. First reproduce the base, then test a disposable prototype that combines per-invocation state. Depends on items 1 and 2. | Target at least 10% less allocation, with no throughput or mean round-trip regression exceeding 5% and measurement uncertainty on a controlled host. Pass 17/21/25 verification and 512 stress seeds, including stale sink, wrong thread, close and reference-release tests. No sink pooling/reactivation or public API changes. If the target is missed, publish the experiment conclusion and keep the runtime unchanged. |

## Conditional queue

| Item | Dependency | Acceptance |
| --- | --- | --- |
| Live compatible-provider trial | Explicit endpoint, selected model and credential environment-variable name supplied by the operator. Local protocol fixtures are already available and do not satisfy this dependency. | Bounded opt-in calls; verify streamed content, cancellation before peer EOF/deadline, error handling and slot/resource drain. Publish only sanitized evidence with service/runtime/source context. Record actual failures and supported capabilities. |
| Further transport or provider optimization | A reproducible regression, profile hotspot or workload gap in current evidence. | Define workload, resource budget and failure injection before changing runtime behavior. Re-run relevant integration tests and attach evidence tied to the changed artifact. |

## Completed increments

| Increment | Result | Evidence |
| --- | --- | --- |
| Java 17 baseline and interruptible HTTP response-body reads | Java 17 minimum with 17/21/25 verification; cancellation releases blocked local HTTP reads without waiting for peer EOF. | [PR #12](https://github.com/g5n-dev/ominicat/pull/12), [PR #14](https://github.com/g5n-dev/ominicat/pull/14), [current Java 17 validation](../reports/java17/http-cancellation/README.md) |
| Performance and profile documentation | README exposes measured throughput, allocation, CPU stacks, GC and resource drain with distinct workload scopes. | [PR #13](https://github.com/g5n-dev/ominicat/pull/13), [PR #15](https://github.com/g5n-dev/ominicat/pull/15), [kernel profiles](../reports/java17/kernel/profiles.md) |
| Repeatable planning and evidence collection | A durable acceptance queue, weekly profiling, complete-result validation, allowlisted/checksummed diagnostics and privacy-checked failure output. | [Workflow](../.github/workflows/performance.yml), [report validation](../scripts/test_report.py), [packaging controls](../scripts/test_package_profiles.py), [failure controls](../scripts/test_profile_failure.py) |

This queue proposes work; it does not assert that an unimplemented optimization is correct or faster. Recorded headline results remain tied to their original source, workload and host until a reviewed replacement measurement exists.

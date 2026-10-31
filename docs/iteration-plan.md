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

### Temporal and annotation semantics

The [temporal contract proposal](temporal-annotations.md) defines optional behavior profiles that compatibility metadata or a result codec alone cannot establish. These are planned capabilities, not shipped algorithms or APIs. Choose work from the concrete integration being enabled; existing raw-frame pipelines need none of these profiles. The preferred first temporal increment is T1a with one immutable range import/export fixture, while I1 remains independently executable.

| Profile | Increment | Acceptance boundary |
| --- | --- | --- |
| T1a | Minimal source/range/version references | Distinguish source/track/time basis and producer allocation scope; reject ambiguous external references and unprovided exact clock mappings. Preserve legacy unknown positions. No editor, reducer or global ID service. |
| T2 basic | Independent, versioned annotation data | Subject-only annotations need no media timeline; range targets use only the required T1a subset. Test dimensions/values, scoped targets, schema and payload limits. Grouped ranges and mutation are separately declared behaviors, not baseline prerequisites. |
| T1b editing | Optional local segment changes | Requires the reference subset used by its edit fixture. Test bounded atomic range adjustment/split/merge and exact expected revisions; reject conflicts before state changes. Read-only annotation import must not wait for this reducer. |
| T3 projection | Optional evidence dependencies and result lifecycle | Use only adapter-owned bounded dependencies and explicit impact policy. Keep current pointer, validity and evidence availability separate. No invalidation from cache eviction, global graph, automatic scheduler or rollback claim for external effects. |

Establish the smallest in-process fixture first. These profiles are not one compulsory dependency chain: I1 is independent, subject-only T2 bypasses temporal profiles, and read-only range T2 does not require T1b/T3. Use I2 registration for the proposed OminiCat versioned wire profile, not as a prerequisite for every project/file codec; add I3 resume binding to the versions actually used. A behavior probe certifies only the advertised profile. Match quality metrics to the algorithm's declared claims and measure optional representation/reducer costs separately from kernel dispatch.

### Integration compatibility

The [cross-project contract proposal](integration-contracts.md) defines supporting integration increments and three fixture families. These are planned capabilities, not shipped APIs. Prioritize one bounded item at a time; retain Java 17 and the existing default API/protocol behavior. I1 establishes compatibility fixtures with current APIs; I2/I3 extend them as their contracts ship.

| Order | Increment | Acceptance boundary |
| --- | --- | --- |
| I1 | Optional plugin descriptions and compatibility fixtures | Preserve old plugin binaries; query metadata without calling `ProcessorFactory.create` or network code; explicitly handle unknown capabilities; reject a known utterance/streaming mismatch. Establish streaming/replay/aggregation baseline fixtures with current APIs; I2/I3 extend them later. |
| I2 | Opt-in namespaced result schemas and provenance | Preserve default wire fixtures; distinguish same-named frame classes; reject duplicate/unsupported schema registrations; retain evidence time units, causal IDs and window scope. Explicit subject mapping must distinguish equal local keys across applications. Measure codec cost separately. |
| I3 | External run manifest and checkpoint binding | Reject mismatched input/definition contents on resume before state changes; allow new-policy reevaluation as a new run. Sink failure must not advance the cursor. Storage and durable effects remain external. |

### Performance evidence

Continue these measurement increments alongside the integration work when their workload is needed to assess a change. Allocation experiments depend on the relevant baselines rather than preceding them.

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
| Resource-budget consolidation | Evidence that the documented per-layer limits cannot express a required integration budget. Parameter duplication alone is insufficient. | Model media copies, queues/branches/outlets, native/JVM overhead and plugin limits; validate contradictions and drain with a defined workload. Keep identity, tenant quotas, billing and distributed admission in applications/edge services. |
| Provider-independent audio preprocessing | A representative target corpus and explicit quality/latency goals for a reusable media plugin. RMS energy detection alone does not satisfy noise suppression. | Declare PCM formats, per-track continuity/state, streaming scope, reset/cancellation and CPU/buffer limits. Evaluate filtering/suppression and downstream recognition quality separately from kernel throughput; do not infer attribution from energy alone. |
| Transport-neutral operation-event adapter | Repeated need to instrument embedded, worker and transport-driven operations through one reusable adapter. | Bound versioned event payloads and delivery, preserve causation/target references and test failure at the declared commit point. Avoid coupling the common event contract to an HTTP-controller interceptor; authorization, retention and durable audit guarantees stay with the application/store. |

## Completed increments

| Increment | Result | Evidence |
| --- | --- | --- |
| Java 17 baseline and interruptible HTTP response-body reads | Java 17 minimum with 17/21/25 verification; cancellation releases blocked local HTTP reads without waiting for peer EOF. | [PR #12](https://github.com/g5n-dev/ominicat/pull/12), [PR #14](https://github.com/g5n-dev/ominicat/pull/14), [current Java 17 validation](../reports/java17/http-cancellation/README.md) |
| Performance and profile documentation | README exposes measured throughput, allocation, CPU stacks, GC and resource drain with distinct workload scopes. | [PR #13](https://github.com/g5n-dev/ominicat/pull/13), [PR #15](https://github.com/g5n-dev/ominicat/pull/15), [kernel profiles](../reports/java17/kernel/profiles.md) |
| Complete profile export and readable figures | Explicit 64-frame capture/export, complete supplied-stack rendering, conserved hotspot summaries and GC pause timeline. Restored deeper stacks from the original Java 17 recording without replacing measurement results. Successful collection generates all figures before moving local raw exports. | [Generator regressions](../scripts/test_summarize_profiles.py), [packaging controls](../scripts/test_package_profiles.py), [restored profiles](../reports/java17/kernel/profiles.md) |
| Repeatable planning and evidence collection | A durable acceptance queue, weekly profiling, complete-result validation, allowlisted/checksummed diagnostics and privacy-checked failure output. | [Workflow](../.github/workflows/performance.yml), [report validation](../scripts/test_report.py), [packaging controls](../scripts/test_package_profiles.py), [failure controls](../scripts/test_profile_failure.py) |
| Cross-project contract review | Source-backed separation of existing mechanisms, proposed compatibility contracts and external responsibilities, with explicit negative acceptance cases. Implementation remains queued as I1–I3. | [Design and acceptance plan](integration-contracts.md) |
| Temporal/annotation coverage review | Separates partial transcript revision from general segment changes, rule results from independent annotations, and local reduction from external publication guarantees. Optional profile implementations remain queued. | [Semantics and acceptance plan](temporal-annotations.md) |
| Minimal-profile architecture review | Removes compulsory editing/media dependencies, qualifies cross-producer references and separates cache availability from result validity. Metrics and conformance follow the profile actually claimed. | [Optional profiles](temporal-annotations.md), repository architecture-review instructions |

This queue proposes work; it does not assert that an unimplemented optimization is correct or faster. Recorded headline results remain tied to their original source, workload and host until a reviewed replacement measurement exists.

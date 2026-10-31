# Temporal segments and annotations

Status: design proposal and acceptance criteria, not implemented APIs, configuration fields or wire types. This extends the [integration contract proposal](integration-contracts.md); the [scenario matrix](scenario-coverage.md) remains the implemented scope.

Reusable temporal processing needs more than a text revision or a rule score. An integration may adjust a range, split or merge segments, attach several independent annotations and revise a result after new evidence arrives. These contracts belong in an optional module and adapters. They must not introduce an application entity model, label vocabulary or global state into the microkernel.

Choose only the behavior profile required by an integration. Existing raw-frame pipelines and I1 capability discovery do not depend on these contracts. Read-only imports do not need an editor, and subject-only annotations do not need a media timeline. Shared representations do not require one shared state machine or reducer.

## Existing mechanisms and their limits

| Mechanism | Implemented behavior | Does not establish |
| --- | --- | --- |
| [Transcript](../ominicat-core/src/main/java/io/ominicat/core/Frame.java) / [gate](../ominicat-providers/src/main/java/io/ominicat/providers/TranscriptGateFactory.java) | Stable explicit segment IDs; higher partial revisions can change text/range; retained committed finals reject conflicting facts | General segment split/merge/retraction or modification of a committed final |
| [Channel split](../ominicat-providers/src/main/java/io/ominicat/providers/AudioChannelSplitFactory.java) | Deinterleaves PCM tracks | Semantic segment boundaries or identity/attribution decisions |
| [ConversationMetrics](../ominicat-conversation/src/main/java/io/ominicat/conversation/ConversationMetrics.java) | Interval-union duration statistics | A segment partition, source alignment or word-level timestamps |
| [EvaluationFrame](../ominicat-conversation/src/main/java/io/ominicat/conversation/EvaluationFrame.java) / [RuleResult](../ominicat-conversation/src/main/java/io/ominicat/conversation/RuleResult.java) | Multiple rule results, scores, retained-window scope and evidence | A general annotation collection, label taxonomy or target-revision lifecycle |
| [Evidence](../ominicat-conversation/src/main/java/io/ominicat/conversation/Evidence.java) | Segment/role/speaker, a whole-turn interval and an excerpt; built-in rules bound generated excerpts | Source-media identity, an explicit timebase, segment revision or precise audio alignment for the excerpt |
| Open Java [Frame](../ominicat-core/src/main/java/io/ominicat/core/Frame.java) | Applications can implement additional events | An already implemented decoder, reducer or interoperable external schema for those events |

## T1a: Minimal source, range and version references

A source reference identifies an application-owned media asset/version, track and coordinate basis. Use an opaque qualified reference rather than an embedded recording, credential-bearing URL or a locally meaningful filename. A content fingerprint is caller-supplied when available; absence is explicit. Compute large media fingerprints at import/run boundaries, not per frame.

Distinguish source samples, normalized media time and local monotonic processing timestamps. A sample range needs its sample rate and per-channel convention. Mapping between clocks or tracks requires an explicit driver mapping; identical numeric positions or track names do not establish a shared timeline. State endpoint, point-event and unknown-position semantics in the new profile without reinterpreting legacy transcript intervals.

An adapter for inclusive discrete frame indices must translate endpoints using the declared frame rate/timebase. A next-frame offset must not become an arbitrary extra millisecond or audio sample in another coordinate system.

Nonuniform frame times require an appropriate timestamp/boundary mapping from the driver. For frames starting at 0/40/100 ms, an average 20 fps predicts 50 ms for frame 1 instead of 40 ms. A unit or average rate alone must not authorize a precise seek or exact range conversion.

Externally shared segment references include an explicit allocation scope, source binding, local segment ID and revision. Independently allocating producers/runs must use distinct scopes even when they process the same source and both emit `segment-1` revision 0. An adapter may supply a known local binding scope for legacy transcripts; the existing Transcript API remains unchanged. Scope is reference data, not authorization, and requires no kernel-global ID allocator or lookup service.

Acceptance:

- Two sources with the same track name and numeric interval stay distinct. Reject unprovided clock mappings and incompatible units. A precise seek must reject an unknown position or changed required source fingerprint; legacy frames with unknown positions remain valid for operations that do not require seeking.
- Verify PCM units: 640 bytes of stereo PCM16 at 16 kHz contain 160 samples per channel, lasting 10 ms. Do not count bytes or both channels as the sample cursor.
- Distinguish identical local segment IDs/revisions from two producer bindings. An unqualified external reference is unresolved unless its adapter supplies an unambiguous scope.
- Use the explicit 0/40/100 ms mapping for exact conversion; reject an exact-seek claim based only on an average rate. No automatic clock-conversion service is implied.

## T1b: Optional local segment changes

Only an integration advertising editing needs this profile. Separate segment identity from its current range. A revision refers to an exact prior version; split/merge events identify all affected parent/child versions and an operation ID. Changes preserve historical references rather than editing committed transcript facts in place. A bounded, single-owner reference reducer validates the whole change before replacing state; applications may provide another conforming implementation. This local atomicity does not supply a persistent transaction or distributed commit.

Acceptance for the declared editing operations:

- Preserve an explicit segment identity when a partial boundary changes. A committed transcript remains immutable; an overlay revision links the old and new segment versions explicitly.
- Validate split/merge atomically: stale expected revision, duplicate child IDs, missing parents or invalid declared lineage leave state untouched. Define and test the overlap/gap policy for that operation; do not prohibit overlapping segments globally.
- Duplicate operations within the declared retention window do not apply twice. The first implementation may reject conflicts immediately; deferred resolution is a separate optional behavior with a deadline and explicit overflow. Persistent deduplication outside retention remains external.

## T2: Independent, versioned annotations

An annotation has its own identity/revision and a precise target: a segment version, point/range or application-qualified subject reference. Keep event, run, segment, annotation and application subject identities distinct. The full subject namespace/kind/key tuple identifies a reference; it does not authorize access or an association.

Declare basic/read-only, media-target, grouped-range and editing support independently. Subject-only annotations use their qualified subject contract without T1a; media targets use the required T1a coordinate/reference subset. None of these data profiles requires T1b. The annotation representation can be used in-process; I2 supplies the proposed OminiCat versioned wire profile when selected. Other project/file codecs remain adapter choices.

Represent independent dimensions and multiple valid values without reusing `speaker`, `role`, rule PASS/FAIL or a single aggregate score as a universal label. A dimension/taxonomy and payload schema have explicit identities/versions. Interpretation and allowed values belong in a plugin. Provenance references the producing rule/model/configuration or human-feedback source when known; unknown confidence or model revision must remain unknown.

A flat vocabulary is valid. A category hierarchy, shared catalog and category-to-label mapping are optional application metadata; a contract namespace/version identifies interpretation without imposing one business taxonomy.

An edit, removal or retraction names its `expectedAnnotationRevision` separately from the evidence's `targetSegmentRevision`. New labels do not overwrite another dimension. A label can target overlapping intervals or several explicitly referenced subjects, with bounded reference counts. Parent/member and attribution semantics remain application policy rather than a universal entity hierarchy.

An annotation may also target a bounded group of disjoint ranges while retaining one annotation identity. Preserve that grouping across revisions; do not fill gaps, duplicate the assignment or collapse independent annotations merely because ranges overlap. Merge compatibility between dimensions/values must be an explicit plugin policy.

Keep producer origin, processing finality, review eligibility and published-result validity as separate concepts. A final model output is not automatically approved feedback; a reviewed annotation is not automatically the current published conclusion. Invalid values must yield a declared rejection, unknown marker or explicitly configured fallback, not a silent valid-looking substitution.

A profile declares which dimensions it uses, their required fields and the meaning of omission. A dimension not adopted is distinct from an adopted dimension with unknown state; neither omission nor a generic default may manufacture approval, rejection or validity.

Existing project permissions and simple save eligibility may suffice. The contract does not require an additional reviewer role or a multistage approval workflow.

Acceptance applies only to the behavior claimed by the profile:

- Round-trip the dimensions/values permitted by the profile without erasing assignments on the same supported target. A media/overlap profile must additionally avoid deduplicating distinct ranges by time or segment ID.
- For grouped-range editing, split and rejoin an annotation without losing its identity or changing its exact covered regions. An incompatible value/dimension merge must reject or preserve independent assignments under the declared policy. Basic point/subject annotations do not need this operation.
- Label identities remain distinct across taxonomy/dimension namespaces; subject identities compare their full namespace/kind/key tuple. Unsupported taxonomy/schema versions fail explicitly rather than being silently recast.
- For annotation editing, a stale `expectedAnnotationRevision` cannot erase the current annotation. An intentional reference to an older `targetSegmentRevision` keeps that exact evidence target and must not be silently redirected. Repeated removal is idempotent within the declared window; competing edits against one base report or resolve a conflict according to an explicit policy. A read-only importer needs no mutation reducer.
- Human feedback records its source and exact target, but does not automatically override a model or publish a new authoritative result. The application owns permissions, review workflow and precedence.
- Bound dimensions, values, text, references and retained revisions before serialization or fan-out. Reject oversized events and saturated queues instead of accumulating an auxiliary unbounded list.

## T3: Evidence dependencies and result lifecycle

Extend the planned versioned results with source/segment/annotation version references, explicit dependencies and supersede/retract relationships. References preserve the original time basis and available precision. A short text excerpt with a whole-turn interval must not be advertised as word-aligned audio evidence.

T3 is an optional bounded projection over dependencies owned by its adapter. Dependency references can be shared, but impact, recomputation and publication policies must be explicit. A boundary-only change may leave a text-only score valid while requiring audio evidence to be relocated. Historical reverse indexes, cross-run/global invalidation graphs, automatic reevaluation scheduling and durable publication remain external.

Distinguish a comparison run from an explicitly published replacement. Reevaluation creates a new run; its existence alone does not invalidate every old result. A correction affects declared dependencies under an adapter-owned impact/publication policy. Failure to produce a replacement must not publish a successful new current conclusion. Keep the current-result pointer separate from its validity: a pointer may still refer to a result already marked stale.

Evidence availability is a third, separate concern. Cache eviction reports local unavailability and preserves reference identity; it must not itself mark a result stale, retract it or declare the underlying fact expired. Rehydration and long-term payload retention are external, and semantic expiry requires an explicit owner policy/event rather than memory pressure.

Acceptance for claimed dependency/publication behaviors:

- Apply a declared dependency change and verify that old results keep their exact references; a segment-edit profile uses split/merge as one such case. Explicit impact policy determines whether results remain current, need reassessment or are replaced. Results with disjoint declared dependencies remain unaffected; different annotation dimensions may still share a dependency and both need reassessment.
- Evict a cached evidence payload while the external source and conclusion remain valid. Preserve the current pointer/validity, report local unavailability and require explicit source resolution rather than automatically retracting the result.
- Apply correction-before-original, repeated retraction, delayed old output and competing replacements. Use an explicit reject or bounded defer policy; arrival order alone is not an undeclared last-writer-wins rule.
- For a controlled-publication adapter, if R0 becomes stale and replacement R1 fails, retain the local pointer to R0 and its recorded stale status; do not restore R0 to valid or publish R1. Advance a local current pointer only after the adapter's declared commit point. An external sink may act before throwing or lose its acknowledgment; the runtime cannot revoke an observed side effect. A suitable external ledger/commit protocol is required for durable idempotency or atomic publication claims.
- Keep async completion separate from result finality. A final/retract event does not close the session; END is not a barrier for independent callbacks. Await the owned operation/session completion appropriate to the driver.
- Exercise cancellation, outlet congestion and retention overflow. Observe limits and cleanup; do not claim production capacity or total JVM memory bounds from a short synthetic pass.

## Compatibility and delivery

Select the smallest profile needed by a concrete fixture. T1a and I1 can proceed independently; a source/range import-export fixture can establish T1a without T1b/T3. Subject-only T2 can also proceed without T1a. Add grouped ranges, local editing and dependency projections only for integrations declaring those behaviors. Each increment needs positive and negative cases using synthetic data; there is no universal T1a → T1b → T2 → T3 prerequisite chain.

Use I2 registration when selecting the proposed OminiCat versioned wire profile; other external codecs and file import/export need not depend on it. Preserve the default decoder, legacy messages and existing factory binaries. I1 descriptions can declare support only after a behavior probe verifies it. I3 checkpoint binding must capture the relevant source/definition versions while continuing to distinguish resume from reevaluation.

Contract conformance and algorithm quality need separate evidence. Fixed-window chunking can be checked with exact boundary/unit/continuity and performance assertions, without an artificial human-labeled corpus or IoU suite. A learned/heuristic segmentation plugin declares the quality claims it makes and the relevant corpus, matching rules and metrics, such as precision/recall, boundary error or fragmentation; these are not a mandatory universal package. Human-reviewed reference eligibility and publication permissions remain application decisions. Synthetic reducer tests do not establish those model/segmentation scores; throughput profiles do not establish their accuracy.

Keep Java 17 and the dependency-free kernel. Measure optional range/annotation/reducer cost separately: small and maximum permitted payloads, revisions and fan-out, B/op, throughput, tail latency, GC and retained state after drain. New metadata discovery, media hashing and pure reducers must not introduce storage/network I/O into ordinary kernel dispatch. Existing adapters may continue to perform their owned, bounded and cooperatively interruptible I/O under the current lifecycle contract. Storage, authorization, source decoding/alignment, domain segmentation algorithms, durable ledgers and global quotas stay in adapters and applications.

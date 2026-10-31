# Cross-project integration contracts

Status: design proposal and acceptance plan. The additions below are not implemented APIs, accepted YAML fields or new runtime guarantees. The current [scenario matrix](scenario-coverage.md) remains the implemented scope.

The next integration goal is to reuse one Java 17+ runtime across streaming event processing, sequential replay and result aggregation adapters by changing configuration and plugins. Keep scheduling and lifecycle in the microkernel; add optional compatibility contracts at the integration boundary. Each implementation increment must include an executable contract fixture before advertising support.

## Current mechanisms and remaining contracts

| Area | Current, source-backed behavior | Next contract / owner |
| --- | --- | --- |
| Plugin assembly | [ProcessorFactory](../ominicat-core/src/main/java/io/ominicat/core/spi/ProcessorFactory.java) exposes `type/create`; [PluginRegistry](../ominicat-core/src/main/java/io/ominicat/core/spi/PluginRegistry.java) discovers factories and rejects duplicate or missing types. Startup performs create/close validation. | Optional machine-readable versions, option descriptions, input/output and streaming capabilities. Existing configuration validation is already implemented. |
| Event identity and time | [FrameEnvelope](../ominicat-core/src/main/java/io/ominicat/core/FrameEnvelope.java) carries event/session/turn/correlation/causation IDs and local monotonic timestamps. Transcripts use media milliseconds; audio uses per-stream sample offsets. | Drivers normalize media timelines. Correlation IDs remain untrusted data, not authorization. Do not compare independent clock domains. |
| Structured results | [EvaluationFrame](../ominicat-conversation/src/main/java/io/ominicat/conversation/EvaluationFrame.java) contains policy labels, rule results, scores, evidence and retained/evicted scope. [Custom wire output](protocol.md#plugin-output) uses simple Java class names and explicitly has no stable global schema namespace. | An optional, explicitly registered, namespaced result codec. This extends an acknowledged boundary rather than fixing a claimed stable protocol. |
| Evidence | [Evidence](../ominicat-conversation/src/main/java/io/ominicat/conversation/Evidence.java) contains segment, role/speaker, media intervals and a copied excerpt; built-in rules bound their generated excerpts. Custom evidence producers own their limits. Committed final segments are immutable within the retained window. | Optional source/revision/run references for external consumers. Final correction and retraction policies belong in plugins and result adapters. Do not silently overwrite a committed final. |
| Resume and reevaluation | [Replay](../examples/Replay.java) checks input SHA and policy label; step restore checks its definition label. [Conversation snapshots](../ominicat-conversation/src/main/java/io/ominicat/conversation/ConversationSnapshot.java) are validated data and can be evaluated by new rules. | An external run/checkpoint manifest can bind definition contents for resume while permitting explicit reevaluation as a new run. |
| Multiple projects | Embedded applications can choose registries/configurations; a standalone server loads one pipeline configuration at startup and creates fresh instances per session. | Separate embedded bindings or deployment instances today. Authentication, project routing, credentials, durable ledgers and global quotas remain application/edge responsibilities. |
| Resource limits | Queues, connections, sessions, frame/output bytes and adapter-specific buffers/deadlines are bounded. | Document and measure these limits together. Configuration consolidation alone does not bound total JVM/native memory or untrusted plugin behavior. |

## I1: Optional plugin descriptions and compatibility fixtures

Trigger: an installed plugin can be syntactically valid yet offer utterance-based transcription where an integration requires native streaming. The current startup preflight cannot establish every cross-stage semantic capability from a string option map.

Add an optional companion description contract and a test harness. Preserve the existing `ProcessorFactory` methods and old binary plugins. Describe plugin/SPI versions, frame/schema formats, streaming direction, media formats, time origin, options and declared limits. Missing metadata means **unknown**, not verified compatible. Legacy integrations retain their current behavior; a caller explicitly requiring a capability must reject unsupported or unknown support before starting work. Descriptions are declarations to test, not evidence of remote service availability.

Description lookup must not construct processors, resolve credentials or contact providers. Keep metadata discovery in the binding/startup path, outside frame dispatch. An open frame contract must remain extensible; a fixed enumeration cannot describe every application frame. Implement this in an optional integration layer without adding production dependencies to core.

Acceptance:

- Load a pre-change Java 17 plugin JAR without recompilation on Java 17, 21 and 25; create distinct session state and close all owned instances.
- Query metadata without calling `ProcessorFactory.create` or network code; ServiceLoader may still instantiate the factory itself. Validate declared options without exposing secret values.
- Reject an utterance-only adapter when native streaming is explicitly required; report the specific capability mismatch. Unknown metadata follows the caller's declared compatibility policy.
- Fail a conformance probe when a plugin advertises streaming but its observed behavior does not satisfy that contract. Metadata alone cannot produce a verified compatibility result.
- Establish the three fixture families below against the same kernel artifact using current frame and driver APIs first. Extend them in I2/I3 as each new contract ships; their future codecs/manifests must not block I1. Test mismatches and lifecycle behavior, not just descriptor parsing.

## I2: Opt-in versioned result codecs and provenance references

Trigger: two unrelated plugins can emit custom frames with the same simple class name; consumers needing a durable external contract cannot rely on that name or on Java member names remaining unchanged.

At the transport/contracts boundary, explicitly register a namespaced `schemaId` and a structural `schemaVersion`. Preserve the default wire behavior and raw-frame path. Register exact supported types/versions; never select Java classes or polymorphic deserialization from client-provided names. Keep domain payloads and rules in plugins rather than introducing a universal result subtype in core.

Optional result metadata should reference a run, result identity, policy/definition label and content fingerprint, with bounded evidence references. Keep structural schema versions separate from policy, prompt, model and flow versions. Evidence retains its media timeline, unit and segment revision; an opaque media reference must not become an unbounded embedded recording. A correction uses an explicit relation to the prior result and an application-defined reducer. It does not mutate a committed transcript.

Acceptance:

- Encode two same-named Java frame classes under distinct schema IDs; reject duplicate schema registration and unsupported versions. Only explicitly registered inbound schemas may decode.
- Preserve envelopes, causation, null scores, evidence and retained/evicted scope. Existing messages without the new schema contract retain the legacy decoder behavior, including rejection of unregistered custom inbound types; default protocol fixtures remain unchanged.
- Apply bounded correction/retraction events in a fixture adapter and verify which result is current without counting the old result twice. A retry test uses an explicit adapter ledger; a result ID alone supplies no durable delivery or exactly-once guarantee.
- Measure the new codec's bytes, allocation and serialization cost separately from kernel dispatch. Bound metadata/evidence sizes and avoid hashing large media on each frame.

## I3: Run manifests and checkpoint binding

Trigger: a version label can stay unchanged while rules, prompts or configuration contents change. That label is insufficient to prove continuation of the same computation.

Wrap existing snapshots externally with a versioned manifest: run identity, input fingerprint, cursor, processor/plugin identities, caller-provided definition/configuration fingerprints and result schema references. Store model/prompt provenance only when available; a mutable model alias is not a pinned model revision. Do not serialize Java predicates, closures, emitters, connections or resolved credentials. Immutable manifest references can be shared per run; hashing and validation belong at creation, resume and export boundaries.

Distinguish two operations:

- **Resume:** validate input, checkpoint schema, cursor and required definition fingerprints before changing state. An unsupported version, missing required identity or changed definition rejects continuation atomically.
- **Reevaluate:** create a new run with explicit lineage to the previous input/run and the new definition. Reuse validated conversation data when appropriate; the low-level snapshot API must continue to permit this operation.

Acceptance:

- A deterministic synchronous fixture produces equivalent results continuously and after resume, with controlled IDs, clock, rules and output ordering.
- Changed input or changed definition contents reject resume even when the human-readable version label is unchanged. Failed restore leaves state untouched; a sink failure does not advance the driver cursor.
- Reevaluation with new rules succeeds as a new run and records lineage. Unknown external model revisions remain unknown and cannot establish deterministic replay.
- External adapters own snapshot storage, output acknowledgment, idempotency and commit atomicity. This contract must not imply crash recovery or durable delivery by itself.

## Three fixture families

These are planned acceptance fixtures, not existing end-to-end product integrations.

I1 establishes their baseline with existing raw/envelope frames, Replay/window APIs and EvaluationFrame output. I2 adds schema/reducer interoperability checks; I3 adds manifest/resume/reevaluation checks. The table describes the final coverage across those increments.

| Family | Reused mechanisms / substitution | Positive and negative evidence |
| --- | --- | --- |
| Streaming event pipeline | Local asynchronous provider fixture, dual tracks/channel mapping, transcript revisions/gate, independent sessions, envelopes and optional result codec. Substitute provider/plugin/configuration without forking core. | Validate speaker/role attribution, normalized media time, per-stream committed results, causal links, bounded queues, cancellation and stale callback rejection. A driver must reject an unnormalized cross-domain comparison. Reuse caller IDs across sessions to test state isolation. Replay exact output only with controlled callback scheduling; otherwise assert the documented partial ordering and cancellation invariants. |
| Sequential batch replay | Existing JSONL replay/window mechanisms plus the proposed external manifest and sink fixture. Media decoding, long-file segmentation and scheduling remain drivers. | Compare uninterrupted execution and checkpoint resume; reject input/definition mismatch, oversized records and invalid evidence time domains. Confirm sink failure preserves the prior cursor and all resources drain. |
| Cross-session result aggregation adapter | Consume versioned results from separate sessions through a bounded outlet; use an explicit fixture ledger and reducer keyed by application-owned entity/result identity. | Test repeated events, corrections, reordered delivery and schema mismatch without double counting. Verify bounded batches and explicit overflow. The kernel does not acquire a global entity registry, durable ledger or cross-session authorization role. |

All three reuse the same kernel artifact and exercise configuration/plugin variation. Session state and callback fencing are logical ownership guarantees, not a security sandbox or CPU/memory isolation from arbitrary plugins. Real ASR/TTS behavior, model quality, remote protocol faults, production capacity and multi-device clock drift still require target-environment validation.

Include an injected failure in every family: delayed/failed callbacks in streaming, sink failure in replay, and outlet congestion/duplicate delivery in aggregation. Also combine a legacy plugin with unknown metadata and legacy messages without schema IDs: preserve its previous execution behavior while clearly reporting that its capability compatibility is unverified.

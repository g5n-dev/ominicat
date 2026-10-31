# Scenario coverage

This matrix separates executable mechanisms from provider integrations and external guarantees. It is a scope contract for the 0.1 preview, not a production certification.

| Generic scenario | Implemented mechanism and evidence | Adapter / external responsibility |
|---|---|---|
| Provider-native streaming transcription | `AsyncProcessor`/`AsyncEmitter`; bounded next-stage continuations, generation fencing, complete/fail/drain tests; stable segment/revision gate | Implement the provider's bidirectional protocol, endpointing and deadlines. The included HTTP adapter is utterance-based. |
| Multiple tracks and speaker attribution | PCM channel deinterleaving; bounded isolated track buffers; stream/sample positions; distinct speaker/role mapping; local WAV payload tests | Source attribution or diarization; normalize capture clocks onto one shared media timeline before cross-track comparisons. |
| Acoustic preprocessing | PCM channel splitting and RMS VAD energy detection are implemented; no noise-suppression processor is shipped | Provider-independent preprocessing needs a separately validated media plugin with declared format, per-track state, buffering and cancellation. Energy thresholds alone do not establish speaker identity or isolate equal-energy sources. |
| Interactive multimodal conversation | Real WebSocket → local HTTP transcription → prompt → SSE text → PCM synthesis integration test; manual interruption | Real provider accuracy, acoustic echo control and deployment latency. |
| Addressable step sequences | `StepFlowProcessor`, ordered conditional Java transitions, versioned state and restore tests; configurable sequential steps | Persist snapshots and provide the matching versioned definition; advanced application transitions remain policy. |
| Single-utterance input | Bounded PCM accumulation, explicit boundary flush, unknown-confidence marker; PCM and short-clip regression tests | Capture usable audio and choose an available provider/model. |
| Long recordings and replay | Deterministic envelope clock/ID injection, committed snapshots, streaming JSONL replay/checkpoint example | Decode recordings, segment utterances, schedule jobs and persist file/sample offsets. No built-in long-audio scheduler. |
| Incremental policy evaluation | `PolicyRule`, `policyVersion`, evidence segment/time references, explicit retained/evicted scope | Supply policy and calibrate its interpretation. Built-ins are deterministic literal/punctuation rules. |
| General temporal segment changes | Transcript gate supports higher partial revisions, but committed finals reject changed facts; no general split/merge/retraction reducer is shipped | [T1a/T1b proposals](temporal-annotations.md) are not implemented. Reference-only consumers need no editing reducer. Drivers/plugins own algorithms, source alignment and correction authority. |
| Independent multidimensional annotations | Multiple `RuleResult` values and custom Java frames are available; no general annotation target/revision/lifecycle contract is shipped | [T2/T3 proposals](temporal-annotations.md) are not implemented. Vocabulary, review decisions, authorization and persistent audit remain application policy. |
| Cross-turn state | Bounded final windows, stable-ID deduplication, role-aware prompts, validated snapshot restore | Retention policy and durable state ownership. Replayed IDs outside the retained dedup window need an external ledger. |
| Parallel processing | Named synchronous branches, bounded outputs, shared deadline, all-success ordered join, sibling cancellation tests | Async branches use separately owned sessions. First-success/race and quorum joins are not included. |
| Slow result destinations | `AsyncOutlet` with independent bounded delivery, explicit saturation, failure and drain tests | Idempotent storage, acknowledgment, retries and dead-letter handling in a destination adapter. |
| Interruption and connection recovery | Out-of-band session interrupt, serialized reset, stale-callback rejection, per-connection ownership; conversation/step restore APIs | Reconnect authentication, persistence and external side-effect deduplication. Old emitters remain bound to permanently closed old session objects. |
| Overload and provider failure | Ingress/session/frame/output bounds; overload responses/close; finite HTTP deadlines; pre-output synchronous fallback; independent metrics endpoint | Upstream pause/credit protocol, global quotas, circuit breaking and distributed admission control. |

## Semantic constraints

Transcript intervals used together must already share a media time axis. `startSample` is local to its source stream. The runtime does not infer cross-device offsets or correct drift. Compare normalized timestamps only; resampling and clock mapping belong in a media/driver plugin.

Committed final segments are immutable within the retained gate/window. A provider correcting a supposedly final result must use a new, explicitly related event or an application-defined correction policy. Empty final segments are allowed; downstream policy decides whether they have meaning. Missing confidence is `-1`, never a fabricated probability.

Current evidence excerpts retain the whole turn's media interval; a substring around a matching phrase does not establish word-level audio alignment. Source-media identity, clock mapping and exact target revisions must be supplied by an integration that requires that precision; the temporal proposals describe future contracts, not an existing locator service.

Async callback output is ordered by accepted enqueue operations. An END frame is an application marker; it can precede a delayed callback. `completion()` after `finish()` is the drain barrier. Await that future before releasing a transport. Callback operations require finite provider deadlines and must handle emitter rejection.

The current fan-out/fallback combinators reject asynchronous child processors at construction. They cannot silently reinterpret a missing synchronous method as a provider outage. Use owned async sessions for streaming branches. Cooperative cancellation cannot force an uncooperative Java plugin to stop or revoke already-entered external effects.

Runtime snapshots contain committed data, not old callback handles or connections. New sessions get new private queues; an emitter from an old session cannot route into a new session by matching a caller-supplied ID. If a distributed result router is added, its protocol must provide durable fencing and idempotency.

## Verification boundary

Local tests exercise deterministic rules, state restore, finite media buffers, real loopback HTTP/WebSocket, malformed payloads, overload, cancellation and asynchronous races. JMH/JFR/load reports measure synthetic local kernels and an echo transport. Live provider-native ASR, long-duration multi-device drift, real network failures, service quotas and production capacity require separate target-environment validation.

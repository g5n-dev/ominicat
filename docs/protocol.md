# Frame protocol

The standalone transport accepts JSON **text** messages on `GET /ws`. Each JSON object represents one frame. WebSocket fragmentation is supported within the configured aggregate message limit; binary WebSocket messages are rejected. There is no polymorphic Java-class deserialization.

The default pipeline echoes frames. Other stages may consume, transform, or expand them. Per-session processing is ordered unless a configured composition explicitly changes that behavior. Client-provided identities and timestamps are data; they are not authorization boundaries or server-issued credentials.

## Content frames

### Text

```json
{"type":"text","text":"Hello, OminiCat."}
```

Each text frame is a chunk. Empty text is valid. Streaming chat emits multiple text frames followed by `turn_end`.

### Audio

```json
{"type":"audio","data":"AAA=","sampleRate":16000,"channels":1,"streamId":"microphone-1","startSample":0}
```

`data` is base64 of interleaved **signed PCM16 little-endian**, without a WAV header. Payloads must be nonempty and contain complete sample frames: byte length must be divisible by `2 * channels`. `sampleRate` is `1..384000` Hz; `channels` is `1..32`. Plugins can impose narrower format requirements.

`streamId` is a nonblank logical track identifier, defaulting to `default`. `startSample` is the per-channel offset within that track; `-1` means unknown and is the default. It is not a byte offset. The kernel does not reorder, resample, mix or synchronize tracks automatically.

The browser playground sends individual mono 16 kHz utterances, not a continuously streamed microphone track. The built-in speech output is mono 24 kHz PCM.

### Image

```json
{"type":"image","data":"iVBORw0KGgo=","mimeType":"image/png"}
```

Use base64 of an actual encoded image; the abbreviated sample above only illustrates the envelope. Payloads must be nonempty, and `mimeType` must be an `image/*` media type. The frame constructor does not verify file signatures or decode pixels. Provider and application adapters own image validation and their supported formats.

### Transcript

```json
{"type":"transcript","text":"Hello there","speaker":"speaker-1","startMillis":0,"endMillis":700,"finalResult":true,"confidence":0.98,"segmentId":"segment-7","revision":2,"role":"user"}
```

Speaker identity and application role are separate. `speaker` and `role` are nonblank. Timestamps satisfy `0 <= startMillis <= endMillis`. `confidence` is `0..1`, or `-1` when unavailable. `finalResult` distinguishes partial and final text. `segmentId` stays stable across revisions; `revision` is nonnegative.

For older clients, `segmentId` defaults to `speaker:startMillis-endMillis`, `revision` to zero, and `role` to `speaker`. Adapters handling revisable ASR segments should supply explicit IDs. The kernel carries revisions; conversation plugins define commitment, deduplication and conflict behavior. It does not perform acoustic speaker diarization.

## Correlation envelope

Any supported frame may be wrapped once:

```json
{"type":"envelope","id":"event-1","sessionId":"session-1","turnId":"turn-1","correlationId":"trace-1","causationId":"","originTimeNanos":100,"ingestTimeNanos":110,"emitTimeNanos":120,"frame":{"type":"text","text":"Hello"}}
```

Identifiers contain `1..256` characters; `causationId` may be empty and defaults to empty. `emitTimeNanos` defaults to `ingestTimeNanos`. The ordering invariant is `originTimeNanos <= ingestTimeNanos <= emitTimeNanos`. Nested envelopes are rejected. JSON parsing rejects duplicate property names, nesting beyond 16 levels and excessively long numeric literals.

Configured server processors are wrapped to preserve envelope metadata. Each emitted frame receives a fresh event ID, links its `causationId` to that stage's input ID, and retains session, turn, correlation and original timing values. Raw input stays raw. An interruption acknowledgement preserves the submitted envelope directly because it does not traverse stages.

Nanosecond values describe a monotonic clock domain, not Unix timestamps. Client and server clocks are not synchronized by this protocol. Do not subtract timestamps from independent clocks to claim end-to-end latency. For cross-system tracing, use caller-owned correlation identifiers and a tracing system that handles clock domains explicitly.

## Controls, cancellation and failure events

```json
{"type":"turn_end"}
{"type":"start"}
{"type":"interrupt"}
{"type":"end"}
{"type":"cancel","reason":"user_requested","scope":"turn","targetId":"turn-1"}
{"type":"failure","code":"provider_timeout","retryable":true,"scope":"request","nodeId":"speech-1","message":"Provider timed out"}
```

| Input | Transport behavior |
| --- | --- |
| `turn_end` | Queued as a normal `Control(TURN_END)`; the browser sends it after a form submission or recording |
| `start` | Queued as an explicit `Control(START)`; the browser sends it once on connection |
| `interrupt` | Drop pending work, fence active-generation output, interrupt blocking stage I/O, acknowledge `interrupt`; keep the session usable |
| `end` | Queue `Control(END)`, stop input, drain accepted work, clean up processors, then close normally |
| `cancel` | Forward an application cancellation event; the application/driver owns scope and target matching |
| `failure` | Forward a classified application event; it does not automatically trigger retries or terminate the session |

The transport does not synthesize a `turn_end`. Send needed application boundaries explicitly. An accepted `end` traverses the stages, allowing report emission or buffered output to flush before cleanup. If its input queue is full, the server returns `overloaded` and keeps intake open; retry `end` after reading pending output. No accepted frame is displaced to make room for it.

An `end` signal is not a barrier for independent asynchronous provider callbacks: it may reach a downstream stage before a delayed callback result. Graceful session completion waits for accepted asynchronous operations and their queued continuations. A stage requiring a complete-turn aggregate must coordinate its own operation completion before emitting a boundary; the transport cannot infer that relationship.

Cancellation scopes are `request`, `turn` and `session`. The linear WebSocket driver does not maintain an owned request/turn index and does not broaden a scoped `cancel` into an unconditional session interruption. Use `interrupt` for the transport's implemented interruption behavior. Identity fields do not permit cancelling a different socket's work.

Interruptions are cooperative. A stage must honor Java thread interruption and clear turn-local buffers in its interruption callback. Emissions from a stale generation are fenced, but previously transmitted data and external side effects cannot be recalled. Clients must stop already scheduled audio playback themselves. The bundled browser's Interrupt button stops playback and pending recording locally; it does not implement automatic barge-in.

Control output includes lowercase `start`, `end`, `interrupt`, `turn_end`, `speech_started` and `speech_stopped`. The standalone decoder only accepts the listed client controls; voice activity signals can be produced by plugins or submitted through Java.

`Failure` fields are application-supplied classifications. `code` and `scope` contain `1..128` characters; `nodeId` contains `1..256`; `message` contains `1..2048`. `Cancel.reason` and `targetId` contain `1..256`. Emitting adapters must sanitize their own failure messages. A received failure frame is not a trusted server incident report.

## Plugin output

Custom Java `Frame` implementations use an output-only envelope:

```json
{"type":"EvaluationFrame","data":{"policyVersion":"v1","score":0.8}}
```

The example illustrates the shape; actual fields depend on the plugin. The type is the implementation's simple class name and is not a stable global schema namespace. Applications that need a versioned external contract should supply a dedicated transport codec. Custom inbound frame class names are not accepted.

## Limits and error handling

`maxFrameBytes` applies to the full UTF-8 JSON message, including base64. A full session input queue returns an error without silently dropping accepted frames:

```json
{"type":"error","code":"overloaded","message":"Session input queue is full or closed"}
```

Invalid JSON, unknown types and invalid modality fields return `invalid_frame` with a sanitized message. Each valid decoded incoming message increments the inbound frame counter, including controls and messages rejected for queue pressure. Rejections have a separate counter.

Outgoing JSON messages are individually limited to `maxFrameBytes`. The runtime also bounds pending outgoing message bytes to at most four times that configured size, checks Netty writability, and closes under output pressure. This byte budget covers queued JSON payloads, not total JVM allocations. A slow consumer must read frames promptly.

| Outcome | Code / status |
| --- | --- |
| Completed pipeline or client closure | WebSocket `1000` |
| Unsupported binary input | WebSocket `1003` |
| Oversized/invalid WebSocket input or idle timeout | WebSocket `1009` |
| Processor initialization/execution or output failure | WebSocket `1011` |
| Output exceeds the message/pending-byte budget or channel is nonwritable | WebSocket `1013` |
| Maximum active sessions reached | HTTP `503`, before upgrade |
| Missing/invalid configured bearer token | HTTP `401` |
| Unrelated browser origin | HTTP `403` |

Authentication is described in [configuration](configuration.md). `/health` returns `{"status":"up"}`. `/metrics` exports aggregate Prometheus session/frame/byte counters and an active-session gauge, without conversation content or per-session labels. There is no durable delivery, retransmission, session resumption or exactly-once guarantee in this transport.

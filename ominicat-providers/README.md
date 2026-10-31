# Processing adapters

This module registers optional `ProcessorFactory` implementations through Java `ServiceLoader`. Every factory creates independent session state. HTTP adapters use the JDK connection pool; the kernel remains independent of HTTP clients, JSON libraries and model vendors.

| Type | Consumes | Produces |
| --- | --- | --- |
| `echo` | Any frame | The same frame |
| `audio-channel-split` | Interleaved PCM | Separate identified mono streams |
| `rms-vad` | PCM from one logical stream | Speech transition controls and unchanged PCM |
| `transcript-gate` | Transcript revisions | One committed final per retained segment; optional partial revisions |
| `openai-transcribe` | PCM, then a boundary control | Final attributed transcripts, then the boundary |
| `openai-chat` | Text, final transcript or image | Streamed text chunks, then `TURN_END` |
| `openai-speech` | Text chunks, then a boundary | Original text; synthesized PCM before the boundary |

Compatible API support means the corresponding Chat Completions, audio transcription or speech endpoint accepts these request formats. It does not imply that every provider supports every endpoint or parameter. Cloud credentials and network access are optional; `echo`, channel splitting, the transcript gate and energy detection work locally.

## HTTP settings

All three HTTP factories accept the following options. A model name is always explicit.

| Option | Default | Bounds or behavior |
| --- | --- | --- |
| `model` | Required | Nonblank provider model identifier |
| `baseUrl` | `https://api.openai.com/v1` | HTTPS; loopback HTTP allowed for local services |
| `apiKeyEnv` | `OPENAI_API_KEY` | Environment variable name; empty allowed only with loopback URL |
| `timeoutSeconds` | `30` | 1–300; request timeout and a finite response-body deadline |
| `maxResponseBytes` | `8388608` | 1024–67108864; binary/JSON response byte budget |

URLs containing credentials, query strings or fragments are rejected. Redirects are disabled. Non-success HTTP responses expose the status code without including response bodies or credentials. `ProviderException.retryable()` identifies 429 and 5xx statuses; adapters do not retry automatically.

Streaming chat also applies conservative character budgets: total line characters may not exceed one quarter of `maxResponseBytes`, and each line/event is limited to 65536 characters. This bounds decoded UTF-8 memory as well as response data. Deadlines cover stalled response bodies after headers arrive. A session interruption cancels cooperative HTTP work and fences stale downstream emissions.

## Separate audio streams

```yaml
stages:
  - type: audio-channel-split
    options:
      streamIds: "left,right"
  - type: openai-transcribe
    options:
      model: "your-transcription-model"
      speakers: "left=person-a,right=person-b"
      roles: "left=participant,right=assistant"
      maxStreams: "8"
      maxAudioBytes: "4194304"
  - type: transcript-gate
    options:
      maxSegments: "256"
```

Input `Audio` is signed 16-bit little-endian PCM, interleaved by channel. `audio-channel-split` emits one mono frame per channel in channel order. It preserves the sample rate and per-channel `startSample`, and assigns the configured unique stream names. Without `streamIds`, names are `<input streamId>/ch-0`, `<input streamId>/ch-1`, and so on. Configured name count must match the input channel count. This stage separates existing channels; it does not identify or separate speakers from a mixed mono recording.

`openai-transcribe` buffers each stream independently and makes one multipart WAV request per nonempty stream at `TURN_END`, `SPEECH_STOPPED` or `END`. Flush order is the order streams were first encountered. Audio frames are consumed; boundary controls pass through after generated transcripts. Partial cloud transcription is not implemented by this file-based adapter.

| Option | Default | Behavior |
| --- | --- | --- |
| `maxAudioBytes` | `4194304` | Total pending PCM bytes across all streams; 2–16777216 |
| `maxStreams` | `8` | Maximum distinct stream identities for the session; 1–32 |
| `speaker` | `user` | Fallback speaker identity |
| `speakers` | Empty | Comma-separated `stream=speaker` mappings |
| `roles` | Empty | Comma-separated `stream=role` mappings; fallback is attributed speaker |

Stream, speaker and role labels are limited to 128 characters. Mapping entries must be unique and nonblank. Commas and equals signs are delimiters in mapping configuration.

Each stream has its own cumulative sample cursor. A known first `startSample` anchors its timeline; unknown positions use the cursor. Known offsets must be ordered and exactly contiguous inside an utterance. Forward gaps are allowed between utterances; backward offsets are rejected. Sample rate and channel count remain fixed for a stream throughout the session. Timestamps are derived from sample offsets and rounded down only at output, so short utterances do not accumulate per-utterance rounding drift. These are media positions, rather than wall-clock timestamps.

Each emitted transcript has a deterministic session-local segment ID `<streamId>:<utterance sequence>`, revision zero, a distinct speaker/role attribution, and confidence `-1` to indicate that confidence is unavailable. Sequence numbers are allocated before the request; failed or interrupted requests can leave sequence gaps. Interruption discards buffered PCM while preserving accepted sample cursors, stream formats and sequence counters. Discarded accepted media therefore still advances the timeline. Buffered arrays are released at flush/interruption, and no track's audio is mixed into another track.

## Transcript revisions

Place `transcript-gate` before processors that trigger external work from transcripts. It defaults to `maxSegments: "256"`, `maxTextChars: "65536"`, and `passPartials: "false"`.

The gate tracks stable segment IDs and monotonic revisions. Older uncommitted revisions are ignored; conflicting facts at the same revision are rejected. A partial can become final at the same revision when its text and interval are unchanged. Speaker and role remain stable across revisions. Final speaker, role, text and interval are immutable: identical repeated finals emit nothing, while conflicting finals are rejected. Changing confidence or revision metadata does not replace an already committed final.

Setting `passPartials: "true"` forwards each accepted partial revision once. It does not create final transcripts from partials. An interruption removes uncommitted partial state and retains committed final identities. Retention is bounded by `maxSegments` (1–10000); oldest segment identities are evicted first, and replay after eviction may emit again. This is bounded session deduplication, with no durable delivery guarantee.

## Text and images

`openai-chat` sends each input as an independent request: one system message plus one user message. It consumes `Text`, final `Transcript` and `Image` inputs. Partial transcripts and other frame types pass through. Use `prompt-template` for explicit retained conversation context and `transcript-gate` to suppress duplicate input finals.

Additional options are `systemPrompt` (default `Be concise and helpful.`), `imagePrompt` (default `Describe this image.`), `maxOutputTokens` (default 1024, range 1–16384), and `maxInputChars` (default 65536, range 1–1000000). Requests use `max_completion_tokens`. Images are sent as inline data URLs and must fit the response-byte budget used by the adapter. SSE comments and multiline events are accepted. A missing `[DONE]` marker is a failed response, so truncated output never emits a successful turn boundary.

`openai-speech` accumulates text until `TURN_END` or `END`, requests PCM once, and emits 24 kHz mono signed 16-bit little-endian audio. Original text chunks pass through immediately; audio is turn-buffered. Options are `voice` (default `alloy`) and `maxTextChars` (default 4096, range 1–4096). Empty or odd-length PCM responses are rejected. Blank turns make no request. Interruption clears text awaiting synthesis.

## Energy transitions

`rms-vad` compares normalized PCM RMS against `threshold` (default `0.02`, range greater than zero through one). It emits `SPEECH_STARTED` before the first active frame, and `SPEECH_STOPPED` after `silenceMillis` of inactive samples (default 300, range 1–60000). Sample duration accounts for sample rate and channel count. `END` emits a pending stop transition and resets state; interruption resets state.

The energy detector has one logical speaking state per stage. Feed one logical stream to it; do not combine independent tracks into that state. It is a deterministic energy threshold, without neural voice classification, automatic utterance semantics or speaker identification.

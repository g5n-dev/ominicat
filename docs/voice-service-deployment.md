<!-- ominicat-language-nav -->
[简体中文](zh-CN/voice-service-deployment.md) · **English**
<!-- /ominicat-language-nav -->

# Voice-service deployment in YAML

This example turns a spoken customer question into a text reply and playable speech. Two containers provide the application entry point and session processing; three separately supplied model services perform recognition, response generation and synthesis. The model services may have different operators, models and credentials.

The architecture is **client → gateway → OminiCat conversation service → ASR, LLM and TTS services**. The conversation service calls the model services in sequence; those services do not call one another. Each WebSocket owns its pipeline and transient conversation state.

## Service responsibilities

| Service | Input and processing | Output and boundary |
| --- | --- | --- |
| Gateway | Serves the same origin for Studio and `/ws`; forwards HTTP and WebSocket connections to the conversation container | Transparent transport forwarding. The checked-in gateway listens only through a host loopback port; production TLS and user authentication require an application edge. |
| OminiCat conversation service | Receives PCM frames and turn boundaries; runs the configured adapters, `transcript-gate` and `prompt-template` in one JVM | Text and audio frames over the originating WebSocket. The gate and prompt stages are local processors, not separate network services. |
| ASR service | `POST /v1/audio/transcriptions` with a multipart WAV utterance and selected model | A final JSON transcript such as `{"text":"..."}`. The adapter turns it into an attributed transcript frame. |
| LLM service | `POST /v1/chat/completions` with the generated prompt and `stream: true` | SSE text deltas followed by `[DONE]`. The adapter emits text frames and then a turn boundary. |
| TTS service | `POST /v1/audio/speech` with the complete reply, selected voice and `response_format: pcm` | 24 kHz mono PCM16 bytes, converted into an audio frame. |

The external services must implement these compatible request and response formats. Their model-serving images, GPU sizing, autoscaling and credentials are supplied by the operator; deploying additional OminiCat containers does not install those models. See [provider formats and limits](../ominicat-providers/README.md).

## Process topology: Compose YAML

[compose.yaml](../deploy/voice-assistant/compose.yaml) describes containers, networking, the published port, read-only configuration files and credential injection:

```yaml
# Process topology. Model services are supplied separately via runtime.yaml.
services:
  gateway:
    image: nginx:stable-alpine
    ports:
      - "127.0.0.1:${OMINICAT_GATEWAY_PORT:-18088}:8080"
    configs:
      - source: gateway
        target: /etc/nginx/conf.d/default.conf
    depends_on:
      - conversation
    networks:
      - voice

  conversation:
    image: ${OMINICAT_IMAGE:-ominicat:voice-example}
    build:
      context: ../..
    command: ["--config", "/app/config/voice-service.yaml"]
    expose:
      - "8080"
    configs:
      - source: pipeline
        target: /app/config/voice-service.yaml
    environment:
      OMINICAT_TOKEN: ${OMINICAT_TOKEN:?Set the gateway-facing runtime token}
      ASR_API_KEY: ${ASR_API_KEY:?Set the ASR service credential}
      LLM_API_KEY: ${LLM_API_KEY:?Set the LLM service credential}
      TTS_API_KEY: ${TTS_API_KEY:?Set the TTS service credential}
    stop_grace_period: 30s
    networks:
      - voice

configs:
  gateway:
    file: ./gateway.conf
  pipeline:
    file: ./runtime.yaml

# Bridge networking permits outbound HTTPS to the configured model services.
networks:
  voice:
    driver: bridge
```

Only the gateway publishes a host port. The conversation service remains reachable on the Compose network and makes outbound HTTPS calls to the model endpoints. The network allows egress; it is not an outbound destination allowlist. Compose resolves its own environment expressions and requires all four credential variables before creating the deployment. [Compose configuration files](https://docs.docker.com/reference/compose-file/configs/) are mounted into their selected containers.

[gateway.conf](../deploy/voice-assistant/gateway.conf) proxies the whole origin, including Studio assets and APIs. It preserves external `Host`, `Origin`, `Authorization` and `Sec-WebSocket-Protocol`, explicitly forwards WebSocket upgrade headers and disables response buffering. This is necessary for the runtime's same-origin and browser-token handling; see [NGINX WebSocket proxying](https://nginx.org/en/docs/http/websocket.html). The example is local HTTP/WS; it does not configure certificates, user accounts or tenant quotas.

## Processing and service bindings: runtime YAML

[runtime.yaml](../deploy/voice-assistant/runtime.yaml) is read by OminiCat. Stage order defines the in-process pipeline; each provider adapter's options identify the remote service it calls:

```yaml
# Replace each HTTPS endpoint and model with an operator-supplied compatible service.
# These .invalid endpoints are placeholders; this file does not deploy model servers.
# RuntimeConfig does not interpolate ${ENV}; only tokenEnv/apiKeyEnv name environment variables.
host: 0.0.0.0
port: 8080
tokenEnv: OMINICAT_TOKEN
maxSessions: 32
maxConnections: 64
queueCapacity: 128
maxFrameBytes: 4194304
idleTimeoutSeconds: 120

stages:
  # ASR service: PCM + turn_end -> multipart WAV -> final Transcript.
  - type: openai-transcribe
    options:
      baseUrl: https://asr.example.invalid/v1
      model: replace-with-asr-model
      apiKeyEnv: ASR_API_KEY
      speaker: user
      maxAudioBytes: "4194304"
      maxStreams: "1"
      timeoutSeconds: "30"
      maxResponseBytes: "1048576"

  # In-process state: suppress duplicate finals, then build bounded input history.
  - type: transcript-gate
    options:
      maxSegments: "256"
      passPartials: "false"
  - type: prompt-template
    options:
      inputSpeaker: user
      role: assistant
      instruction: "Answer the customer's latest question briefly. Do not invent account or order information."
      maxTurns: "16"

  # LLM service: prompt text -> SSE text chunks + TURN_END.
  - type: openai-chat
    options:
      baseUrl: https://llm.example.invalid/v1
      model: replace-with-chat-model
      apiKeyEnv: LLM_API_KEY
      maxOutputTokens: "256"
      timeoutSeconds: "30"
      maxResponseBytes: "1048576"

  # TTS service: completed reply -> 24 kHz mono PCM16. Text passes through immediately.
  - type: openai-speech
    options:
      baseUrl: https://tts.example.invalid/v1
      model: replace-with-speech-model
      apiKeyEnv: TTS_API_KEY
      voice: alloy
      maxTextChars: "4096"
      timeoutSeconds: "30"
      # 2 MiB raw PCM leaves room for Base64/JSON inside the 4 MiB message limit.
      maxResponseBytes: "2097152"
```

The TTS `maxResponseBytes` budget allows 2 MiB of raw PCM under a 4 MiB `maxFrameBytes` wire-message cap, leaving room for Base64 and frame metadata. These are illustrative limits to tune for the actual workload.

Replace the three placeholder `baseUrl` values and their `model` identifiers with explicitly chosen services. Keep the API root in each URL, including `/v1` when required by that provider; the adapter appends its operation path. Select a supported TTS `voice` as well. The example endpoints under `.invalid` deliberately provide no live service.

The mounted runtime file is not a Compose template: OminiCat does not expand `${ENV}` in it. Only `tokenEnv` and `apiKeyEnv` resolve environment-variable names. Write endpoint and model values directly into this file, or render a concrete file in your own deployment tooling before launch. Provider URLs require HTTPS outside loopback; a container service address such as `http://asr:8000` does not qualify for the local HTTP exception. Self-hosted HTTPS endpoints must present certificates trusted by the runtime JVM.

## One spoken turn

1. The client sends PCM audio followed by `turn_end` through the gateway. The transcription adapter buffers the utterance within its configured byte limit, wraps it as WAV and calls the ASR service.
2. The final transcript passes through the deduplication gate. The prompt stage retains a bounded window of incoming transcripts and builds the next request. It does not automatically retain downstream assistant replies or provide a complete user/assistant history.
3. The chat adapter sends that prompt to the LLM service. Text deltas pass through the speech stage to the client immediately, while the speech stage accumulates a bounded reply.
4. After successful SSE completion, the chat adapter emits `TURN_END`. The speech stage makes one TTS request and returns the synthesized audio before forwarding that boundary. The original input boundary can also pass downstream; an empty speech buffer causes no additional TTS request.
5. The connection can accept another turn. `end` requests completion of the session; `interrupt` cancels the current generation cooperatively and fences stale output.

This is utterance recognition and whole-turn synthesis. The LLM streams text, but the example does not implement continuous provider-native streaming ASR or chunked TTS. Errors and deadlines are handled by the adapters and session lifecycle; automatic retries and cross-service transactions are not configured. [Wire controls](protocol.md) and [voice interaction scope](scenario-coverage.md) describe the remaining boundaries.

For account or order lookup, add an application-owned processor that calls the relevant business service with explicit authorization, deadlines and bounded results, then supplies those results to the prompt. Implement and register that plugin before selecting its type in YAML. Durable storage, audit requirements and external-effect retry policy belong to that integration; no database or general business-service RPC adapter is installed by this example.

## Start and inspect the example

Install Docker with Compose, then work from the repository root. First edit the runtime configuration for your selected services. Populate and export the environment variables `OMINICAT_TOKEN`, `ASR_API_KEY`, `LLM_API_KEY` and `TTS_API_KEY` through your shell or deployment secret mechanism. The first is the runtime's shared bearer token; each remaining variable belongs to its corresponding model service. Keep secret values out of YAML and source control.

```sh
docker compose -f deploy/voice-assistant/compose.yaml config --quiet
docker compose -f deploy/voice-assistant/compose.yaml up --build --detach
voice_port="${OMINICAT_GATEWAY_PORT:-18088}"
curl --fail --retry 30 --retry-connrefused --retry-delay 1 \
  "http://127.0.0.1:${voice_port}/health"
curl --fail -H "Authorization: Bearer ${OMINICAT_TOKEN}" \
  "http://127.0.0.1:${voice_port}/metrics"
```

The quiet validation command avoids printing resolved credential values. The default gateway address is **http://127.0.0.1:18088**; set `OMINICAT_GATEWAY_PORT` before launch to use another available host port. Startup and health only verify the local listener and configuration, not the reachability or capabilities of the remote models.

Open Studio at that address, enter the runtime token and connect in the pipeline debugger. Select audio input, start recording, then stop and send. Studio sends the recording followed by the turn boundary automatically. With compatible services configured, the client receives reply text followed by synthesized audio. Use the finish-and-drain control after your last turn. To remove this example's containers and network:

```sh
docker compose -f deploy/voice-assistant/compose.yaml down
```

Configuration is read at process startup. After changing mounted configuration or plugin contents, run `docker compose -f deploy/voice-assistant/compose.yaml up --build --force-recreate --detach`. Recreating both services also refreshes NGINX's startup resolution of the conversation container address; do not expect live reload or uninterrupted active sessions.

## Production ownership and validation scope

The supplied gateway is a local development entry point. For external clients, place an HTTPS/WSS application edge in front, implement user identity and tenant quotas, retain a common browser origin and restrict direct access to the runtime network. The shared runtime token does not identify individual users. Pin and maintain deployment images according to your environment's release process.

Size the instance from the actual audio and provider workload. `maxSessions` and `maxConnections` bound instance admission; `queueCapacity`, `maxFrameBytes`, audio/text buffers and response budgets limit different parts of a session. These counts and byte limits are not a total JVM/native-memory budget. Coordinate client heartbeats with `idleTimeoutSeconds`; align proxy timeouts with the request and session durations you intend to support. Scrape authenticated metrics per instance and provide application-specific readiness checks.

Adding instances distributes new connections. A reconnect creates a fresh session, and the built-in Studio views are instance-local. Persistence and recovery require application snapshot storage, identity and restore logic. Before replacing an instance, stop routing new connections to it and finish active conversations through the application. `stop_grace_period: 30s` is an example container termination allowance, not an automatic conversation-drain or migration mechanism.

The [configuration fixture](../ominicat-server/src/test/java/io/ominicat/server/VoiceServiceDeploymentTest.java) loads the actual runtime YAML and redirects its three model bindings to independent loopback HTTP fixtures with synthetic data. It checks service routing, model selection and the real WebSocket path from audio to reply text and PCM. Those fixtures verify wiring and local protocol behavior; a live-provider trial still requires the operator's explicit endpoint, model and credential configuration. They do not establish model quality or deployment capacity.

CI also starts the Compose example and runs the [gateway probe](../scripts/check_voice_gateway.py), checking health, metrics authentication, rejection of an unrelated browser origin and an authenticated WebSocket upgrade without submitting model work. After starting the example, reproduce that probe with `python3 scripts/check_voice_gateway.py --port "${OMINICAT_GATEWAY_PORT:-18088}"` and the same runtime token in your environment.

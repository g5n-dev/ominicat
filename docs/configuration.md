<!-- ominicat-language-nav -->
[简体中文](zh-CN/configuration.md) · **English**
<!-- /ominicat-language-nav -->

# Configuration

OminiCat reads YAML once at startup. Restart the runtime to apply a changed configuration. Unknown server properties fail early; each plugin owns validation of its string-valued options. Factories are created and closed during a startup preflight before the listener binds, then created afresh for each accepted session. Factory construction must not start external requests or long-running work.

```sh
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
```

Running without `--config` uses the same loopback echo defaults. Invalid command-line arguments exit with code 2.

## Server settings

```yaml
host: 127.0.0.1
port: 8080
queueCapacity: 256
maxSessions: 256
maxConnections: 512
maxFrameBytes: 1048576
idleTimeoutSeconds: 60
# tokenEnv: OMINICAT_TOKEN
stages:
  - type: echo
    options: {}
```

| Setting | Default | Meaning / bounds |
| --- | --- | --- |
| `host` | `127.0.0.1` | Bind address; nonblank |
| `port` | `8080` | `0..65535`; zero selects an available port |
| `queueCapacity` | `256` | `1..65536` pending input frames per session, plus active work |
| `maxSessions` | `256` | `1..100000` admitted WebSocket sessions; admission remains occupied until socket closure and worker cleanup both complete |
| `maxConnections` | `max(32, 2 * maxSessions)` | `1..1000000` accepted TCP connections, including incomplete HTTP/WebSocket handshakes |
| `maxFrameBytes` | `1048576` | `1024..16777216` bytes per complete WebSocket JSON message |
| `idleTimeoutSeconds` | `60` | `1..86400` seconds without incoming socket activity |
| `tokenEnv` | absent | Name of a required bearer-token environment variable |
| `stages` | one `echo` stage | Ordered list of `1..64` plugin definitions |

Limits are per session and do not establish a machine-wide memory budget. Media frames contain payload bytes; JSON/base64 adds overhead. A queue with 256 large frames consumes much more memory than 256 small text frames. Size limits, session admission and queue capacity should be tuned together for the workload. Output has its own finite pending-byte budget, and nonwritable connections are closed under pressure. Details are in the [protocol](protocol.md).

Pong and data output share that finite budget. Output pressure cancels processing immediately, and a socket with an unflushable close frame is forcibly closed after two seconds. A plugin that ignores interruption keeps its admission slot until cleanup completes, so reconnecting cannot create unlimited orphan workers. Transport shutdown cannot forcibly kill arbitrary Java code; retained worker/admission gauges remain visible.

`idleTimeoutSeconds` measures inbound activity. A long-running request with no incoming traffic can exceed this timeout; send WebSocket pings or increase the value when needed.

## Plugin options

```yaml
stages:
  - type: openai-chat
    options:
      model: "your-chat-model"
      apiKeyEnv: OPENAI_API_KEY
      baseUrl: https://api.openai.com/v1
      timeoutSeconds: "30"
      maxOutputTokens: "1024"
      systemPrompt: "Respond concisely."
```

The `type` is an installed `ProcessorFactory.type()`. Plugin options use strings, including numeric and boolean settings. `apiKeyEnv` is the **name** of an environment variable, not its secret value. Model names are selected by the caller; availability and modality support depend on the chosen provider. The compatible HTTP client requires HTTPS, except for loopback endpoints used for local development and tests. HTTP redirects are not followed.

The built-in factories are `echo`, `rms-vad`, `audio-channel-split`, `transcript-gate`, `openai-transcribe`, `openai-chat`, `openai-speech`, `conversation-evaluator`, `prompt-template`, and `step-flow`. Refer to [plugin documentation](plugins.md) and [conversation primitives](../ominicat-conversation/README.md) for individual options and frame behavior. Different provider stages can use different endpoints, models and credential environment variables.

For a clip-based voice path, order stages as transcription → chat → speech. Send PCM chunks followed by `turn_end` to flush buffered transcription streams. Chat emits a `TURN_END` when its SSE response completes; speech buffers text until that boundary. This does not turn the HTTP transcription adapter into continuous streaming ASR. An RMS stage detects energy events; those events do not automatically interrupt playback.

Ordinary configuration failures identify the stage and option keys while suppressing the original factory exception, which may contain sensitive values. Fatal errors propagate after all earlier owned resources have been offered cleanup. Inspect the named stage's documented requirements, numeric bounds and referenced environment variables. A successfully validated factory can still fail during actual provider I/O; that failure closes its session without sending provider details to the client.

## Authentication and browser origins

```yaml
tokenEnv: OMINICAT_TOKEN
```

Set `OMINICAT_TOKEN` in the runtime's environment. If the configured variable is missing or blank, startup fails. `/ws`, `/metrics`, `/api/runtime` and `/api/activity` then require `Authorization: Bearer <token>`. `/`, `/console.css`, `/console.js` and `/health` remain public. The [Studio guide](studio.md) describes its separate overview/debugger views and metadata allowlist.

An unauthenticated listener bound to loopback also validates `Host` before routing: `localhost`, canonical `127/8` IPv4 literals and bracketed `::1` are allowed. A `Host` outside this allowlist is rejected even if its `Origin` matches; no DNS lookup is performed. This server-side check complements browser local-network restrictions. Configure `tokenEnv` for a reverse proxy or external hostname; wildcard/public listeners still require explicit deployment authentication and quotas.

Native browser WebSockets cannot set an arbitrary authorization header. The bundled playground offers subprotocols `ominicat` and `bearer.<base64url-encoded UTF-8 token>`. The server selects `ominicat` and checks the bearer value. Tokens are not stored by the playground or put in URLs. A reverse proxy must preserve the subprotocol header and avoid logging credential-bearing headers.

Browser `Origin` must match the request `Host`; unrelated browser sites cannot connect to an unauthenticated loopback runtime. Non-browser clients may omit `Origin` and use the bearer header. No cross-origin exception list is included. Serve the playground and WebSocket endpoint on the same origin, or provide an application-specific authenticated transport.

## Deployment and embedding

The standalone transport is HTTP/WebSocket. Place it behind an HTTPS/WSS reverse proxy when exposing it beyond localhost, preserve WebSocket upgrade headers and the external `Host`, and configure proxy timeouts for the expected session duration. The runtime does not provide TLS termination, user accounts, tenant routing, persistence or cluster coordination.

```java
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.server.OminiCatServer;
import io.ominicat.server.RuntimeConfig;
import java.nio.file.Path;

var config = RuntimeConfig.load(Path.of("config/demo.yaml"));
var plugins = new PluginRegistry();
try (var server = new OminiCatServer(config, plugins).start()) {
    server.awaitTermination();
}
```

Register in-process factories before startup, or place plugin JARs on the application classpath with their `ServiceLoader` descriptor. The packaged server merges bundled service descriptors. An external plugin can be launched with a classpath, for example `java -cp 'ominicat-server.jar:plugins/*' io.ominicat.server.OminiCat --config runtime.yaml` on Unix-like systems. Use the platform's classpath separator on Windows.

A server instance is single-use. `close()` stops admission, closes sockets, cancels pipeline workers and releases Netty event loops. Processor cancellation is cooperative; custom implementations must honor interruption and release resources. Kernel sessions expose `completion()` for callers that need to await all stage cleanup.

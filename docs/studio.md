<!-- ominicat-language-nav -->
[简体中文](zh-CN/studio.md) · **English**
<!-- /ominicat-language-nav -->

# OminiCat Studio

Studio is the runtime's bundled, same-origin browser console. Its operation overview and pipeline debugger are separate views. The page and its assets ship inside the Java 17 server JAR; using it requires no frontend build or external service.

```sh
./mvnw -B verify
java -jar ominicat-server/target/ominicat-server.jar --config config/studio.yaml
```

Open `http://127.0.0.1:8080/`. The [offline Studio configuration](../config/studio.yaml) runs three real processors: RMS energy detection, transcript revision gating and multimodal echo. It needs no provider credentials. The regular [demo](../config/demo.yaml) remains a single echo stage. A model/voice configuration uses its own configured processors and credentials.

## Explore the task board

The default `#overview` view arranges observed work in columns: incoming content, each actual configured processor and emitted outputs. Task cards show bounded content previews and stage invocations show observed input/output. Click a task, invocation or output to inspect its retained journal, frame references and handoffs. JVM resources stay in a compact secondary strip.

Studio groups a task by server-owned WebSocket session plus the caller's correlation ID. This is an observation group, not a kernel task lifecycle or a universal agent abstraction. Raw frames without a correlation remain unassigned. The server exposes one configured pipeline; the board does not invent planners, workers or model stages.

Activity tokens replay actual retained events across curved links. Replay intentionally slows down fast local processing and is labelled separately from real execution times. Journal sequence is insertion order, not a causal total order: concurrent output acceptance and downstream entry can arrive in a different order. Frame IDs, causation references and invocation IDs preserve their distinct meanings. A synchronous return, asynchronous completion or Netty write is not a committed business result.

Drag a module header to rearrange its display position. This does not edit execution order. Main arcs connect configured module ports; task handoffs connect visible cards through ports outside the module, clear of scrollbars. Use the wheel or grab the visible scrollbar inside a task column to scroll that column. Empty-space dragging pans the canvas; zoom and **Reset layout** are separate controls. Reset restores module positions and column scroll positions without deleting activity. Hidden cards have no dangling handoff links.

Choose **Prepare offline example** to prefill synthetic text and enter the separate `#debug` view, only when all configured processors are in the local example allowlist. Connect explicitly, then send the sample. Other configurations can be inspected by opening the debugger and starting a session. Opening a view or a detail panel never starts a WebSocket or provider call. Reduced-motion and pause controls stop visual playback.

## Operate a session

In the debugger, connect to create a WebSocket session, then send text, a final transcript, an image or a bounded microphone utterance. Transcript speaker/role and media positions are frame data; a transcript input does not perform speech recognition. Microphone capture requires localhost or HTTPS and browser permission. Interruption stops local capture/playback and asks the runtime to fence the current generation. Disconnect releases the browser session and local media resources. Leaving the debugger also cancels local capture/playback and invalidates unfinished media reads.

The graph shows the configured stage order. Its flow animation is driven by this browser's actual send/receive events and illustrates direction; it does not certify that every stage handled a particular frame. The aggregate API declares `nodeTracing: false`: ordinary connections are not traced. Optional activity recording adds stage event observations, but stage queue depths and individual node CPU usage remain unavailable. Client response timing uses one browser clock and an explicitly correlated first output, rather than subtracting client and server timestamps. An output is not necessarily a completed model response or final result.

Runtime counters cover all connections, while the local frame stream describes this browser. The runtime snapshot is a collection of concurrent aggregate gauges, not an atomic checkpoint. HTTP observation requests themselves count as TCP connections. Zero pending output is not by itself proof that every session has drained; admission and pipeline-worker gauges remain relevant.

## Resource meanings

| View | Meaning |
| --- | --- |
| Heap | JVM used, committed and maximum heap from `MemoryMXBean` |
| Direct buffers | JDK direct buffer pool usage/capacity; not total native memory, RSS or Netty's pooled reservation |
| Platform threads | `ThreadMXBean` live/daemon/peak counts; excludes virtual threads on newer JDKs |
| Uptime | JVM uptime, which can predate an embedded server instance |
| Capacity | Configured per-session input, session/connection and message/output limits |

An unavailable JVM gauge is represented by a negative value and displayed as unknown. Small samples and manual browser traffic do not establish production capacity. CPU, allocation and GC profiling remains a separate [reproducible workflow](performance.md).

## Configuration boundary

The running configuration is read at startup. Studio does not remotely replace it or mutate a live session's graph. A local YAML draft/export is separate from the running graph; complete required plugin options, save the file and restart the runtime to use it.

Runtime metadata deliberately omits plugin options, model names, prompts, endpoint URLs, credential references and the configured token environment-variable name. A draft built from that metadata is not a complete backup of the original YAML. Restore required options and authentication explicitly; never copy the browser bearer token into an exported file.

## Read-only runtime API

`GET /api/runtime` returns schema version 1:

| Field | Contents |
| --- | --- |
| `pipeline` | Ordered stage IDs, indexes and plugin types; `editable: false`, `nodeTracing: false` |
| `config` | An allowlist of capacities and idle/message/output limits |
| `security` | Whether bearer authentication is required, without its value or environment reference |
| `activity` | Whether connection-opt-in activity recording is supported |
| `runtime` | Java version, worker backend, processor count and JVM uptime |
| `metrics` | Aggregate frame, connection, admission, worker, rejection, failure and output-byte counters/gauges |
| `memory`, `threads` | The resource measurements defined above |

The endpoint uses the existing bearer authentication, loopback Host validation and same-origin browser checks. Non-GET requests fail. Responses use `Cache-Control: no-store`; no conversation content or per-session identifiers are returned. Health and the fixed console assets remain public. Provider credentials stay on the server. See [configuration](configuration.md) for authentication and deployment.

Snapshots are generated only on HTTP requests. Ordinary WebSocket connections have no activity processor wrappers or per-stage observation clocks. Browser observation and rendering have their own costs and are outside the recorded numeric-frame kernel benchmark.

## Optional activity API

Connect to `/ws?observe=1` to record that connection. `/ws` and `/ws?observe=0` generate no activity events. Studio's **Record task activity** checkbox chooses this for the next connection. Recording is optional and implemented in the server adapter; it does not add a kernel domain contract or change frame payloads.

`GET /api/activity?after=N` returns at most 128 events from a process-local 1,024-event ring. The response includes `streamId`, `oldestSeq`, `latestSeq`, `nextCursor`, `gap`, `lost`, `reset` and `incomplete`. A restart or overwritten history must remain visibly incomplete. Nanosecond event times are decimal strings so JavaScript does not lose integer precision. Time differences are meaningful only inside this server clock domain.

Events include server session, caller session/correlation, frame/causation and invocation references, configured stage, phase, outcome and preview. Known text/transcripts retain at most 240 characters; audio/images retain metadata rather than media bytes. Unknown frame types are not reflected or stringified. Exception messages and plugin options are excluded. **Text previews are content**: authorized API readers can see previews from other opted-in connections in this process. This is a runtime-wide operator view, not a tenant-isolated or durable audit store. Use synthetic content when demonstrating it.

The activity endpoint has the same authentication, Host/origin, GET-only and `no-store` boundaries as the runtime endpoint. This opt-in observer has allocation and synchronization costs. It is intended for bounded inspection, not as evidence that traced workloads equal the unobserved kernel benchmark. The browser also bounds retained events, task groups, cards and flight tokens and labels missing/evicted history.

| Browser window | Bound |
| --- | ---: |
| Global retained activity events / correlation groups | 512 / 48 |
| Retained events per group / queued replay events | 64 / 48 |
| Visible input / per-stage / output cards | 6 / 4 / 4 |
| Visual particles / flying tokens | 32 / 8 |
| Client frame log / aggregate samples | 80 / 60 |

These are local observation windows; reaching a display limit does not cancel server processing or establish business completion.

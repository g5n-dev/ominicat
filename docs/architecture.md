<!-- ominicat-language-nav -->
[简体中文](zh-CN/architecture.md) · **English**
<!-- /ominicat-language-nav -->

# Runtime architecture

OminiCat is a Java 17+ microkernel for multimodal conversations. The kernel provides scheduling and lifecycle mechanisms; plugins provide media processing, model calls and application policy. A new provider does not require a kernel change.

[![OminiCat module boundaries and assembly](assets/previews/architecture-overview.png)](assets/architecture-overview.svg)

The diagram separates dependencies and assembly from frame flow. The kernel can be used directly from Java. Server YAML and factory discovery add a configurable container experience; providers and conversation state depend on the core contract rather than becoming kernel policy. Factories are discovered at startup and configured processors are created for each WebSocket session.

| Module | Responsibility | Production dependencies |
|---|---|---|
| `ominicat-core` | Frames, envelopes, processor SPI, lifecycle, bounded queues, composition | JDK only |
| `ominicat-conversation` | Final transcript windows, policy evaluation, prompt composition, step flows | Core |
| `ominicat-providers` | PCM utilities, transcript gate, compatible HTTP model adapters | Core, Jackson |
| `ominicat-server` | Netty WebSocket/HTTP, YAML assembly, authentication, metrics, playground | Core, plugins, Netty, Jackson |
| `ominicat-benchmarks` | JMH, allocation and bounded load measurement | Core, Conversation, JMH |

## Two execution paths

[![Direct calls, session ownership and async re-entry](assets/previews/execution-path.png)](assets/execution-path.svg)

| Entry | Execution and ownership | Cost boundary |
| --- | --- | --- |
| `Pipeline.process(frame, sink)` | Synchronous processor calls on the caller thread. The caller serializes access and closes processors. A session-claimed pipeline or one containing `AsyncProcessor` rejects direct use. | No session FIFO or inter-stage codec; each invocation still creates a scoped `SynchronousSink` and continuation. |
| `openSession(...).trySubmit(frame)` | A bounded FIFO feeds one owner worker. Accepted callbacks enqueue `(frame, generation, nextStage)` into the same FIFO; that worker resumes downstream stages. | Queue locking, pending records, generation checks and worker handoffs remain part of execution. Accepted enqueue order governs continuation order. |

The standalone server uses Netty NIO event loops for transport. Frame decoding and connection/stage assembly occur on I/O callbacks; normal processor execution and output encoding occur on the session worker, followed by Netty writes. This is a processing boundary, not a claim that every expensive operation has been moved off the event loop.

Source: [Pipeline](../ominicat-core/src/main/java/io/ominicat/core/Pipeline.java), [SynchronousSink](../ominicat-core/src/main/java/io/ominicat/core/SynchronousSink.java), [PipelineSession](../ominicat-core/src/main/java/io/ominicat/core/PipelineSession.java), [server callbacks and output](../ominicat-server/src/main/java/io/ominicat/server/OminiCatServer.java).

## Contracts

`Frame` is open. Extensions can introduce events without editing a central subtype list. Built-in media frames snapshot input bytes and return independent read-only buffers. Audio is interleaved signed PCM16 little endian; `streamId` identifies a track and `startSample` identifies its per-channel sample position (`-1` means unavailable).

`Transcript` separates speaker identity from application role. A source supplies a stable `segmentId`, nonnegative revision, time range, final flag and confidence (`-1` means unavailable). Explicit IDs are required when an ASR source revises a segment's time range. The six-argument convenience constructor derives an ID from speaker/time range for simple immutable segments.

`transcript-gate` makes partial replacement and final commit explicit: revisions replace earlier partials; identical committed finals are suppressed; contradictory committed facts fail. Deduplication is bounded to retained IDs. The conversation window evaluates committed finals and exposes eviction counts; it never labels a retained window as a complete session.

`FrameEnvelope` is a first-class frame. `EnvelopeProcessor.wrap` preserves session, turn and correlation IDs while deriving new IDs and causation links for emitted frames, including asynchronous emissions. Inject IDs and a monotonic clock for replay. Raw frames remain available for simple local pipelines and low-overhead kernels. Nanosecond fields share one local monotonic clock domain; remote capture clocks must be normalized by the driver before comparison. These caller-owned IDs are correlation data, not authentication identities.

`Failure` and scoped `Cancel` are typed application events. A current-generation processor exception fails its session. The generic transport passes scoped cancellation events to application logic; the explicit `interrupt` command interrupts the current session generation. An application that owns request IDs must implement request-specific cancellation rather than treating a request as an entire session.

## Scheduling and ownership

The same Java 17 bytecode runs on Java 17, 21 and 25. `WorkerThreads` uses named daemon platform threads on Java 17 and discovers virtual-thread factories through the public JDK API on Java 21+. Worker capability discovery occurs during initialization, outside frame dispatch. Branch executors have explicit concurrency and queue bounds on both backends. See [Java compatibility](java-compatibility.md).

Each session exclusively owns a fresh pipeline and its processor instances. Synchronous processor invocations, reset hooks and cleanup run serially on one session worker. A processor may emit zero, one or many frames synchronously. It must not retain a synchronous `FrameSink` for later use.

`AsyncProcessor` is the separate callback contract. Its emitter queues continuations at the next stage, with the originating cancellation generation. Callback threads never enter downstream processors directly. Continuations share the bounded session queue; live asynchronous invocations have their own count limit. A successful `tryEmit` means queue acceptance, not downstream execution or delivery. Producers must handle rejection and eventually call `complete()` or `fail()`.

## Locking and callback boundaries

[![Lock responsibilities, callback handoff and HTTP body ownership](assets/previews/locking-boundaries.png)](assets/locking-boundaries.svg)

One session worker serializes processor state; a `ReentrantLock` coordinates callers and callback producers with that worker. The lock protects short state and queue operations rather than a complete processing call. Synchronous downstream stages execute on the same worker after one FIFO dequeue, with a fresh generation check at each stage boundary. The check releases the session lock before invoking the stage or output sink.

| Guard | Protected responsibility | Boundary while held |
| --- | --- | --- |
| Session `ReentrantLock` and `Condition` | Intake state, generation, FIFO, live handles, cancellation queue and metrics | Queue admission/removal and lifecycle changes. Processor calls, cancellation actions, reset hooks and reverse cleanup run outside this lock. |
| Envelope and optional observation wrapper monitors | Callback terminal state, metadata and delegated handle references | An emission can enter the core session lock while a wrapper monitor remains held. Provider cancellation actions run after the wrapper releases its own monitor. |
| Explicit parallel branch locks | Each branch's process, reset and close ownership | Branch code runs while its own lock is held. This differs from the session lock, which is released before processor calls. |
| HTTP body `stateLock` | Subscription/demand state, current byte buffers and terminal state | Local checks and buffer/queue updates. Blocking `items.take()`, `Subscription.request(1)` and `Subscription.cancel()` run outside this monitor. |
| Server `outputLock` | Writability checks, payload-byte reservation, write submission and close-frame submission | `writeAndFlush` submission is inside the monitor; network completion is asynchronous. Its future returns the reservation on success or failure. |
| `PluginRegistry` monitor | Factory registration, lookup and processor creation | `register` invokes `factory.type` and `create` invokes `factory.create` while synchronized. `describe` takes a configuration snapshot and invokes the description callback outside the monitor. Discovery belongs outside frame dispatch. |
| Optional `ActivityJournal` monitor | Ring insertion, session numbering and event-page serialization | `json` currently builds and encodes the page under the same monitor used by writers; it does not serialize a detached snapshot. |

For an enveloped, observed asynchronous emission, the wrapper nesting is envelope monitor → observation monitor → session lock. The core releases its lock before the observation wrapper appends a journal event, although wrapper monitors can still be held. This is the implemented nesting, not a promise that arbitrary plugin calls are lock-free or always outside every lock. Studio observation is opt-in; its sequence numbers record journal insertion order, not a global causal order.

`SynchronousSink` accepts emissions only on its invocation thread. Its `finally` path clears both the owner thread and downstream delegate when `process` returns or throws. A cached retired sink therefore rejects use and does not retain its downstream graph. An asynchronous core handle instead keeps a weak reference to its owning session. The session holds live handles strongly and removes that membership when each operation terminates; completed handles drop their cancellation reference. Envelope wrappers also detach their emitter, metadata, ID supplier and clock at termination. A plugin or SDK that independently captures media or application objects still owns those references.

## Lifecycle, cancellation and release

[![Session states, generation fencing and resource release](assets/previews/session-lifecycle.png)](assets/session-lifecycle.svg)

| Operation | State and accepted work | Completion boundary |
| --- | --- | --- |
| Ordinary `Frame.Control(END)` | An application payload with no hidden kernel state change. The server separately calls `finish()` after accepting an END command. | The marker can pass downstream before an earlier asynchronous invocation emits its final continuation. It is not a drain barrier. |
| `finish()` | `OPEN` → `DRAINING`. External intake closes; live emitters may still enqueue continuations within capacity. | The worker waits for both an empty FIFO and no live handles, then performs cleanup. A callback that never terminates can prevent this drain; plugins need finite deadlines. |
| `interrupt()` | Only effective in `OPEN`: advance generation, clear queued work, invalidate handles and interrupt the worker; intake stays open. | The worker runs cancellation actions, then stage-order `onInterrupt()` resets, before resuming work. A current-generation cancellation/reset failure enters `FAILED` and cleanup instead; reset stops at the first throwing hook. Repeated requests can coalesce into one reset; there is no per-request hook count guarantee. |
| `close()` | `OPEN` or `DRAINING` → `CANCELLED`: close intake, clear queued work, invalidate handles and interrupt the worker. | Returns without waiting for cooperative cleanup. The completion future reports cancellation and any retained cleanup errors. |
| Current-generation processor exception or active `AsyncEmitter.fail()` | The session enters `FAILED`, advances generation and cancels pending/active work. | Cancellation actions and reverse-order stage cleanup run before exceptional completion; cleanup errors remain attached. |

The worker removes cancellation actions from the queue under the session lock and invokes them after unlocking. During finalization it drains remaining cancellation actions, closes processors in reverse order, publishes logical `TERMINATED` and completes the future. `completion()` is this cleanup barrier. The future is completed on the worker, so synchronous completion callbacks may run there before the thread returns; it is not a physical `Thread.join()` guarantee.

`complete()` retires one asynchronous invocation; it does not acknowledge consumption of its already queued outputs. Normal completion clears the registered cancellation action without invoking it, so the plugin must release normal-path provider resources itself. Cancellation registration is allowed once, on the session worker, during `processAsync`. A handle that completed normally remains normally completed even if its session is later interrupted; its queued outputs can still be discarded by that later interruption.

Generation checks prevent stale continuation admission and check downstream entry. They do not undo an output or external effect whose invocation has already been admitted. Slow or uncooperative processor code, cancellation actions or cleanup can delay the worker; neither interruption nor a configured timeout is a hard real-time shutdown guarantee.

Transport lifetime is separate. A raw TCP connection slot returns on channel close. An admitted WebSocket session slot returns once, only after both socket inactivity and worker cleanup have been reported. Closing a socket alone does not free that session capacity. A Netty write future returning payload-byte reservations also does not prove that the peer consumed the message.

### HTTP adapters and response ownership

The built-in `openai-chat`, `openai-transcribe` and `openai-speech` factories create synchronous `Processor` instances. On the session path, the worker performs the HTTP request and reads the response. JDK Flow callbacks feed a separate internal body queue; they are not `AsyncEmitter` callbacks and do not execute downstream stages. One requested `List<ByteBuffer>` batch and a terminal slot provide backpressure without polling or a per-response reader thread. The batch count is independent of the raw response-byte budget.

The HTTP request timeout and body deadline are separate phases: the latter starts after response headers arrive. Expiry is recorded before closing the body. A blocked body read cancels its subscription, restores the thread's interrupted flag and raises `InterruptedIOException` when interrupted. Close clears retained queue/buffer/subscription references, wakes the reader and cancels upstream outside the body monitor; late data is ignored. Normal terminal notification releases the subscription reference while preserving earlier data before EOF or an error. Every adapter closes its `Response` even if parsing or downstream code throws; this cancels the deadline task and closes the body. The shared HTTP connection pool and daemon deadline scheduler remain process-scoped.

Chat can already have emitted text when a later provider error occurs; it emits no successful `TURN_END` without `[DONE]`. Speech emits audio only after reading and validating the complete PCM response. Transcription and speech consume their buffered input before the request, so a failure does not automatically requeue it or retry the remote operation. Transcription interruption drops pending PCM while preserving its accepted sample timeline. None of these local cleanup steps rolls back a remote request already received by a provider.

### Source and executable evidence

| Contract | Implementation | Local executable evidence |
| --- | --- | --- |
| Bounded callback → FIFO → worker continuation; END versus drain | [PipelineSession](../ominicat-core/src/main/java/io/ominicat/core/PipelineSession.java), [AsyncEmitter](../ominicat-core/src/main/java/io/ominicat/core/AsyncEmitter.java) | [AsyncSessionTest](../ominicat-core/src/test/java/io/ominicat/core/AsyncSessionTest.java), [AsyncRaceStressTest](../ominicat-core/src/test/java/io/ominicat/core/AsyncRaceStressTest.java) |
| Invocation lifetime and callback reference release | [SynchronousSink](../ominicat-core/src/main/java/io/ominicat/core/SynchronousSink.java), [EnvelopeProcessor](../ominicat-core/src/main/java/io/ominicat/core/EnvelopeProcessor.java) | [SinkLifetimeTest](../ominicat-core/src/test/java/io/ominicat/core/SinkLifetimeTest.java), [RetiredCallbackRetentionTest](../ominicat-core/src/test/java/io/ominicat/core/RetiredCallbackRetentionTest.java) |
| Parallel branch process/reset/close serialization | [ParallelProcessor](../ominicat-core/src/main/java/io/ominicat/core/ParallelProcessor.java) | [CompositeLifetimeTest](../ominicat-core/src/test/java/io/ominicat/core/CompositeLifetimeTest.java) |
| HTTP demand, terminal ordering, cancellation and deadlines | [CompatibleClient](../ominicat-providers/src/main/java/io/ominicat/providers/CompatibleClient.java) | [PublisherInputStreamTest](../ominicat-providers/src/test/java/io/ominicat/providers/PublisherInputStreamTest.java), [ProviderFaultMatrixTest](../ominicat-providers/src/test/java/io/ominicat/providers/ProviderFaultMatrixTest.java) |
| Held-open provider body: disconnect release and same-socket interruption recovery | [ChatFactory](../ominicat-providers/src/main/java/io/ominicat/providers/ChatFactory.java), [server lifecycle](../ominicat-server/src/main/java/io/ominicat/server/OminiCatServer.java) | [HttpBodyCancellationTest](../ominicat-server/src/test/java/io/ominicat/server/HttpBodyCancellationTest.java), [SpeechFactoryTest](../ominicat-providers/src/test/java/io/ominicat/providers/SpeechFactoryTest.java) |
| Declaration versus factory creation; optional activity ordering and bounds | [PluginRegistry](../ominicat-core/src/main/java/io/ominicat/core/spi/PluginRegistry.java), [ActivityJournal](../ominicat-server/src/main/java/io/ominicat/server/ActivityJournal.java) | [PluginCapabilityTest](../ominicat-core/src/test/java/io/ominicat/core/spi/PluginCapabilityTest.java), [ActivityJournalTest](../ominicat-server/src/test/java/io/ominicat/server/ActivityJournalTest.java), [ActivitySessionTest](../ominicat-server/src/test/java/io/ominicat/server/ActivitySessionTest.java) |

These fixtures exercise local scheduling, protocol and cleanup behavior. They do not establish remote service availability, model quality, a universal memory bound or production shutdown latency.

## Independent resource limits

[![Queue, async, branch and transport boundaries](assets/previews/bounded-runtime.png)](assets/bounded-runtime.svg)

| Boundary | Implemented limit | Unit and important scope |
| --- | --- | --- |
| Pending session work | `queue.size() <= C`, using `ReentrantLock` and `ArrayDeque` | Queue entries, including accepted asynchronous continuations; the currently executing frame is not a pending queue entry. |
| Live asynchronous invocations | `asyncHandles.size() <= C` | A separate count limit with the same configured `SessionOptions.queueCapacity`; it is not a shared queue-plus-in-flight budget. |
| Explicit parallel branches | `P` workers, a `P`-slot executor task queue and at most `B` buffered output frame references per branch | Branch scheduling/wait uses a shared deadline. Joined delivery can run longer; counts do not bound payload bytes or total RSS. |
| Network output | Pending encoded payload bytes at most `4 * maxFrameBytes`, plus channel writability checks | Reservation is released on write-future completion. This does not bound every Netty task or native allocation, and completion is not client consumption. |
| Server admission | Configured connection/session capacity | TCP slots return on channel close. WebSocket session slots additionally wait for worker cleanup, so slow cleanup retains session capacity. |

These limits apply to different ownership boundaries. A queue or handle count does not bound all payload bytes, native allocations or memory retained by a third-party plugin.

Source: [session intake, handles and generation checks](../ominicat-core/src/main/java/io/ominicat/core/PipelineSession.java), [parallel composition](../ominicat-core/src/main/java/io/ominicat/core/ParallelProcessor.java), [bounded worker executors](../ominicat-core/src/main/java/io/ominicat/core/WorkerThreads.java), [server output/admission](../ominicat-server/src/main/java/io/ominicat/server/OminiCatServer.java).

## Composition and failure

`ParallelProcessor` owns named, distinct branch instances, bounds each branch's buffered frames, applies one deadline and emits joined results in declaration order only after every branch succeeds. A failure, timeout or parent interruption cancels siblings. It is an explicit fork/join node, not an arbitrary YAML DAG scheduler.

`FallbackProcessor` uses a secondary only if the primary fails before any output. It never replays a partially delivered stream or falls back on interruption. Adapter deadlines remain explicit. `AsyncOutlet` separates slow delivery from the session worker using a bounded queue; saturation, sink failures and drain completion are observable. It does not supply durable retries or exactly-once delivery.

The server bounds sessions, frame bytes, ingress capacity and pending outbound bytes. Overload is explicit rather than a silent loss of control frames. Client code must react to errors/closure and stop sending. Global quotas and multi-node admission control belong in an edge service or application plugin.

## State and integration boundaries

Conversation and step-flow snapshots are validated runtime state. Applications choose a durable store, commit point, access controls and reconnection identity. Restore does not replay completed external effects. The runtime does not claim crash recovery, durable message delivery, a media echo canceller, WebRTC transport, distributed orchestration, or live provider-specific ASR implementations.

Stateful plugins can be composed in-process. External model services, message queues and stores can scale separately. Keep credentials in environment variables or a deployment secret manager; include only environment variable names in YAML. The process loads plugins from its classpath at startup; runtime bundle hot-unload is not provided.

## Performance evidence and diagram source

The [Linux performance guide](performance.md) connects these implementation mechanisms to explicit workload and diagnostic scopes. Current public figures use [Ubuntu and CentOS CI recordings](../reports/comparison/README.md); the measurements do not assign causal speedup ratios to individual architectural choices.

All five diagrams have editable SVG originals with accessible titles/descriptions and no external assets. Documentation embeds pre-rendered PNGs so readers do not need Mermaid or SVG support; click a diagram to open its vector original. Regenerate the SVGs with `python3 scripts/render_architecture.py`, update the Chinese entry figures with `python3 scripts/localize_readme.py`, then refresh the 2× PNG previews with `python3 scripts/render_doc_previews.py` (requires librsvg and a Chinese font for localized previews). Shared figures are reused across the READMEs and this guide; Chinese variants follow the same source geometry and translation catalog. CI checks translation freshness, source/preview hashes and image links. [Diagram source](../scripts/render_architecture.py) · [Preview exporter](../scripts/render_doc_previews.py) · [Translation maintenance](i18n/README.md).

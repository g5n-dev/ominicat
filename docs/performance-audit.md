<!-- ominicat-language-nav -->
[简体中文](zh-CN/performance-audit.md) · **English**
<!-- /ominicat-language-nav -->

# Performance audit

This audit examines local framework work, excluding model and network latency. It separates source-confirmed work from measured optimization benefits. Existing numeric-frame framework comparisons do not measure prompt construction, media codecs, asynchronous providers or Studio observation.

## Selected increment: bounded prompt rendering

The original prompt path called `ConversationWindow.snapshot()` only to obtain turns. That also recomputed interval unions and per-speaker statistics. It assembled every retained history line before discarding old lines, even when a template omitted history. These costs are unnecessary for producing the same prompt.

The candidate reads an immutable turn-only snapshot, caches the number of history placeholders, and constructs only the complete history suffix that fits the configured character budget. A template without history returns after its base-prompt limit check. Transcript retention, deduplication, error messages, interruption, UTF-16 budgets and single-pass substitution retain their existing behavior. Public evaluation snapshots still calculate their full metrics.

The [paired Linux workflow](../.github/workflows/prompt-cost.yml) uses the same [JMH probe](../ominicat-benchmarks/src/main/java/io/ominicat/benchmarks/PromptCostProbe.java) with baseline and candidate runtime JARs. Nine cases cover 32, 256 and 10,000 retained turns with omitted, full or truncated history. Setup seeds a full window outside timing; every measured operation creates a new final transcript, updates the real window, renders and checks the exact expected output. The full-string oracle is included in both timings. This is a prompt-stage cost, not kernel dispatch or end-to-end conversation latency.

One Ubuntu 24.04 / Java 17 job executes baseline → candidate → candidate → baseline sequentially: two independent JVM forks per side and case, with three one-second warmups and five one-second measurements in each fork. Every fork verifies the actual core, conversation and probe JAR hashes. The [collector](../scripts/prompt_cost.py) rejects incomplete matrices, changed raw data, mixed runtimes and settings. Hosted-runner noise remains; observed ranges are not confidence intervals or a production capacity estimate.

## Remaining optimization candidates

| Candidate | Confirmed work | Evidence needed before changing behavior |
| --- | --- | --- |
| Synchronous invocation allocation | [Dispatch](../ominicat-core/src/main/java/io/ominicat/core/Pipeline.java) creates a captured continuation plus a scoped sink per stage. Both appear in [Linux allocation samples](../reports/comparison/linux-ubuntu24-java17-2026-10-31/profiles/ominicat/profiles.md). Samples are not exact allocation per operation. | Matching Linux allocation and latency baselines, plus raw/envelope async and media-construction probes. Try a disposable combined invocation; retain thread/lifetime checks and reference release. Never reactivate a retired sink. |
| Generation validity reads | [Session validity](../ominicat-core/src/main/java/io/ominicat/core/PipelineSession.java) takes the session lock at each stage boundary and final output. | Contended multi-submitter and bounded-inflight tests, paired CPU/latency/allocation evidence. A published per-generation ticket is an experiment; ordinary unsynchronized field reads cannot replace cancellation fencing. |
| Outbound wire representations | [Frame encoding](../ominicat-server/src/main/java/io/ominicat/server/FrameCodec.java) creates a JSON tree, Base64 data and a string before the [transport](../ominicat-server/src/main/java/io/ominicat/server/OminiCatServer.java) measures UTF-8 bytes and creates the network frame. | Isolate text/audio/image encoding before trying an internal byte-oriented path. Preserve public encoding, Unicode, exact byte limits, ordering and buffer release. Keep immutable input snapshots. |
| Activity journal lock scope | [Journal reads](../ominicat-server/src/main/java/io/ominicat/server/ActivityJournal.java) serialize a whole event page while holding the same monitor used by traced writers. | Multi-session append plus concurrent pagination, exact cursor/gap checks and bounded immutable event snapshots. Measure observation-enabled workloads separately from default sessions. |
| Transcription request copies | [Transcription buffering](../ominicat-providers/src/main/java/io/ominicat/providers/TranscriptionFactory.java) copies PCM through temporary chunks, accumulation, WAV and multipart materialization. Source inspection identifies at least six payload-length copies before upload, excluding growth, immutable input construction and HTTP internals. This is a copy count, not measured allocation or RSS. | Separate append, WAV and complete request construction with exact byte oracles, stream isolation, interruption and near-limit inputs. Preserve defensive ownership. |
| SSE parsing | [Chat parsing](../ominicat-providers/src/main/java/io/ominicat/providers/ChatFactory.java) performs character scanning, line/event assembly and JSON-tree parsing for streamed text. | In-memory UTF-8 event fixtures with fragmented reads, per-event allocation and first-event delivery. Retain strict decoding, limits, cancellation, CR handling and the explicit completion marker. |
| Parallel result copies and flush batching | [Branch joining](../ominicat-core/src/main/java/io/ominicat/core/ParallelProcessor.java) copies a private reference buffer; transport flushes each output. | Measure these separately. Reference transfer can retain excess capacity; flush batching can worsen single-frame tail latency. Preserve all-success ordered joins, deadlines and per-frame transport reservations. |

## Acceptance and follow-up

Prompt regression tests compare complete outputs with an independently assembled suffix oracle, including random event sequences, repeated placeholders, Unicode, literal placeholder text, errors, deduplication and interruption. The normal Java 17/21/25 checks remain required. Runtime changes need coverage/SBOM verification and 512 stress seeds.

The kernel allocation experiment remains behind the [existing baseline prerequisites](iteration-plan.md#performance-evidence). Async weak references, generation fences, scoped sinks and immutable media snapshots carry verified lifecycle responsibilities. Source inspection identifies candidates; only matched before/after evidence can establish a benefit.

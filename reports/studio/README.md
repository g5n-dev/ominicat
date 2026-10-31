# Studio verification

Recorded on 2026-10-31 on a shared macOS/aarch64 development host, using OpenJDK 17.0.21.0.3. Studio ships with the Java server and uses same-origin browser APIs. It has no frontend build or hosted model dependency.

## Reproduce

```sh
./mvnw -B -ntp -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify
node --check ominicat-server/src/main/resources/web/console.js
node --test scripts/test_console.cjs
python3 -m unittest discover -s scripts -p 'test_*.py' -v
java -jar ominicat-server/target/ominicat-server.jar --config config/studio.yaml
```

The [Java verification report](../verification.md) records final test counts and coverage. [Runtime API fixtures](../../ominicat-server/src/test/java/io/ominicat/server/RuntimeApiTest.java) check safe metadata, authentication/origins, immutable configuration, routes and real WebSocket counter/drain behavior. Activity fixtures separately cover a bounded 1,024-event journal, 128-event cursor pages, missing history, qualified correlation, content-preview limits, default-off observation, real opted-in WebSocket output, async lifecycle and cancellation. The [browser-platform fixtures](../../scripts/test_console.cjs) use Node's VM and deterministic browser substitutes for media/session races, polling and task projection; they are not physical browser or microphone tests.

## Browser review

The reviewed local configuration contains the real `rms-vad`, `transcript-gate` and `echo` processors. It uses synthetic inputs, with no external model calls. The overview and debugger have separate URL states and DOM views. Task cards expose retained content, stage observations and outputs; detail panels contain event references and sequence order. Activity playback is labelled separately from execution timing. JVM resource scopes remain explicit.

The [interaction audit](interaction-review.md) records the reproduced wheel/pan defect, native scrollbar fix, module movement and connection review. Browser interaction checks include offline-example preparation and explicit connection/send, task/stage/result drilldown, return to overview, independent text/transcript debugging, literal rendering of markup-like text, interruption/end controls, configuration draft and narrow-screen layout. The screenshots below are captured from the running app; they are not mockups.

![Task execution overview](../../docs/assets/studio-overview.jpg)

![Task content drilldown](../../docs/assets/studio-details.jpg)

![Separate pipeline debugger](../../docs/assets/studio-debug.jpg)

Physical microphone recording was not performed. The file-picker button opens, but this Chrome automation environment could not populate a local image because its extension lacks file-URL access; extension permissions were not expanded. Media success/race paths have deterministic browser-platform coverage, and server media protocol fixtures run against real sockets. This limitation is not labelled an image-upload browser pass.

## Observation cost and resource scope

The [activity adapter check](activity-check.json) uses the same Java 17 server build in off/on/on/off order: 16 connections × 5,000 measured text frames in each trial, **320,000 measured frames** total. Every trial reports zero rejected/failed frames and returns active sessions, retained admission, pipeline workers and pending output to zero. Ordinary connections add zero activity events. Observed trials each generate 979,216 events while retention stays at 1,024 and each page at 128; overwritten history is explicitly reported.

| Same-build finite samples | Activity off | Activity on |
| --- | ---: | ---: |
| Transport p99 | 486.5–521.2 µs | 615.5–634.4 µs |
| Closed-loop frame rate | 63,453–65,126 /s | 57,428–59,320 /s |

These are two samples per mode in one JVM after short warmups, with aggregate polling at 10 Hz and activity polling at 2 Hz. JIT, GC and host load remain confounders; this is not a causal overhead estimate, an independent-fork benchmark or a replacement for the kernel profile. The artifact hash refers to the server adapter/transport candidate before final UI packaging.

The [concurrent observation check](observer-check.json) records an earlier API/transport candidate: 16 synthetic WebSocket connections, 80,000 measured frames plus warmup, and concurrent aggregate snapshots. It includes source and preview artifact hashes and its pre-final-UI boundary. It is historical functional drain evidence, not validation of the final activity adapter, a throughput baseline or a before/after comparison.

Ordinary connections (`/ws` or `observe=0`) generate no activity events, stage observation wrappers, previews or per-stage observation clock reads. Opted-in observation allocates bounded metadata and serializes writes to its shared ring; rendering and polling also cost resources. These costs are outside the recorded [kernel benchmark](../java17/kernel/report.md). No per-node CPU, production capacity, model quality or durable-task guarantee is inferred from this Studio check.

Activity previews can contain user text. The authenticated API is a process-wide operator view of opted-in connections, not a tenant-isolated audit store. Media payloads, raw exception messages, model configuration, credentials and raw local logs are excluded from the published report.

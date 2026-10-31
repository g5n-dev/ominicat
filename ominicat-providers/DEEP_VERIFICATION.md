# Adversarial verification

The provider and conversation suites use local HTTP peers and deterministic generated media. They make no cloud requests and require no credentials. This verification covers the checked-in Java adapters and state primitives; it does not establish a vendor's model accuracy, live API compatibility, production capacity or long-term reliability.

## Repeatable matrix

| Area | Workload and independent checks |
| --- | --- |
| SSE fragmentation | 42 combinations: LF/CRLF/CR, with/without an initial UTF-8 BOM, and input fragments of 1/2/3/5/7/13/64 bytes; identical Unicode output |
| SSE truncation | Every one of 65 byte cuts before `[DONE]`, including cuts inside Unicode code points; no successful completion |
| Invalid data | Malformed UTF-8, invalid JSON, oversized lines, byte/character budgets and truncated fixed-length bodies |
| Remote failures | HTTP 301/302/400/401/408/413/429/500/502/503; redirects disabled and body contents absent from errors |
| Network faults | Slow headers, stalled SSE body, stalled PCM body, literal TCP reset after partial text, successful subsequent requests |
| Deadline race | EOF unblocks while the scheduled deadline's `close()` is deliberately still running; partial data cannot become successful output |
| Resource retirement | 250 completed requests with 300-second configured deadlines leave zero canceled deadline tasks in the scheduler; descriptor growth is checked on supported Unix JVMs |
| Revision commitment | Seeds `0x6a7e..0x6a9d`: 32,000 segments, 352,000 gate operations, shuffled partial revisions, duplicate/stale finals and conflicting commits |
| Bounded identity retention | 10,000 committed IDs with a seven-ID gate; retained duplicates suppressed and evicted IDs explicitly permitted again |
| Audio timelines | Seeds `0x5a91e..0x5a921`: 1,440 generated PCM frames and 108 WAV requests across three independent tracks, mixed known/unknown offsets, forward gaps and interruptions |
| Audio payload isolation | WAV channel/rate headers and exact PCM bytes compared to independent per-track buffers; sample-to-time conversion checked using `BigInteger` |
| Interval metrics | Seeds `0x51a7..0x51e6`: 32,000 turns, 3,200 retained-window checks against discrete-time occupancy bitsets, plus snapshot replay |
| Evidence provenance | Seed `0xeff1de`: 1,000 randomly placed phrase matches; segment, role, timestamp and excerpt checked |
| State corruption | Invalid snapshots, duplicate IDs, throwing transition predicates, revision overflow and eviction-count overflow preserve the previous committed state |

Existing adapter tests also exercise multipart form bodies, PCM format changes, aggregate stream budgets, sample rounding drift, cancellation reset, TTS turn aggregation and channel deinterleaving.

## Defects reproduced and corrected

Four new regressions failed before their fixes: valid CR-only SSE lines were rejected; malformed UTF-8 was silently replaced; a step revision overflow changed the step and dedup state before failing; and a restored eviction counter could silently wrap negative. The checked-in tests retain those reproductions.

Additional hardening counts response bytes before decoding, removes canceled deadline tasks immediately, records timeout expiry before closing a body, and rejects impossible unstarted/zero-revision flow snapshots. A deterministic injected close-in-progress test protects the timeout race. These are implementation checks, not assertions of zero possible faults.

## Reproduce in an isolated checkout

Use Java 21+ and Maven 3.9+, or the repository's Maven wrapper. From the repository root on a Unix-like system:

```sh
task_verify_dir=$(mktemp -d)
git archive HEAD | tar -x -C "$task_verify_dir"
rsync -a --exclude target ominicat-providers/ "$task_verify_dir/ominicat-providers/"
rsync -a --exclude target ominicat-conversation/ "$task_verify_dir/ominicat-conversation/"
mvn -f "$task_verify_dir/pom.xml" -B -Pcoverage \
  -pl ominicat-providers,ominicat-conversation -am clean verify
```

The overlays include uncommitted owned-module changes while keeping build outputs out of the shared checkout. Surefire XML and HTML coverage reports are under the isolated modules' `target` directories. To repeat only the fault and oracle cases:

```sh
mvn -f "$task_verify_dir/pom.xml" -B \
  -pl ominicat-providers,ominicat-conversation -am \
  -Dtest=ProviderFaultMatrixTest,SseBoundaryTest,TranscriptionTimelineOracleTest,TranscriptGateOracleTest,ConversationOracleTest,StateAtomicityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The clean verification on Java 21 passed 55 provider tests and 40 conversation tests with no failures or errors. The 19 fault/oracle test methods were then repeated three additional times without failure. Provider line coverage was 359/363 (98.90%) and branch coverage 282/314 (89.81%); conversation line coverage was 416/434 (95.85%) and branch coverage 297/360 (82.50%). Test-method counts are distinct from the generated cases and operations listed above. Timing assertions provide generous test watchdogs and timeout checks; they are not service latency objectives.

## Release addendum: held-open CR streaming liveness

The counts above describe the earlier provider source. A subsequent held-open-stream probe found an additional liveness defect: after reading a CR line terminator, the parser eagerly read one more character to distinguish CRLF. A complete `data: ...\r\r` event therefore waited for the following event or EOF before delivering its text. A complete `[DONE]\r\r` could similarly wait for more bytes instead of terminating. The earlier finite-body CR fixtures closed the stream and did not expose this delay.

Two new local HTTP controls failed against the old source, with two failures and zero errors. Each peer flushed a complete event and then waited on a latch; text delivery or successful completion had to occur before that latch was released. Neither reproduction depended on deadline expiry.

The correction changes only production `ChatFactory.java`: a per-stream line reader now returns immediately on CR and defers skipping an optional LF until the next read. UTF-8 validation, BOM handling, byte/character budgets and other adapter behavior are retained.

### Provenance of this delta

The completed 20-minute echo/resource run belongs to artifact SHA-256 `081520dcc8c78baea46c013f78a3be0878bcba1c24789bce3b8c6c535710978f`. That artifact is unchanged and did not contain this SSE correction. Its echo measurements must not be relabeled as a sustained SSE test of the corrected release.

| Item | Before | Corrected isolated build |
| --- | --- | --- |
| `ChatFactory.java` SHA-256 | `150edf64c285a6b09febe6518a43e3230e69a83b65fa88dd898231893bb4ca0b` | `5ad587fb28bbdc1f093f25fd9c7c6b35ef8f16003e52515a3f4ff4cc9f20946b` |
| `ChatFactory.class` SHA-256 | `a740d1bd94132947647580b91768c67878033211621e3d281f386e2d6ea466e7` | `ebbaddfc4950b288dddfc3058852318a04be08c559f5d08c200ee5051771ba91` |
| `ChatFactory$SseLines.class` SHA-256 | Absent | `5e78363c7d4bb526a8b2ad8331a10a99b2a5ab0322f8a2b2e36222c60afceba3` |

Comparing provider production classes from the isolated build to that frozen artifact found only those two changed/added entries. Whole-distribution hashes and the archive-entry comparison are recorded separately in the final release evidence; this module comparison does not assign new bytes to the old artifact hash.

### Completed checks after the correction

| Check | Observed result |
| --- | --- |
| Clean owned-module suite | 57 provider tests and 40 conversation tests; zero failures, errors or skips |
| Provider coverage | 363/367 lines (98.91%); 282/314 branches (89.81%) |
| Conversation coverage | Unchanged: 416/434 lines (95.85%); 297/360 branches (82.50%) |
| Held-open boundary regressions | Text and DONE, each with LF/CRLF/CR and with/without BOM; all 12 configurations passed |
| Additional repeats | Five runs of both held-open methods plus the finite-body SSE boundary suite; all passed, including 60 additional held-open configurations |

The opt-in CR streaming fixture then ran for **60.061915583 seconds** with a producer paced at 100 events/s. It delivered **6,000 of 6,000 exact, ordered Unicode text chunks**. For every chunk, the producer waited for client observation before writing the following event; all 6,000 acknowledgments arrived. The producer held its response body open after `[DONE]\r\r`, and the client completed without requiring producer EOF. Its provider deadline was 120 seconds and its raw response budget was 4 MiB.

First text arrived 12.591667 ms after producer start; the maximum observed inter-text gap was 51.422292 ms. These are workstation observations from the paced fixture, not latency objectives or a saturation benchmark. The [raw CR streaming result](../reports/deep/sse-cr-streaming.json) records the counters and timing.

Reproduce after preparing an isolated checkout as above:

```sh
mvn -f "$task_verify_dir/pom.xml" -B -Pcoverage \
  -pl ominicat-providers,ominicat-conversation -am clean verify

mvn -f "$task_verify_dir/pom.xml" -B -pl ominicat-providers -am \
  -Dtest=SseOpenStreamTest,SseBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -f "$task_verify_dir/pom.xml" -B -pl ominicat-providers -am \
  -Dtest=SseCrStreamingSoakIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dominicat.sse.soak.report="$task_verify_dir/cr-streaming-60s.json" test
```

`SseCrStreamingSoakIT` is explicitly selected and is not part of the ordinary short test suite. This addendum validates the affected local streaming path; it does not claim a new 20-minute distribution run or a live cloud-provider result.

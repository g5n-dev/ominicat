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

<!-- ominicat-language-nav -->
[简体中文](../../zh-CN/performance-audit/session-state-2026-11-22.md) · **English**
<!-- /ominicat-language-nav -->

# Paired session-invocation evidence — 2026-11-22

[Ubuntu run 44932115058](https://github.com/g5n-dev/ominicat/actions/runs/44932115058) completed the preregistered three-block ABBA experiment for the merged `SessionInvocation` change ([PR #43](https://github.com/g5n-dev/ominicat/pull/43)). The observed decision is **`uncertain`** under the preregistered rule: every session-path allocation and time target was met in every block, while two *unchanged* direct-path controls (`audio-direct-4`, `image-direct-4`) each had one block whose time ratio exceeded 1.05 on a ~29 ns measurement with allocation ratio exactly 1.000000 — shared-runner noise on byte-identical code, not a code effect. Per the protocol this stays an honest `uncertain`; it does not upgrade to `observed-targets-met` by post-hoc reasoning.

For the primary `raw-session-4` scenario, paired allocation ratios were **0.7027 / 0.7027 / 0.7568** (mean −27.9%): 592.0 → 426.7 B/op with time ratio ≤1.024 in every block. `raw-session-1` reduced allocation ~15% and `raw-session-16` ~35.6%; the untouched direct paths held allocation at exactly 1.000000. These are results for one controlled session-invocation representation change with the same probe and paired runtime JARs; they do not establish a whole-framework, model-latency or production-capacity improvement.

## Controlled source and behavior

Only `Pipeline.java` differs between the two measured core revisions (3a71e3f → 84ae2cd, bound by SHA-256 in `manifest.json`). The baseline allocates one `SynchronousSink` plus one captured continuation lambda per stage per frame on both session dispatch paths. The candidate allocates a single `SessionInvocation` holding the combined synchronous boundary, continuation cursor and thread guard. Thread checks, post-return retirement, generation fences (before every stage entry, async handoff and terminal output), null-emission rejection and async-stage routing back to the session scheduler are preserved; sinks are never pooled or reactivated.

Behavior was established before merging: 419 tests and 512 stress seeds passed, including the existing `interruptsFenceStaleEmissionsAndDropPendingOldTurn` and 65,536-operation kernel-model stress oracles that caught and verified the fence fix during development. Java 17 coverage/SBOM and Java 17/21/25 compatibility checks passed on the PR.

## Environment and plan

| Item | Recorded value |
| --- | --- |
| Host | Ubuntu 24.04.5 LTS; x86_64; shared hosted runner, no exclusive CPU isolation |
| CPU | Intel(R) Xeon(R) 6975P-C; 4 logical processors in the affinity set |
| JVM / harness | Eclipse Adoptium OpenJDK 17.0.21; JMH 1.37; fixed 256 MiB heap; one benchmark thread |
| Plan | 7 scenarios; 3 ABBA blocks; 84 fresh single-fork JVMs; 6 forks per side/scenario |
| Each fork | 3 × 1 s warmup; 5 × 1 s measurement; AverageTime ns/op; GC-profiler B/op |
| Capture | 2026-11-21T17:50:06 UTC; scenario order reversed in block 2 |

## Seven steady-state scenarios

Time and allocation columns are arithmetic means of six fork means. Paired mean is the arithmetic mean of the three block ratios; the bracketed range is the observed min–max across blocks, not a confidence interval.

| Scenario | Baseline ns/op | Candidate ns/op | Paired time mean [range] | Baseline B/op | Candidate B/op | Paired allocation mean [range] |
| --- | ---: | ---: | --- | ---: | ---: | --- |
| `raw-direct-4` (control) | 37.268524 | 37.408110 | 1.003817 [0.997631–1.014711] | 256.000221 | 256.000222 | 1.000000 [1.000000–1.000000] |
| `raw-session-1` | 6916.127700 | 6938.528238 | 1.003253 [0.995701–1.015043] | 328.049109 | 278.729564 | 0.849658 [0.829317–0.890310] |
| `raw-session-4` (primary) | 6969.775068 | 7028.533799 | 1.008475 [0.999702–1.024298] | 592.045056 | 426.713050 | 0.720744 [0.702725–0.756777] |
| `raw-session-16` | 7247.449322 | 7298.709960 | 1.007089 [0.989188–1.017490] | 1616.049463 | 1040.048372 | 0.643871 [0.611661–0.663377] |
| `envelope-session-4` (negative control) | 7412.643238 | 7336.137127 | 0.989787 [0.977483–1.007415] | 1351.537904 | 1309.381660 | 0.968812 [0.952664–0.977439] |
| `audio-direct-4` (control) | 29.126959 | 29.616248 | 1.017969 [0.982509–1.069637] | 96.000173 | 96.000176 | 1.000000 [1.000000–1.000000] |
| `image-direct-4` (control) | 28.849484 | 30.108854 | 1.043425 [1.005399–1.107061] | 96.000171 | 96.000179 | 1.000000 [1.000000–1.000000] |

The decision criteria, per-block values, all 84 raw JMH cells, privacy-checked logs, source/artifact SHA-256 bindings and checksums are preserved in this directory (`summary.json`, `suite.json`, `manifest.json`, `raw/`, `logs/`, `SHA256SUMS`). Technical collection succeeded; `uncertain` is a complete, publishable observation under the preregistered rule.

## Interpretation boundaries

Session dispatch time (~7 µs) is dominated by queue/worker handoff, so the allocation win does not translate into a time win — as expected and as preregistered (≤1.05 guard, observed ≤1.024). Direct-path controls with allocation ratio exactly 1.000000 showing single-block time spikes up to 1.107 on 29 ns measurements is shared-runner variance, the exact case the protocol labels noise rather than signal. No model/network latency, p99, RSS or production-capacity claim is made. Future re-collection on a quieter host may upgrade the observed decision; this archive does not.

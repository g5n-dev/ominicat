# Separate pipeline-construction diagnostics

Separate pipeline-construction and first-direct-call diagnostics in warmed-up Java 17 JVMs. Every operation builds a fresh four-stage pipeline. This is not OS/JVM cold-start latency, a main acceptance case, a saturated-load capacity measurement or a main-result correction.

Two scenarios, each in fixed baseline / candidate / candidate / baseline order: eight fresh single-fork JVMs. Each fork uses one thread, a 256 MiB heap, three 1-second warmup iterations and five 1-second measurement iterations. Each side/scenario retains two fork means and ten iterations; iterations are nested observations, not ten independent replicates.

| Scenario | Baseline time ns/op | Candidate time ns/op | Baseline B/op | Candidate B/op |
| --- | ---: | ---: | ---: | ---: |
| build-4 | 217.755988 | 229.977451 | 824.001306 | 960.001374 |
| first-direct-4 | 250.636521 | 259.014361 | 1016.001489 | 1048.001541 |

build-4 times building a fresh four-stage pipeline; first-direct-4 also executes its first direct call. Timed work includes the declared probe checks. Setup, artifact verification and cleanup remain outside timing. Consult the bound probe source for the exact method boundary.

No observation target is applied here. These costs cannot pass, repair or select cases from the main seven-scenario experiment. Do not subtract times across different probes to infer a capacity, an exclusive component cost or an explanation of a prior framework gap. Two-fork and iteration ranges are observations, not confidence intervals.

The [manifest](manifest.json) binds the actual complete main measurement manifest, its core/probe artifacts, source inventories, host/JVM and the construction collector/probe sources. [summary.json](summary.json) preserves all fork means, iterations and ranges. Raw JSON, privacy-checked logs and [checksums](SHA256SUMS) are retained. This directory alone is insufficient for validation: the complete bound main measurements must also be available.

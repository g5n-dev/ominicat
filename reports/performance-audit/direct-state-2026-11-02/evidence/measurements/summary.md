# Paired direct-invocation state experiment

Three preregistered ABBA blocks; seven scenarios; 84 fresh single-fork JVMs. Each fork has three 1-second warmup and five 1-second measurement iterations. The middle block reverses scenario order.

Ubuntu 24.04.5 LTS; INTEL(R) XEON(R) PLATINUM 8573C; Java 17.0.21 (Eclipse Adoptium); 4 CPUs in the process affinity set. Shared runner; sequential cases; no exclusive CPU isolation. No RSS or p99 measurement.

Observed decision: **observed-targets-met**. This is an experiment outcome, not a CI performance gate or production guarantee.

| Scenario | Baseline time ns/op | Candidate time ns/op | Three paired time ratios | Baseline B/op | Candidate B/op | Three paired allocation ratios |
| --- | ---: | ---: | --- | ---: | ---: | --- |
| raw-direct-1 | 27.053324 | 25.301904 | 0.919298 / 0.947532 / 0.939490 | 112.000161 | 88.000150 | 0.785714 / 0.785715 / 0.785715 |
| raw-direct-4 | 55.214569 | 44.072193 | 0.778756 / 0.815576 / 0.803024 | 352.000328 | 256.000262 | 0.727273 / 0.727273 / 0.727273 |
| raw-direct-16 | 174.496221 | 135.011025 | 0.718003 / 0.846970 / 0.765367 | 1312.001037 | 928.000803 | 0.707317 / 0.707317 / 0.707317 |
| envelope-direct-4 | 252.158704 | 243.118718 | 0.952305 / 0.967546 / 0.973089 | 1144.001499 | 1048.001446 | 0.916084 / 0.916084 / 0.916084 |
| raw-session-4 | 26906.981091 | 26767.086956 | 1.041690 / 0.981071 / 0.963048 | 586.089036 | 592.192980 | 1.000016 / 0.999999 / 1.031893 |
| audio-direct-4 | 41.007870 | 34.659036 | 0.841766 / 0.846183 / 0.847633 | 192.000244 | 96.000206 | 0.500000 / 0.500000 / 0.500000 |
| image-direct-4 | 39.832097 | 34.318963 | 0.834251 / 0.867494 / 0.884550 | 192.000237 | 96.000204 | 0.500000 / 0.500000 / 0.500000 |

Each ratio divides the mean of the two candidate fork means by the mean of the two baseline fork means within one block. Decisions use all three block ratios; point estimates use their arithmetic mean. The block range is not a confidence interval. Thirty measured iterations per side/scenario are retained as observations inside six forks, not treated as thirty independent replicates.

The primary raw-direct-4 allocation target is at least 10% less B/op in every block. Every direct scenario also targets time ratio ≤1.05 and allocation ratio ≤1.01. If point estimates meet the rules but a block exceeds a limit, the outcome is uncertain. The unchanged raw-session-4 path is a negative control only; it does not correct or override another result.

Time must be strictly positive. For allocation ratios, zero divided by zero is 1 (unchanged); a positive candidate over a zero baseline is undefined, retained as null with zeroBaselineIncrease in JSON, and cannot meet an observation target. Affected ratio means and ranges remain null, never Infinity. A direct-case increase therefore yields targets-not-met rather than a technical collection failure; the session control remains excluded from the decision.

See [summary.json](summary.json) for all six fork means, all measured iterations, block ranges and each criterion. Time and allocation include the common correctness oracle and harness. This experiment does not measure model/network latency, p99, RSS or CPU self-time. Raw JSON, privacy-checked logs, [source/artifact bindings](manifest.json) and checksums are preserved. Technical collection failures cannot become complete evidence; targets-not-met and uncertain outcomes remain complete, publishable observations.

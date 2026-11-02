# Paired direct-invocation state experiment

Three preregistered ABBA blocks; seven scenarios; 84 fresh single-fork JVMs. Each fork has three 1-second warmup and five 1-second measurement iterations. The middle block reverses scenario order.

Ubuntu 24.04.5 LTS; INTEL(R) XEON(R) PLATINUM 8573C; Java 17.0.21 (Eclipse Adoptium); 4 CPUs in the process affinity set. Shared runner; sequential cases; no exclusive CPU isolation. No RSS or p99 measurement.

Observed decision: **targets-not-met**. This is an experiment outcome, not a CI performance gate or production guarantee.

| Scenario | Baseline time ns/op | Candidate time ns/op | Three paired time ratios | Baseline B/op | Candidate B/op | Three paired allocation ratios |
| --- | ---: | ---: | --- | ---: | ---: | --- |
| raw-direct-1 | 26.527372 | 25.989376 | 0.966951 / 0.976526 / 0.996092 | 112.000158 | 96.000155 | 0.857143 / 0.857143 / 0.857143 |
| raw-direct-4 | 51.903752 | 45.616218 | 0.868768 / 0.878719 / 0.889470 | 352.000309 | 288.000271 | 0.818182 / 0.818182 / 0.818182 |
| raw-direct-16 | 160.827074 | 129.895761 | 0.810086 / 0.796693 / 0.816470 | 1312.000956 | 1056.000772 | 0.804878 / 0.804878 / 0.804878 |
| envelope-direct-4 | 248.308578 | 262.478580 | 1.064100 / 1.070106 / 1.037070 | 1144.001477 | 1080.001560 | 0.944056 / 0.944056 / 0.944056 |
| raw-session-4 | 25339.686894 | 24661.073191 | 0.990779 / 0.971678 / 0.957839 | 570.887179 | 581.514575 | 1.057102 / 1.000003 / 0.999808 |
| audio-direct-4 | 39.720922 | 37.547382 | 1.004177 / 0.957480 / 0.878762 | 192.000236 | 128.000223 | 0.666667 / 0.666667 / 0.666667 |
| image-direct-4 | 39.932610 | 37.977696 | 0.974792 / 0.963655 / 0.915699 | 192.000238 | 128.000226 | 0.666667 / 0.666667 / 0.666667 |

Each ratio divides the mean of the two candidate fork means by the mean of the two baseline fork means within one block. Decisions use all three block ratios; point estimates use their arithmetic mean. The block range is not a confidence interval. Thirty measured iterations per side/scenario are retained as observations inside six forks, not treated as thirty independent replicates.

The primary raw-direct-4 allocation target is at least 10% less B/op in every block. Every direct scenario also targets time ratio ≤1.05 and allocation ratio ≤1.01. If point estimates meet the rules but a block exceeds a limit, the outcome is uncertain. The unchanged raw-session-4 path is a negative control only; it does not correct or override another result.

Time must be strictly positive. For allocation ratios, zero divided by zero is 1 (unchanged); a positive candidate over a zero baseline is undefined, retained as null with zeroBaselineIncrease in JSON, and cannot meet an observation target. Affected ratio means and ranges remain null, never Infinity. A direct-case increase therefore yields targets-not-met rather than a technical collection failure; the session control remains excluded from the decision.

See [summary.json](summary.json) for all six fork means, all measured iterations, block ranges and each criterion. Time and allocation include the common correctness oracle and harness. This experiment does not measure model/network latency, p99, RSS or CPU self-time. Raw JSON, privacy-checked logs, [source/artifact bindings](manifest.json) and checksums are preserved. Technical collection failures cannot become complete evidence; targets-not-met and uncertain outcomes remain complete, publishable observations.

# Paired session-invocation state experiment

Three preregistered ABBA blocks; seven scenarios; 84 fresh single-fork JVMs. Each fork has three 1-second warmup and five 1-second measurement iterations. The middle block reverses scenario order.

Ubuntu 24.04.5 LTS; Intel(R) Xeon(R) 6975P-C; Java 17.0.21 (Eclipse Adoptium); 4 CPUs in the process affinity set. Shared runner; sequential cases; no exclusive CPU isolation. No RSS or p99 measurement.

Observed decision: **uncertain**. This is an experiment outcome, not a CI performance gate or production guarantee.

| Scenario | Baseline time ns/op | Candidate time ns/op | Three paired time ratios | Baseline B/op | Candidate B/op | Three paired allocation ratios |
| --- | ---: | ---: | --- | ---: | ---: | --- |
| raw-direct-4 | 37.268524 | 37.408110 | 0.999110 / 1.014711 / 0.997631 | 256.000221 | 256.000222 | 1.000000 / 1.000000 / 1.000000 |
| raw-session-1 | 6916.127700 | 6938.528238 | 1.015043 / 0.995701 / 0.999014 | 328.049109 | 278.729564 | 0.829347 / 0.890310 / 0.829317 |
| raw-session-4 | 6969.775068 | 7028.533799 | 1.024298 / 0.999702 / 1.001426 | 592.045056 | 426.713050 | 0.702731 / 0.702725 / 0.756777 |
| raw-session-16 | 7247.449322 | 7298.709960 | 0.989188 / 1.014588 / 1.017490 | 1616.049463 | 1040.048372 | 0.656575 / 0.663377 / 0.611661 |
| envelope-session-4 | 7412.643238 | 7336.137127 | 1.007415 / 0.977483 / 0.984461 | 1351.537904 | 1309.381660 | 0.952664 / 0.977439 / 0.976332 |
| audio-direct-4 | 29.126959 | 29.616248 | 1.001760 / 1.069637 / 0.982509 | 96.000173 | 96.000176 | 1.000000 / 1.000000 / 1.000000 |
| image-direct-4 | 28.849484 | 30.108854 | 1.005399 / 1.107061 / 1.017816 | 96.000171 | 96.000179 | 1.000000 / 1.000000 / 1.000000 |

Each ratio divides the mean of the two candidate fork means by the mean of the two baseline fork means within one block. Decisions use all three block ratios; point estimates use their arithmetic mean. The block range is not a confidence interval. Thirty measured iterations per side/scenario are retained as observations inside six forks, not treated as thirty independent replicates.

The primary raw-session-4 allocation target is at least 10% less B/op in every block. Every session scenario also targets time ratio ≤1.05 and allocation ratio ≤1.01. If point estimates meet the rules but a block exceeds a limit, the outcome is uncertain. The envelope-session-4 path is a negative control only; it does not correct or override another result.

Time must be strictly positive. For allocation ratios, zero divided by zero is 1 (unchanged); a positive candidate over a zero baseline is undefined, retained as null with zeroBaselineIncrease in JSON, and cannot meet an observation target. Affected ratio means and ranges remain null, never Infinity. A direct-case increase therefore yields targets-not-met rather than a technical collection failure; the session control remains excluded from the decision.

See [summary.json](summary.json) for all six fork means, all measured iterations, block ranges and each criterion. Time and allocation include the common correctness oracle and harness. This experiment does not measure model/network latency, p99, RSS or CPU self-time. Raw JSON, privacy-checked logs, [source/artifact bindings](manifest.json) and checksums are preserved. Technical collection failures cannot become complete evidence; targets-not-met and uncertain outcomes remain complete, publishable observations.

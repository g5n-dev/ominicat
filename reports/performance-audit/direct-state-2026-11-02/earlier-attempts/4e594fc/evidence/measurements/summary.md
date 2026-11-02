# Paired direct-invocation state experiment

Three preregistered ABBA blocks; seven scenarios; 84 fresh single-fork JVMs. Each fork has three 1-second warmup and five 1-second measurement iterations. The middle block reverses scenario order.

Ubuntu 24.04.5 LTS; AMD EPYC 7763 64-Core Processor; Java 17.0.21 (Eclipse Adoptium); 4 CPUs in the process affinity set. Shared runner; sequential cases; no exclusive CPU isolation. No RSS or p99 measurement.

Observed decision: **uncertain**. This is an experiment outcome, not a CI performance gate or production guarantee.

| Scenario | Baseline time ns/op | Candidate time ns/op | Three paired time ratios | Baseline B/op | Candidate B/op | Three paired allocation ratios |
| --- | ---: | ---: | --- | ---: | ---: | --- |
| raw-direct-1 | 24.787406 | 24.898681 | 0.997389 / 1.008546 / 1.007483 | 112.000147 | 96.000148 | 0.857143 / 0.857143 / 0.857143 |
| raw-direct-4 | 63.443085 | 52.097158 | 0.818883 / 0.819482 / 0.825121 | 352.000376 | 288.000309 | 0.818182 / 0.818182 / 0.818182 |
| raw-direct-16 | 203.437877 | 159.939637 | 0.754757 / 0.759061 / 0.844769 | 1312.001206 | 1056.000948 | 0.804878 / 0.804878 / 0.804878 |
| envelope-direct-4 | 334.428099 | 346.166497 | 1.005529 / 1.053953 / 1.046721 | 1144.001982 | 1080.002051 | 0.944056 / 0.944056 / 0.944056 |
| raw-session-4 | 20565.191601 | 20242.386910 | 0.986655 / 0.982335 / 0.983969 | 592.149328 | 592.151975 | 1.000003 / 0.999977 / 1.000033 |
| audio-direct-4 | 47.909803 | 44.651828 | 0.930618 / 0.939232 / 0.926280 | 192.000284 | 128.000265 | 0.666667 / 0.666667 / 0.666667 |
| image-direct-4 | 47.856195 | 45.342153 | 0.937604 / 0.975721 / 0.929658 | 192.000284 | 128.000269 | 0.666667 / 0.666667 / 0.666667 |

Each ratio divides the mean of the two candidate fork means by the mean of the two baseline fork means within one block. Decisions use all three block ratios; point estimates use their arithmetic mean. The block range is not a confidence interval. Thirty measured iterations per side/scenario are retained as observations inside six forks, not treated as thirty independent replicates.

The primary raw-direct-4 allocation target is at least 10% less B/op in every block. Every direct scenario also targets time ratio ≤1.05 and allocation ratio ≤1.01. If point estimates meet the rules but a block exceeds a limit, the outcome is uncertain. The unchanged raw-session-4 path is a negative control only; it does not correct or override another result.

Time must be strictly positive. For allocation ratios, zero divided by zero is 1 (unchanged); a positive candidate over a zero baseline is undefined, retained as null with zeroBaselineIncrease in JSON, and cannot meet an observation target. Affected ratio means and ranges remain null, never Infinity. A direct-case increase therefore yields targets-not-met rather than a technical collection failure; the session control remains excluded from the decision.

See [summary.json](summary.json) for all six fork means, all measured iterations, block ranges and each criterion. Time and allocation include the common correctness oracle and harness. This experiment does not measure model/network latency, p99, RSS or CPU self-time. Raw JSON, privacy-checked logs, [source/artifact bindings](manifest.json) and checksums are preserved. Technical collection failures cannot become complete evidence; targets-not-met and uncertain outcomes remain complete, publishable observations.

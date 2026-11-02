# Controlled Java mechanism experiment

Six cells; forward then reverse order; two fresh single-thread JMH forks per cell. Each fork has three 1-second warmup iterations and five 1-second measurement iterations. The table retains both fork means and the full ten-iteration range; a range is not a confidence interval.

| Cell | Time ns/op: fork means [iteration range] | Allocation B/op: fork means [iteration range] | Process CPU ns/op: fork means [iteration range] |
| --- | --- | --- | --- |
| ominicat-caller | 65.446331 / 67.232506 [65.132667–67.386922] | 368.000407 / 368.000419 [368.000405–368.000420] | 66.483556 / 68.429702 [65.775056–68.649077] |
| ominicat-handoff | 20478.514538 / 20702.550855 [18894.140685–22305.598764] | 455.711318 / 456.109393 [454.126969–456.127568] | 18922.152423 / 19415.821408 [15679.903276–22386.924297] |
| camel-caller | 1120.297286 / 1124.911145 [1117.550697–1125.838132] | 1624.124994 / 1624.125676 [1624.006974–1624.600357] | 1133.383541 / 1140.035644 [1120.475799–1169.848505] |
| camel-handoff | 21889.011584 / 21200.573560 [20737.538535–22482.091827] | 1714.421073 / 1714.313708 [1712.056076–1723.682238] | 19745.545343 / 18653.724244 [17700.957934–21130.243223] |
| identity | 12.358382 / 12.361294 [12.322853–12.404126] | 0.000824 / 0.000825 [0.000076–0.003820] | 12.500617 / 12.497492 [12.320775–12.703864] |
| correlated-copy | 86.299019 / 90.023251 [85.955963–90.148227] | 704.005753 / 704.006020 [704.000535–704.027858] | 88.996521 / 92.887672 [87.885811–94.462415] |

Path cells use four immutable numeric stages and validate every terminal result. Caller mode executes inline; handoff mode uses the same external bounded queues and worker. Copy cells measure their declared isolated operation, not a complete SEDA request.

Time includes each benchmark’s oracle and acknowledgment work. JMH GC allocation includes harness work; process CPU includes JVM activity. These measurements do not provide p99 latency or RSS, and cannot be added or subtracted to explain a percentage of an earlier native SEDA comparison. Full iteration values and allocation/CPU ranges are in [summary.json](summary.json); all original JMH JSON and privacy-checked logs are retained with their checksums.

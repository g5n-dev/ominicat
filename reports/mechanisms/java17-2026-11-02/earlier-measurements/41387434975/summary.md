# Controlled Java mechanism experiment

Six cells; forward then reverse order; two fresh single-thread JMH forks per cell. Each fork has three 1-second warmup iterations and five 1-second measurement iterations. The table retains both fork means and the full ten-iteration range; a range is not a confidence interval.

| Cell | Time ns/op: fork means [iteration range] | Allocation B/op: fork means [iteration range] | Process CPU ns/op: fork means [iteration range] |
| --- | --- | --- | --- |
| ominicat-caller | 48.650191 / 49.310180 [48.553139–49.469495] | 368.000303 / 368.000307 [368.000302–368.000308] | 49.322591 / 49.895247 [49.023008–50.333775] |
| ominicat-handoff | 17043.633008 / 17388.232606 [16442.348371–17645.319660] | 456.100376 / 456.105593 [456.093745–456.114326] | 15762.201201 / 17521.176241 [13645.480551–18433.988764] |
| camel-caller | 874.863897 / 883.634921 [874.457298–885.904421] | 1624.097696 / 1624.098382 [1624.005439–1624.469868] | 884.847176 / 895.863527 [874.453794–900.716551] |
| camel-handoff | 18491.115028 / 18029.129771 [17544.484115–19046.387903] | 1714.051377 / 1714.052573 [1712.097219–1721.845745] | 17726.260945 / 17046.055486 [15261.819139–19356.172364] |
| identity | 10.167737 / 10.169038 [10.155541–10.193385] | 0.000679 / 0.000679 [0.000062–0.003144] | 10.204835 / 10.226011 [10.154184–10.354028] |
| correlated-copy | 66.607151 / 65.958414 [65.834682–66.745112] | 704.004453 / 704.004406 [704.000410–704.020603] | 68.196063 / 67.530612 [67.174006–69.205322] |

Path cells use four immutable numeric stages and validate every terminal result. Caller mode executes inline; handoff mode uses the same external bounded queues and worker. Copy cells measure their declared isolated operation, not a complete SEDA request.

Time includes each benchmark’s oracle and acknowledgment work. JMH GC allocation includes harness work; process CPU includes JVM activity. These measurements do not provide p99 latency or RSS, and cannot be added or subtracted to explain a percentage of an earlier native SEDA comparison. Full iteration values and allocation/CPU ranges are in [summary.json](summary.json); all original JMH JSON and privacy-checked logs are retained with their checksums.

# Controlled Java mechanism experiment

Six cells; forward then reverse order; two fresh single-thread JMH forks per cell. Each fork has three 1-second warmup iterations and five 1-second measurement iterations. The table retains both fork means and the full ten-iteration range; a range is not a confidence interval.

| Cell | Time ns/op: fork means [iteration range] | Allocation B/op: fork means [iteration range] | Process CPU ns/op: fork means [iteration range] |
| --- | --- | --- | --- |
| ominicat-caller | 64.858443 / 66.021972 [64.817953–66.126019] | 368.000403 / 368.000411 [368.000403–368.000412] | 65.590657 / 66.929902 [65.456665–67.297304] |
| ominicat-handoff | 20509.650152 / 20096.774972 [18787.634137–21652.357220] | 456.108707 / 456.017034 [455.691825–456.125411] | 18396.014268 / 17990.361057 [15403.978735–20783.719420] |
| camel-caller | 1118.709841 / 1111.712394 [1109.996812–1120.565750] | 1624.124559 / 1624.124259 [1624.006908–1624.594909] | 1133.457611 / 1124.883275 [1109.855741–1156.767591] |
| camel-handoff | 21489.207443 / 22370.642259 [20716.610477–23434.461338] | 1714.411311 / 1714.579519 [1712.048433–1724.486480] | 18953.554727 / 20855.771732 [17606.727841–24113.309142] |
| identity | 12.931525 / 12.415209 [12.401548–12.976387] | 0.000863 / 0.000831 [0.000077–0.003994] | 13.032664 / 12.487439 [12.411880–13.282292] |
| correlated-copy | 86.015033 / 82.698090 [82.543851–86.314787] | 704.005762 / 704.005524 [704.000514–704.026669] | 87.723074 / 84.960825 [84.182872–88.717441] |

Path cells use four immutable numeric stages and validate every terminal result. Caller mode executes inline; handoff mode uses the same external bounded queues and worker. Copy cells measure their declared isolated operation, not a complete SEDA request.

Time includes each benchmark’s oracle and acknowledgment work. JMH GC allocation includes harness work; process CPU includes JVM activity. These measurements do not provide p99 latency or RSS, and cannot be added or subtracted to explain a percentage of an earlier native SEDA comparison. Full iteration values and allocation/CPU ranges are in [summary.json](summary.json); all original JMH JSON and privacy-checked logs are retained with their checksums.

# Historical comparison after callback lifetime fixes

These are separate measurements, not a controlled A/B experiment. The current run shared the host with a rate-limited 64-steady-client plus 4-churn-client correctness soak and development work. Neither run establishes server or provider capacity.

Previous source fingerprint: `7e06420ee8e1911f75afc30a4fc687fecdf3c468dc7e805d73ced416c6e030aa`. Current fingerprint: `7f3b929c919da480b5014b1bd01b9891c1b1aa034787fe8a5facfcb320978017`.

| Benchmark | Stages | Previous | Current | Score change | Unit | Previous B/op | Current B/op | Allocation change |
|---|---:|---:|---:|---:|---|---:|---:|---:|
| PipelineBenchmark.dispatch | 1 | 234,421,494.592 | 113,224,138.704 | -51.70% | ops/s | 64.000 | 112.000 | +48.000 B/op |
| PipelineBenchmark.dispatch | 4 | 79,935,482.684 | 40,009,652.744 | -49.95% | ops/s | 136.000 | 328.000 | +192.000 B/op |
| PipelineBenchmark.dispatch | 16 | 25,120,225.657 | 11,615,086.320 | -53.76% | ops/s | 424.000 | 1192.001 | +768.000 B/op |
| SessionBenchmark.roundTrip | 1 | 8.320 | 8.128 | -2.32% | us/op | 215.893 | 272.032 | +56.140 B/op |
| SessionBenchmark.roundTrip | 4 | 8.114 | 8.229 | +1.42% | us/op | 287.893 | 512.014 | +224.122 B/op |
| SessionBenchmark.roundTrip | 16 | 8.805 | 8.575 | -2.61% | us/op | 575.897 | 1472.031 | +896.134 B/op |

For `ops/s`, a lower score means lower throughput. For `us/op`, a higher score means a slower round trip. Allocation values are JMH GC-profiler estimates per operation; they include the benchmark workload.

The direct dispatch benchmark exercises the new synchronous invocation guards. The session benchmark also includes queueing, worker wakeup and output handoff. These benchmarks do not separately exercise or isolate asynchronous handle retirement or envelope callback wrapping; those paths have dedicated correctness, race and reachability tests.

Changes in inlining and escape analysis, JVM behavior, and shared-host scheduling can all influence these observations. This run does not identify the contribution of each. No optimization or claim that correctness guards are free is inferred from the results.

Evidence: [current JMH JSON](jmh.json), [previous JMH JSON](previous-jmh.json), [machine-readable comparison](comparison.json), [current source manifest](source-sha256.json), [previous source manifest](previous-source-sha256.json), [full measurement report](report.md).

# Paired prompt-rendering cost

Ubuntu 24.04 / Java 17; same host, same probe; baseline → candidate → candidate → baseline.

Synthetic final-transcript processing only: transcript/segment-ID construction, window insertion, prompt rendering, exact output oracle and Blackhole. Preloaded full windows and expected strings are prepared outside timing. No pipeline, model, codec or network.

Two independent JVM forks per side/case; each fork warms 3 × 1 s and measures 5 × 1 s. Reported values average fork means; ranges below cover measured iteration means, not confidence intervals. Hosted-runner variability remains; no fixed performance gate or production-latency claim.

| Turns | History | Baseline µs/op | Candidate µs/op | Time reduction | Baseline B/op | Candidate B/op | Allocation reduction |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 32 | full | 6.827 | 5.434 | 20.4% | 19544.0 | 14708.3 | 24.7% |
| 32 | none | 3.710 | 0.917 | 75.3% | 11824.0 | 2260.0 | 80.9% |
| 32 | trimmed | 4.000 | 2.231 | 44.2% | 11264.0 | 5420.1 | 51.9% |
| 256 | full | 35.470 | 30.412 | 14.3% | 110140.3 | 83785.7 | 23.9% |
| 256 | none | 13.364 | 0.847 | 93.7% | 52648.1 | 2248.0 | 95.7% |
| 256 | trimmed | 12.160 | 2.469 | 79.7% | 43404.1 | 7224.1 | 83.4% |
| 10000 | full | 1196.349 | 1016.495 | 15.0% | 4239049.0 | 2824799.8 | 33.4% |
| 10000 | none | 337.319 | 0.862 | 99.7% | 2278627.2 | 2248.1 | 99.9% |
| 10000 | trimmed | 331.202 | 13.939 | 95.8% | 1889355.0 | 85153.8 | 95.5% |

Full fork means, measured iteration ranges and raw per-fork JMH output are in comparison.json and the four JMH files. Runtime JAR origins are checked inside every fork; manifests bind both revisions, sources, artifacts and raw outputs.

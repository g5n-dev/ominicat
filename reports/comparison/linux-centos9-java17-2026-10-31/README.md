# Model-free framework comparison

Recorded 2026-10-31 on Linux / x86_64, 4 logical processors. The [manifest](manifest.json) binds every result to the protocol, measured JAR, verified installed Python dependency inventory and exact source fingerprints. All **45 fresh processes** passed output, count, rejection and cleanup checks; no failed fork was omitted.

## Linux CI environment

**CentOS Stream 9 / x86_64**. CentOS container user space on an Ubuntu 24.04 runner; the kernel is shared with the runner. Kernel: `7.0.0-1017-azure`. CPU: `AMD EPYC 9V45 96-Core Processor`. [Workflow run](https://github.com/g5n-dev/ominicat/actions/runs/41222330745); measured revision `fbd3c610eb4e2c8ad0c4bc53fcebfdb903bbb96a`. Java/Python versions, libc, affinity and readable cgroup limits are in the manifest. The three engines run sequentially within this job. Separate platform jobs use independent hosts; their measurements are not an isolated experiment on distribution/kernel differences.

Declared image: `quay.io/centos/centos:stream9@sha256:6f8045fc4f552544d8a401d61848bafc8d06ca7c11897c5b54a2c37fc419a20a`.


![Framework comparison overview](overview.svg)

## What this measures

One process, one pipeline/session, **one outstanding data frame**. Each of 1 / 4 / 16 application stages creates the same immutable numeric transformation. Throughput covers the complete client loop; terminal latency ends after output validation, before acknowledgment returns to the caller. Startup and teardown are excluded. Every process warms for 3 seconds and measures for 5 seconds. Values below are medians, followed by observed min–max ranges across five independent processes; these are **not confidence intervals**.

| Framework | Stages | Frames/s · W=1 | p50 µs | p99 µs | Peak phase RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| OminiCat | 1 | 66,985.74 (61,951.58–68,604.53) | 7.23 (7.12–7.52) | 12.36 (10.45–13.82) | 145.93 (143.75–147.32) |
| Pipecat | 1 | 19,405.11 (18,833.39–19,621.18) | 47.38 (46.57–48.95) | 63.95 (56.20–66.16) | 87.97 (87.88–88.02) |
| Apache Camel | 1 | 59,887.15 (57,632.92–63,146.58) | 8.22 (7.88–8.33) | 14.14 (13.20–15.85) | 189.25 (180.81–219.12) |
| OminiCat | 4 | 66,567.06 (65,013.45–67,750.86) | 7.28 (7.16–7.35) | 12.44 (10.89–13.47) | 145.28 (141.71–146.62) |
| Pipecat | 4 | 10,018.98 (9,911.71–10,333.29) | 94.58 (91.89–95.65) | 130.88 (114.64–153.14) | 88.04 (87.97–88.25) |
| Apache Camel | 4 | 59,526.61 (59,023.56–61,759.43) | 8.49 (8.29–8.68) | 13.78 (12.88–15.22) | 188.88 (187.19–191.49) |
| OminiCat | 16 | 65,540.53 (63,944.05–67,791.41) | 7.50 (7.26–7.61) | 12.10 (11.53–13.54) | 146.50 (144.48–147.17) |
| Pipecat | 16 | 3,694.49 (3,650.83–3,733.31) | 262.68 (260.25–268.70) | 332.99 (298.56–415.29) | 88.26 (88.18–88.30) |
| Apache Camel | 16 | 57,329.89 (55,684.55–58,004.65) | 9.12 (9.04–9.34) | 15.12 (14.58–17.14) | 188.89 (181.68–201.06) |

![Stage scaling](throughput.svg)

![Terminal latency](latency.svg)

![Process resources](resources.svg)

## Profile boundaries

| Engine | Tested path | Execution scope |
| --- | --- | --- |
| OminiCat 0.1.0-SNAPSHOT | PipelineSession, capacity 1, terminal callback + caller acknowledgment queue | Java 17 platform session worker; exact source hash is in the manifest |
| Pipecat 1.15.0 | PipelineWorker / WorkerRunner, default queued FrameProcessor mode, native downstream output hook | CPython 3.12 / ordinary asyncio; explicit RTVI, turn tracking, metrics, usage, tracing and heartbeats off; native output-event dispatch remains included |
| Apache Camel 4.22.2 LTS | SEDA, size 1, one consumer, no discard, request/reply acknowledgment | Java 17 platform consumer; Exchange, Message and native request/reply costs included |

Pipecat is the multimodal frame-pipeline peer. Camel is a Java routing-framework comparison, not a voice-quality equivalent. Different runtimes, scheduler architectures, native frame metadata and public APIs remain part of each measured path. A common output oracle and W=1 limit do not make their internal queue/backpressure or lifecycle contracts identical. Pipecat's internal queues are not bounded by this experiment; the harness bounds admitted work.

The figures do not compare OminiCat's synchronous dispatch headline against another framework's queued path. They do not measure models, codecs, transport, audio quality, interruption latency, saturation or production session capacity. This controlled synthetic profile cannot establish universal framework superiority.

RSS is sampled using the same OS collector every 50 ms during measurement and includes runtime, imported dependencies and a pre-touched 16 MiB sample buffer. Java uses -Xms128m -Xmx512m; Python has no equivalent managed-heap limit. RSS is not framework-only allocation, retained heap, native footprint or B/op, and brief peaks may be missed. The shared host has no exclusive CPU isolation. Engine order rotates between repetitions; JIT, GC, host load and thermal effects remain confounders.

## Reproduce and inspect

See [benchmark instructions](https://github.com/g5n-dev/ominicat/blob/fbd3c610eb4e2c8ad0c4bc53fcebfdb903bbb96a/comparison/README.md), the [protocol](https://github.com/g5n-dev/ominicat/blob/fbd3c610eb4e2c8ad0c4bc53fcebfdb903bbb96a/comparison/protocol.md), [complete suite](suite.json) and per-process [raw JSON](raw/). Collection rejects nonzero exits, stale output, bad values, missing/duplicate cases, mixed manifests, short phases and missing resource samples. Report generation revalidates raw-result hashes before drawing.

Official sources: [Pipecat release](https://github.com/pipecat-ai/pipecat/releases/tag/v1.15.0), [queued processor source](https://github.com/pipecat-ai/pipecat/blob/v1.15.0/src/pipecat/processors/frame_processor.py), [worker source](https://github.com/pipecat-ai/pipecat/blob/v1.15.0/src/pipecat/pipeline/worker.py), [Camel 4.22.2 / Java compatibility](https://camel.apache.org/releases/release-4.22.2/), [SEDA semantics](https://camel.apache.org/components/4.22.x/seda-component.html).

## Separate instrumented diagnostics

[CPU samples, allocation stacks, GC and Python call profiles](profiles/README.md) are collected in additional runs. Their instrumented timings are excluded from the 45-process comparison above. Java JFR and Python cProfile retain their different collection scopes.

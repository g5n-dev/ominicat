# Model-free framework comparison

Recorded 2026-10-31 on Darwin / arm64, 10 logical processors. The [manifest](manifest.json) binds every result to the protocol, measured JAR, verified installed Python dependency inventory and exact source fingerprints. All **45 fresh processes** passed output, count, rejection and cleanup checks; no failed fork was omitted.

![Framework comparison overview](overview.svg)

## What this measures

One process, one pipeline/session, **one outstanding data frame**. Each of 1 / 4 / 16 application stages creates the same immutable numeric transformation. Throughput covers the complete client loop; terminal latency ends after output validation, before acknowledgment returns to the caller. Startup and teardown are excluded. Every process warms for 3 seconds and measures for 5 seconds. Values below are medians, followed by observed min–max ranges across five independent processes; these are **not confidence intervals**.

| Framework | Stages | Frames/s · W=1 | p50 µs | p99 µs | Peak phase RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| OminiCat | 1 | 139,059.79 (135,988.56–143,214.95) | 3.54 (3.50–3.58) | 10.04 (9.12–10.54) | 146.23 (145.03–148.23) |
| Pipecat | 1 | 8,105.28 (7,774.47–8,211.78) | 99.46 (99.33–99.92) | 217.96 (199.25–271.25) | 84.98 (84.83–85.23) |
| Apache Camel | 1 | 108,939.53 (101,539.29–110,858.15) | 4.00 (3.96–4.08) | 12.12 (11.38–14.38) | 203.72 (199.05–215.08) |
| OminiCat | 4 | 140,088.62 (137,468.18–143,133.24) | 3.62 (3.54–3.62) | 9.79 (9.71–10.58) | 146.95 (145.53–149.27) |
| Pipecat | 4 | 3,906.31 (3,818.11–3,962.96) | 224.17 (223.42–224.88) | 455.29 (413.50–520.83) | 85.03 (84.91–85.45) |
| Apache Camel | 4 | 107,210.42 (105,759.88–108,434.15) | 4.54 (4.50–4.54) | 12.54 (12.08–13.00) | 205.38 (201.58–209.33) |
| OminiCat | 16 | 135,855.35 (135,281.48–139,110.99) | 3.83 (3.83–3.88) | 10.04 (9.71–10.67) | 148.52 (146.61–149.78) |
| Pipecat | 16 | 1,301.28 (1,261.52–1,312.37) | 725.67 (720.17–730.12) | 1,145.50 (1,129.42–1,429.29) | 85.20 (85.11–85.52) |
| Apache Camel | 16 | 101,756.89 (100,992.57–103,090.52) | 5.46 (5.17–5.54) | 14.00 (13.50–14.42) | 207.12 (204.78–211.67) |

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

RSS is sampled using the same OS collector every 50 ms during measurement and includes runtime, imported dependencies and a pre-touched 16 MiB sample buffer. Java uses -Xms128m -Xmx512m; Python has no equivalent managed-heap limit. RSS is not framework-only allocation, retained heap, native footprint or B/op, and brief peaks may be missed. The shared development host has no exclusive CPU isolation. Engine order rotates between repetitions; JIT, GC, host load and thermal effects remain confounders.

## Reproduce and inspect

See [benchmark instructions](../../../comparison/README.md), the [protocol](../../../comparison/protocol.md), [complete suite](suite.json) and per-process [raw JSON](raw/). Collection rejects nonzero exits, stale output, bad values, missing/duplicate cases, mixed manifests, short phases and missing resource samples. Report generation revalidates raw-result hashes before drawing.

Official sources: [Pipecat release](https://github.com/pipecat-ai/pipecat/releases/tag/v1.15.0), [queued processor source](https://github.com/pipecat-ai/pipecat/blob/v1.15.0/src/pipecat/processors/frame_processor.py), [worker source](https://github.com/pipecat-ai/pipecat/blob/v1.15.0/src/pipecat/pipeline/worker.py), [Camel 4.22.2 / Java compatibility](https://camel.apache.org/releases/release-4.22.2/), [SEDA semantics](https://camel.apache.org/components/4.22.x/seda-component.html).

## Separate instrumented diagnostics

[CPU samples, allocation stacks, GC and Python call profiles](profiles/README.md) are collected in additional runs. Their instrumented timings are excluded from the 45-process comparison above. Java JFR and Python cProfile retain their different collection scopes.

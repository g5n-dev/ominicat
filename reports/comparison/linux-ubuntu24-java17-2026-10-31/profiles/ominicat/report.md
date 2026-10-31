# Separate Java queued-path diagnostic

Whole selected recording: JVM/framework startup, setup, 3-second warmup, 5-second W=1 measurement, harness and teardown. No phase filtering. Separate profiled diagnostics; not headline timings, framework-only allocation, retained heap, or a shared cross-language bytes-per-operation axis.

Engine: **ominicat**; four application stages; Java 17; one outstanding item.

The JFR-instrumented result is retained only to validate this diagnostic workload. It must not replace any of the 45 unprofiled comparison results. Parent-observed phase windows record stdout receipt, not exact JVM event boundaries; the charts do not filter by these windows.

[CPU/allocation/GC figures](profiles.md) · [Diagnostic context](diagnostic-summary.json) · [Profiled validation result](result.json)

Raw JFR, uncompressed event exports, configuration and logs stay under ignored `reports/local`. No automatic public copy is performed. Sampled allocation weights are neither retained memory nor framework-only or cross-language B/op. Zero sampled events do not establish absence.

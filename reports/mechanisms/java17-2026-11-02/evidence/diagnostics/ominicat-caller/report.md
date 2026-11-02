# Separate mechanism diagnostic: ominicat-caller

Separate instrumented Java 17 JMH diagnostic JVM: whole recording includes JVM/framework startup, setup, two 1-second warmup iterations, two 1-second measurement iterations, harness and teardown. No phase filtering. CPU execution sampling requests a 10ms period; allocation uses profile.jfc defaults. Recording and exported stacks retain up to 64 frames. These are not headline timings, framework-exclusive CPU, retained heap, exact object counts, p99 latency or RSS measurements.

This is one independent `-f 0` diagnostic JVM, not either of the two fresh measurement forks. The identity copy control is measured in the main matrix but intentionally not profiled here. CPU stacks cover all JVM threads; allocation weights are sampled allocated-byte weights. GC sums cover the selected whole recording, not only the timed measurement interval.

[CPU, allocation and GC figures](profiles.md) · [compact event summary](jfr-hotspots.json) · [recording context](diagnostic-context.json) · [validation](validation.json)

Raw JFR, uncompressed events, JMH output and tool logs remain local under `capture/raw`; none are included in this diagnostic archive.

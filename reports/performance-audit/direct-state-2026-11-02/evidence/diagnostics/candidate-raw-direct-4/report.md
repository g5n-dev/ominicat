# Paired direct-state diagnostic: candidate-raw-direct-4

Independent instrumented Java 17 JMH diagnostic JVM, including startup, setup, two 1-second warmups, two 1-second measurements, harness and teardown. No phase filtering. CPU sampling requests 10ms; allocation sampling is throttled to 50 events/second; recording and export retain up to 64 frames. This is not main timing evidence, per-operation allocation, retained heap, method duration, p99 latency, RSS or production capacity.

This separate `-f 0` recording does not add a main measurement fork. The selected side uses its bound core JAR and the same probe JAR. CPU samples span JVM threads. Allocation weights and GC totals are not normalized by operations, so they cannot rank per-operation efficiency across these recordings.

[CPU, allocation and GC figures](profiles.md) · [event summary](jfr-hotspots.json) · [source context](diagnostic-context.json) · [validation](validation.json)

Raw JFR, private configuration, uncompressed exports and unreviewed tool/JMH logs remain outside this publication allowlist.

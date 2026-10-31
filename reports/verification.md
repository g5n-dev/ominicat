# Verification evidence

Verified on **2026-10-31**, with actual Homebrew OpenJDK **17.0.21.0.3**, macOS/aarch64. All modules target Java 17 bytecode.

Command: `./mvnw -B -ntp -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify`. All six reactor projects succeeded.

| Module | Tests | Failures / errors / skips | Line coverage | Branch coverage |
| --- | ---: | --- | ---: | ---: |
| `ominicat-core` | 89 | 0 / 0 / 0 | 92.34% | 79.67% |
| `ominicat-conversation` | 40 | 0 / 0 / 0 | 95.85% | 82.50% |
| `ominicat-providers` | 57 | 0 / 0 / 0 | 98.91% | 89.81% |
| `ominicat-server` | 58 | 0 / 0 / 0 | 93.04% | 80.37% |

**244 tests passed, zero failures/errors/skips.** The [Java 17 report](java17/README.md) records the exact artifact, a 120-second/138,388-frame mixed-media run, 6,000 incremental SSE chunks, the native transport source variant and fresh Java 17 CPU/allocation/JFR evidence. Core/server module suites also passed on Java 21. The [CI matrix](https://github.com/g5n-dev/ominicat/actions/workflows/ci.yml) verifies Java 17, 21 and 25 plus the Java 17 container.

[Machine-readable counters](verification.json) · [Dependency inventory](runtime-dependencies.cdx.json) · [Java compatibility](../docs/java-compatibility.md).

The earlier [20-minute Java 21 deep run](deep/README.md) remains historical evidence with its original artifact hashes. It is not a test of the Java 17 compatibility changes. No live-provider success is claimed.

# Verification evidence

Verified on **2026-10-31**, actual Homebrew OpenJDK **17.0.21.0.3**, macOS/aarch64. All modules target Java 17 bytecode.

Command: `./mvnw -B -ntp -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify`. All six reactor projects succeeded.

| Module | Tests | Failures / errors / skips | Line coverage | Branch coverage |
| --- | ---: | --- | ---: | ---: |
| `ominicat-core` | 89 | 0 / 0 / 0 | 92.34% | 79.91% |
| `ominicat-conversation` | 40 | 0 / 0 / 0 | 95.85% | 82.50% |
| `ominicat-providers` | 67 | 0 / 0 / 0 | 97.89% | 88.19% |
| `ominicat-server` | 60 | 0 / 0 / 0 | 93.04% | 80.37% |

**256 tests passed, zero failures/errors/skips.** The [HTTP-body cancellation report](java17/http-cancellation/README.md) records deterministic pre-fix failures, Java 17/21 module verification, held-open stream delivery and the exact distribution/source delta. The public Flow bridge preserves interruption on Java 17 without polling or per-response reader threads. The [CI matrix](https://github.com/g5n-dev/ominicat/actions/workflows/ci.yml) verifies Java 17/21/25 and the default Java 17 container.

[Current counters](verification.json) · [Dependency inventory](runtime-dependencies.cdx.json) · [Java compatibility](../docs/java-compatibility.md).

The [kernel performance profile](java17/kernel/report.md) still applies to unchanged core bytecode; kernel timings have a separate scope from provider/network timing. Earlier [Java 17 resource evidence](java17/README.md) and [Java 21 deep verification](deep/README.md) retain their original source and artifact hashes. No live-provider success is claimed.

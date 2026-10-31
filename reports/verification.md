# Verification evidence

Verified on 2026-10-31 (Asia/Shanghai), using Homebrew OpenJDK 21.0.13 on macOS/aarch64.

Command: `./mvnw -B -ntp -Pcoverage,sbom clean verify`. All six reactor projects succeeded.

| Module | Tests | Failures/errors | Line coverage | Branch coverage |
|---|---:|---:|---:|---:|
| `ominicat-core` | 59 | 0 | 91.65% | 79.70% |
| `ominicat-conversation` | 35 | 0 | 95.79% | 82.08% |
| `ominicat-providers` | 41 | 0 | 98.82% | 90.54% |
| `ominicat-server` | 41 | 0 | 88.51% | 75.19% |

Total: **176 tests, zero failures/errors**. Coverage includes production Java code in these four modules; benchmark and browser JavaScript are not part of the coverage denominator.

The suite includes real loopback WebSocket/HTTP, a complete configured PCM → transcription fixture → prompt → SSE text → speech fixture pipeline, async drain/late-callback cancellation, overload, malformed JSON, stable transcript revisions, snapshots and media bounds. Cloud model endpoints were not called.

HTML coverage is generated under each module’s `target/site/jacoco/`; the CI job uploads it and a CycloneDX dependency inventory. The [machine-readable coverage summary](verification.json) retains covered/missed counters. An SBOM is a dependency inventory, not a vulnerability assessment.

Additional executable checks: the standalone shaded server health endpoint, real WebSocket echo measurement, and deterministic replay/resume are recorded alongside the [performance reports](baseline/report.md). The [scenario matrix](../docs/scenario-coverage.md) distinguishes mechanisms, supplied adapters and external responsibilities.

These are controlled local checks, not a production reliability certification or a model accuracy assessment.

Standalone runtime [CycloneDX dependency inventory](runtime-dependencies.cdx.json).

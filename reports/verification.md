# Verification evidence

Verified on 2026-10-31 (Asia/Shanghai), OpenJDK 21.0.13, macOS/aarch64.

Command: `./mvnw -B -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify`. All six reactor projects succeeded.

| Module | Tests | Failures/errors/skips | Line coverage | Branch coverage |
|---|---:|---:|---:|---:|
| `ominicat-core` | 80 | 0 / 0 / 0 | 94.20% | 80.50% |
| `ominicat-conversation` | 40 | 0 / 0 / 0 | 95.85% | 82.50% |
| `ominicat-providers` | 57 | 0 / 0 / 0 | 98.91% | 89.81% |
| `ominicat-server` | 57 | 0 / 0 / 0 | 92.97% | 80.00% |

**234 tests passed, zero failures/errors/skips.** Java 21/25, container lifecycle/authentication and a short validation-driver run also passed in [CI](https://github.com/g5n-dev/ominicat/actions/workflows/ci.yml).

The [deep verification report](deep/README.md) records 1,572,864 selected randomized operations, fault fixtures, callback reachability, 20 measured minutes of mixed-media traffic, 1,405,303 successful frames, 42,729 new connections, complete resource drain, and CPU/allocation/GC/native-memory evidence. It identifies exact source and distribution hashes.

[Machine-readable test/coverage counters](verification.json) · [Dependency inventory](runtime-dependencies.cdx.json) · [Deep resource timeline](deep/soak-resources.svg).

Live-provider verification remains unrun because the test endpoint, credential environment-variable name and models have not been supplied. Local fixtures and synthetic tests are distinct from real-provider evidence.

A localized post-soak CR-stream liveness correction is [separately documented](deep/release-delta.json), with 6,000 incremental Unicode chunks over 60 seconds and final 234-test verification. Core/server/conversation bytecode remained unchanged; the whole 20-minute resource run is not retroactively claimed for a different binary.

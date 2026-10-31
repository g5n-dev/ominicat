# Verification evidence

Verified on2026-10-31 (Asia/Shanghai), OpenJDK21.0.13, macOS/aarch64.

Command: `./mvnw -B -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify`. All six reactor projects succeeded.

| Module | Tests | Failures/errors/skips | Line coverage | Branch coverage |
|---|---:|---:|---:|---:|
| `ominicat-core` | 80 | 0 / 0 / 0 | 94.20% | 80.50% |
| `ominicat-conversation` | 40 | 0 / 0 / 0 | 95.85% | 82.50% |
| `ominicat-providers` | 55 | 0 / 0 / 0 | 98.90% | 89.81% |
| `ominicat-server` | 57 | 0 / 0 / 0 | 92.97% | 80.00% |

**232 tests passed, zero failures/errors/skips.** Java21/25, container lifecycle/authentication and a short validation-driver run also passed in [CI](https://github.com/g5n-dev/ominicat/actions/runs/41175697513).

The [deep verification report](deep/README.md) records1,572,864 selected randomized operations, fault fixtures, callback reachability,20measured minutes of mixed-media traffic,1,405,303 successful frames,42,729 new connections, complete resource drain, and CPU/allocation/GC/native-memory evidence. It identifies exact source and distribution hashes.

[Machine-readable test/coverage counters](verification.json) · [Dependency inventory](runtime-dependencies.cdx.json) · [Deep resource timeline](deep/soak-resources.svg).

Live-provider verification remains unrun because the test endpoint, credential environment-variable name and models have not been supplied. Local fixtures and synthetic tests are distinct from real-provider evidence.

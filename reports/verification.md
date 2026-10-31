# Verification evidence

Verified on **2026-10-31**, actual Homebrew OpenJDK **17.0.21.0.3**, macOS/aarch64. All modules target Java 17 bytecode.

Command: `./mvnw -B -ntp -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify`. All six reactor projects succeeded.

| Module | Tests | Failures / errors / skips | Line coverage | Branch coverage |
| --- | ---: | --- | ---: | ---: |
| `ominicat-core` | 89 | 0 / 0 / 0 | 92.34% | 79.91% |
| `ominicat-conversation` | 60 | 0 / 0 / 0 | 96.57% | 82.87% |
| `ominicat-providers` | 67 | 0 / 0 / 0 | 97.89% | 88.19% |
| `ominicat-server` | 81 | 0 / 0 / 0 | 94.05% | 80.00% |

**297 tests passed, zero failures/errors/skips.** Twenty [media-reference tests](../ominicat-conversation/src/test/java/io/ominicat/conversation/reference/) cover producer/source/version isolation, PCM per-channel units, unknown positions, required fingerprints, nonuniform frame boundaries, integer overflow and bounded binary import/export. The I/O fixture checks full `long` precision, malformed UTF-8, every truncated prefix and caller-owned stream lifetime.

Sixteen activity tests additionally cover default-off observation, cursor/ring bounds, preview privacy, real WebSocket event shape, async completion/cancellation and cleanup-failure classification. Five [runtime API tests](../ominicat-server/src/test/java/io/ominicat/server/RuntimeApiTest.java) cover safe metadata, authentication/origins, immutable configuration, static routes and real WebSocket counter/drain behavior. Ten Node-only [browser-platform fixtures](../scripts/test_console.cjs) cover media/session races, bounded state, polling, view separation and details interaction. [Studio verification](studio/README.md) keeps browser checks and concurrent-observer traffic separate from the kernel benchmark.

The [HTTP-body cancellation report](java17/http-cancellation/README.md) retains deterministic pre-fix failures, Java 17/21 module verification, held-open stream delivery and the exact distribution/source delta. The public Flow bridge preserves interruption on Java 17 without polling or per-response reader threads. The [CI matrix](https://github.com/g5n-dev/ominicat/actions/workflows/ci.yml) verifies Java 17/21/25 and the default Java 17 container.

[Current counters](verification.json) · [Dependency inventory](runtime-dependencies.cdx.json) · [Java compatibility](../docs/java-compatibility.md).

The rebuilt benchmark JAR retains SHA-256 `a312af033f1f9886883479147e4d82ede152afba3628baf5fb12a48e2db87bc7`, matching the [kernel performance profile](java17/kernel/report.md). The optional reference API and Studio server observer do not alter core dispatch. Reference construction, mapping and serialization are outside that numeric-frame benchmark's scope. Earlier [Java 17 resource evidence](java17/README.md) and [Java 21 deep verification](deep/README.md) retain their original source and artifact hashes. No live-provider success is claimed.

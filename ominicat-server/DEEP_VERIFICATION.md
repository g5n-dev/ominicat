# Native transport and resource verification

Run date: **2026-10-31, Asia/Shanghai**. These are local, synthetic transport tests with real TCP sockets. No commercial model API or external network service was used.

The server changes were checked in isolated source snapshots so concurrent module builds could not modify classes under a running test process. The archived base was commit `14e6bb0`, overlaid with the server module under review. [Source hashes](validation/source-sha256.json) identify the production files measured in the resource runs below, whose server `src/main` aggregate SHA-256 was `148b6ee26fb50e5b996aa66a2fff4e2ae0f3d3d6abacced0ecdbdc2c77bbd8b0`. A subsequent Host-header hardening change has its own verification below. Whole-repository verification and the longer final-distribution soak are separate evidence.

## Failures reproduced, then corrected

The initial four native-socket tests produced **three failures**. The [original failing log](validation/regressions-before-fixes.log) is retained.

| Reproduction | Observed failure | Correction and regression |
| --- | --- | --- |
| Disconnect while a deliberately noncooperative processor remains blocked | Reconnecting received HTTP `101` even with `maxSessions: 1`; socket churn could accumulate orphan workers | Admission remains occupied until both socket closure and processor cleanup complete; reconnect gets `503` until the fixture is released |
| Stop reading TCP after requesting a large output burst | The close frame remained behind unflushable data; another connection still received `503` after three seconds | Cancel the pipeline immediately; force-close an unflushable socket after a two-second deadline; admission recovers |
| Send 50,000 masked Ping frames while never reading TCP output | Pong writes bypassed the JSON output budget, leaving the session occupied after four seconds | Pong and data writes now share the finite output budget and writability check; pressure closes and recovers admission |

Additional review found that raw HTTP/unfinished-handshake sockets were outside WebSocket admission. `maxConnections` now caps them before HTTP handlers are allocated; it defaults to `max(32, 2 * maxSessions)`. A two-connection test verifies rejection, exact resource accounting and recovery after one raw socket closes.

Factory ownership cleanup also needed to cover `Error`, rather than only `Exception`/`RuntimeException`. Tests now inject an `AssertionError` from a later startup cleanup, and a session factory failure followed by another cleanup error. All earlier returned resources are still closed, fatal startup errors are preserved, and per-connection initialization failure produces sanitized `1011` with admission released.

## Verification runs

The extended suite completed **52 tests with zero failures**, including a 180-second soak. A separate 60-second run completed **11 tests with zero failures** and added worker event-loop task/timer sampling. The normal server suite contains 51 tests; `ServerSoakIT` is opt-in and is excluded by Surefire's default test naming rules.

| Condition | 180-second run | 60-second task-sampling run |
| --- | ---: | ---: |
| Measured soak duration | 180.441 s | 60.473 s |
| Concurrent native clients | 16 | 16 |
| New connection rate limit, total | 100/s | 100/s |
| Session capacity / raw connection capacity | 64 / 128 | 64 / 128 |
| Pending input frames / maximum JSON bytes | 8 / 32,768 | 8 / 32,768 |
| Successful connect → exchange → disconnect cycles | 17,248 | 5,744 |
| Ping/Pong exchanges | 2,464 | 816 |
| Deliberate nonreading TCP consumers | 60 | 20 |
| Unexpected failures / admission rejections | 0 / 0 | 0 / 0 |
| Netty leak detector | PARANOID | PARANOID |
| Reported Netty leak warnings | 0 | 0 |
| JVM heap limit | 512 MiB | 512 MiB |

The generator sends invalid JSON field types periodically and verifies that the same session subsequently exchanges a valid frame. Half the normal cycles send explicit `end`; the remainder close the TCP connection abruptly. The slow-consumer fixture reads the `101` handshake and **does not read any subsequent TCP bytes**. It is not a JDK WebSocket listener that merely withholds message demand.

Published log copies replace local temporary build directories with `[isolated-workspace]`; source hashes, test outcomes and numeric measurements are preserved. [Artifact checksums](validation/artifacts-sha256.json) cover those published copies.

Raw artifacts:

- [180-second samples and counters](validation/native-soak-180s.json) and [test log](validation/native-soak-180s.log)
- [60-second task/resource samples](validation/native-soak-tasks-60s.json) and [test log](validation/native-soak-tasks-60s.log)

Environment: OpenJDK `21.0.13.0.3`, macOS, ten processors available to the JVM. This is a workstation measurement, not a deployment capacity estimate.

## Resource observations

The following figures come from the 180-second run after its first ten seconds, except where a different interval is stated.

| Observation | Result |
| --- | --- |
| Netty pooled direct memory reserved | Exactly 16 MiB at every sample |
| Test-process open file descriptors | 58–92; final sample 58 |
| Platform threads | 31–35; final sample 31 |
| Sampled heap usage | 11.5–32.6 MB |
| Peak admitted sessions / live pipeline workers, whole run | 17 / 16 |
| Peak pending output payload bytes, whole run | 128,499 |
| Connections, active sessions, admission slots, workers and pending output after drain | All zero |

The separate task-sampling run observed **0–5 queued worker event-loop tasks** and **0–15 scheduled worker event-loop tasks**, with both returning to zero after drain. Its Netty direct reservation was also exactly 16 MiB throughout. Queue/timer inspection is test-only and uses the pinned Netty version's internals, sampled on the owning event loops. A 250-connection regression verifies that timeout/close timers do not remain after churn.

The 60-second run's sampled heap ranged from 16.6 to 289.2 MB without a forced final collection. Raw heap samples include uncollected temporary objects; they are not retained-heap measurements. Likewise, Netty's pooled direct reservation includes reusable allocator capacity. Zero resource gauges and zero detector warnings are evidence for these finite workloads, not a proof that every possible custom plugin is leak-free.

## Other adversarial cases

- **Idle connection:** a one-second inbound idle timeout closes a native peer and releases admission.
- **Stalled provider body:** a local HTTP fixture sends SSE headers, then stalls the body. A one-second provider deadline closes the session with `1011` within four seconds; another session then receives a successful response.
- **Input pressure:** an explicitly blocked stage fills a one-frame queue. Overflow is reported; accepted frames are retained. A rejected `end` leaves intake open and succeeds when retried after drain.
- **Malformed/authenticated input:** the regular suite covers bearer headers, browser bearer subprotocols, unrelated origins, invalid base64/metadata/types, duplicate fields, multiple JSON roots, nested envelopes and oversized/fragmented messages.
- **Configured voice path:** actual WebSocket → PCM buffering → local HTTP transcription → transcript gate → prompt template → SSE chat → local HTTP speech → PCM output. Payloads, streamed text, synthesized audio, duplicate-final suppression and cleanup are checked together.
- **Shutdown with a noncooperative processor:** transport shutdown returns within three seconds and closes all sockets. The blocked worker and its retained admission remain visible until the fixture is explicitly released. Java cannot safely force-terminate an arbitrary callback that ignores interruption; the test does not claim cleanup before that callback exits.

## Generator limit found during testing

An initial unpaced generator completed **31,930 connections in about five seconds**, then exhausted the workstation's ephemeral client ports with `BindException: Can't assign requested address`. Subsequent local clients were temporarily affected by TIME_WAIT. Server-owned gauges nevertheless drained to zero, and pooled direct reservation stayed at 16 MiB. That run is not counted as a passing soak or a server throughput result. The successful runs were repeated with a total 100-connect/s pace, below the observed host port budget.

## Subsequent loopback authority hardening

An independent native probe could connect to an unauthenticated loopback listener using an attacker-controlled `Host` and a matching `Origin`. This was **not demonstrated as a browser exploit**. Browser local-network restrictions do not remove a service's responsibility to protect itself, as discussed by the [WICG Local Network Access security considerations](https://wicg.github.io/local-network-access/#dns-rebinding).

Unauthenticated listeners bound to a loopback address now reject non-loopback authorities with HTTP `403` before routing or WebSocket admission. The check inspects the actual listening channel's bound address and performs no DNS lookup. It accepts `localhost`, canonical dotted `127/8` literals and bracketed `::1`, with optional valid decimal ports. Token-configured and wildcard/public listeners retain their existing authority behavior; their authentication/deployment policy remains the operator's responsibility.

**33 tests passed**, including six new native regressions, nine resource adversarial tests and eighteen existing server integration tests. The new tests cover every public HTTP route and a matching-origin WebSocket attempt, canonical local authorities, malformed/lookalike authorities, duplicate Host headers, authenticated external proxy authorities, wildcard binding, and an actual IPv6 loopback listener. No IPv6 test was skipped on the test host. See the [test log](validation/loopback-host-guard.log) and [source hashes](validation/host-guard-source-sha256.json).

The new server `src/main` aggregate SHA-256 is `4405542a7fa8d5157b729c03f415e29cc48e9ca5613f35d3ad80162f91d8a24c`. The earlier resource runs remain labeled with their measured source. Final-distribution verification must rebuild and restart its long soak after this change.

## Reproduce

Use JDK 21+ and Python 3 with tar extraction-filter support:

```sh
./scripts/server_soak.sh 180 16 /tmp/ominicat-server-validation 100
```

Arguments are duration seconds, concurrent clients, artifact directory and total new connections per second. The script archives `HEAD`, overlays the current server module, and builds in a temporary directory. It enables PARANOID Netty leak detection and uses a 128 MiB initial / 512 MiB maximum heap. It leaves source snapshots and reports available for inspection.

For a manually prepared isolated checkout:

```sh
./mvnw -B -pl ominicat-server -am test \
  -Dtest=ServerAdversarialTest,StalledProviderRecoveryTest,ServerSoakIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dominicat.soak.seconds=180 -Dominicat.soak.clients=16 \
  -Dominicat.soak.connectsPerSecond=100 \
  -Dominicat.soak.output=/tmp/ominicat-server-validation.json \
  '-DargLine=-Xms128m -Xmx512m -Dio.netty.leakDetection.level=paranoid'
```

Connection churn, kernel dispatch, model response latency and user-perceived audio latency are different workloads. These measurements make no superiority claim over another runtime and do not measure provider quality, Internet jitter, cross-machine clocks, WebRTC or acoustic processing.

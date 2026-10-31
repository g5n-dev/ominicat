# Adversarial and sustained validation

The normal test suite includes deterministic concurrency/model tests and real local HTTP/TCP/WebSocket fault fixtures. The drivers here add sustained mixed-media validation and explicit opt-in live service checks. They do not use production conversation data.

## Sustained mixed-media probe

Build an immutable server artifact, then compile the observers/drivers against it:

```bash
./mvnw -pl ominicat-server -am clean package -DskipTests
mkdir -p reports/local/deep/classes
javac --release 21 -cp ominicat-server/target/ominicat-server.jar \
  -d reports/local/deep/classes validation/ValidationServer.java validation/Soak.java
```

Run the server with constrained memory. The extra observer binds only to loopback; it is validation instrumentation outside the production distribution:

```bash
java -Xms256m -Xmx256m -XX:MaxDirectMemorySize=128m -XX:NativeMemoryTracking=summary \
  -cp ominicat-server/target/ominicat-server.jar:reports/local/deep/classes \
  ValidationServer validation/soak.yaml 18082
```

In another terminal, run 60 seconds of warmup followed by 20 minutes of measured traffic:

```bash
java -Xmx512m -cp ominicat-server/target/ominicat-server.jar:reports/local/deep/classes \
  Soak http://127.0.0.1:18081 http://127.0.0.1:18082 1200 60 64 reports/local/deep/soak
```

The probe maintains 64 persistent connections at approximately20 frames/s each plus4 connection-churn workers (100ms pause). Half the churn closures complete a WebSocket close handshake; the others abort TCP. Frames rotate through text, PCM audio, revision-aware transcripts, an encoded image and causal envelopes. Every response is checked against the serialized input; numeric JSON widths are normalized by parsing the sent representation. A persistent connection failure is recorded and never silently healed. The harness itself is preflighted with a short run before collecting sustained evidence.

Latency histograms are bounded:1µs buckets up to100ms, then1ms buckets, with overflow beyond10.1s; reported percentiles are bucket upper bounds. Measurement includes send/receive and payload validation, and is closed-loop/rate-limited. Churn latency excludes handshake time. Unexpected failures are counted separately and fail the run. This is a correctness/resource probe, not a saturation capacity benchmark.

The loopback observer records heap, direct buffers, Netty allocator memory, classes, platform threads, GC time, CPU and open file descriptors. Prometheus samples track active sockets, retained admission and live pipeline workers. Virtual workers are not included in the JDK platform-thread count. Full GC is requested once after warmup and once after draining to compare retained heap. Full-GC endpoints are validation-only. Persistent metrics, full-GC comparisons and post-drain class histograms complement one another; stable sampled memory alone does not prove absence of all leaks.

`soak-criteria.json` records exploratory guardrails before the run. The driver requires every persistent client to sustain at least10 successful validated frames/s, zero unexpected failures, and complete active/admitted/worker/output drain. The final analyzer checks all frame types were exercised, histogram/type counters balance, full-GC heap growth (8MiB), direct reservation growth (4MiB), and descriptor/platform-thread growth (8 each). These thresholds are local investigation limits, not production promises.

Keep raw JFR, native-memory output and machine-specific logs under ignored `reports/local/`. Publish selected counters, workload, source/artifact hashes and actual failure counts. A 20-minute local run does not establish multi-day stability or a production SLA.

Optional class-histogram diagnostics request additional GC. JFR captures the entire process around the measurement window, including warmup, drain and those diagnostics. NMT committed/reserved memory is not RSS; a256MiB heap limit is not a256MiB process-memory limit.

## Check the checker

`NegativeControl.java` provides deliberately wrong `corrupt` and `drop` fixtures on caller-selected loopback ports. Compile it with the other drivers, run a fixture with an observer port, then point a short `Soak` run at it. The corruption fixture must fail payload checks; the drop fixture must fail per-client progress checks even if errors arrive after the measurement stops. Its observer values are explicitly dummy values, so these controls are not resource measurements. [Recorded negative controls](../reports/deep/harness-controls.json) retain both expected rejections.

## Opt-in real compatible service

Copy `live-example.json` to an ignored `*.local.json` file, provide a test endpoint and the name of an already configured credential environment variable. Model fields are explicit. The probe makes one request per configured capability, with short synthetic input and bounded output; it does not auto-discover models or silently use a different account.

```bash
javac --release 21 -cp ominicat-server/target/ominicat-server.jar \
  -d reports/local/deep/classes validation/LiveCompatible.java
java -cp ominicat-server/target/ominicat-server.jar:reports/local/deep/classes \
  LiveCompatible validation/test.local.json reports/local/deep/live-result.json
```

Optional configuration fields are `vision: true`, `transcriptionModel`, `pcmFixture` (PCM16LE/16kHz/mono, at most10seconds), `speechModel`, and `voice`. The speech fixture should say “The blue cat has seven stars.” Text and vision checks use an exact marker and a solid blue image. Speech output must contain non-silent PCM; transcription must recover the known words. The results omit endpoint and credentials. Live-provider success applies only to configured capabilities and that endpoint/model at test time. No credential means no real-provider result.

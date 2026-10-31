<!-- ominicat-language-nav -->
[简体中文](../docs/zh-CN/comparison/profile_java.md) · **English**
<!-- /ominicat-language-nav -->

# Separate Java JFR diagnostics

Run this **after the unprofiled comparison has finished**, serially on the same host. The wrapper does not build or modify the measured JAR. Use the Java 17 executable and optionally bind the exact JAR SHA-256 from the comparison manifest:

```sh
python3 comparison/profile_java.py \
  --java "$JAVA_HOME/bin/java" \
  --jar comparison/benchmarks/target/benchmark.jar \
  --expected-jar-sha256 <javaArtifactSha256-from-manifest> \
  --output reports/local/comparison-java-profiles
```

The default collects OminiCat and Camel sequentially, each at four stages, one outstanding item, three seconds of warmup and five seconds of measurement. `--engines ominicat` or `--engines camel` selects one. Choose a new empty output directory; only destinations beneath ignored `reports/local` are accepted. The matching JDK's sibling `jfr` tool is used.

Each recording requests a 1 ms Java execution-sample period and stack depth 64 and uses a profile configuration with initial environment/system/security properties, property changes, process inventory/start and OS/JVM-information events disabled. Export requests all supplied frames up to depth 64 for five selected diagnostic event types. Existing renderer APIs generate complete supplied CPU/allocation flame graphs, compact hotspots and a GC collection-pause timeline. The fixed 16 MiB sample buffer and harness allocations remain within the recording scope; sampling may omit individual allocations.

These are **whole-recording diagnostics**, including JVM/framework startup, setup, warmup, measured loop, harness and teardown. They are not filtered to measurement. Parent-observed UTC phase windows describe stdout receipt and process launch/exit, not exact JVM event boundaries. `load.json` is explicitly renderer context with the observed Java version; it contains no invented CoreLoad results.

`summary.json` appears only after every selected engine validates, drains, closes, exports and renders. Per-engine `diagnostic-summary.json` binds the unchanged benchmark JAR and diagnostic outputs by SHA-256, retains phase observations and labels the profiled result as non-headline. These two profiled runs never replace or supplement the 45 unprofiled repetitions in the main comparison table. Allocation sample weights are not retained memory, framework-only allocation or a cross-language B/op axis.

Raw JFR, uncompressed selected-event JSON, logs and configuration stay local. No public copying is performed. If publishing reviewed diagnostics, use an explicit allowlist of the generated figures, summaries, context/result JSON and privacy-checked compressed selected events; exclude raw JFR and logs. Missing samples should be reported honestly; zero observed events do not prove absence.

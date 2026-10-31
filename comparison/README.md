# Reproducible, model-free framework comparison

This optional tool compares OminiCat, Pipecat **1.15.0** and Apache Camel **4.22.2 LTS** through their native queued public APIs. It adds no production dependency. The [protocol](protocol.md) fixes numeric work, exact output validation, timing, warmup, independent processes and resource scope before measurement.

The main matrix is **3 engines × 3 stage counts × 5 fresh processes = 45 results**. One frame is outstanding at a time; the frame rate measures that loop rather than saturated capacity. Direct synchronous dispatch is not mixed into this comparison. Pipecat is the multimodal peer; Camel is a Java routing comparison. Different runtime, frame metadata and scheduler costs remain included.

## Reproduce

Use a POSIX host (macOS or Linux), an actual Java 17 JDK and CPython 3.12. Run builds and measurements serially on the same host. Choose a new output folder; earlier evidence is never silently overwritten.

```sh
# JAVA_HOME must point to a Java 17 JDK.
./mvnw -B -ntp -pl ominicat-core -am install -DskipTests
./mvnw -B -ntp -f comparison/benchmarks/pom.xml clean verify
python3.12 -m venv reports/local/framework-env
reports/local/framework-env/bin/python -m pip install -r comparison/requirements.lock
reports/local/framework-env/bin/python comparison/run.py \
  --java "$JAVA_HOME/bin/java" \
  --python reports/local/framework-env/bin/python \
  --output reports/local/framework-run
reports/local/framework-env/bin/python comparison/report.py \
  reports/local/framework-run reports/comparison/my-java17-run
```

Every fresh process warms for 3 seconds and measures for 5 seconds. With startup, the complete matrix takes several minutes. No provider credentials, model calls or network operations are needed during timed execution. Initial dependency installation uses Maven Central and PyPI. Optional Pipecat RTVI, turn tracking, metrics, usage, heartbeat and tracing features are disabled explicitly; ordinary queued stage behavior and the native output event hook remain enabled.

A quick wiring check uses the same collector, with one repetition, stage 4, `--warmup 0.2 --measure 0.3`. It validates native APIs and cleanup but the publisher refuses to turn smoke durations or incomplete matrices into headline figures.

## Evidence and failure behavior

- The collector records the exact source tree and artifact hashes, requested settings, rotated execution order, selected runtime versions, installed Python dependency inventory and installed Pipecat source hashes. Source, artifact and dependency state are checked again after collection.
- The terminal oracle checks every numeric output, including sequence, transformed value, phase, original timer binding and sample position. Failed, duplicated, missing, rejected or unclean outputs fail the entire run.
- A 16 MiB pre-touched primitive buffer stores at most 2,000,000 exact latency samples. Sample overflow fails rather than truncating a successful run. Nearest-rank quantiles are calculated after timing.
- The parent process uses the same OS RSS sampler at 50 ms for every framework. Whole-process and measurement-phase peaks are distinct; missing samples never become zero. RSS includes JVM/Python, dependencies and the common buffer. It is not B/op or retained heap.
- Results from all 45 processes must exist with unique, unmodified raw files and one shared manifest. Missing/duplicate cases, changed manifests, mismatched versions, invalid durations, nonfinite numbers, failed cleanup and inconsistent rates prevent publication. Five-process ranges are not confidence intervals.
- Only final JSON, manifests, figures, report and checksums are published. Private diagnostic logs, environments, JARs, raw JFR and Python profiler dumps remain local.

The shared-host report does not claim universal superiority, equivalent lifecycle/backpressure behavior, audio quality, saturated capacity or production sizing. Profiler runs are collected separately and do not replace unprofiled timings. Existing synchronous JMH and large concurrent-load results retain their original scope.

## Separate diagnostic profiles

After the unprofiled matrix finishes, run these additional instrumented passes:

```sh
reports/local/framework-env/bin/python comparison/profile_java.py \
  --java "$JAVA_HOME/bin/java" --output reports/local/framework-java-profiles
reports/local/framework-env/bin/python comparison/profile_python.py \
  --output reports/local/framework-python-profile
reports/local/framework-env/bin/python comparison/package_diagnostics.py \
  --suite reports/local/framework-run \
  --java-input reports/local/framework-java-profiles \
  --python-input reports/local/framework-python-profile \
  --output reports/comparison/my-java17-run/profiles
# Refresh the report links and all-file checksums after adding profiles:
reports/local/framework-env/bin/python comparison/report.py \
  reports/local/framework-run reports/comparison/my-java17-run
```

Java JFR covers the whole diagnostic process, including startup and warmup. Pipecat cProfile captures only the measured event-loop phase. Their charts explain their own observations; call self time, native CPU samples and allocation weights do not share a comparison axis. The publisher checks capture flags, source/artifact binding, allowlisted file hashes and privacy before copying. Published instrumented results are explicitly ineligible for the main comparison. Raw recordings, private paths and diagnostic logs are excluded.

## Linux CI

The [comparison workflow](../.github/workflows/framework-comparison.yml) runs automatically on pull requests and protected-main pushes that change the kernel, comparison tooling or its build/workflow inputs. It also supports manual dispatch. Each target runs the complete **45-process matrix**, then separate JFR/cProfile diagnostics, report validation and allowlisted publication.

| Target | Java / Python | Execution environment |
| --- | --- | --- |
| Ubuntu 24.04 x86_64 | Temurin 17 / CPython 3.12 | Native GitHub-hosted Ubuntu runner |
| CentOS Stream 9 x86_64 | Distribution OpenJDK 17 / CPython 3.12 RPMs | Digest-pinned official CentOS container on an Ubuntu 24.04 runner; shared runner kernel |

The preflight checks the actual `/etc/os-release`, architecture and declared execution target. The manifest binds this context to the measured checkout and records CPU model, libc, affinity, readable cgroup-v2 limits, actual runtime versions and the workflow run URL. OS-reported host memory is separate from a container memory limit. Null resource limits mean unlimited or unavailable. This is checked execution metadata, not remote attestation.

Each job's summary shows its validated four-stage medians. Download its `framework-<target>-java17-<run>-<attempt>` artifact for all SVG/PNG figures, every raw result, source/runtime/dependency manifest, checksums and separate CPU/allocation/GC or call profiles. Artifacts are retained for 30 days; raw recordings, environments and private logs are excluded. A failed matrix or diagnostic publication never uploads a successful performance artifact.

Every job measures the three frameworks sequentially on the same runner. The two platform jobs use independent hosts, so their results are separate observations, not a controlled experiment isolating distribution or kernel differences. CentOS coverage validates its user space and native dependencies, not a CentOS kernel. Hosted-runner variation does not automatically replace the recorded Mac baseline or establish a performance threshold.

## Checks

```sh
python3 -m unittest discover -s comparison/tests -v
python3 -m unittest discover -s comparison/pipecat -p 'test_*.py' -v
python3 -m unittest discover -s comparison/profile_tests -p 'test_*.py' -v
```

The standalone Java project includes native tests for both engines. The dependency-free Python tests cover the arithmetic/output oracle, evidence and Linux-provenance failure controls; native Pipecat behavior is separately checked by the actual process run. CI collects its own Linux evidence without changing recorded local headline results.

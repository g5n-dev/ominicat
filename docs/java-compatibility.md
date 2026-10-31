# Java compatibility

Java 17 is the minimum build and runtime version for all modules. The Maven compiler targets Java 17 bytecode (`--release 17`); no preview features or Java 21 runtime are required. The same distribution runs on Java 17, 21 and 25.

| Runtime | Session and branch workers | Build target |
| --- | --- | --- |
| Java 17 | Named daemon platform threads | Java 17 |
| Java 21 | Virtual threads | Java 17 |
| Java 25 | Virtual threads | Java 17 |

`WorkerThreads` discovers the public virtual-thread factory on Java 21+ once during initialization. Thread creation then uses an ordinary `ThreadFactory`; frame dispatch does not use reflection. On Java 17 it uses platform-thread factories directly. Deadline schedulers always use platform threads. The [virtual-thread API was finalized in Java 21](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html).

Each session still has one worker and a bounded ingress queue. Branch executors have explicit parallelism and queue limits on every JDK. Cancellation generations, ordered processing, reverse cleanup and admission retained until worker termination apply to both backends.

On Java 17, session workers consume native platform threads and their stacks. Choose session limits for the selected JDK and workload; virtual-thread measurements do not establish Java 17 capacity. Custom processors must respond to interruption on every runtime.

## Build and run

Select a JDK 17 installation through `JAVA_HOME` and `PATH`, then run:

```sh
./mvnw -B -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify
java -jar ominicat-server/target/ominicat-server.jar --config config/demo.yaml
```

The default container builds and runs on Java 17:

```sh
docker build -t ominicat .
docker run --rm -p 127.0.0.1:8080:8080 -e OMINICAT_TOKEN=your-runtime-token ominicat
```

Use `--build-arg JAVA_VERSION=21` to build the same sources into a Java 21 container. Provider APIs, YAML and wire frames are shared across these runtimes.

CI runs the complete verification suite and the standalone validation drivers on Java 17, 21 and 25. The container job checks the Java 17 default image. All five checks are required by protected `main`; there is no documentation-path bypass.

## Evidence

The [Java 17 compatibility report](../reports/java17/README.md) records the completed checks, actual JDK versions and artifact scope. The existing [baseline](../reports/baseline/report.md) and [deep verification](../reports/deep/README.md) retain their original Java 21 sources and distribution hashes. Those historical measurements do not describe the changed worker implementation or Java 17 capacity. Reproduce profiles with the JDK used for deployment.

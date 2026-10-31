# Interruptible HTTP response bodies on Java 17

Recorded on 2026-10-31. A held-open HTTP response could keep a Java 17 session worker blocked after cancellation. The repair replaces the response-body reader with an interruptible bridge over the public HTTP/Flow API. The clean Java 17 repository build passed **256 tests**, with zero failures, errors or skips. All service inputs below are generated local fixtures.

## Failure and reproduction

The inspected OpenJDK 17 body-reader implementation retries its queue wait after interruption. In the [Java 17 control](probe-java17-before.txt), the worker was confirmed inside the body read, remained waiting after 500 ms, and no longer had its interrupt flag set. Releasing the peer then allowed it to exit. The corresponding [Java 21 control](probe-java21-before.txt) exited while the body remained held open. These controls compare cancellation behavior, not runtime performance.

The earlier test waited for response headers before interrupting. That allowed interruption to arrive before the worker entered the blocking body read, so the test could pass without exercising the defect. The replacement regressions confirm the worker is blocked in the body-queue read before interruption; the peer keeps the body open independently of the worker-completion assertion.

Against the original implementation:

- The [text-stream and binary-speech controls](before-provider-java17.txt) both timed out: two tests, **two errors**.
- The [native WebSocket controls](native-before-java17.json) produced **one failure and one error**. They cover processing the next generation on the same socket after interruption, and releasing the worker/admission after disconnection while the provider body remains held open.

The implementation reference is [OpenJDK 17's response subscriber source](https://github.com/openjdk/jdk/blob/jdk-17%2B35/src/java.net.http/share/classes/jdk/internal/net/http/ResponseSubscribers.java). The source is linked for diagnosis; no JDK implementation is reproduced in this report.

## Repair and boundaries

`CompatibleClient` uses `BodyHandlers.ofPublisher()` and a private `PublisherInputStream` bridge:

- One upstream request admits one complete `List<ByteBuffer>` batch. The next request occurs only on a subsequent read after that whole batch has drained. A two-slot queue reserves room for one data batch and a terminal notification. This bounds batch count, not arbitrary payload bytes; the existing raw-byte budget remains in force.
- A blocked or already-interrupted read cancels upstream, restores the interrupt flag, and throws `InterruptedIOException`. It does not turn cancellation into EOF or permit a fallback request to mistake cancellation for an ordinary provider error.
- Close marks the stream closed, drops buffered references and wakes the reader locally. Late data is ignored. EOF and upstream errors remain terminal; previously accepted data retains its ordering.
- Queue waits and subscription `request/cancel` calls occur outside the short state lock. The bridge creates **no extra response-reading thread, polling loop or interruption-monitor thread**. The existing shared deadline scheduler and raw-response limits remain intact.

The [Java 17 body-subscriber contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscriber.html) transfers the delivered buffers to the subscriber, permitting retention without copying their contents. The [subscription contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Subscription.html) permits late callbacks after cancellation and does not guarantee a subsequent terminal callback; therefore cancellation cannot rely on upstream to wake the reader. Cancelling an unfinished HTTP body can also prevent connection reuse.

The bridge has one reader, as used by the adapters. Cancellation remains cooperative for other processor work and cannot revoke an external effect already entered.

## Completed verification

| Evidence | Scope | Result |
| --- | --- | --- |
| [Clean Java 17 build](build-java17.txt), [counters](verification.json) | Core 89 + conversation 40 + providers 67 + server 60 | **256 passed**, no failures/errors/skips |
| [Isolated Java 17 and 21 module verification](domain-verification.json) | On each JDK: core 89 + conversation 40 + providers 67 | **196 passed per JDK**, no failures/errors/skips |
| [Isolated Java 17 and 21 server suites](server-full-suites.json) | Complete server module on each JDK | **60 passed per JDK**, no failures/errors/skips |
| [Java 17 native regressions](native-after-java17.json) | The two held-open-body socket cases above | **2 passed** |
| [Java 21 native regressions](native-after-java21.json) | The same two socket cases | **2 passed** |
| Provider bridge semantics, included in the provider totals | Nine methods: whole-batch demand, no prefetch, ordered data/EOF/error, repeated close, interruption, late/duplicate subscription, failed demand, overproduction and cross-thread subscription callbacks | **9 passed on each JDK** |
| [Java 17 sustained SSE fixture](sse-streaming.json) | 100 generated chunks/s, held-open body | **6,000 ordered Unicode chunks in 60.071 s**; every chunk observed before the next write; DONE completed without peer EOF |

These are distinct scopes: a complete Java 17 build, isolated module checks, focused native regressions, and a paced local HTTP stream. The 60-second stream is not a full-package stability or capacity test. No live-model or external-provider result is claimed.

## Exact-artifact mixed-media resource run

The repaired distribution identified below completed a separate paced local echo run: **10 seconds of warmup followed by 120.127 measured seconds**, with 64 persistent clients plus four churn workers, a 256 MiB heap limit and a 128 MiB direct-memory limit. It validated **138,239 mixed-media frames** and **4,377 new connections**, with zero unexpected failures. All eleven predeclared checks passed; every client progressed. See the [summary](soak-summary.json), [criteria](soak-criteria.json), [numeric samples](soak-samples.jsonl) and [resource timeline](soak-resources.svg).

| Observation | After warmup | After drain |
| --- | ---: | ---: |
| Heap used after requested full GC | 13,303,592 bytes | 8,173,240 bytes |
| Netty direct reservation | 16 MiB | 16 MiB |
| Open file descriptors | 96 | 32 |
| Platform threads | 80 | 16 |

Active sessions, retained admission, live pipeline workers and pending output bytes all reached zero after drain. The metrics request itself accounts for the remaining transport connection. [Diagnostics](resource-diagnostics.json) recorded zero Netty leak warnings and 496,290 KiB of NMT committed memory, which includes JVM/native/profiling costs and is neither RSS nor a 256 MiB total-process claim. The [JFR summary](soak-jfr-summary.txt) spans 303 seconds of whole-process execution, not 303 measured seconds of traffic.

This generated echo workload covers text, PCM audio, transcripts, images and envelopes; it is a resource/correctness probe, not a saturation benchmark or a live compatible-service check. The affected HTTP-body path is exercised separately by the provider suites, held-open native regressions and 60-second SSE fixture above. A two-minute run and selected resource checks do not establish multi-day stability, freedom from all leaks or a production SLA.

## Artifact provenance and reproduction

The [artifact delta manifest](artifact-delta.json) identifies the repaired distribution:

```text
9601f5104aef55dae06bcbf7e09a0284d0362455f3894eddeaedc9763d39c9ba
```

The prior distribution was `370e4e056d2336526ffe5f4975470671b195235eeb43623efed873bc2e32883e`. Exactly three existing `CompatibleClient` class-file entries changed, and `PublisherInputStream` plus its `Item` record were added. All **73 core/conversation/server classes** are byte-for-byte unchanged. Kernel benchmark sources are unchanged; the [existing kernel measurements](../kernel/report.md) retain their original scope and do not measure this HTTP bridge, network latency or model inference.

The 120.127-second echo resource run above applies to this exact artifact. Historical runs for other binaries are not transferred to it. The [manifest](artifact-delta.json) and separate test/resource evidence retain those boundaries; no external-provider success result is claimed.

For the complete Java 17 verification, select a real JDK 17 using `JAVA_HOME` and run in an isolated checkout:

```sh
./mvnw -B -Pcoverage,sbom -Dominicat.stress.seeds=512 clean verify
```

The [isolated module evidence](domain-verification.json) retains the focused commands, runtime versions and source hashes. The Java 21 checks establish the observed cancellation behavior on that tested runtime; the separate resource run remains scoped to the identified Java 17 artifact.

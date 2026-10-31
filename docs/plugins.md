# Add a plugin

A plugin depends on `io.ominicat:ominicat-core:0.1.0-SNAPSHOT`. Build and install this checkout first with `./mvnw install`; artifacts are not yet published to Maven Central.

```java
package example;

import io.ominicat.core.*;
import io.ominicat.core.spi.ProcessorFactory;
import java.util.Map;

public final class PrefixFactory implements ProcessorFactory {
    public String type() { return "prefix"; }
    public Processor create(Map<String, String> options) {
        String prefix = options.getOrDefault("value", "[event] ");
        return (frame, next) -> {
            if (frame instanceof Frame.Text text) next.accept(new Frame.Text(prefix + text.text()));
            else next.accept(frame);
        };
    }
}
```

Add this line to `META-INF/services/io.ominicat.core.spi.ProcessorFactory` in the plugin JAR:

```text
example.PrefixFactory
```

Place the plugin and its dependencies on the application's classpath. To extend the standalone shaded distribution:

```bash
java -cp 'ominicat-server/target/ominicat-server.jar:plugins/*' \
  io.ominicat.server.OminiCat --config config/custom.yaml
```

```yaml
stages:
  - type: prefix
    options:
      value: "[event] "
```

The registry rejects duplicate plugin names. Factories must create fresh processor state per session, reject unknown/invalid options and release resources in `close()`. The server performs create/close preflight before binding. Avoid network requests during construction; perform owned I/O during processing/startup callbacks instead.

## Streaming callbacks

Implement `AsyncProcessor` when a provider returns results on its own threads. `processAsync` starts or feeds the operation and returns promptly. Retain its `AsyncEmitter`, not a synchronous `FrameSink`:

```java
if (!emitter.tryEmit(transcript)) {
    // Explicit backpressure: pause the source or terminate this operation.
}
// When no further callbacks can arrive for this invocation:
emitter.complete();
```

Register a provider cancellation handle with `emitter.onCancel(...)` during the initial invocation, propagate provider errors with `fail(...)`, and enforce a provider deadline. Finalizing an emitter is idempotent. Late emissions after interruption/completion are rejected. The kernel schedules accepted outputs at the next stage on the session worker.

Capabilities vary by adapter. Declare them in the plugin's documentation: input/output formats, streaming direction, channel mapping, timestamp origin, partial/final semantics, limits, deadlines and cancellation behavior. A file-transcription HTTP endpoint must not be advertised as a streaming ASR connection.

## Composition

Use `ParallelProcessor` for independent branches with bounded ordered join, `FallbackProcessor` for pre-output failover, and `AsyncOutlet` for an independently bounded destination. Resource cleanup remains owned by the composing node. Persistent acknowledgment, deduplication and retry policy belong in a destination plugin that uses envelope IDs and external storage.

See [architecture](architecture.md), [configuration](configuration.md), [wire protocol](protocol.md) and the [provider reference](../ominicat-providers/README.md).

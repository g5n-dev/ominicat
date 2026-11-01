<!-- ominicat-language-nav -->
[简体中文](zh-CN/plugin-capabilities.md) · **English**
<!-- /ominicat-language-nav -->

# Optional plugin capability declarations

I1a adds opt-in declaration lookup and explicit requirement checks. It preserves the existing factory methods, ServiceLoader entries, YAML, frame protocol and Studio metadata. This is a small part of the [integration plan](integration-contracts.md), not a universal compatibility or service-readiness result.

## Query before creating processors

Use Java 17+ and the current checkout. Save the following as `CapabilityCheck.java` at the repository root:

```java
import io.ominicat.core.spi.PluginRegistry;
import io.ominicat.providers.TranscriptionFactory;
import java.util.Map;
import java.util.Set;

public class CapabilityCheck {
    public static void main(String[] args) {
        var plugins = new PluginRegistry();
        var options = Map.<String, String>of();
        var description = plugins.describe("openai-transcribe", options).orElseThrow();
        System.out.println(description.capabilities());
        String required = args.length == 0
                ? TranscriptionFactory.UTTERANCE_FINALS
                : TranscriptionFactory.PRE_BOUNDARY_PARTIALS;
        plugins.requireDeclaredCapabilities("openai-transcribe", options, Set.of(required));
        System.out.println("Declared requirement accepted");
    }
}
```

Build the bundled server JAR, then run the passing utterance-final check and the deliberately rejected partial-result check:

```sh
./mvnw -pl ominicat-server -am package -DskipTests
java --class-path ominicat-server/target/ominicat-server.jar CapabilityCheck.java
java --class-path ominicat-server/target/ominicat-server.jar CapabilityCheck.java partials
```

The first invocation accepts the declared capability. The second exits with an unsupported-capability error. With the bundled factories, neither query creates a processor, resolves provider credentials or sends a provider request. Empty options are sufficient for this declaration lookup; they are not sufficient proof of a runnable provider configuration.

## Publish bounded declarations

A factory may implement `DescribedProcessorFactory` in addition to the unchanged `ProcessorFactory` contract. Keep registering it under `META-INF/services/io.ominicat.core.spi.ProcessorFactory`. Implement `describe(Map<String, String>)` using the supplied immutable options snapshot; return the owning factory's type. For example, a factory importing `ProcessorDescription` and `java.util.Optional` can provide:

```java
public ProcessorDescription describe(Map<String, String> options) {
    return new ProcessorDescription(type(), Optional.empty(),
            Map.of("example.prefix.v1", ProcessorDescription.Support.SUPPORTED));
}
```

| Field | Contract |
| --- | --- |
| `type` | The registered factory identity; mismatched description types are rejected. |
| `implementationVersion` | `Optional.empty()` means unknown. A present value is nonblank and at most 128 characters; a label does not prove artifact identity or compatible behavior. |
| `capabilities` | An immutable defensive copy, at most 128 entries. Namespaced IDs match `[a-z][a-z0-9]*(?:[._:-][a-z0-9]+)+`, at most 128 characters. Keys and support values cannot be null. |
| `ProcessorDescription.Support` | `SUPPORTED`, `UNSUPPORTED` or `UNKNOWN`. A missing entry is unknown, not unsupported or supported. |
| `support(capability)` | Returns the declared state or unknown for an absent entry; validates the capability ID. |

A legacy factory produces `Optional.empty()` from `PluginRegistry.describe(type, options)`. An unregistered type remains an error. A present description may still omit a requested capability or explicitly declare it unknown. `requireDeclaredCapabilities(type, options, required)` accepts only declared support, distinguishing unknown from unsupported rejection; the required set is also limited to 128 entries. Call it explicitly before starting work; ordinary `create` callers acquire no new requirement checks.

## Transcription profiles

| Constant | Capability ID and meaning | Built-in declaration |
| --- | --- | --- |
| `TranscriptionFactory.UTTERANCE_FINALS` | `io.ominicat.transcription.utterance-finals.v1`: buffered utterance transcription with final results triggered by explicit boundaries. | `SUPPORTED` |
| `TranscriptionFactory.PRE_BOUNDARY_PARTIALS` | `io.ominicat.transcription.pre-boundary-partials.v1`: non-final transcripts before an explicit boundary, while subsequent PCM input remains accepted. | `UNSUPPORTED` |

The built-in file-transcription adapter buffers audio and flushes on `TURN_END`, `SPEECH_STOPPED` or `END`; it does not emit partial transcripts before those boundaries. The incremental profile requires an actual non-final `Frame.Transcript` before a boundary and acceptance of later `Frame.Audio` while the utterance remains open. Audio chunk input, chat SSE output and the `AsyncProcessor` interface alone do not satisfy it. These identifiers describe adapter behavior, not remote model accuracy or availability.

## Compatibility and trust boundaries

Descriptions depend on options and are not cached by plugin type. Querying the same factory with different options must obtain the corresponding declaration. Use the same immutable options snapshot for requirements and creation, for example with `Map.copyOf(options)`. A successful check does not bind a later call to the checked settings: changing a mutable map before `create` changes the configuration being created. Metadata is not full configuration validation: the built-in transcription method checks allowed option names but does not parse numeric values, require a model or resolve credential/endpoint values. An invalid `maxAudioBytes` may therefore still fail later during `create` or server preflight.

Description methods must not create processors, start owned workers, resolve credentials, perform network I/O or return secret option values. The registry does not call `create`, but cannot sandbox third-party constructors or description implementations. ServiceLoader can instantiate a factory before lookup. Keep declarations small and immutable; do not retain media, processor instances or callbacks in them.

A supported declaration does not grant access, verify a provider, choose a model or certify a behavior probe. Implementation versions can remain unknown; there is no verified flag. Session creation still creates independent state and server preflight still creates/closes configured processors. No default wire/configuration behavior or per-frame scheduling changes.

## Acceptance and remaining scope

The [frozen legacy JAR](../ominicat-core/src/test/resources/legacy-plugin/legacy-plugin.jar), [baseline/compiler binding](../ominicat-core/src/test/resources/legacy-plugin/metadata.json) and [source fixture](../ominicat-core/src/test/fixtures/legacy-plugin/LegacyFactory.java) were built against the pre-description core at d6698fa with Java 17. The [maintainer rebuild tool](../scripts/build_legacy_plugin.py) reads that frozen revision; normal CI loads the stored JAR without recompiling it. [Binary compatibility tests](../ominicat-core/src/test/java/io/ominicat/core/spi/LegacyBinaryPluginTest.java) check class version, artifact hash, isolated state and cleanup; [local behavior tests](../ominicat-providers/src/test/java/io/ominicat/providers/TranscriptionDescriptionTest.java) separate declarations from observed transcription delivery.


- Load a pre-change Java 17 plugin JAR without recompilation on Java 17, 21 and 25; preserve unknown metadata, ordinary creation, distinct state and cleanup.
- Exercise invalid descriptor IDs, bounds, null values, type mismatches and attempted map mutation. Querying must not call `ProcessorFactory.create`, and explicit unknown/unsupported requirements must fail before processor creation.
- Verify the built-in declarations with no configured provider credentials and no HTTP requests. Keep full option validation separate from metadata lookup.
- Use local scripted positive/negative behavior fixtures: observe a pre-boundary partial followed by more PCM, and reject a boundary-only final implementation for that profile. These are local conformance checks, not live ASR validation.

Rich option schemas, frame/schema and media formats, time contracts, the wider I1 fixture families, I2/I3 and T2 remain planned. See [plugin authoring](plugins.md), the [integration contracts](integration-contracts.md) and the [iteration plan](iteration-plan.md).

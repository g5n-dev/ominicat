package io.ominicat.core.spi;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Processor;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class PluginCapabilityTest {
    private static final String CAPABILITY = "acme.asr:partial-before-turn-end.v1";
    private static final String SECRET = "private-value-never-publish";

    @Test void legacyFactoryRemainsUnknownAndEmptyRequirementsDoNotCreateProcessors() {
        PluginRegistry registry = new PluginRegistry();
        AtomicInteger creations = new AtomicInteger();
        registry.register(new ProcessorFactory() {
            @Override public String type() { return "legacy-capability-fixture"; }
            @Override public Processor create(Map<String, String> options) {
                creations.incrementAndGet();
                return (frame, next) -> next.accept(frame);
            }
        });
        assertTrue(registry.describe("legacy-capability-fixture", Map.of()).isEmpty());
        assertDoesNotThrow(() -> registry.requireDeclaredCapabilities("legacy-capability-fixture", Map.of(), Set.of()));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> registry.requireDeclaredCapabilities("legacy-capability-fixture",
                        Map.of("privateOption", SECRET), Set.of(CAPABILITY)));
        assertCapabilityRejection(rejected, "UNKNOWN");
        assertEquals(0, creations.get());
        assertNotNull(registry.create("legacy-capability-fixture", Map.of()));
        assertEquals(1, creations.get());
    }

    @Test void declarationChecksNeitherCreateNorProbeTheDeclaredBehavior() {
        PluginRegistry registry = new PluginRegistry();
        AtomicInteger descriptions = new AtomicInteger();
        registry.register(described("declared-only", options -> {
            descriptions.incrementAndGet();
            return description("declared-only", ProcessorDescription.Support.SUPPORTED);
        }));
        ProcessorDescription declared = registry.describe("declared-only", Map.of()).orElseThrow();
        assertEquals(ProcessorDescription.Support.SUPPORTED, declared.support(CAPABILITY));
        assertDoesNotThrow(() -> registry.requireDeclaredCapabilities("declared-only", Map.of(), Set.of(CAPABILITY)));
        assertTrue(descriptions.get() >= 1);
        // The helper's create throws: metadata acceptance cannot be mistaken for a behavior probe.
    }

    @Test void metadataReceivesAnImmutableIndependentOptionsSnapshot() {
        PluginRegistry registry = new PluginRegistry();
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        registry.register(described("option-snapshot", options -> {
            observed.set(options);
            assertThrows(UnsupportedOperationException.class, () -> options.put("mode", "changed"));
            return description("option-snapshot", ProcessorDescription.Support.UNKNOWN);
        }));
        Map<String, String> source = new HashMap<>(Map.of("mode", "stream", "privateOption", SECRET));
        registry.describe("option-snapshot", source);
        source.put("mode", "utterance");
        source.remove("privateOption");
        assertEquals(Map.of("mode", "stream", "privateOption", SECRET), observed.get());
    }

    @Test void configuredModesAreQueriedIndependentlyWithoutCrossConfigurationCaching() {
        PluginRegistry registry = new PluginRegistry();
        AtomicInteger descriptions = new AtomicInteger();
        registry.register(described("configured-capability", options -> {
            descriptions.incrementAndGet();
            return description("configured-capability", switch (options.getOrDefault("mode", "unknown")) {
                case "stream" -> ProcessorDescription.Support.SUPPORTED;
                case "utterance" -> ProcessorDescription.Support.UNSUPPORTED;
                default -> ProcessorDescription.Support.UNKNOWN;
            });
        }));
        assertEquals(ProcessorDescription.Support.UNSUPPORTED,
                registry.describe("configured-capability", Map.of("mode", "utterance")).orElseThrow().support(CAPABILITY));
        assertEquals(ProcessorDescription.Support.SUPPORTED,
                registry.describe("configured-capability", Map.of("mode", "stream")).orElseThrow().support(CAPABILITY));
        assertEquals(2, descriptions.get());
        assertDoesNotThrow(() -> registry.requireDeclaredCapabilities("configured-capability",
                Map.of("mode", "stream"), Set.of(CAPABILITY)));
        assertCapabilityRejection(assertThrows(IllegalArgumentException.class,
                () -> registry.requireDeclaredCapabilities("configured-capability",
                        Map.of("mode", "utterance", "privateOption", SECRET), Set.of(CAPABILITY))), "UNSUPPORTED");
        assertCapabilityRejection(assertThrows(IllegalArgumentException.class,
                () -> registry.requireDeclaredCapabilities("configured-capability",
                        Map.of("privateOption", SECRET), Set.of(CAPABILITY))), "UNKNOWN");
    }

    @Test void omittedAndExplicitUnknownClaimsBothRejectAnExplicitRequirement() {
        for (Map<String, ProcessorDescription.Support> claims : java.util.List.of(
                Map.<String, ProcessorDescription.Support>of(),
                Map.of(CAPABILITY, ProcessorDescription.Support.UNKNOWN))) {
            PluginRegistry registry = new PluginRegistry();
            registry.register(described("unknown-claim", options ->
                    new ProcessorDescription("unknown-claim", Optional.empty(), claims)));
            assertCapabilityRejection(assertThrows(IllegalArgumentException.class,
                    () -> registry.requireDeclaredCapabilities("unknown-claim",
                            Map.of("privateOption", SECRET), Set.of(CAPABILITY))), "UNKNOWN");
        }
    }

    @Test void mismatchedDescriptionTypeDoesNotExposeTheReturnedValue() {
        assertSanitizedMetadataFailure(options -> new ProcessorDescription(SECRET, Optional.empty(), Map.of()));
    }

    @Test void nullMetadataDoesNotBecomeLegacyUnknownOrExposeOptions() {
        assertSanitizedMetadataFailure(options -> null);
    }

    @Test void metadataRuntimeFailuresLoseTheirMessageCauseAndSuppressedDetails() {
        assertSanitizedMetadataFailure(options -> {
            IllegalStateException failure = new IllegalStateException(SECRET, new IllegalArgumentException(SECRET));
            failure.addSuppressed(new RuntimeException(SECRET));
            throw failure;
        });
    }

    @Test void anUnknownInstalledTypeIsNotTreatedAsALegacyFactory() {
        PluginRegistry registry = new PluginRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.describe("missing-capability-fixture", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> registry.requireDeclaredCapabilities("missing-capability-fixture", Map.of(), Set.of()));
    }

    @Test void malformedRequiredCapabilityIdsAreRejectedRatherThanTreatedAsUnknown() {
        PluginRegistry registry = new PluginRegistry();
        registry.register(described("requirement-syntax", options ->
                description("requirement-syntax", ProcessorDescription.Support.SUPPORTED)));
        for (String malformed : new String[]{"bare", "Acme:stream", "acme::stream", "a:" + "x".repeat(127)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.requireDeclaredCapabilities("requirement-syntax", Map.of(), Set.of(malformed)));
        }
    }

    @Test void requirementCountIsBoundedBeforeInvokingPluginMetadata() {
        PluginRegistry registry = new PluginRegistry();
        Map<String, ProcessorDescription.Support> claims = new HashMap<>();
        for (int index = 0; index < 128; index++) claims.put("acme:cap-" + index, ProcessorDescription.Support.SUPPORTED);
        AtomicInteger queries = new AtomicInteger();
        registry.register(described("bounded-requirements", options -> {
            queries.incrementAndGet();
            return new ProcessorDescription("bounded-requirements", Optional.empty(), claims);
        }));
        assertDoesNotThrow(() -> registry.requireDeclaredCapabilities("bounded-requirements", Map.of(), claims.keySet()));
        assertEquals(1, queries.get());
        Set<String> overflow = new java.util.HashSet<>(claims.keySet());
        overflow.add("acme:overflow");
        assertThrows(IllegalArgumentException.class,
                () -> registry.requireDeclaredCapabilities("bounded-requirements", Map.of(), overflow));
        assertEquals(1, queries.get(), "oversized requirements must not invoke plugin metadata");
    }

    @Test void metadataCallbackCanWaitForAnotherThreadToRegisterWithoutHoldingTheRegistryMonitor() throws Exception {
        PluginRegistry registry = new PluginRegistry();
        CountDownLatch registered = new CountDownLatch(1);
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        AtomicReference<Thread> helper = new AtomicReference<>();
        registry.register(described("reentrant-description", options -> {
            assertFalse(Thread.holdsLock(registry), "plugin metadata must run outside the registry monitor");
            Thread registrar = new Thread(() -> {
                try { registry.register(described("registered-from-description", ignored ->
                        description("registered-from-description", ProcessorDescription.Support.UNKNOWN))); }
                catch (Throwable failure) { registrationFailure.set(failure); }
                finally { registered.countDown(); }
            }, "test-description-registration");
            registrar.setDaemon(true);
            helper.set(registrar);
            registrar.start();
            try { assertTrue(registered.await(2, TimeUnit.SECONDS), "registry registration blocked behind metadata"); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("metadata wait interrupted", interrupted);
            }
            assertNull(registrationFailure.get());
            return description("reentrant-description", ProcessorDescription.Support.SUPPORTED);
        }));
        try {
            assertEquals(ProcessorDescription.Support.SUPPORTED,
                    registry.describe("reentrant-description", Map.of()).orElseThrow().support(CAPABILITY));
            assertTrue(registry.types().contains("registered-from-description"));
        } finally {
            Thread registrar = helper.get();
            if (registrar != null) {
                registrar.interrupt();
                registrar.join(2000);
                assertFalse(registrar.isAlive(), "metadata registration helper did not terminate");
            }
        }
    }

    private static DescribedProcessorFactory described(String type,
            Function<Map<String, String>, ProcessorDescription> describe) {
        return new DescribedProcessorFactory() {
            @Override public String type() { return type; }
            @Override public ProcessorDescription describe(Map<String, String> options) { return describe.apply(options); }
            @Override public Processor create(Map<String, String> options) {
                throw new AssertionError("description lookup must not create a processor");
            }
        };
    }

    private static ProcessorDescription description(String type, ProcessorDescription.Support support) {
        return new ProcessorDescription(type, Optional.of("fixture-1"), Map.of(CAPABILITY, support));
    }

    private static void assertSanitizedMetadataFailure(Function<Map<String, String>, ProcessorDescription> metadata) {
        PluginRegistry registry = new PluginRegistry();
        registry.register(described("invalid-description", metadata));
        for (boolean require : new boolean[]{false, true}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                if (require) registry.requireDeclaredCapabilities("invalid-description",
                        Map.of("privateOption", SECRET), Set.of(CAPABILITY));
                else registry.describe("invalid-description", Map.of("privateOption", SECRET));
            });
            assertFalse(failure.toString().contains(SECRET));
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
        }
    }

    private static void assertCapabilityRejection(IllegalArgumentException failure, String support) {
        assertTrue(failure.getMessage().contains(CAPABILITY));
        assertTrue(failure.getMessage().contains(support));
        assertFalse(failure.toString().contains(SECRET));
        assertNull(failure.getCause());
    }
}

package io.ominicat.core.spi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProcessorDescriptionTest {
    private static final String CAPABILITY = "acme.asr:partial-before-turn-end.v1";

    @Test void preservesExplicitSupportUnknownVersionAndUnspecifiedCapabilities() {
        ProcessorDescription description = new ProcessorDescription("acme-asr", Optional.empty(), Map.of(
                CAPABILITY, ProcessorDescription.Support.SUPPORTED,
                "acme:final-output.v1", ProcessorDescription.Support.UNSUPPORTED,
                "acme:word-times.v1", ProcessorDescription.Support.UNKNOWN));
        assertEquals("acme-asr", description.type());
        assertTrue(description.implementationVersion().isEmpty());
        assertEquals(ProcessorDescription.Support.SUPPORTED, description.support(CAPABILITY));
        assertEquals(ProcessorDescription.Support.UNSUPPORTED, description.support("acme:final-output.v1"));
        assertEquals(ProcessorDescription.Support.UNKNOWN, description.support("acme:word-times.v1"));
        assertEquals(ProcessorDescription.Support.UNKNOWN, description.support("acme:not-declared.v1"));
    }

    @Test void copiesCapabilityDeclarationsAndExposesNoMutableView() {
        Map<String, ProcessorDescription.Support> source = new HashMap<>();
        source.put(CAPABILITY, ProcessorDescription.Support.SUPPORTED);
        ProcessorDescription description = new ProcessorDescription("acme-asr", Optional.of("1.0+Build"), source);
        source.put(CAPABILITY, ProcessorDescription.Support.UNSUPPORTED);
        source.put("acme:later.v1", ProcessorDescription.Support.SUPPORTED);
        assertEquals(ProcessorDescription.Support.SUPPORTED, description.support(CAPABILITY));
        assertEquals(ProcessorDescription.Support.UNKNOWN, description.support("acme:later.v1"));
        assertEquals(Optional.of("1.0+Build"), description.implementationVersion());
        assertThrows(UnsupportedOperationException.class,
                () -> description.capabilities().put(CAPABILITY, ProcessorDescription.Support.UNKNOWN));
        assertThrows(UnsupportedOperationException.class, () -> description.capabilities().clear());
    }

    @Test void enforcesExistingTypeSyntaxWithoutNarrowingLegalLegacyNames() {
        for (String type : new String[]{"a", "acme-asr.v1_test", "a".repeat(64)}) {
            assertEquals(type, description(type, Optional.empty(), Map.of()).type());
        }
        for (String type : new String[]{"", " ", "Acme", "1acme", "acme:asr", "acme/asr", "语音", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> description(type, Optional.empty(), Map.of()));
        }
    }

    @Test void versionIsOptionalOpaqueBoundedTextRatherThanAnInferredCompatibilityRule() {
        assertEquals(Optional.empty(), description("acme", Optional.empty(), Map.of()).implementationVersion());
        for (String version : new String[]{"snapshot+BuildCase", " 1.0-custom ", "v".repeat(128)}) {
            assertEquals(Optional.of(version), description("acme", Optional.of(version), Map.of()).implementationVersion());
        }
        for (String version : new String[]{"", " \t\n", "v".repeat(129)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> description("acme", Optional.of(version), Map.of()));
        }
    }

    @Test void capabilityIdsRequireNamespacedLowercaseTokensAndHaveAnExactLengthBound() {
        for (String id : new String[]{"acme.partial", "acme_partial", "acme-partial", "acme:partial",
                CAPABILITY, "a:" + "x".repeat(126)}) {
            ProcessorDescription description = description("acme", Optional.empty(),
                    Map.of(id, ProcessorDescription.Support.SUPPORTED));
            assertEquals(ProcessorDescription.Support.SUPPORTED, description.support(id));
        }
        for (String id : new String[]{"", "bare", "Acme:partial", "1acme:partial", ":partial", "acme:",
                "acme..partial", "acme:_partial", "acme:partial output", "acme:partial/one", "acme:语音",
                "a:" + "x".repeat(127)}) {
            assertThrows(IllegalArgumentException.class, () -> description("acme", Optional.empty(),
                    Map.of(id, ProcessorDescription.Support.SUPPORTED)));
        }
    }

    @Test void boundsTheDeclarationCountWithoutDroppingEntries() {
        Map<String, ProcessorDescription.Support> capabilities = new HashMap<>();
        for (int index = 0; index < 128; index++) capabilities.put("acme:cap-" + index, ProcessorDescription.Support.UNKNOWN);
        ProcessorDescription description = description("acme", Optional.empty(), capabilities);
        assertEquals(128, description.capabilities().size());
        capabilities.put("acme:overflow", ProcessorDescription.Support.SUPPORTED);
        assertThrows(IllegalArgumentException.class, () -> description("acme", Optional.empty(), capabilities));
        assertEquals(128, description.capabilities().size());
    }

    @Test void lookupDoesNotTurnMalformedCapabilityIdsIntoUnknownSupport() {
        ProcessorDescription description = description("acme", Optional.empty(), Map.of());
        assertThrows(NullPointerException.class, () -> description.support(null));
        for (String malformed : new String[]{"bare", "Acme:partial", "acme::partial", "a:" + "x".repeat(127)}) {
            assertThrows(IllegalArgumentException.class, () -> description.support(malformed));
        }
    }

    @Test void nullFieldsKeysAndSupportValuesAreProgrammingErrors() {
        assertThrows(NullPointerException.class, () -> description(null, Optional.empty(), Map.of()));
        assertThrows(NullPointerException.class, () -> description("acme", null, Map.of()));
        assertThrows(NullPointerException.class, () -> description("acme", Optional.empty(), null));
        Map<String, ProcessorDescription.Support> nullKey = new HashMap<>();
        nullKey.put(null, ProcessorDescription.Support.UNKNOWN);
        assertThrows(NullPointerException.class, () -> description("acme", Optional.empty(), nullKey));
        Map<String, ProcessorDescription.Support> nullValue = new HashMap<>();
        nullValue.put(CAPABILITY, null);
        assertThrows(NullPointerException.class, () -> description("acme", Optional.empty(), nullValue));
    }

    private static ProcessorDescription description(String type, Optional<String> version,
                                                     Map<String, ProcessorDescription.Support> capabilities) {
        return new ProcessorDescription(type, version, capabilities);
    }
}

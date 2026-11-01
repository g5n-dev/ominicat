package io.ominicat.core.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bounded immutable declarations for one configured factory, not a conformance certificate.
 * Capability identifiers are open, namespaced contracts owned by adapters/integrations.
 * Omission means unknown. Implementation versions are labels, not content fingerprints.
 */
public record ProcessorDescription(String type, Optional<String> implementationVersion,
                                   Map<String, Support> capabilities) {
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z][a-z0-9]*(?:[._:-][a-z0-9]+)+");

    /** A plugin's declaration only; none of these values means behavior was verified. */
    public enum Support { SUPPORTED, UNSUPPORTED, UNKNOWN }

    public ProcessorDescription {
        if (!TYPE.matcher(Objects.requireNonNull(type, "type")).matches()) {
            throw new IllegalArgumentException("invalid processor description type");
        }
        Objects.requireNonNull(implementationVersion, "implementationVersion");
        implementationVersion.ifPresent(version -> {
            if (version.isBlank() || version.length() > 128) {
                throw new IllegalArgumentException("implementationVersion must contain 1..128 characters when present");
            }
        });
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.size() > 128) throw new IllegalArgumentException("at most 128 capability declarations are allowed");
        capabilities = Map.copyOf(capabilities);
        if (capabilities.size() > 128) throw new IllegalArgumentException("at most 128 capability declarations are allowed");
        capabilities.forEach((id, support) -> {
            validateCapabilityId(id);
            Objects.requireNonNull(support, "capability support");
        });
    }

    /** Return declared support; a missing key remains unknown rather than unsupported. */
    public Support support(String capability) {
        validateCapabilityId(capability);
        return capabilities.getOrDefault(capability, Support.UNKNOWN);
    }

    static void validateCapabilityId(String value) {
        if (Objects.requireNonNull(value, "capability id").length() > 128 || !CAPABILITY.matcher(value).matches()) {
            throw new IllegalArgumentException("capability id must be a namespaced lowercase identifier of at most 128 characters");
        }
    }
}

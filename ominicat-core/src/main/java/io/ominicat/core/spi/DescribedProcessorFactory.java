package io.ominicat.core.spi;

import java.util.Map;

/**
 * Optional declaration contract; legacy ProcessorFactory binaries need not implement it.
 * Queries receive an immutable configuration snapshot. They must be fast and side-effect
 * free: do not construct a processor, resolve credentials or contact a provider here.
 * A declaration is not a successful behavior probe or proof of remote availability.
 */
public interface DescribedProcessorFactory extends ProcessorFactory {
    /** Describe the implementation under these options, without retaining resolved secrets. */
    ProcessorDescription describe(Map<String, String> options);
}

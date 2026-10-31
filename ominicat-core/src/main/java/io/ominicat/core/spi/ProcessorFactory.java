package io.ominicat.core.spi;

import io.ominicat.core.Processor;
import java.util.Map;

/**
 * Java ServiceLoader extension point. A provider JAR registers its implementation in
 * META-INF/services/io.ominicat.core.spi.ProcessorFactory. Factories must create a
 * fresh processor for every call; configuration values must not contain resolved secrets.
 */
public interface ProcessorFactory {
    /** Stable configuration type, such as {@code echo} or {@code acme-inspection}. */
    String type();
    /** Create a new stage from an immutable options snapshot. */
    Processor create(Map<String, String> options);
}

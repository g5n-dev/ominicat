package io.ominicat.core.spi;

import io.ominicat.core.Processor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/** Discover installed factories and instantiate configured stages without framework coupling. */
public final class PluginRegistry {
    private final Map<String, ProcessorFactory> factories = new LinkedHashMap<>();

    /** Discover factories using the current thread's context class loader. */
    public PluginRegistry() { this(Thread.currentThread().getContextClassLoader()); }

    /** Discover factories from an explicit plugin class loader. */
    public PluginRegistry(ClassLoader loader) {
        ServiceLoader.load(ProcessorFactory.class, Objects.requireNonNull(loader, "loader")).forEach(this::register);
    }

    /** Register an in-process factory. Duplicate types fail instead of silently replacing a plugin. */
    public synchronized PluginRegistry register(ProcessorFactory factory) {
        Objects.requireNonNull(factory, "factory");
        String type = Objects.requireNonNull(factory.type(), "factory type");
        if (!type.matches("[a-z][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("invalid processor type: " + type);
        }
        if (factories.containsKey(type)) throw new IllegalArgumentException("duplicate processor type: " + type);
        factories.put(type, factory);
        return this;
    }

    /** Create a fresh processor, reporting installed types when configuration names an unknown plugin. */
    public synchronized Processor create(String type, Map<String, String> options) {
        Objects.requireNonNull(type, "type");
        ProcessorFactory factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("unknown processor type '" + type + "'; installed: " + types());
        }
        return Objects.requireNonNull(factory.create(Map.copyOf(Objects.requireNonNull(options, "options"))),
                "processor factory returned null: " + type);
    }

    /** Installed configuration types in stable sorted order. */
    public synchronized Set<String> types() {
        return Collections.unmodifiableSet(new TreeSet<>(factories.keySet()));
    }
}

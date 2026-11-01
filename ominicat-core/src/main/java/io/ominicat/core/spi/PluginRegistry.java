package io.ominicat.core.spi;

import io.ominicat.core.Processor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /**
     * Query optional declarations without invoking create. Unknown legacy metadata
     * remains absent. The factory callback executes outside the registry monitor;
     * third-party constructors/callbacks must themselves honor the discovery contract.
     * No result is cached across option sets and no query is made during dispatch.
     */
    public Optional<ProcessorDescription> describe(String type, Map<String, String> options) {
        Objects.requireNonNull(type, "type");
        Map<String, String> snapshot = Map.copyOf(Objects.requireNonNull(options, "options"));
        ProcessorFactory factory;
        synchronized (this) {
            factory = factories.get(type);
            if (factory == null) {
                throw new IllegalArgumentException("unknown processor type '" + type + "'; installed: " + types());
            }
        }
        if (!(factory instanceof DescribedProcessorFactory described)) return Optional.empty();
        ProcessorDescription description;
        try {
            description = described.describe(snapshot);
            if (description == null || !type.equals(description.type())) {
                throw new IllegalArgumentException("invalid processor metadata");
            }
        } catch (RuntimeException invalid) {
            // A plugin exception can contain option values. Expose neither it nor its cause.
            throw new IllegalArgumentException("processor metadata rejected for type '" + type + "'");
        }
        return Optional.of(description);
    }

    /**
     * Explicit opt-in declaration check. Unknown and unsupported requirements reject
     * before processor creation. Success accepts declarations only, not conformance.
     * Ordinary create and legacy configuration behavior remain independent of this check.
     */
    public void requireDeclaredCapabilities(String type, Map<String, String> options, Set<String> required) {
        Objects.requireNonNull(required, "required capabilities");
        if (required.size() > 128) throw new IllegalArgumentException("at most 128 capability requirements are allowed");
        Set<String> snapshot = new TreeSet<>();
        for (String capability : required) {
            ProcessorDescription.validateCapabilityId(capability);
            snapshot.add(capability);
            if (snapshot.size() > 128) throw new IllegalArgumentException("at most 128 capability requirements are allowed");
        }
        Optional<ProcessorDescription> description = describe(type, options);
        for (String capability : snapshot) {
            ProcessorDescription.Support support = description.map(value -> value.support(capability))
                    .orElse(ProcessorDescription.Support.UNKNOWN);
            if (support != ProcessorDescription.Support.SUPPORTED) {
                throw new IllegalArgumentException("processor '" + type + "' required capability '" + capability
                        + "' is " + support);
            }
        }
    }
}

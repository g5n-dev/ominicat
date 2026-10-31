package io.ominicat.providers;

import java.util.Map;
import java.util.Set;

final class Options {
    private Options() { }
    static void allowed(Map<String, String> options, String... names) {
        Set<String> valid = Set.of(names);
        for (String name : options.keySet()) {
            if (!valid.contains(name)) throw new IllegalArgumentException("unknown option: " + name);
        }
    }
    static int integer(Map<String, String> options, String name, int fallback, int min, int max) {
        int value = Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be in " + min + ".." + max);
        return value;
    }
}

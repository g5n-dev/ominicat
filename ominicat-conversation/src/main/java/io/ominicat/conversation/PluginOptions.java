package io.ominicat.conversation;

import java.util.Map;
import java.util.Set;

final class PluginOptions {
    private PluginOptions() {}

    static void checkKeys(Map<String, String> options, Set<String> supported) {
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (!supported.contains(entry.getKey())) {
                throw new IllegalArgumentException("unsupported option: " + entry.getKey());
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("option must not be null: " + entry.getKey());
            }
        }
    }

    static int integer(Map<String, String> options, String key, int defaultValue) {
        try {
            return Integer.parseInt(options.getOrDefault(key, Integer.toString(defaultValue)));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer", error);
        }
    }

    static boolean bool(Map<String, String> options, String key, boolean defaultValue) {
        String value = options.getOrDefault(key, Boolean.toString(defaultValue));
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }
}

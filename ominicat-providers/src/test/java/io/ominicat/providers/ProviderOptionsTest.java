package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderOptionsTest {
    @Test
    void onlyHttpsAndLoopbackHttpAreAcceptedAndCredentialsFailEarly() {
        for (String url : List.of("http://example.com/v1", "file:///tmp/api", "https://user@example.com/v1",
                "https://example.com/v1?key=x", "https://example.com/v1#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> new ChatFactory().create(options("baseUrl", url)));
        }
        assertThrows(IllegalArgumentException.class, () -> new ChatFactory().create(options("model", " ")));
        assertThrows(IllegalArgumentException.class, () -> new ChatFactory().create(options("apiKeyEnv", "OMINICAT_TEST_NONEXISTENT_KEY_6827")));
        assertThrows(IllegalArgumentException.class, () -> new ChatFactory().create(options("baseUrl", "https://example.com/v1")));
    }

    @Test
    void budgetsAndFactorySpecificOptionsAreValidated() {
        for (Map.Entry<String, String> invalid : Map.of("timeoutSeconds", "0", "maxResponseBytes", "1",
                "maxOutputTokens", "0", "maxInputChars", "0").entrySet()) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ChatFactory().create(options(invalid.getKey(), invalid.getValue())));
        }
        assertThrows(IllegalArgumentException.class, () -> new SpeechFactory().create(options("voice", " ")));
        assertThrows(IllegalArgumentException.class, () -> new SpeechFactory().create(options("maxTextChars", "4097")));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptionFactory().create(options("speaker", " ")));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptionFactory().create(options("maxAudioBytes", "1")));
    }

    private static Map<String, String> options(String key, String value) {
        Map<String, String> options = new HashMap<>(Map.of("model", "test", "baseUrl", "http://127.0.0.1:1/v1", "apiKeyEnv", ""));
        options.put(key, value);
        return options;
    }
}

package io.ominicat.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SseBoundaryTest {
    @Test void allLegalLineEndingsAndFragmentSizesHaveIdenticalOutput() throws Exception {
        for (String ending : List.of("\n", "\r\n", "\r")) {
          for (String bom : List.of("", "\ufeff")) {
            String stream = ": comment\n\n" + ProviderHttpTestSupport.event("你好🎵")
                    + ProviderHttpTestSupport.event(" second") + "data: [DONE]\n\n";
            byte[] bytes = (bom + stream.replace("\n", ending)).getBytes(StandardCharsets.UTF_8);
            for (int fragment : List.of(1, 2, 3, 5, 7, 13, 64)) {
                List<Frame> output = new ArrayList<>();
                try (var response = response(bytes, fragment)) {
                    ChatFactory.readStream(response, output::add);
                }
                assertEquals(List.of(new Frame.Text("你好🎵"), new Frame.Text(" second")), output,
                        "line ending=" + ending.replace("\r", "CR").replace("\n", "LF") + ", fragment=" + fragment);
            }
          }
        }
    }

    @Test void malformedUtf8FailsRatherThanChangingTranscriptContent() throws Exception {
        byte[] prefix = "data: {\"choices\":[{\"delta\":{\"content\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[prefix.length + 1 + suffix.length];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        bytes[prefix.length] = (byte) 0xff;
        System.arraycopy(suffix, 0, bytes, prefix.length + 1, suffix.length);
        try (var response = response(bytes, 1)) {
            assertThrows(IOException.class, () -> ChatFactory.readStream(response, ignored -> {}));
        }
    }

    @Test void everyPrematureCutBeforeDoneFails() throws Exception {
        String prefix = ProviderHttpTestSupport.event("unicode: 你好🎵");
        byte[] complete = (prefix + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        int done = prefix.getBytes(StandardCharsets.UTF_8).length;
        // UTF-8 byte boundaries, including cuts inside a code point and between event delimiters.
        for (int cut = 0; cut < done; cut++) {
            try (var response = response(java.util.Arrays.copyOf(complete, cut), 3)) {
                assertThrows(IOException.class, () -> ChatFactory.readStream(response, ignored -> {}), "cut=" + cut);
            }
        }
    }

    @Test void rawByteBudgetCountsIgnoredCarriageReturns() throws Exception {
        try (var response = new CompatibleClient.Response(new ByteArrayInputStream("\r".repeat(2000).getBytes(StandardCharsets.UTF_8)), new NeverDeadline(), 1024)) {
            assertThrows(IOException.class, () -> response.stream().readAllBytes());
        }
    }

    private static CompatibleClient.Response response(byte[] bytes, int fragment) {
        InputStream stream = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, fragment));
            }
        };
        return new CompatibleClient.Response(stream, new NeverDeadline(), 8 * 1024 * 1024);
    }

    private static final class NeverDeadline implements ScheduledFuture<Object> {
        @Override public long getDelay(TimeUnit unit) { return unit.convert(1, TimeUnit.HOURS); }
        @Override public int compareTo(Delayed other) { return 0; }
        @Override public boolean cancel(boolean interrupt) { return true; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return false; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
    }
}

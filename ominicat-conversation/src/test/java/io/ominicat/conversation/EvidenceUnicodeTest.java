package io.ominicat.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EvidenceUnicodeTest {
    @Test
    void endBoundaryDoesNotIntroduceAnUnpairedSurrogate() {
        String text = "needle" + "x".repeat(173) + "🙂" + "tail";
        Evidence evidence = Evidence.around(turn(text), 0);

        assertStrictUtf8(evidence.excerpt());
        assertEquals("needle" + "x".repeat(173) + "…", evidence.excerpt());
        assertMetadata(turn(text), evidence);
    }

    @Test
    void startBoundaryDoesNotIntroduceAnUnpairedSurrogateOrExtendTheOriginalWindow() {
        String text = "x".repeat(60) + "🙂" + "x".repeat(59) + "needle" + "z".repeat(200);
        Evidence evidence = Evidence.around(turn(text), text.indexOf("needle"));

        assertStrictUtf8(evidence.excerpt());
        assertEquals("…" + "x".repeat(59) + "needle" + "z".repeat(114) + "…", evidence.excerpt());
        assertMetadata(turn(text), evidence);
    }

    @Test
    void oneWindowCanShrinkBothBoundariesWithoutExpandingItsRange() {
        String text = "a".repeat(119) + "🙂" + "b".repeat(178) + "𠀀" + "c".repeat(10);
        Evidence evidence = Evidence.around(turn(text), 180);

        assertEquals("…" + "b".repeat(178) + "…", evidence.excerpt());
        assertStrictUtf8(evidence.excerpt());
        assertMetadata(turn(text), evidence);
    }

    @Test
    void completeEmojiAndSupplementaryCjkAtEitherBoundaryAreRetained() {
        for (String glyph : List.of("🙂", "𠀀")) {
            String endAligned = "needle" + "x".repeat(172) + glyph + "tail";
            assertEquals("needle" + "x".repeat(172) + glyph + "…",
                    Evidence.around(turn(endAligned), 0).excerpt());

            String startAligned = "x".repeat(60) + glyph + "x".repeat(58) + "needle" + "z".repeat(200);
            String expected = "…" + glyph + "x".repeat(58) + "needle" + "z".repeat(114) + "…";
            Evidence evidence = Evidence.around(turn(startAligned), startAligned.indexOf("needle"));
            assertStrictUtf8(evidence.excerpt());
            assertEquals(expected, evidence.excerpt());
        }
    }

    @Test
    void emptyShortAndExactlyFittingTextStayUnchanged() {
        for (String text : List.of("", "needle", "中文 needle 🙂𠀀", "x".repeat(178) + "🙂")) {
            Evidence evidence = Evidence.around(turn(text), Math.max(0, text.indexOf("needle")));
            assertEquals(text, evidence.excerpt());
            assertStrictUtf8(evidence.excerpt());
            assertMetadata(turn(text), evidence);
        }
    }

    @Test
    void asciiMatchesLegacyWindowsAndExistingMalformedInputIsNotSanitized() {
        for (String text : List.of("", "short needle", "prefix-" + "x".repeat(400) + "-needle")) {
            for (int position = 0; position <= text.length(); position++) {
                int start = Math.max(0, position - 60);
                int end = Math.min(text.length(), start + 180);
                String legacy = (start > 0 ? "…" : "") + text.substring(start, end)
                        + (end < text.length() ? "…" : "");
                assertEquals(legacy, Evidence.around(turn(text), position).excerpt());
            }
        }

        String high = String.valueOf((char) 0xd83d);
        String low = String.valueOf((char) 0xde42);
        for (String original : List.of("a" + high + "b", "a" + low + "b")) {
            assertEquals(original, Evidence.around(turn(original), 0).excerpt());
        }
        String malformedEnd = "x".repeat(179) + high + "tail";
        assertEquals("x".repeat(179) + high + "…", Evidence.around(turn(malformedEnd), 0).excerpt());
        String malformedStart = "x".repeat(61) + low + "b".repeat(200);
        assertEquals("…" + low + "b".repeat(179) + "…", Evidence.around(turn(malformedStart), 121).excerpt());
    }

    @Test
    void publicPolicyResultsPreserveScoresPhraseAndAttribution() throws Exception {
        String text = "needle" + "x".repeat(173) + "𠀀" + "tail";
        Frame.Transcript transcript = new Frame.Transcript(text, "track-a", 7, 81,
                true, 0.75, "segment-7", 9, "participant");
        ConversationWindow window = new ConversationWindow(2);
        window.add(transcript);

        RuleResult required = PolicyRules.requiredPhrase("required", "needle").evaluate(window.snapshot(), "track-a");
        RuleResult prohibited = PolicyRules.prohibitedPhrase("prohibited", "needle").evaluate(window.snapshot(), "track-a");
        assertEquals(RuleResult.Status.PASS, required.status());
        assertEquals(1.0, required.score());
        assertEquals(RuleResult.Status.FAIL, prohibited.status());
        assertEquals(0.0, prohibited.score());
        assertEquals(required.evidence(), prohibited.evidence());
        assertEquals(1, required.evidence().size());
        Evidence evidence = required.evidence().get(0);
        assertEquals("needle" + "x".repeat(173) + "…", evidence.excerpt());
        assertTrue(evidence.excerpt().contains("needle"));
        assertStrictUtf8(evidence.excerpt());
        assertMetadata(Turn.from(transcript), evidence);
    }

    @Test
    void seededValidInputsMatchIndependentCodepointBoundaryOracle() {
        Random random = new Random(0xe71deaceL);
        for (int example = 0; example < 512; example++) {
            String prefix = randomText(random, random.nextInt(320));
            String text = prefix + "needle" + randomText(random, random.nextInt(320));
            int position = prefix.length();
            Turn turn = turn(text);
            Evidence evidence = Evidence.around(turn, position);

            assertStrictUtf8(text);
            assertStrictUtf8(evidence.excerpt());
            assertEquals(codepointWindow(text, position), evidence.excerpt(), "seed case " + example);
            assertTrue(evidence.excerpt().length() <= 182);
            assertTrue(evidence.excerpt().contains("needle"));
            assertMetadata(turn, evidence);
        }
    }

    /** Select only complete codepoints contained in the original UTF-16 window. */
    private static String codepointWindow(String text, int position) {
        int[] points = text.codePoints().toArray();
        int[] boundaries = new int[points.length + 1];
        for (int index = 0; index < points.length; index++) {
            boundaries[index + 1] = boundaries[index] + Character.charCount(points[index]);
        }
        int lower = Math.max(0, position - 60);
        int upper = Math.min(text.length(), lower + 180);
        int first = Arrays.stream(boundaries).filter(boundary -> boundary >= lower).min().orElseThrow();
        int last = Arrays.stream(boundaries).filter(boundary -> boundary <= upper).max().orElseThrow();
        return (first > 0 ? "…" : "") + text.substring(first, last) + (last < text.length() ? "…" : "");
    }

    private static String randomText(Random random, int count) {
        int[] points = {'x', 'y', '中', '?', 0x0301, 0x1f642, 0x1f680, 0x20000, 0x20001};
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < count; index++) text.appendCodePoint(points[random.nextInt(points.length)]);
        return text.toString();
    }

    private static Turn turn(String text) {
        return new Turn(text, "track-a", 7, 81, 0.75, "segment-7", 9, "participant");
    }

    private static void assertMetadata(Turn turn, Evidence evidence) {
        assertEquals(turn.speaker(), evidence.speaker());
        assertEquals(turn.role(), evidence.role());
        assertEquals(turn.segmentId(), evidence.segmentId());
        assertEquals(turn.startMillis(), evidence.startMillis());
        assertEquals(turn.endMillis(), evidence.endMillis());
        assertTrue(evidence.excerpt().length() <= 182);
    }

    private static void assertStrictUtf8(String text) {
        assertDoesNotThrow(() -> StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text)), "excerpt must remain valid Unicode for strict UTF-8 encoding");
    }
}

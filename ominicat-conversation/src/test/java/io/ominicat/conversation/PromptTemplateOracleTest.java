package io.ominicat.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PromptTemplateOracleTest {
    private static final String ROLE = "$role\\{{history}}🙂";
    private static final String CONTEXT = "context {{transcript}}\\$";
    private static final String INSTRUCTION = "literal $1\\ and {{role}}";
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");

    @Test
    void seededPromptsMatchExhaustiveFullSuffixOracle() throws Exception {
        String[] templates = {
                "{{history}}",
                "{{history}}|{{history}}|{{transcript}}",
                "{{role}}|{{context}}|{{instruction}}|{{history}}|{{speaker}}={{transcript}}",
                "{{role}}|{{transcript}}|{{speaker}}",
                "constant prompt",
                "literal {{history} :: {{transcript}}"
        };
        String[] fragments = {"abc", "🙂", "汉字", "\n", "$1", "\\", "{{role}}", "{{history}}", ""};
        for (long seed = 0x70a1; seed < 0x70a1 + 64; seed++) {
            Random random = new Random(seed);
            int capacity = 1 + random.nextInt(24);
            int budget = 1 + random.nextInt(512);
            String template = templates[random.nextInt(templates.length)];
            PromptTemplateProcessor processor = processor(template, capacity, 256, budget);
            List<Frame.Transcript> retained = new ArrayList<>();
            for (int event = 0; event < 80; event++) {
                StringBuilder text = new StringBuilder();
                for (int count = 1 + random.nextInt(12); count > 0; count--) {
                    text.append(fragments[random.nextInt(fragments.length)]);
                }
                String speaker = random.nextInt(3) == 0 ? "other🙂" : "user";
                boolean complete = random.nextInt(5) != 0;
                long start = random.nextInt(1000);
                Frame.Transcript input = new Frame.Transcript(text.toString(), speaker, start,
                        start + random.nextInt(50), complete, -1, seed + ":" + event, 0, "participant");
                List<Frame> output = new ArrayList<>();
                if (!complete) {
                    processor.process(input, output::add);
                    assertEquals(List.of(input), output);
                    continue;
                }
                retained.add(input);
                if (retained.size() > capacity) retained.remove(0);
                if (!speaker.equals("user")) {
                    processor.process(input, output::add);
                    assertEquals(List.of(input), output);
                } else {
                    assertMatchesOracle(processor, template, retained, input, budget,
                            "seed=" + seed + ", event=" + event);
                }
            }
        }
    }

    @Test
    void everyBudgetPreservesWholeTurnsAndUtf16BoundariesWithRepeatedHistory() throws Exception {
        List<Frame.Transcript> turns = List.of(
                transcript("old🙂\n$1\\{{history}}", "other", "old"),
                transcript("", "other🙂", "middle"),
                transcript("新🙂\\$1{{role}}", "user", "latest"));
        String template = "{{role}}|{{history}}|{{history}}|{{transcript}}";
        for (int budget = 1; budget <= 180; budget++) {
            PromptTemplateProcessor processor = processor(template, 8, 256, budget);
            processor.process(turns.get(0), ignored -> {});
            processor.process(turns.get(1), ignored -> {});
            assertMatchesOracle(processor, template, turns, turns.get(2), budget, "budget=" + budget);
        }
    }

    @Test
    void noHistoryTemplateDoesNotBudgetRetainedTextOrRecursivelyExpandVariables() throws Exception {
        PromptTemplateProcessor processor = processor("{{role}}", 10_000, 256, ROLE.length());
        for (int index = 0; index < 10_000; index++) {
            processor.process(transcript("x".repeat(128), "other", "old-" + index), ignored -> {});
        }
        List<Frame> output = new ArrayList<>();
        processor.process(transcript("y".repeat(256), "user", "latest"), output::add);
        assertEquals(List.of(new Frame.Text(ROLE)), output);
    }

    @Test
    void tinyHistoryBudgetKeepsOnlyTheLatestCompleteTurnFromLargeWindow() throws Exception {
        PromptTemplateProcessor processor = processor("{{history}}", 10_000, 256, 8);
        for (int index = 0; index < 10_000; index++) {
            processor.process(transcript("x".repeat(128), "other", "old-" + index), ignored -> {});
        }
        List<Frame> output = new ArrayList<>();
        processor.process(transcript("ok", "user", "latest"), output::add);
        assertEquals(List.of(new Frame.Text("user: ok")), output);
    }

    @Test
    void renderingFailureStillCommitsFinalAndInterruptResetsDeduplication() throws Exception {
        for (String template : List.of("{{history}}", "{{transcript}}")) {
            PromptTemplateProcessor processor = processor(template, 2, 256, 3);
            Frame.Transcript input = transcript("long", "user", "same");
            String expected = template.equals("{{history}}") ? "latest turn exceeds maxPromptChars"
                    : "prompt exceeds maxPromptChars even without history";
            List<Frame> output = new ArrayList<>();
            assertEquals(expected, assertThrows(IllegalArgumentException.class,
                    () -> processor.process(input, output::add)).getMessage());
            processor.process(input, output::add);
            assertTrue(output.isEmpty(), "a committed duplicate must not render or fail again");
            processor.onInterrupt();
            assertEquals(expected, assertThrows(IllegalArgumentException.class,
                    () -> processor.process(input, output::add)).getMessage());
        }
    }

    @Test
    void omittedHistoryStillHonorsTextLimitFinalConflictsEvictionAndInterruption() throws Exception {
        PromptTemplateProcessor processor = processor("ok", 1, 3, 2);
        List<Frame> output = new ArrayList<>();
        Frame.Transcript first = transcript("one", "user", "first");
        processor.process(first, output::add);
        processor.process(first, output::add);
        assertEquals(1, output.size());
        assertThrows(IllegalArgumentException.class,
                () -> processor.process(transcript("two", "user", "first"), output::add));
        assertThrows(IllegalArgumentException.class,
                () -> processor.process(transcript("long", "user", "oversized"), output::add));
        processor.process(transcript("two", "user", "second"), output::add);
        processor.process(first, output::add);
        assertEquals(3, output.size(), "an evicted ID may be retained again");
        processor.onInterrupt();
        processor.process(first, output::add);
        assertEquals(List.of(new Frame.Text("ok"), new Frame.Text("ok"),
                new Frame.Text("ok"), new Frame.Text("ok")), output);
    }

    @Test
    void retainedTurnViewIsImmutableIndependentAndLeavesSnapshotMetricsIntact() {
        ConversationWindow window = new ConversationWindow(2);
        window.add(new Frame.Transcript("a", "a", 0, 100, true, -1));
        List<Turn> retained = window.retainedTurns();
        assertThrows(UnsupportedOperationException.class, retained::clear);
        window.add(new Frame.Transcript("b", "b", 50, 150, true, -1));
        assertEquals(1, retained.size());
        assertEquals(window.retainedTurns(), window.snapshot().turns());
        assertEquals(new ConversationMetrics(150, 150, 0, Map.of("a", 100L, "b", 100L)),
                window.snapshot().metrics());
        window.reset();
        assertEquals(1, retained.size());
        assertTrue(window.retainedTurns().isEmpty());
    }

    private static PromptTemplateProcessor processor(String template, int capacity, int maxText, int budget) {
        return new PromptTemplateProcessor("user", template, ROLE, CONTEXT, INSTRUCTION,
                capacity, maxText, budget);
    }

    private static Frame.Transcript transcript(String text, String speaker, String id) {
        return new Frame.Transcript(text, speaker, 0, 1, true, -1, id, 0, "participant");
    }

    private static void assertMatchesOracle(PromptTemplateProcessor processor, String template,
                                           List<Frame.Transcript> retained, Frame.Transcript input,
                                           int budget, String context) throws Exception {
        String expected = null;
        IllegalArgumentException failure = null;
        try { expected = oracle(template, retained, input, budget); }
        catch (IllegalArgumentException invalid) { failure = invalid; }
        List<Frame> output = new ArrayList<>();
        if (failure != null) {
            assertEquals(failure.getMessage(), assertThrows(IllegalArgumentException.class,
                    () -> processor.process(input, output::add), context).getMessage(), context);
            assertTrue(output.isEmpty(), context);
        } else {
            processor.process(input, output::add);
            assertEquals(List.of(new Frame.Text(expected)), output, context);
            assertTrue(expected.length() <= budget, context);
        }
    }

    /** Exhaustively render complete suffixes; no production length formula or reverse scan. */
    private static String oracle(String template, List<Frame.Transcript> retained,
                                 Frame.Transcript latest, int budget) {
        if (expand(template, latest, "").length() > budget) {
            throw new IllegalArgumentException("prompt exceeds maxPromptChars even without history");
        }
        for (int first = 0; first < retained.size(); first++) {
            List<String> lines = new ArrayList<>();
            for (Frame.Transcript turn : retained.subList(first, retained.size())) {
                lines.add(turn.speaker() + ": " + turn.text());
            }
            String candidate = expand(template, latest, String.join("\n", lines));
            if (candidate.length() <= budget) return candidate;
        }
        throw new IllegalArgumentException("latest turn exceeds maxPromptChars");
    }

    /** Append literal replacement values rather than using regex replacement semantics. */
    private static String expand(String template, Frame.Transcript latest, String history) {
        Map<String, String> values = Map.of("role", ROLE, "context", CONTEXT, "instruction", INSTRUCTION,
                "speaker", latest.speaker(), "transcript", latest.text(), "history", history);
        StringBuilder result = new StringBuilder();
        Matcher matcher = VARIABLE.matcher(template);
        int cursor = 0;
        while (matcher.find()) {
            result.append(template, cursor, matcher.start());
            result.append(values.get(matcher.group(1)));
            cursor = matcher.end();
        }
        return result.append(template, cursor, template.length()).toString();
    }
}

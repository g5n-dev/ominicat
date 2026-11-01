package io.ominicat.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ominicat.conversation.ConversationEvaluatorProcessor;
import io.ominicat.conversation.EvaluationFrame;
import io.ominicat.conversation.PolicyRules;
import io.ominicat.conversation.RuleResult;
import io.ominicat.core.Frame;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceUnicodeWireTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SPEAKER = "speaker-\uD840\uDC00";
    private static final String SEGMENT = "segment-\uD83D\uDE00";
    private static final String ROLE = "participant";
    private static final String POLICY = "unicode-v1";
    private static final List<String> SUPPLEMENTARY_CHARACTERS = List.of("\uD83D\uDE00", "\uD840\uDC00");

    @Test void leadingExcerptBoundarySurvivesStrictUtf8AndCustomFrameJson() throws Exception {
        for (String character : SUPPLEMENTARY_CHARACTERS) {
            // The old UTF-16 start index, match position minus 60, lands on the low surrogate.
            String text = "a".repeat(10) + character + "b".repeat(59) + "needle" + "c".repeat(140);
            assertValidEvidenceWire(text);
        }
    }

    @Test void trailingExcerptBoundarySurvivesStrictUtf8AndCustomFrameJson() throws Exception {
        for (String character : SUPPLEMENTARY_CHARACTERS) {
            // The old 180-code-unit end index falls between the pair at indexes 179 and 180.
            String text = "needle" + "a".repeat(173) + character + "tail";
            assertValidEvidenceWire(text);
        }
    }

    @Test void missingRequiredPhraseRemainsFailAfterWireSerialization() throws Exception {
        EvaluationFrame evaluation = evaluate("present \uD83D\uDE00", SPEAKER, "absent");
        JsonNode data = encodeAndParse(evaluation);
        assertEquals(0.0, evaluation.score());
        assertEquals(RuleResult.Status.FAIL, evaluation.results().get(0).status());
        assertEquals(0.0, data.path("score").asDouble());
        assertEquals("FAIL", data.path("results").get(0).path("status").asText());
        assertTrue(data.path("results").get(0).path("evidence").isEmpty());
    }

    @Test void missingTargetSpeakerRemainsNotApplicableWithNullScoresOnWire() throws Exception {
        EvaluationFrame evaluation = evaluate("needle \uD840\uDC00", "other-speaker", "needle");
        JsonNode data = encodeAndParse(evaluation);
        assertNull(evaluation.score());
        assertEquals(RuleResult.Status.NOT_APPLICABLE, evaluation.results().get(0).status());
        assertTrue(data.path("score").isNull());
        JsonNode rule = data.path("results").get(0);
        assertEquals("NOT_APPLICABLE", rule.path("status").asText());
        assertTrue(rule.path("score").isNull());
        assertTrue(rule.path("evidence").isEmpty());
        assertEquals(1500, data.path("metrics").path("speakerSpeechMillis").path("other-speaker").asLong());
    }

    private static void assertValidEvidenceWire(String text) throws Exception {
        EvaluationFrame evaluation = evaluate(text, SPEAKER, "needle");
        assertEquals(1.0, evaluation.score());
        assertEquals(RuleResult.Status.PASS, evaluation.results().get(0).status());
        var evidence = evaluation.results().get(0).evidence().get(0);
        String excerpt = evidence.excerpt();
        assertPairedSurrogates(excerpt);
        assertTrue(excerpt.length() <= 182, "excerpt retains its UTF-16 limit including ellipses");
        assertTrue(excerpt.contains("needle"));
        String retained = excerpt.startsWith("…") ? excerpt.substring(1) : excerpt;
        if (retained.endsWith("…")) retained = retained.substring(0, retained.length() - 1);
        assertTrue(text.contains(retained), "excerpt must remain a contiguous part of the original text");
        assertEquals(SPEAKER, evidence.speaker());
        assertEquals(SEGMENT, evidence.segmentId());
        assertEquals(ROLE, evidence.role());
        assertEquals(1250, evidence.startMillis());
        assertEquals(2750, evidence.endMillis());

        JsonNode data = encodeAndParse(evaluation);
        assertEquals(POLICY, data.path("policyVersion").asText());
        assertEquals(SPEAKER, data.path("targetSpeaker").asText());
        assertEquals(1.0, data.path("score").asDouble());
        assertEquals(1, data.path("retainedTurns").asInt());
        assertEquals(0, data.path("evictedTurns").asLong());
        JsonNode rule = data.path("results").get(0);
        assertEquals("required", rule.path("ruleId").asText());
        assertEquals("PASS", rule.path("status").asText());
        assertEquals(1.0, rule.path("score").asDouble());
        assertEquals("Required phrase was found in retained turns: needle", rule.path("explanation").asText());
        JsonNode wireEvidence = rule.path("evidence").get(0);
        assertEquals(excerpt, wireEvidence.path("excerpt").asText());
        assertEquals(SPEAKER, wireEvidence.path("speaker").asText());
        assertEquals(SEGMENT, wireEvidence.path("segmentId").asText());
        assertEquals(ROLE, wireEvidence.path("role").asText());
        assertEquals(1250, wireEvidence.path("startMillis").asLong());
        assertEquals(2750, wireEvidence.path("endMillis").asLong());
        JsonNode metrics = data.path("metrics");
        assertEquals(1500, metrics.path("durationMillis").asLong());
        assertEquals(1500, metrics.path("speechMillis").asLong());
        assertEquals(0, metrics.path("silenceMillis").asLong());
        assertEquals(1500, metrics.path("speakerSpeechMillis").path(SPEAKER).asLong());
    }

    private static EvaluationFrame evaluate(String text, String inputSpeaker, String phrase) throws Exception {
        try (var processor = new ConversationEvaluatorProcessor(SPEAKER,
                List.of(PolicyRules.requiredPhrase("required", phrase)), 4, 8192, false, POLICY)) {
            var transcript = new Frame.Transcript(text, inputSpeaker, 1250, 2750, true, .93, SEGMENT, 7, ROLE);
            var end = new Frame.Control(Frame.Signal.END);
            List<Frame> emitted = new ArrayList<>();
            processor.process(transcript, emitted::add);
            processor.process(end, emitted::add);
            assertEquals(3, emitted.size());
            assertSame(transcript, emitted.get(0), "the original transcript and its metadata are forwarded unchanged");
            assertSame(end, emitted.get(2), "evaluation is emitted before the original END boundary");
            return assertInstanceOf(EvaluationFrame.class, emitted.get(1));
        }
    }

    private static JsonNode encodeAndParse(EvaluationFrame evaluation) throws Exception {
        String wire = new FrameCodec().encode(evaluation);
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(wire));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        assertEquals(wire, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString());
        JsonNode root = JSON.readTree(bytes);
        assertEquals("EvaluationFrame", root.path("type").asText());
        JsonNode data = root.path("data");
        assertTrue(data.isObject());
        assertTextNodesHavePairedSurrogates(data);
        return data;
    }

    private static void assertTextNodesHavePairedSurrogates(JsonNode node) {
        if (node.isTextual()) assertPairedSurrogates(node.asText());
        else node.forEach(EvidenceUnicodeWireTest::assertTextNodesHavePairedSurrogates);
    }

    private static void assertPairedSurrogates(String text) {
        for (int index = 0; index < text.length(); index++) {
            char unit = text.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                assertTrue(index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1)),
                        "unpaired high surrogate at UTF-16 index " + index);
                index++;
            } else assertFalse(Character.isLowSurrogate(unit), "unpaired low surrogate at UTF-16 index " + index);
        }
    }
}

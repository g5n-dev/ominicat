package io.ominicat.conversation;

import static io.ominicat.conversation.ConversationWindowTest.finalTurn;
import static org.junit.jupiter.api.Assertions.*;

import io.ominicat.core.Frame;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.PluginRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationEvaluatorTest {
    @Test
    void evaluatesOnlyTargetSpeakerAndEmitsBeforeEnd() throws Exception {
        Processor processor = new ConversationEvaluatorFactory().create(Map.of("speaker", "agent",
                "requiredPhrases", "hello", "prohibitedPhrases", "never", "minimumQuestionRatio", "0.5"));
        List<Frame> output = new ArrayList<>();
        processor.process(finalTurn("never", "user", 0, 100), output::add);
        processor.process(finalTurn("HELLO, ready?", "agent", 120, 220), output::add);
        assertEquals(2, output.size());
        processor.process(new Frame.Control(Frame.Signal.END), output::add);
        EvaluationFrame evaluation = assertInstanceOf(EvaluationFrame.class, output.get(2));
        assertEquals(1.0, evaluation.score());
        assertEquals(3, evaluation.results().size());
        assertTrue(evaluation.results().stream().allMatch(result -> result.status() == RuleResult.Status.PASS));
        assertEquals("agent", evaluation.results().getFirst().evidence().getFirst().speaker());
        assertEquals("agent:120-220", evaluation.results().getFirst().evidence().getFirst().segmentId());
        assertEquals(new Frame.Control(Frame.Signal.END), output.get(3));
    }

    @Test
    void missingSpeakerAndEmptyPolicyDoNotProduceFalsePasses() throws Exception {
        ConversationEvaluatorProcessor processor = new ConversationEvaluatorProcessor("agent",
                List.of(PolicyRules.requiredPhrase("required", "hello")), 4);
        processor.process(finalTurn("hello", "user", 0, 100), ignored -> {});
        EvaluationFrame evaluation = processor.evaluate();
        assertNull(evaluation.score());
        assertEquals(RuleResult.Status.NOT_APPLICABLE, evaluation.results().getFirst().status());
        assertNull(evaluation.results().getFirst().score());
        assertNull(new ConversationEvaluatorProcessor("agent", List.of(), 4).evaluate().score());
    }

    @Test
    void failedPoliciesHaveScoresAndAttributableEvidence() throws Exception {
        ConversationEvaluatorProcessor processor = new ConversationEvaluatorProcessor("agent", List.of(
                PolicyRules.requiredPhrase("required", "absent"),
                PolicyRules.prohibitedPhrase("blocked", "blocked"),
                PolicyRules.minimumQuestionRatio("ratio", 1)), 4);
        processor.process(finalTurn("x".repeat(250) + " blocked!", "agent", 20, 50), ignored -> {});
        processor.process(finalTurn("ready？", "agent", 60, 90), ignored -> {});
        EvaluationFrame evaluation = processor.evaluate();
        assertEquals(1.0 / 6, evaluation.score(), 1e-10);
        assertEquals(RuleResult.Status.FAIL, evaluation.results().getFirst().status());
        Evidence evidence = evaluation.results().get(1).evidence().getFirst();
        assertTrue(evidence.excerpt().contains("blocked"));
        assertTrue(evidence.excerpt().length() <= 182);
        assertEquals(20, evidence.startMillis());
        assertEquals(.5, evaluation.results().get(2).score());
    }

    @Test
    void optionalTurnBoundaryEvaluationRetainsBoundedHistory() throws Exception {
        Processor processor = new ConversationEvaluatorFactory().create(Map.of("evaluateOnTurnEnd", "true",
                "speaker", "user", "requiredPhrases", "current", "maxTurns", "1", "policyVersion", "v2"));
        List<Frame> output = new ArrayList<>();
        processor.process(finalTurn("old", "user", 0, 10), output::add);
        processor.process(finalTurn("current", "user", 10, 20), output::add);
        processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
        EvaluationFrame evaluation = assertInstanceOf(EvaluationFrame.class, output.get(2));
        assertEquals(1, evaluation.retainedTurns());
        assertEquals(1, evaluation.evictedTurns());
        assertEquals(1.0, evaluation.score());
        assertEquals("v2", evaluation.policyVersion());
    }

    @Test
    void ignoresPartialsAndDuplicateFinalsAndPassesOtherFramesThrough() throws Exception {
        ConversationEvaluatorProcessor processor = new ConversationEvaluatorProcessor("user", List.of(), 4);
        List<Frame> output = new ArrayList<>();
        Frame.Transcript transcript = finalTurn("hello", "user", 0, 10);
        processor.process(new Frame.Transcript("h", "user", 0, 1, false, .4), output::add);
        processor.process(transcript, output::add);
        processor.process(transcript, output::add);
        processor.process(new Frame.Text("chunk"), output::add);
        processor.process(new Frame.Control(Frame.Signal.TURN_END), output::add);
        assertEquals(5, output.size());
        assertEquals(1, processor.evaluate().retainedTurns());
    }

    @Test
    void customRulesReceiveImmutableWindowAndTheirFailuresPropagate() throws Exception {
        PolicyRule custom = (snapshot, speaker) -> new RuleResult("custom", RuleResult.Status.PASS,
                .75, speaker + ":" + snapshot.turns().size(), List.of());
        ConversationEvaluatorProcessor processor = new ConversationEvaluatorProcessor("agent", List.of(custom), 4);
        assertEquals(.75, processor.evaluate().score());
        assertEquals("agent:0", processor.evaluate().results().getFirst().explanation());
        ConversationEvaluatorProcessor failure = new ConversationEvaluatorProcessor("agent",
                List.of((snapshot, speaker) -> { throw new java.io.IOException("unavailable"); }), 4);
        assertThrows(java.io.IOException.class, failure::evaluate);
    }

    @Test
    void servicesDiscoverBothFactoriesAndCreateFreshInstances() {
        PluginRegistry registry = new PluginRegistry();
        assertTrue(registry.types().containsAll(List.of("conversation-evaluator", "prompt-template")));
        assertNotSame(registry.create("conversation-evaluator", Map.of()),
                registry.create("conversation-evaluator", Map.of()));
    }

    @Test
    void factoryRejectsInvalidConfiguration() {
        ConversationEvaluatorFactory factory = new ConversationEvaluatorFactory();
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("typo", "1")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("maxTurns", "many")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("maxTurns", "0")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("evaluateOnTurnEnd", "yes")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("minimumQuestionRatio", "NaN")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("minimumQuestionRatio", "-1")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("minimumQuestionRatio", "1.5")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("minimumQuestionRatio", "abc")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("speaker", " ")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("policyVersion", " ")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(Map.of("policyVersion", "x".repeat(129))));
        assertThrows(IllegalArgumentException.class, () -> PolicyRules.requiredPhrase("id", " "));
        assertThrows(IllegalArgumentException.class, () -> PolicyRules.prohibitedPhrase(" ", "x"));
    }

    @Test
    void evaluatorRetainsCommittedTurnsAcrossInterruption() throws Exception {
        ConversationEvaluatorProcessor processor = new ConversationEvaluatorProcessor("user", List.of(), 4);
        processor.process(finalTurn("committed", "user", 0, 10), ignored -> {});
        processor.onInterrupt();
        assertEquals(1, processor.evaluate().retainedTurns());
    }

    @Test
    void snapshotRestoreProducesIdenticalEvaluation() throws Exception {
        var rules = List.of(PolicyRules.requiredPhrase("phrase", "hello"));
        var original = new ConversationEvaluatorProcessor("agent", rules, 4, 8192, false, "v3");
        original.process(finalTurn("hello", "agent", 0, 10), ignored -> {});
        var restored = new ConversationEvaluatorProcessor("agent", rules, 4, 8192, false, "v3");
        restored.restore(original.snapshot());
        assertEquals(original.evaluate(), restored.evaluate());
    }

    @Test
    void zeroQuestionThresholdPassesAndEvidenceIsBounded() throws Exception {
        ConversationWindow window = new ConversationWindow(20);
        for (int i = 0; i < 12; i++) {
            window.add(finalTurn("found?", "agent", i, i + 1));
        }
        assertEquals(8, PolicyRules.requiredPhrase("phrase", "found")
                .evaluate(window.snapshot(), "agent").evidence().size());
        assertEquals(1.0, PolicyRules.minimumQuestionRatio("ratio", 0)
                .evaluate(window.snapshot(), "agent").score());
    }

    @Test
    void resultTypesRejectInvalidScores() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuleResult("r", RuleResult.Status.PASS, null, "why", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleResult("r", RuleResult.Status.NOT_APPLICABLE, 1.0, "why", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleResult("r", RuleResult.Status.FAIL, Double.NaN, "why", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationFrame("user", 2.0, List.of(), new ConversationMetrics(0, 0, 0, Map.of()), 0, 0));
    }
}

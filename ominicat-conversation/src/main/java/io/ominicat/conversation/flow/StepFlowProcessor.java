package io.ominicat.conversation.flow;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A session-confined, deterministic state machine. Definitions own routing policy;
 * the processor owns step identity, ordered transitions and bounded deduplication.
 * Snapshots contain no transcript text and can be persisted by an application.
 */
public final class StepFlowProcessor implements Processor {
    public record Transition(String target, Predicate<Frame.Transcript> condition) {
        public Transition { label(target, "target"); Objects.requireNonNull(condition, "condition"); }
    }
    public record Step(String id, String prompt, List<Transition> transitions) {
        public Step {
            label(id, "step id");
            Objects.requireNonNull(prompt, "prompt");
            if (prompt.length() > 65536) throw new IllegalArgumentException("prompt exceeds 65536 characters");
            transitions = List.copyOf(transitions);
            if (transitions.size() > 64) throw new IllegalArgumentException("too many step transitions");
        }
    }
    public record StepEvent(String definitionVersion, String stepId, long revision, boolean terminal) implements Frame { }
    public record Snapshot(String definitionVersion, String currentStep, long revision,
                           boolean started, List<String> committedSegmentIds) {
        public Snapshot { committedSegmentIds = List.copyOf(committedSegmentIds); }
    }
    private final String version;
    private final String inputRole;
    private final String initialStep;
    private final Map<String, Step> steps;
    private final int maxRememberedSegments;
    private final LinkedHashSet<String> committed = new LinkedHashSet<>();
    private String current;
    private long revision;
    private boolean started;

    public StepFlowProcessor(String version, String initialStep, String inputRole,
                             List<Step> definition, int maxRememberedSegments) {
        label(version, "definitionVersion"); label(inputRole, "inputRole");
        if (definition.isEmpty() || definition.size() > 256) throw new IllegalArgumentException("step count must be 1..256");
        if (maxRememberedSegments < 1 || maxRememberedSegments > 10000) throw new IllegalArgumentException("maxRememberedSegments must be 1..10000");
        var entries = new LinkedHashMap<String, Step>();
        for (Step step : definition) if (entries.putIfAbsent(step.id(), step) != null) throw new IllegalArgumentException("duplicate step: " + step.id());
        if (!entries.containsKey(initialStep)) throw new IllegalArgumentException("unknown initial step");
        for (Step step : definition) for (Transition transition : step.transitions()) {
            if (!entries.containsKey(transition.target())) throw new IllegalArgumentException("unknown transition target: " + transition.target());
        }
        this.version = version; this.current = initialStep; this.initialStep = initialStep; this.inputRole = inputRole;
        this.steps = Map.copyOf(entries); this.maxRememberedSegments = maxRememberedSegments;
    }

    @Override public void process(Frame frame, FrameSink next) throws Exception {
        if (frame instanceof Frame.Control control && control.signal() == Frame.Signal.START) {
            if (!started) { started = true; enter(next); }
            return;
        }
        if (!(frame instanceof Frame.Transcript transcript) || !transcript.finalResult() || !transcript.role().equals(inputRole)) {
            next.accept(frame); return;
        }
        if (!started) throw new IllegalStateException("step-flow requires START before final input");
        label(transcript.segmentId(), "segmentId");
        if (committed.contains(transcript.segmentId())) return;
        // Determine a transition before committing state; a throwing predicate cannot partially advance it.
        String target = null;
        for (Transition transition : steps.get(current).transitions()) {
            if (transition.condition().test(transcript)) { target = transition.target(); break; }
        }
        long nextRevision = target == null ? revision : Math.incrementExact(revision);
        if (committed.size() == maxRememberedSegments) committed.removeFirst();
        committed.add(transcript.segmentId());
        if (target != null) { current = target; revision = nextRevision; enter(next); }
    }
    private void enter(FrameSink next) throws Exception {
        Step step = steps.get(current);
        next.accept(new StepEvent(version, current, revision, step.transitions().isEmpty()));
        if (!step.prompt().isBlank()) next.accept(new Frame.Text(step.prompt()));
    }
    public Snapshot snapshot() { return new Snapshot(version, current, revision, started, new ArrayList<>(committed)); }
    /** Restore only a snapshot of the same definition version; no external side effects are replayed. */
    public void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!version.equals(snapshot.definitionVersion()) || !steps.containsKey(snapshot.currentStep()) || snapshot.revision() < 0
                || snapshot.committedSegmentIds().size() > maxRememberedSegments) throw new IllegalArgumentException("incompatible flow snapshot");
        if (!snapshot.started() && (snapshot.revision() != 0 || !snapshot.currentStep().equals(initialStep)
                || !snapshot.committedSegmentIds().isEmpty())) throw new IllegalArgumentException("unstarted snapshot contains progressed state");
        if (snapshot.revision() == 0 && !snapshot.currentStep().equals(initialStep)) {
            throw new IllegalArgumentException("zero-revision snapshot must remain at the initial step");
        }
        var unique = new LinkedHashSet<String>();
        for (String id : snapshot.committedSegmentIds()) { label(id, "segmentId"); if (!unique.add(id)) throw new IllegalArgumentException("duplicate snapshot segment"); }
        current = snapshot.currentStep(); revision = snapshot.revision(); started = snapshot.started();
        committed.clear(); committed.addAll(unique);
    }
    private static void label(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) throw new IllegalArgumentException(name + " must have 1..256 characters");
    }
}

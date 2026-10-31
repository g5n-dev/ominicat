package io.ominicat.providers;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.ProcessorFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded ASR revision gate; commits each retained segment at most once. */
public final class TranscriptGateFactory implements ProcessorFactory {
    @Override public String type() { return "transcript-gate"; }

    @Override public Processor create(Map<String, String> options) {
        Options.allowed(options, "maxSegments", "maxTextChars", "passPartials");
        int maxSegments = Options.integer(options, "maxSegments", 256, 1, 10_000);
        int maxTextChars = Options.integer(options, "maxTextChars", 65_536, 1, 1_000_000);
        String partialOption = options.getOrDefault("passPartials", "false");
        if (!partialOption.equals("true") && !partialOption.equals("false")) {
            throw new IllegalArgumentException("passPartials must be true or false");
        }
        boolean passPartials = Boolean.parseBoolean(partialOption);
        return new Processor() {
            private final Map<String, Frame.Transcript> segments = new LinkedHashMap<>();

            @Override public void process(Frame frame, FrameSink next) throws Exception {
                if (frame instanceof Frame.Transcript transcript) {
                    if (transcript.text().length() > maxTextChars || transcript.segmentId().length() > 256
                            || transcript.speaker().length() > 128 || transcript.role().length() > 128) {
                        throw new IllegalArgumentException("transcript exceeds gate text or identity limits");
                    }
                    Frame.Transcript previous = segments.get(transcript.segmentId());
                    if (previous != null) {
                        if (!previous.speaker().equals(transcript.speaker()) || !previous.role().equals(transcript.role())) {
                            throw new IllegalArgumentException("segment speaker and role must remain stable");
                        }
                        if (previous.finalResult()) {
                            if (transcript.finalResult() && !sameFacts(previous, transcript)) {
                                throw new IllegalArgumentException("conflicting final transcript for retained segment");
                            }
                            if (!transcript.finalResult() && transcript.revision() > previous.revision()) {
                                throw new IllegalArgumentException("committed final transcript cannot be revised");
                            }
                            return;
                        }
                        if (transcript.revision() < previous.revision()) return;
                        if (transcript.revision() == previous.revision()) {
                            if (!sameFacts(previous, transcript)) {
                                throw new IllegalArgumentException("conflicting transcript facts at same revision");
                            }
                            if (!transcript.finalResult()) return;
                        }
                    } else if (segments.size() == maxSegments) {
                        segments.remove(segments.keySet().iterator().next());
                    }
                    segments.put(transcript.segmentId(), transcript);
                    if (transcript.finalResult() || passPartials) next.accept(frame);
                    return;
                }
                if (frame instanceof Frame.Control control && control.signal() == Frame.Signal.INTERRUPT) onInterrupt();
                next.accept(frame);
            }

            @Override public void onInterrupt() {
                segments.values().removeIf(transcript -> !transcript.finalResult());
            }

            @Override public void close() { segments.clear(); }
        };
    }

    private static boolean sameFacts(Frame.Transcript first, Frame.Transcript second) {
        return first.text().equals(second.text()) && first.startMillis() == second.startMillis()
                && first.endMillis() == second.endMillis();
    }
}

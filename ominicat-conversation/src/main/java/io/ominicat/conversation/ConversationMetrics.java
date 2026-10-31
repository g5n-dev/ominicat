package io.ominicat.conversation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Interval-derived metrics over the retained window; these are not acoustic measurements. */
public record ConversationMetrics(long durationMillis, long speechMillis, long silenceMillis,
                                  Map<String, Long> speakerSpeechMillis) {
    public ConversationMetrics {
        speakerSpeechMillis = Map.copyOf(speakerSpeechMillis);
    }

    static ConversationMetrics from(List<Turn> turns) {
        if (turns.isEmpty()) {
            return new ConversationMetrics(0, 0, 0, Map.of());
        }
        long start = turns.stream().mapToLong(Turn::startMillis).min().orElseThrow();
        long end = turns.stream().mapToLong(Turn::endMillis).max().orElseThrow();
        long speech = unionDuration(turns);
        Map<String, List<Turn>> bySpeaker = new LinkedHashMap<>();
        for (Turn turn : turns) {
            bySpeaker.computeIfAbsent(turn.speaker(), ignored -> new ArrayList<>()).add(turn);
        }
        Map<String, Long> perSpeaker = new LinkedHashMap<>();
        bySpeaker.forEach((speaker, intervals) -> perSpeaker.put(speaker, unionDuration(intervals)));
        return new ConversationMetrics(end - start, speech, end - start - speech, perSpeaker);
    }

    private static long unionDuration(List<Turn> turns) {
        List<Turn> sorted = new ArrayList<>(turns);
        sorted.sort(Comparator.comparingLong(Turn::startMillis).thenComparingLong(Turn::endMillis));
        long total = 0;
        long start = sorted.getFirst().startMillis();
        long end = sorted.getFirst().endMillis();
        for (int i = 1; i < sorted.size(); i++) {
            Turn turn = sorted.get(i);
            if (turn.startMillis() <= end) {
                end = Math.max(end, turn.endMillis());
            } else {
                total += end - start;
                start = turn.startMillis();
                end = turn.endMillis();
            }
        }
        return total + end - start;
    }
}

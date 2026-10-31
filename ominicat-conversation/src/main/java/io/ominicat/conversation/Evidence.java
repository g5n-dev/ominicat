package io.ominicat.conversation;

/** A short, attributable excerpt supporting a rule result. */
public record Evidence(String speaker, long startMillis, long endMillis, String excerpt,
                       String segmentId, String role) {
    static Evidence from(Turn turn) {
        return around(turn, 0);
    }

    static Evidence around(Turn turn, int position) {
        int start = Math.max(0, position - 60);
        int end = Math.min(turn.text().length(), start + 180);
        String excerpt = (start > 0 ? "…" : "") + turn.text().substring(start, end)
                + (end < turn.text().length() ? "…" : "");
        return new Evidence(turn.speaker(), turn.startMillis(), turn.endMillis(), excerpt, turn.segmentId(), turn.role());
    }
}

package io.ominicat.conversation;

/** A short, attributable excerpt supporting a rule result. */
public record Evidence(String speaker, long startMillis, long endMillis, String excerpt,
                       String segmentId, String role) {
    static Evidence from(Turn turn) {
        return around(turn, 0);
    }

    static Evidence around(Turn turn, int position) {
        String text = turn.text();
        int start = Math.max(0, position - 60);
        int end = Math.min(text.length(), start + 180);
        // Shrink only split pairs; keep the original UTF-16 window and existing malformed input.
        if (start > 0 && start < text.length() && Character.isHighSurrogate(text.charAt(start - 1))
                && Character.isLowSurrogate(text.charAt(start))) start++;
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        String excerpt = (start > 0 ? "…" : "") + text.substring(start, end)
                + (end < text.length() ? "…" : "");
        return new Evidence(turn.speaker(), turn.startMillis(), turn.endMillis(), excerpt, turn.segmentId(), turn.role());
    }
}

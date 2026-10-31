package io.ominicat.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import java.util.UUID;

/** Bounded, process-local projection of explicitly observed connections. Never owns a frame or media buffer. */
final class ActivityJournal {
    static final int CAPACITY = 1024;
    static final int PAGE_SIZE = 128;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String streamId = UUID.randomUUID().toString();
    private final Event[] ring = new Event[CAPACITY];
    private long latest;
    private long sessions;

    synchronized ActivitySession openSession() { return new ActivitySession(this, "ws-" + streamId + "-" + ++sessions); }

    synchronized void append(String session, long invocation, int stage, String stageType, String phase,
                             Description frame, String outcome) {
        long seq = ++latest;
        String run = frame.correlationId().isEmpty() ? "" : session + ":correlation:" + frame.correlationId();
        ring[(int) ((seq - 1) % CAPACITY)] = new Event(seq, System.nanoTime(), session, frame.clientSessionId(), run,
                frame.correlationId(), frame.frameId(), frame.causationId(), invocation == 0 ? "" : session + ":invocation:" + invocation,
                stage < 0 ? "transport" : "stage-" + stage, stage, stageType, phase, frame.frameType(), frame.preview(), outcome);
    }

    synchronized String json(long after) throws JsonProcessingException {
        long oldest = latest == 0 ? 0 : Math.max(1, latest - CAPACITY + 1);
        boolean reset = after > latest;
        long cursor = reset ? 0 : after;
        long lost = oldest == 0 ? 0 : Math.max(0, oldest - cursor - 1);
        long first = Math.max(cursor + 1, oldest);
        long last = Math.min(latest, first + PAGE_SIZE - 1);
        ObjectNode response = mapper.createObjectNode().put("schemaVersion", 1).put("streamId", streamId)
                .put("capacity", CAPACITY).put("pageSize", PAGE_SIZE).put("oldestSeq", oldest).put("latestSeq", latest)
                .put("nextCursor", last >= first ? last : cursor).put("gap", lost > 0).put("lost", lost)
                .put("reset", reset).put("incomplete", reset || lost > 0);
        var events = response.putArray("events");
        for (long seq = first; seq <= last; seq++) {
            Event event = ring[(int) ((seq - 1) % CAPACITY)];
            events.addObject().put("seq", event.seq).put("timeNanos", Long.toString(event.timeNanos))
                    .put("sessionId", event.sessionId).put("clientSessionId", event.clientSessionId).put("runId", event.runId)
                    .put("correlationId", event.correlationId).put("frameId", event.frameId).put("causationId", event.causationId)
                    .put("invocationId", event.invocationId).put("stageId", event.stageId).put("stageIndex", event.stageIndex)
                    .put("stageType", event.stageType).put("phase", event.phase).put("frameType", event.frameType)
                    .put("preview", event.preview).put("outcome", event.outcome);
        }
        return mapper.writeValueAsString(response);
    }

    /** Only small immutable descriptions cross the observation boundary. No arbitrary serialization/toString. */
    static Description describe(Frame input) {
        String session = "", correlation = "", id = "", cause = "";
        Frame frame = input;
        if (input instanceof FrameEnvelope envelope) {
            session = envelope.sessionId(); correlation = envelope.correlationId(); id = envelope.id(); cause = envelope.causationId();
            frame = envelope.frame();
        }
        String type;
        String preview = "";
        if (frame instanceof Frame.Text value) { type = "text"; preview = clip(value.text(), 240); }
        else if (frame instanceof Frame.Transcript value) { type = "transcript"; preview = clip(value.text(), 240); }
        else if (frame instanceof Frame.Audio value) {
            type = "audio"; preview = "PCM16 " + value.sampleRate() + " Hz, " + value.channels() + " channel(s), "
                    + value.data().remaining() + " bytes, stream=" + clip(value.streamId(), 64);
        } else if (frame instanceof Frame.Image value) {
            type = "image"; preview = clip(value.mimeType(), 64) + ", " + value.data().remaining() + " bytes";
        } else if (frame instanceof Frame.Control value) { type = value.signal().name().toLowerCase(java.util.Locale.ROOT); }
        else if (frame instanceof Frame.Failure) { type = "failure"; }
        else if (frame instanceof Frame.Cancel) { type = "cancel"; }
        else { type = clip(frame.getClass().getSimpleName(), 64); }
        return new Description(session, correlation, id, cause, type, clip(preview, 240));
    }

    static String clip(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
    }

    record Description(String clientSessionId, String correlationId, String frameId, String causationId,
                       String frameType, String preview) {
        static final Description NONE = new Description("", "", "", "", "", "");
    }

    private record Event(long seq, long timeNanos, String sessionId, String clientSessionId, String runId,
                         String correlationId, String frameId, String causationId, String invocationId,
                         String stageId, int stageIndex, String stageType, String phase, String frameType,
                         String preview, String outcome) { }
}

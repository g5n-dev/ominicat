package io.ominicat.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Locale;

/** Explicit wire protocol: no Java class names or polymorphic deserialization from clients. */
public final class FrameCodec {
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(16 * 1024 * 1024).maxNumberLength(24).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Frame decode(String json) throws JsonProcessingException {
        JsonNode node = mapper.readTree(json);
        return decode(node, true);
    }

    private Frame decode(JsonNode node, boolean allowEnvelope) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("frame must be an object");
        return switch (text(node, "type")) {
            case "text" -> new Frame.Text(text(node, "text"));
            case "audio" -> new Frame.Audio(bytes(node), integer(node, "sampleRate"), integer(node, "channels"),
                    optionalText(node, "streamId", "default"), optionalLong(node, "startSample", -1));
            case "image" -> new Frame.Image(bytes(node), text(node, "mimeType"));
            case "transcript" -> transcript(node);
            case "envelope" -> {
                if (!allowEnvelope) throw new IllegalArgumentException("nested envelopes are not supported");
                long ingest = longValue(node, "ingestTimeNanos");
                yield new FrameEnvelope(decode(node.get("frame"), false), text(node, "id"), text(node, "sessionId"),
                        text(node, "turnId"), text(node, "correlationId"), optionalText(node, "causationId", ""),
                        longValue(node, "originTimeNanos"), ingest, optionalLong(node, "emitTimeNanos", ingest));
            }
            case "failure" -> new Frame.Failure(text(node, "code"), bool(node, "retryable"), text(node, "scope"),
                    text(node, "nodeId"), text(node, "message"));
            case "cancel" -> new Frame.Cancel(text(node, "reason"),
                    Frame.CancelScope.valueOf(text(node, "scope").toUpperCase(Locale.ROOT)), text(node, "targetId"));
            case "interrupt" -> new Frame.Control(Frame.Signal.INTERRUPT);
            case "start" -> new Frame.Control(Frame.Signal.START);
            case "end" -> new Frame.Control(Frame.Signal.END);
            case "turn_end" -> new Frame.Control(Frame.Signal.TURN_END);
            default -> throw new IllegalArgumentException("unknown frame type");
        };
    }

    public String encode(Frame frame) throws JsonProcessingException {
        return mapper.writeValueAsString(encodeNode(frame));
    }

    private ObjectNode encodeNode(Frame frame) {
        ObjectNode node = mapper.createObjectNode();
        switch (frame) {
            case Frame.Text value -> node.put("type", "text").put("text", value.text());
            case Frame.Audio value -> node.put("type", "audio").put("data", base64(value.data()))
                    .put("sampleRate", value.sampleRate()).put("channels", value.channels())
                    .put("streamId", value.streamId()).put("startSample", value.startSample());
            case Frame.Image value -> node.put("type", "image").put("data", base64(value.data())).put("mimeType", value.mimeType());
            case Frame.Transcript value -> node.put("type", "transcript").put("text", value.text()).put("speaker", value.speaker())
                    .put("startMillis", value.startMillis()).put("endMillis", value.endMillis())
                    .put("finalResult", value.finalResult()).put("confidence", value.confidence())
                    .put("segmentId", value.segmentId()).put("revision", value.revision()).put("role", value.role());
            case Frame.Control value -> node.put("type", value.signal().name().toLowerCase(Locale.ROOT));
            case Frame.Failure value -> node.put("type", "failure").put("code", value.code()).put("retryable", value.retryable())
                    .put("scope", value.scope()).put("nodeId", value.nodeId()).put("message", value.message());
            case Frame.Cancel value -> node.put("type", "cancel").put("reason", value.reason())
                    .put("scope", value.scope().name().toLowerCase(Locale.ROOT)).put("targetId", value.targetId());
            case FrameEnvelope value -> {
                node.put("type", "envelope").put("id", value.id()).put("sessionId", value.sessionId())
                        .put("turnId", value.turnId()).put("correlationId", value.correlationId()).put("causationId", value.causationId())
                        .put("originTimeNanos", value.originTimeNanos()).put("ingestTimeNanos", value.ingestTimeNanos()).put("emitTimeNanos", value.emitTimeNanos());
                node.set("frame", encodeNode(value.frame()));
            }
            default -> {
                node.put("type", frame.getClass().getSimpleName());
                node.set("data", mapper.valueToTree(frame));
            }
        }
        return node;
    }

    public String error(String code, String message) {
        ObjectNode node = mapper.createObjectNode().put("type", "error").put("code", code).put("message", message);
        return node.toString();
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(name + " must be a string");
        return value.textValue();
    }

    private static Frame.Transcript transcript(JsonNode node) {
        String speaker = text(node, "speaker");
        long start = longValue(node, "startMillis"), end = longValue(node, "endMillis");
        return new Frame.Transcript(text(node, "text"), speaker, start, end, bool(node, "finalResult"), number(node, "confidence"),
                optionalText(node, "segmentId", speaker + ":" + start + "-" + end), optionalLong(node, "revision", 0), optionalText(node, "role", speaker));
    }

    private static String optionalText(JsonNode node, String name, String fallback) {
        return node.has(name) ? text(node, name) : fallback;
    }

    private static long optionalLong(JsonNode node, String name, long fallback) {
        return node.has(name) ? longValue(node, name) : fallback;
    }

    private static long longValue(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException(name + " must be an integer");
        return value.longValue();
    }

    private static int integer(JsonNode node, String name) {
        long value = longValue(node, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException(name + " is out of range");
        return (int) value;
    }

    private static boolean bool(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException(name + " must be a boolean");
        return value.booleanValue();
    }

    private static double number(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isNumber()) throw new IllegalArgumentException(name + " must be a number");
        return value.doubleValue();
    }

    private static ByteBuffer bytes(JsonNode node) {
        return ByteBuffer.wrap(Base64.getDecoder().decode(text(node, "data")));
    }

    private static String base64(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.duplicate().get(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}

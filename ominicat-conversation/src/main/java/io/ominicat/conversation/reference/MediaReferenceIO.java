package io.ominicat.conversation.reference;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A small version-1 binary import/export adapter for {@link MediaReference}.
 * This is not a WebSocket/JSON codec. Records preserve Java long precision, contain
 * no media payload and have eight bounded UTF-8 strings. Caller owns stream lifetime,
 * framing and commit policy; an I/O failure can leave a partially written record.
 */
public final class MediaReferenceIO {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_STRING_BYTES = 1024;
    /** Hard upper bound from the fixed fields and eight length-prefixed strings. */
    public static final int MAX_RECORD_BYTES = 8 * (2 + MAX_STRING_BYTES) + 4 + 1 + 1 + 4 + 8 + 1 + 8 + 8;
    private static final int MAGIC = 0x4f4d5246; // OMRF

    private MediaReferenceIO() { }

    /** Write one record without flushing or closing the caller's output. */
    public static void write(MediaReference reference, DataOutput output) throws IOException {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(output, "output");
        MediaReference.Source source = reference.source();
        output.writeInt(MAGIC);
        output.writeByte(FORMAT_VERSION);
        writeString(source.namespace(), output);
        writeString(source.id(), output);
        writeString(source.version(), output);
        writeString(source.track(), output);
        writeString(source.timeBasis(), output);
        output.writeByte(switch (source.unit()) {
            case PCM_SAMPLES -> 1;
            case MEDIA_MILLISECONDS -> 2;
            case FRAME_INDEX -> 3;
        });
        output.writeInt(source.sampleRate());
        writeString(source.sha256(), output);
        writeString(reference.producerScope(), output);
        writeString(reference.segmentId(), output);
        output.writeLong(reference.revision());
        output.writeByte(switch (reference.position().kind()) {
            case RANGE -> 1;
            case POINT -> 2;
            case UNKNOWN -> 3;
        });
        output.writeLong(reference.position().start());
        output.writeLong(reference.position().end());
    }

    /**
     * Read exactly one record, leaving following records to the caller. Unsupported
     * versions, malformed UTF-8, oversize lengths and invalid value states fail.
     * Lengths are checked before allocation; truncated records raise IOException.
     */
    public static MediaReference read(DataInput input) throws IOException {
        Objects.requireNonNull(input, "input");
        if (input.readInt() != MAGIC) throw new IOException("invalid media-reference magic");
        if (input.readUnsignedByte() != FORMAT_VERSION) throw new IOException("unsupported media-reference version");
        String namespace = readString(input), id = readString(input), version = readString(input);
        String track = readString(input), timeBasis = readString(input);
        MediaReference.Unit unit = switch (input.readUnsignedByte()) {
            case 1 -> MediaReference.Unit.PCM_SAMPLES;
            case 2 -> MediaReference.Unit.MEDIA_MILLISECONDS;
            case 3 -> MediaReference.Unit.FRAME_INDEX;
            default -> throw new IOException("unsupported media-reference unit");
        };
        int sampleRate = input.readInt();
        String sha256 = readString(input), scope = readString(input), segmentId = readString(input);
        long revision = input.readLong();
        MediaReference.Kind kind = switch (input.readUnsignedByte()) {
            case 1 -> MediaReference.Kind.RANGE;
            case 2 -> MediaReference.Kind.POINT;
            case 3 -> MediaReference.Kind.UNKNOWN;
            default -> throw new IOException("unsupported media-reference position kind");
        };
        long start = input.readLong(), end = input.readLong();
        try {
            return new MediaReference(new MediaReference.Source(namespace, id, version, track, timeBasis,
                    unit, sampleRate, sha256), scope, segmentId, revision, new MediaReference.Position(kind, start, end));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid media-reference value", invalid);
        }
    }

    private static void writeString(String value, DataOutput output) throws IOException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        if (encoded.remaining() > MAX_STRING_BYTES) throw new IOException("media-reference string exceeds byte budget");
        output.writeShort(encoded.remaining());
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        output.write(bytes);
    }

    private static String readString(DataInput input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAX_STRING_BYTES) throw new IOException("media-reference string exceeds byte budget");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}

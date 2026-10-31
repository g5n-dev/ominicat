package io.ominicat.conversation.reference;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaReferenceIOTest {
    private static final int MAGIC = 0x4f4d5246;

    private static byte[] export(MediaReference reference) throws IOException {
        var bytes = new ByteArrayOutputStream();
        MediaReferenceIO.write(reference, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static MediaReference read(byte[] bytes) throws IOException {
        return MediaReferenceIO.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    @Test void immutableImportExportFixturePreservesQualifiedIdentityPositionsAndFullLongPrecision() throws Exception {
        var source = MediaReferenceTest.source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, "a".repeat(64));
        var first = new MediaReference(source, "producer/run-A", "段/segment-1🎧", 9_007_199_254_740_993L,
                MediaReference.Position.range(9_007_199_254_740_993L, Long.MAX_VALUE));
        var second = new MediaReference(source, "producer/run-B", "段/segment-1", 0, MediaReference.Position.point(0));
        var unknown = new MediaReference(source, "producer/run-A", "unknown", 0, MediaReference.Position.unknown());
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        for (var reference : List.of(first, second, unknown)) MediaReferenceIO.write(reference, output);
        var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(first, MediaReferenceIO.read(input));
        assertEquals(second, MediaReferenceIO.read(input));
        assertEquals(unknown, MediaReferenceIO.read(input));
        assertEquals(-1, input.read());
        assertNotEquals(first, second);
        assertArrayEquals(export(first), export(read(export(first))));
    }

    @Test void allSupportedUnitsAndKindsRoundTripWithoutNormalization() throws Exception {
        for (var unit : MediaReference.Unit.values()) {
            var source = MediaReferenceTest.source(unit, unit == MediaReference.Unit.PCM_SAMPLES ? 16_000 : 0, "");
            for (var position : List.of(MediaReference.Position.range(1, 2), MediaReference.Position.point(1), MediaReference.Position.unknown())) {
                var reference = new MediaReference(source, " scope/Case ", "id:#保持", 12, position);
                assertEquals(reference, read(export(reference)));
            }
        }
    }

    @Test void maximumStringsStayWithinTheDeclaredRecordBudget() throws Exception {
        var source = new MediaReference.Source("界".repeat(128), "界".repeat(256), "界".repeat(256),
                "界".repeat(256), "界".repeat(256), MediaReference.Unit.MEDIA_MILLISECONDS, 0, "a".repeat(64));
        var reference = new MediaReference(source, "界".repeat(256), "界".repeat(256), Long.MAX_VALUE,
                MediaReference.Position.range(0, Long.MAX_VALUE));
        byte[] encoded = export(reference);
        assertTrue(encoded.length <= MediaReferenceIO.MAX_RECORD_BYTES);
        assertEquals(reference, read(encoded));
    }

    @Test void unknownVersionAndMagicFailBeforeReadingAnyFields() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeByte(2);
        assertThrows(IOException.class, () -> read(bytes.toByteArray()));
        bytes.reset();
        output.writeInt(0);
        output.writeByte(MediaReferenceIO.FORMAT_VERSION);
        assertThrows(IOException.class, () -> read(bytes.toByteArray()));
    }

    @Test void declaredStringLengthIsCheckedBeforeReadingOrAllocatingItsPayload() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeByte(MediaReferenceIO.FORMAT_VERSION);
        output.writeShort(MediaReferenceIO.MAX_STRING_BYTES + 1);
        IOException failure = assertThrows(IOException.class, () -> read(bytes.toByteArray()));
        assertEquals("media-reference string exceeds byte budget", failure.getMessage());
    }

    @Test void malformedUtf8DoesNotBecomeAReplacementCharacter() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeByte(MediaReferenceIO.FORMAT_VERSION);
        output.writeShort(2);
        output.write(new byte[]{(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> read(bytes.toByteArray()));
    }

    @Test void everyTruncatedRecordFailsInsteadOfReturningPartialData() throws Exception {
        var reference = new MediaReference(MediaReferenceTest.source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, ""),
                "run", "segment", 0, MediaReference.Position.range(0, 10));
        byte[] complete = export(reference);
        for (int length = 0; length < complete.length; length++) {
            byte[] truncated = Arrays.copyOf(complete, length);
            assertThrows(IOException.class, () -> read(truncated), "truncated length=" + length);
        }
    }

    @Test void invalidImportedRevisionAndPositionKindAreRejected() throws Exception {
        var reference = new MediaReference(MediaReferenceTest.source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, ""),
                "run", "segment", 0, MediaReference.Position.range(0, 10));
        byte[] negativeRevision = export(reference);
        Arrays.fill(negativeRevision, negativeRevision.length - 25, negativeRevision.length - 17, (byte) 0xff);
        assertThrows(IOException.class, () -> read(negativeRevision));
        byte[] unsupportedKind = export(reference);
        unsupportedKind[unsupportedKind.length - 17] = 127;
        assertThrows(IOException.class, () -> read(unsupportedKind));
        byte[] emptyRange = export(reference);
        Arrays.fill(emptyRange, emptyRange.length - 8, emptyRange.length, (byte) 0);
        assertThrows(IOException.class, () -> read(emptyRange));
    }

    @Test void adapterDoesNotCloseOrFlushCallerOwnedStreams() throws Exception {
        class OwnedOutput extends ByteArrayOutputStream {
            boolean closed;
            boolean flushed;
            @Override public void close() { closed = true; }
            @Override public void flush() { flushed = true; }
        }
        class OwnedInput extends ByteArrayInputStream {
            boolean closed;
            OwnedInput(byte[] bytes) { super(bytes); }
            @Override public void close() { closed = true; }
        }
        var reference = new MediaReference(MediaReferenceTest.source(MediaReference.Unit.MEDIA_MILLISECONDS, 0, ""),
                "run", "segment", 0, MediaReference.Position.point(0));
        var output = new OwnedOutput();
        MediaReferenceIO.write(reference, new DataOutputStream(output));
        assertFalse(output.closed);
        assertFalse(output.flushed);
        var input = new OwnedInput(output.toByteArray());
        assertEquals(reference, MediaReferenceIO.read(new DataInputStream(input)));
        assertFalse(input.closed);
    }
}

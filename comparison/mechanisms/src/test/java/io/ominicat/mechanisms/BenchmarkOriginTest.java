package io.ominicat.mechanisms;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkOriginTest {
    @TempDir Path temporary;

    @Test void requiresAnExplicitValidFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkOrigin.expectedHash(null));
        for (String value : new String[]{"", "wrong", "a".repeat(63), "g".repeat(64), "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> BenchmarkOrigin.expectedHash(value));
        }
        assertEquals("a".repeat(64), BenchmarkOrigin.expectedHash("A".repeat(64)));
    }

    @Test void verifiesActualBytesAndRejectsWrongHashOrPostHashMutation() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3, 4};
        Path artifact = temporary.resolve("fixture.jar");
        Files.write(artifact, bytes);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(expected, BenchmarkOrigin.verifyArtifact(artifact, expected));
        assertThrows(IllegalStateException.class, () -> BenchmarkOrigin.verifyArtifact(artifact, "0".repeat(64)));
        Files.write(artifact, new byte[]{9});
        assertThrows(IllegalStateException.class, () -> BenchmarkOrigin.verifyArtifact(artifact, expected));
        assertThrows(IllegalStateException.class, () -> BenchmarkOrigin.verifyArtifact(temporary, expected));
    }
}

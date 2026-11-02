package io.ominicat.mechanisms;

import io.ominicat.core.Pipeline;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.component.direct.DirectProducer;
import org.apache.camel.support.ExchangeHelper;

/** Trial-only origin checks; no filesystem work belongs to a measured operation. */
final class BenchmarkOrigin {
    static final String PROPERTY = "ominicat.mechanism.jarSha256";

    private BenchmarkOrigin() { }

    static void verify(Class<?> anchor) throws Exception {
        if (Runtime.version().feature() != 17) throw new IllegalStateException("mechanism measurements require Java 17");
        String expected = expectedHash(System.getProperty(PROPERTY));
        Path artifact = origin(anchor);
        for (Class<?> dependency : new Class<?>[]{Pipeline.class, DefaultCamelContext.class, DirectProducer.class, ExchangeHelper.class}) {
            if (!artifact.equals(origin(dependency))) throw new IllegalStateException("mechanism dependencies must come from the measured JAR");
        }
        String actual = verifyArtifact(artifact, expected);
        System.out.println("MECHANISM_ORIGIN_SHA256=" + actual);
    }

    static String expectedHash(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("the measured JAR SHA-256 is required");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    static String verifyArtifact(Path artifact, String expected) throws Exception {
        expected = expectedHash(expected);
        if (!Files.isRegularFile(artifact)) throw new IllegalStateException("mechanism origin must be a JAR file");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expected)) throw new IllegalStateException("measured JAR SHA-256 mismatch");
        return actual;
    }

    private static Path origin(Class<?> type) throws Exception {
        if (type.getProtectionDomain().getCodeSource() == null) throw new IllegalStateException("mechanism origin unavailable");
        var location = type.getProtectionDomain().getCodeSource().getLocation();
        if (!"file".equals(location.getProtocol())) throw new IllegalStateException("mechanism origin must be a local JAR");
        Path artifact = Path.of(location.toURI()).toRealPath();
        if (!Files.isRegularFile(artifact)) throw new IllegalStateException("mechanism origin must be a JAR file");
        return artifact;
    }
}

package io.ominicat.mechanisms;

import org.apache.camel.ExchangePropertyKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeCopyFixtureTest {
    @Test void exactCorrelatedCopyFlagsPreserveImmutableBodyAndMetadataWithoutMutatingSource() throws Exception {
        try (var fixture = new ExchangeCopyFixture()) {
            var source = fixture.source();
            Object body = source.getMessage().getBody();
            for (int i = 0; i < 32; i++) {
                assertSame(source, fixture.invoke(false));
                var copy = fixture.invoke(true);
                assertNotSame(source, copy);
                assertSame(body, copy.getMessage().getBody());
                assertEquals(ExchangeCopyFixture.MESSAGE_ID, copy.getIn().getMessageId());
                assertEquals(ExchangeCopyFixture.SOURCE_ID, copy.getProperty(ExchangePropertyKey.CORRELATION_ID));
                assertNull(copy.getUnitOfWork());
                copy.getMessage().setHeader("label", "changed-copy");
                copy.setProperty("fixture", "changed-copy");
                fixture.validate(source);
                assertEquals(ExchangeCopyFixture.SOURCE_ID, source.getExchangeId());
            }
        }
    }

    @Test void sharedReadOracleRejectsBodyAndMetadataDrift() throws Exception {
        try (var fixture = new ExchangeCopyFixture()) {
            var wrongBody = fixture.invoke(true);
            wrongBody.getMessage().setBody(new ExchangeCopyFixture.Payload(1, 2));
            assertThrows(IllegalStateException.class, () -> fixture.validate(wrongBody));
            var wrongMetadata = fixture.invoke(true);
            wrongMetadata.getIn().setMessageId("changed");
            assertThrows(IllegalStateException.class, () -> fixture.validate(wrongMetadata));
            fixture.validate(fixture.source());
        }
    }
}

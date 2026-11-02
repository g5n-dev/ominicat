package io.ominicat.mechanisms;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.ExchangeHelper;

/** Fixed small metadata fixture; correlated copying shares this immutable body by identity. */
final class ExchangeCopyFixture implements AutoCloseable {
    static final String SOURCE_ID = "synthetic-source";
    static final String MESSAGE_ID = "synthetic-message";
    static final String LABEL = "synthetic-label";
    static final String PROPERTY_VALUE = "synthetic-property";
    record Payload(long sequence, long value) { }
    private final DefaultCamelContext context = new DefaultCamelContext();
    private final Payload body = new Payload(1, 0x12345678abcdef01L);
    private final Exchange source;

    ExchangeCopyFixture() throws Exception {
        try {
            if (!"4.22.2".equals(context.getVersion())) throw new IllegalStateException("unexpected Camel version");
            context.disableJMX();
            context.start();
            source = new DefaultExchange(context, ExchangePattern.InOut);
            source.setExchangeId(SOURCE_ID);
            source.getIn().setMessageId(MESSAGE_ID);
            source.getIn().setBody(body);
            source.getIn().setHeader("label", LABEL);
            source.setProperty("fixture", PROPERTY_VALUE);
            // Identity and copy can use exactly the same read oracle, including correlation metadata.
            source.setProperty(ExchangePropertyKey.CORRELATION_ID, SOURCE_ID);
            source.getExchangeId();
            source.getIn().getMessageId();
            validate(source);
        } catch (Throwable error) {
            try { context.stop(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
            if (error instanceof Exception exception) throw exception;
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("exchange fixture initialization failed", error);
        }
    }

    Exchange invoke(boolean copy) {
        // Exact flags used by the pinned SEDA request/reply producer; this is not a SEDA route.
        Exchange result = copy ? ExchangeHelper.createCorrelatedCopy(source, false, true) : source;
        validate(result);
        if ((result == source) == copy) throw new IllegalStateException("unexpected exchange identity");
        return result;
    }

    void validate(Exchange exchange) {
        if (exchange.getPattern() != ExchangePattern.InOut || exchange.getMessage().getBody() != body
                || !LABEL.equals(exchange.getMessage().getHeader("label"))
                || !PROPERTY_VALUE.equals(exchange.getProperty("fixture"))
                || !SOURCE_ID.equals(exchange.getProperty(ExchangePropertyKey.CORRELATION_ID))
                || !MESSAGE_ID.equals(exchange.getIn().getMessageId())
                || exchange.getUnitOfWork() != null) {
            throw new IllegalStateException("exchange body or metadata mismatch");
        }
    }

    Exchange source() { return source; }

    @Override public void close() throws Exception {
        context.stop();
        if (!context.isStopped()) throw new IllegalStateException("exchange fixture did not stop");
    }
}

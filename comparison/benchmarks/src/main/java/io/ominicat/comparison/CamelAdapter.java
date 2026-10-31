package io.ominicat.comparison;

import io.ominicat.comparison.NumericWorkload.Terminal;
import io.ominicat.comparison.NumericWorkload.Value;
import java.util.concurrent.TimeUnit;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.seda.SedaEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.RouteDefinition;

/** Native bounded SEDA, including Exchange creation/copying and request/reply acknowledgment. */
final class CamelAdapter implements QueuedAdapter {
    private static final String ENDPOINT = "seda:numeric?size=1&concurrentConsumers=1&multipleConsumers=false"
            + "&blockWhenFull=false&discardWhenFull=false&discardIfNoConsumers=false&failIfNoConsumers=true"
            + "&waitForTaskToComplete=Always&timeout=10000&purgeWhenStopping=false&pollTimeout=10"
            + "&virtualThreadPerTask=false";
    private final DefaultCamelContext context;
    private final ProducerTemplate producer;
    private final SedaEndpoint endpoint;
    private final Terminal terminal;
    private long acknowledged;

    CamelAdapter(int stages, Terminal terminal) throws Exception {
        this.terminal = terminal;
        context = new DefaultCamelContext();
        ProducerTemplate created = null;
        try {
            context.disableJMX();
            context.setMessageHistory(false);
            context.setStreamCaching(false);
            context.getShutdownStrategy().setTimeout(10);
            context.getShutdownStrategy().setTimeUnit(TimeUnit.SECONDS);
            context.addRoutes(new RouteBuilder() {
                @Override public void configure() {
                    RouteDefinition route = from(ENDPOINT).routeId("numeric-comparison");
                    for (int index = 0; index < stages; index++) {
                        int stage = index;
                        route.process(exchange -> {
                            Object body = exchange.getMessage().getBody();
                            if (body instanceof Value value) {
                                if (stage == 0) terminal.observeFirstStage(value);
                                exchange.getMessage().setBody(value.transform(stage));
                            }
                        });
                    }
                    route.process(exchange -> terminal.accept(exchange.getMessage().getBody()));
                }
            });
            context.start();
            created = context.createProducerTemplate();
            created.start();
            endpoint = context.getEndpoint(ENDPOINT, SedaEndpoint.class);
            if (endpoint.getQueue().remainingCapacity() != 1) throw new IllegalStateException("SEDA queue is not bounded to one");
            producer = created;
        } catch (Throwable failure) {
            if (created != null) {
                try { created.stop(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            try { context.stop(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public Value invoke(Value input) {
        long before = terminal.count();
        terminal.expect(input);
        Value output = producer.requestBody(endpoint, input, Value.class);
        terminal.assertAcknowledged(input, output, before);
        acknowledged++;
        return output;
    }

    @Override public void assertDrained() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!endpoint.getQueue().isEmpty() || context.getInflightRepository().size() != 0) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Camel work did not drain");
            Thread.sleep(1);
        }
        if (acknowledged != terminal.count()) throw new IllegalStateException("Camel terminal count mismatch");
    }

    @Override public String version() { return context.getVersion(); }

    @Override public void close() throws Exception {
        try {
            assertDrained();
        } finally {
            try { producer.stop(); }
            finally { context.stop(); }
        }
        if (!context.isStopped()) throw new IllegalStateException("Camel cleanup incomplete");
        Thread worker = terminal.worker();
        if (worker != null) {
            worker.join(10_000);
            if (worker.isAlive()) throw new IllegalStateException("SEDA consumer did not terminate");
        }
    }
}

package io.ominicat.mechanisms;

import io.ominicat.core.Frame;
import io.ominicat.core.Pipeline;
import io.ominicat.core.Processor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.ExchangePattern;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;

/** One outstanding invocation. Both frameworks use this exact external handoff implementation. */
final class PathHarness implements AutoCloseable {
    static final int STAGES = 4;
    private static final long SALT = 0x9e3779b97f4a7c15L;
    private static final long WATCHDOG_SECONDS = 10;
    enum Fault { NONE, WRONG_VALUE, MISSING_OUTPUT, DUPLICATE_OUTPUT, POST_OUTPUT_THROW }
    public record Value(long sequence, long value) implements Frame {
        Value transform(int stage) { return new Value(sequence, Long.rotateLeft(value ^ (SALT + stage), 7)); }
    }
    private record Reply(Value value, Throwable failure) { }
    private final Thread caller = Thread.currentThread();
    private final Oracle oracle = new Oracle();
    private final Backend backend;
    private final ArrayBlockingQueue<Value> ingress;
    private final ArrayBlockingQueue<Reply> replies;
    private final Thread worker;
    private volatile boolean closed;
    private volatile Throwable failure;
    private long acknowledged;

    PathHarness(String framework, String execution, Fault fault) throws Exception {
        if (!execution.equals("caller") && !execution.equals("handoff")) throw new IllegalArgumentException("unknown execution");
        backend = switch (framework) {
            case "ominicat" -> new OminiCatBackend(oracle, terminal(oracle, fault));
            case "camel" -> new CamelBackend(oracle, terminal(oracle, fault));
            default -> throw new IllegalArgumentException("unknown framework");
        };
        if (execution.equals("handoff")) {
            ingress = new ArrayBlockingQueue<>(1);
            replies = new ArrayBlockingQueue<>(1);
            worker = new Thread(this::run, "mechanism-handoff");
            worker.setDaemon(true);
            oracle.owner = worker;
            worker.start();
        } else {
            ingress = null;
            replies = null;
            worker = null;
            oracle.owner = caller;
        }
    }

    Value invoke(Value input) throws Exception {
        if (Thread.currentThread() != caller) throw new IllegalStateException("one caller thread is required");
        if (closed) throw new IllegalStateException("path is closed");
        if (failure != null) rethrow(failure);
        try {
            Value output;
            if (worker == null) output = checked(input);
            else {
                if (!ingress.offer(input)) throw new IllegalStateException("external ingress is full");
                Reply reply = replies.poll(WATCHDOG_SECONDS, TimeUnit.SECONDS);
                if (reply == null) throw new IllegalStateException("external acknowledgment timed out");
                if (reply.failure() != null) rethrow(reply.failure());
                output = reply.value();
            }
            if (output == null || output.sequence() != input.sequence() || output.value() != oracle.expected(input.sequence(), STAGES)
                    || oracle.completed != acknowledged + 1) throw new IllegalStateException("caller acknowledgment mismatch");
            acknowledged++;
            return output;
        } catch (Throwable error) {
            failure = error;
            rethrow(error);
            throw new AssertionError("unreachable");
        }
    }

    private Value checked(Value input) throws Exception {
        oracle.begin(input);
        try {
            Value result = backend.invoke(input);
            // No reply is published until the backend returns, even if it previously emitted a valid output.
            oracle.returned(result);
            return result;
        } finally { oracle.end(); }
    }

    private void run() {
        try {
            while (!closed) {
                Value input = ingress.take();
                Reply reply;
                try { reply = new Reply(checked(input), null); }
                catch (Throwable error) { reply = new Reply(null, error); }
                if (!replies.offer(reply)) throw new IllegalStateException("duplicate external acknowledgment");
                if (reply.failure() != null) return;
            }
        } catch (InterruptedException interrupted) {
            if (!closed) {
                failure = interrupted;
                replies.offer(new Reply(null, interrupted));
            }
        } catch (Throwable error) {
            failure = error;
            replies.offer(new Reply(null, error));
        }
    }

    void assertDrained() throws Exception {
        if (failure != null) rethrow(failure);
        if (oracle.active || oracle.completed != acknowledged
                || ingress != null && (!ingress.isEmpty() || !replies.isEmpty())) throw new IllegalStateException("path did not drain");
        backend.assertDrained();
    }

    Thread worker() { return worker; }
    Thread stageOwner() { return oracle.owner; }
    long completed() { return oracle.completed; }

    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        if (worker != null) {
            worker.interrupt();
            try { worker.join(TimeUnit.SECONDS.toMillis(WATCHDOG_SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
            if (worker.isAlive()) throw new IllegalStateException("external worker did not terminate");
        }
        try {
            if (oracle.active || ingress != null && (!ingress.isEmpty() || !replies.isEmpty())) {
                throw new IllegalStateException("closing path retained work");
            }
            backend.assertDrained();
        } finally { backend.close(); }
    }

    private static Consumer<Value> terminal(Oracle oracle, Fault fault) {
        return switch (fault) {
            case NONE -> oracle::emit;
            case WRONG_VALUE -> value -> oracle.emit(new Value(value.sequence(), value.value() ^ 1));
            case MISSING_OUTPUT -> value -> { };
            case DUPLICATE_OUTPUT -> value -> { oracle.emit(value); oracle.emit(value); };
            case POST_OUTPUT_THROW -> value -> { oracle.emit(value); throw new IllegalStateException("synthetic post-output failure"); };
        };
    }

    static final class Oracle {
        private final long[] masks = new long[STAGES + 1];
        private Thread owner;
        private boolean active;
        private Value input;
        private Value output;
        private int stages;
        private int emissions;
        private long completed;
        Oracle() { this(null); }
        Oracle(Thread owner) {
            this.owner = owner;
            for (int i = 0; i < STAGES; i++) masks[i + 1] = Long.rotateLeft(masks[i] ^ (SALT + i), 7);
        }
        long expected(long sequence, int stage) { return Long.rotateLeft(sequence, stage * 7) ^ masks[stage]; }
        void begin(Value value) {
            thread();
            if (active || value == null || value.sequence() != completed + 1 || value.value() != value.sequence()) {
                throw new IllegalStateException("invalid or overlapping input");
            }
            active = true; input = value; stages = 0; emissions = 0; output = null;
        }
        void stage(int index, Object frame) {
            thread();
            if (!active || index != stages || !(frame instanceof Value value)
                    || value.sequence() != input.sequence() || value.value() != expected(input.sequence(), index)) {
                throw new IllegalStateException("stage sequence, value or order mismatch");
            }
            stages++;
        }
        void emit(Value value) {
            thread();
            if (!active || stages != STAGES || emissions++ != 0 || value.sequence() != input.sequence()
                    || value.value() != expected(input.sequence(), STAGES)) throw new IllegalStateException("terminal output mismatch");
            output = value;
        }
        Value output() { return output; }
        void returned(Value value) {
            thread();
            if (!active || stages != STAGES || emissions != 1 || value == null || output == null
                    || value.sequence() != output.sequence() || value.value() != output.value()) {
                throw new IllegalStateException("backend returned without exactly one valid terminal output");
            }
            completed++;
        }
        void end() { active = false; input = null; output = null; }
        private void thread() { if (Thread.currentThread() != owner) throw new IllegalStateException("stage/terminal ran on the wrong thread"); }
    }

    private interface Backend extends AutoCloseable {
        Value invoke(Value input) throws Exception;
        void assertDrained();
        @Override void close() throws Exception;
    }

    private static final class OminiCatBackend implements Backend {
        private final Pipeline pipeline;
        private final Oracle oracle;
        private final Consumer<Value> terminal;
        private final List<Processor> processors = new ArrayList<>();
        OminiCatBackend(Oracle oracle, Consumer<Value> terminal) {
            this.oracle = oracle; this.terminal = terminal;
            var builder = Pipeline.builder();
            for (int i = 0; i < STAGES; i++) {
                int index = i;
                Processor processor = (frame, next) -> {
                    oracle.stage(index, frame);
                    next.accept(((Value) frame).transform(index));
                };
                processors.add(processor); builder.add(processor);
            }
            pipeline = builder.build();
        }
        @Override public Value invoke(Value input) throws Exception {
            pipeline.process(input, frame -> terminal.accept((Value) frame));
            return oracle.output();
        }
        @Override public void assertDrained() { }
        @Override public void close() throws Exception {
            Throwable failure = null;
            for (int i = processors.size() - 1; i >= 0; i--) {
                try { processors.get(i).close(); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (failure != null) rethrow(failure);
        }
    }

    private static final class CamelBackend implements Backend {
        private static final String URI = "direct:mechanism?block=false&failIfNoConsumers=true&synchronous=true";
        private final DefaultCamelContext context = new DefaultCamelContext();
        private final ProducerTemplate producer;
        private final org.apache.camel.Endpoint endpoint;
        CamelBackend(Oracle oracle, Consumer<Value> terminal) throws Exception {
            ProducerTemplate created = null;
            try {
                if (!"4.22.2".equals(context.getVersion())) throw new IllegalStateException("unexpected Camel version");
                context.disableJMX(); context.setMessageHistory(false); context.setStreamCaching(false);
                context.getShutdownStrategy().setTimeout(WATCHDOG_SECONDS);
                context.getShutdownStrategy().setTimeUnit(TimeUnit.SECONDS);
                context.addRoutes(new RouteBuilder() {
                    @Override public void configure() {
                        var route = from(URI).routeId("mechanism-direct");
                        for (int i = 0; i < STAGES; i++) {
                            int index = i;
                            route.process(exchange -> {
                                if (exchange.getPattern() != ExchangePattern.InOut) throw new IllegalStateException("route must be InOut");
                                Object body = exchange.getMessage().getBody();
                                oracle.stage(index, body);
                                exchange.getMessage().setBody(((Value) body).transform(index));
                            });
                        }
                        route.process(exchange -> terminal.accept((Value) exchange.getMessage().getBody()));
                    }
                });
                context.start();
                created = context.createProducerTemplate(); created.start();
                endpoint = context.getEndpoint(URI);
                producer = created;
            } catch (Throwable error) {
                if (created != null) try { created.stop(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                try { context.stop(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                rethrow(error);
                throw new AssertionError("unreachable");
            }
        }
        @Override public Value invoke(Value input) { return producer.requestBody(endpoint, input, Value.class); }
        @Override public void assertDrained() {
            if (context.getInflightRepository().size() != 0) throw new IllegalStateException("Camel route remains in flight");
        }
        @Override public void close() throws Exception {
            try { producer.stop(); } finally { context.stop(); }
            if (!context.isStopped()) throw new IllegalStateException("Camel did not stop");
        }
    }

    private static void rethrow(Throwable error) throws Exception {
        if (error instanceof Error fatal) throw fatal;
        if (error instanceof Exception exception) throw exception;
        throw new IllegalStateException("mechanism execution failed", error);
    }
}

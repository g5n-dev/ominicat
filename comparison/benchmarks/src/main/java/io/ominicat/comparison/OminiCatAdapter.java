package io.ominicat.comparison;

import io.ominicat.comparison.NumericWorkload.Terminal;
import io.ominicat.comparison.NumericWorkload.Value;
import io.ominicat.core.Pipeline;
import io.ominicat.core.PipelineSession;
import io.ominicat.core.SessionMetrics;
import io.ominicat.core.SessionOptions;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Natural trySubmit admission and a bounded terminal-to-caller handoff. */
final class OminiCatAdapter implements QueuedAdapter {
    private final Terminal terminal;
    private final ArrayBlockingQueue<Value> outputs = new ArrayBlockingQueue<>(1);
    private final PipelineSession session;

    OminiCatAdapter(int stages, Terminal terminal) {
        this.terminal = terminal;
        Pipeline.Builder builder = Pipeline.builder();
        for (int index = 0; index < stages; index++) {
            int stage = index;
            builder.add((frame, next) -> {
                if (frame instanceof Value value) {
                    if (stage == 0) terminal.observeFirstStage(value);
                    next.accept(value.transform(stage));
                } else next.accept(frame);
            });
        }
        session = builder.build().openSession(new SessionOptions(1), frame -> {
            terminal.accept(frame);
            if (!outputs.offer((Value) frame)) throw new IllegalStateException("duplicate terminal acknowledgment");
        });
    }

    @Override public Value invoke(Value input) throws Exception {
        long before = terminal.count();
        terminal.expect(input);
        if (!session.trySubmit(input)) throw new IllegalStateException("session rejected input");
        Value output = outputs.poll(10, TimeUnit.SECONDS);
        if (output == null) {
            if (session.completion().isDone()) session.completion().get();
            throw new IllegalStateException("session output timed out");
        }
        terminal.assertAcknowledged(input, output, before);
        return output;
    }

    @Override public void assertDrained() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            SessionMetrics metrics = session.metrics();
            if (metrics.failed() != 0 || metrics.rejected() != 0 || metrics.cancelled() != 0) {
                throw new IllegalStateException("session failed, rejected, or cancelled work");
            }
            if (metrics.queueDepth() == 0 && metrics.accepted() == metrics.processed()) {
                if (!outputs.isEmpty() || metrics.processed() != terminal.count()) {
                    throw new IllegalStateException("session terminal count mismatch");
                }
                return;
            }
            if (System.nanoTime() >= deadline) throw new IllegalStateException("session failed to drain");
            Thread.sleep(1);
        }
    }

    @Override public String version() { return "0.1.0-SNAPSHOT"; }

    @Override public void close() throws Exception {
        session.finish();
        try {
            session.completion().get(10, TimeUnit.SECONDS);
            assertDrained();
            Thread worker = terminal.worker();
            if (worker != null) {
                worker.join(10_000);
                if (worker.isAlive()) throw new IllegalStateException("session worker did not terminate");
            }
        } finally {
            session.close();
        }
    }
}

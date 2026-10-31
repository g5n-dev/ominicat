package io.ominicat.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * An ordered chain of processor instances. A session claims exclusive lifetime
 * ownership of the chain; build a new pipeline for each session. No START/END frames
 * are injected, and control frames alone do not start, finish, or interrupt a session.
 */
public final class Pipeline {
    private final Processor[] processors;
    private final boolean hasAsync;
    private final AtomicBoolean claimed = new AtomicBoolean();

    private Pipeline(List<Processor> processors) {
        this.processors = processors.toArray(Processor[]::new);
        this.hasAsync = processors.stream().anyMatch(AsyncProcessor.class::isInstance);
    }

    /** Begin building a processor chain. An empty chain passes frames through unchanged. */
    public static Builder builder() { return new Builder(); }

    /**
     * Dispatch synchronously, without scheduling or queue overhead. The caller owns
     * cleanup and must serialize access to stateful processors. Not available once a
     * session has claimed this pipeline.
     */
    public void process(Frame frame, FrameSink output) throws Exception {
        if (claimed.get()) throw new IllegalStateException("pipeline is owned by a session");
        if (hasAsync) throw new IllegalStateException("AsyncProcessor requires Pipeline.openSession");
        dispatch(0, Objects.requireNonNull(frame, "frame"), Objects.requireNonNull(output, "output"));
    }

    /** Open the only session this pipeline can own; the session closes all stages. */
    public PipelineSession openSession(SessionOptions options, FrameSink output) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(output, "output");
        if (!claimed.compareAndSet(false, true)) {
            throw new IllegalStateException("pipeline already has a session; create fresh processor instances");
        }
        return new PipelineSession(this, options, output);
    }

    void dispatch(int index, Frame frame, FrameSink output) throws Exception {
        if (index == processors.length) output.accept(frame);
        else processors[index].process(frame, emitted ->
                dispatch(index + 1, Objects.requireNonNull(emitted, "emitted frame"), output));
    }

    void dispatchSession(int index, Frame frame, FrameSink output, BooleanSupplier valid) throws Exception {
        if (!valid.getAsBoolean()) throw StaleFrameException.INSTANCE;
        if (index == processors.length) output.accept(frame);
        else processors[index].process(frame, emitted ->
                dispatchSession(index + 1, Objects.requireNonNull(emitted, "emitted frame"), output, valid));
    }

    boolean hasAsync() { return hasAsync; }

    @FunctionalInterface interface AsyncDispatch {
        void start(AsyncProcessor processor, Frame frame, int nextStage) throws Exception;
    }

    void dispatchAsyncSession(int index, Frame frame, FrameSink output, BooleanSupplier valid,
                              AsyncDispatch async) throws Exception {
        if (!valid.getAsBoolean()) throw StaleFrameException.INSTANCE;
        if (index == processors.length) output.accept(frame);
        else if (processors[index] instanceof AsyncProcessor processor) async.start(processor, frame, index + 1);
        else processors[index].process(frame, emitted -> dispatchAsyncSession(index + 1,
                Objects.requireNonNull(emitted, "emitted frame"), output, valid, async));
    }

    private static final class StaleFrameException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private static final StaleFrameException INSTANCE = new StaleFrameException();
        private StaleFrameException() { super("frame was interrupted", null, false, false); }
    }

    void interruptProcessors() throws Exception {
        for (Processor processor : processors) processor.onInterrupt();
    }

    void closeProcessors() throws Throwable {
        Throwable failure = null;
        for (int i = processors.length - 1; i >= 0; i--) {
            try { processors[i].close(); }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    /** Mutable builder; each build takes a snapshot of the current stage list. */
    public static final class Builder {
        private final List<Processor> processors = new ArrayList<>();
        private Builder() { }
        /** Append a processor. Instances must not be shared between active pipelines. */
        public Builder add(Processor processor) {
            processors.add(Objects.requireNonNull(processor, "processor"));
            return this;
        }
        /** Construct the chain. */
        public Pipeline build() { return new Pipeline(processors); }
    }
}

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
    private final DirectStage[] directStages;
    private final AtomicBoolean claimed = new AtomicBoolean();

    private Pipeline(List<Processor> processors) {
        this.processors = processors.toArray(Processor[]::new);
        this.hasAsync = processors.stream().anyMatch(AsyncProcessor.class::isInstance);
        // Prebind only immutable topology; request state always belongs to a fresh invocation.
        this.directStages = hasAsync ? null : new DirectStage[this.processors.length];
        if (directStages != null) {
            DirectStage next = null;
            for (int index = this.processors.length - 1; index >= 0; index--) {
                next = new DirectStage(this.processors[index], next);
                directStages[index] = next;
            }
        }
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
        else directStages[index].invoke(frame, output);
    }

    /** Privately owned topology, shared without storing any per-call cursor, output or thread. */
    private static final class DirectStage {
        private final Processor processor;
        private final DirectStage next;

        DirectStage(Processor processor, DirectStage next) {
            this.processor = processor;
            this.next = next;
        }

        void invoke(Frame frame, FrameSink output) throws Exception {
            DirectInvocation invocation = new DirectInvocation(next, output);
            try { processor.process(frame, invocation); }
            finally { invocation.retire(); }
        }
    }

    /** One direct invocation owns both the synchronous boundary and its continuation. */
    private static final class DirectInvocation implements FrameSink {
        private Thread owner = Thread.currentThread();
        private DirectStage nextStage;
        private FrameSink output;

        DirectInvocation(DirectStage nextStage, FrameSink output) {
            this.nextStage = nextStage;
            this.output = output;
        }

        @Override public void accept(Frame frame) throws Exception {
            Thread invocationOwner = owner;
            if (invocationOwner == null) {
                throw new IllegalStateException("synchronous sink is no longer valid after process returns");
            }
            if (Thread.currentThread() != invocationOwner) {
                throw new IllegalStateException("synchronous sink may only emit on its invocation thread; use AsyncProcessor for callbacks");
            }
            dispatchNext(frame);
        }

        private void dispatchNext(Frame frame) throws Exception {
            Frame emitted = Objects.requireNonNull(frame, "emitted frame");
            DirectStage next = nextStage;
            if (next == null) output.accept(emitted);
            else next.invoke(emitted, output);
        }

        void retire() {
            // A plugin may retain this handle permanently. Detach every owned graph.
            owner = null;
            nextStage = null;
            output = null;
        }
    }

    void dispatchSession(int index, Frame frame, FrameSink output, BooleanSupplier valid) throws Exception {
        if (!valid.getAsBoolean()) throw StaleFrameException.INSTANCE;
        if (index == processors.length) output.accept(frame);
        else invoke(processors[index], frame, emitted ->
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
        else invoke(processors[index], frame, emitted -> dispatchAsyncSession(index + 1,
                Objects.requireNonNull(emitted, "emitted frame"), output, valid, async));
    }

    private static void invoke(Processor processor, Frame frame, FrameSink next) throws Exception {
        SynchronousSink invocation = new SynchronousSink(next);
        try { processor.process(frame, invocation); }
        finally { invocation.invalidate(); }
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
        /** Append a privately owned processor; duplicate identities in a chain are rejected. */
        public Builder add(Processor processor) {
            Objects.requireNonNull(processor, "processor");
            if (processors.stream().anyMatch(existing -> existing == processor)) {
                throw new IllegalArgumentException("each pipeline stage must own a distinct processor instance");
            }
            processors.add(processor);
            return this;
        }
        /** Construct the chain. */
        public Pipeline build() { return new Pipeline(processors); }
    }
}

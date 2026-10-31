package io.ominicat.server;

import io.ominicat.core.AsyncEmitter;
import io.ominicat.core.AsyncProcessor;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Optional connection-local instrumentation, deliberately separate from kernel scheduling and state. */
final class ActivitySession {
    private final WeakReference<ActivityJournal> journal;
    private final String sessionId;
    private final AtomicLong invocations = new AtomicLong();

    ActivitySession(ActivityJournal journal, String sessionId) {
        this.journal = new WeakReference<>(journal);
        this.sessionId = sessionId;
    }

    void record(String phase, Frame frame, String outcome) { record(0, -1, "transport", phase, ActivityJournal.describe(frame), outcome); }
    void record(String phase, String outcome) { record(0, -1, "transport", phase, ActivityJournal.Description.NONE, outcome); }
    void transport(String phase, ActivityJournal.Description frame, String outcome) { record(0, -1, "transport", phase, frame, outcome); }

    private void record(long invocation, int stage, String type, String phase, ActivityJournal.Description frame, String outcome) {
        ActivityJournal target = journal.get();
        if (target != null) target.append(sessionId, invocation, stage, type, phase, frame, outcome);
    }

    Processor wrap(Processor processor, int stage, String type) {
        return processor instanceof AsyncProcessor async ? new ObservedAsync(async, this, stage, type)
                : new ObservedSync(processor, this, stage, type);
    }

    private Invocation enter(int stage, String type, Frame frame) {
        var invocation = new Invocation(this, invocations.incrementAndGet(), stage, type, ActivityJournal.describe(frame));
        invocation.event("enter", invocation.input, "observed");
        return invocation;
    }

    /** Small metadata only: this object never owns a payload, processor, channel, or server. */
    private record Invocation(ActivitySession activity, long id, int stage, String type, ActivityJournal.Description input) {
        void event(String phase, ActivityJournal.Description frame, String outcome) { activity.record(id, stage, type, phase, frame, outcome); }
        void event(String phase, String outcome) { event(phase, input, outcome); }
    }

    private static class ObservedSync implements Processor {
        final Processor delegate;
        final ActivitySession activity;
        final int stage;
        final String type;
        ObservedSync(Processor delegate, ActivitySession activity, int stage, String type) {
            this.delegate = delegate; this.activity = activity; this.stage = stage; this.type = type;
        }
        @Override public void process(Frame frame, FrameSink next) throws Exception {
            Invocation invocation = activity.enter(stage, type, frame);
            boolean[] downstreamFailed = {false};
            try {
                delegate.process(frame, output -> {
                    // A sink call is an offered stage output, never a delivery acknowledgment.
                    invocation.event("output", ActivityJournal.describe(output), "offered");
                    try { next.accept(output); }
                    catch (Throwable failure) {
                        downstreamFailed[0] = true;
                        invocation.event("downstream_error", "propagated");
                        rethrow(failure);
                    }
                });
                invocation.event("sync_return", "returned");
            } catch (Throwable failure) {
                if (!downstreamFailed[0]) invocation.event("invocation_error", "thrown");
                rethrow(failure);
            }
        }
        @Override public void onInterrupt() throws Exception { delegate.onInterrupt(); }
        @Override public void close() throws Exception { delegate.close(); }
    }

    private static final class ObservedAsync extends ObservedSync implements AsyncProcessor {
        ObservedAsync(AsyncProcessor delegate, ActivitySession activity, int stage, String type) { super(delegate, activity, stage, type); }
        @Override public void process(Frame frame, FrameSink next) {
            throw new IllegalStateException("AsyncProcessor requires Pipeline.openSession");
        }
        @Override public void processAsync(Frame frame, AsyncEmitter emitter) throws Exception {
            Invocation invocation = activity.enter(stage, type, frame);
            var observed = new ObservedEmitter(emitter, invocation);
            try {
                ((AsyncProcessor) delegate).processAsync(frame, observed);
                invocation.event("async_return", "returned");
            } catch (Throwable failure) {
                invocation.event("invocation_error", "thrown");
                observed.invocationFailed();
                rethrow(failure);
            } finally { observed.endRegistration(); }
        }
    }

    /** A cached retired provider callback must not retain the pipeline, payload or observation journal. */
    private static final class ObservedEmitter implements AsyncEmitter {
        private AsyncEmitter delegate;
        private Invocation invocation;
        private boolean registrationOpen = true;
        private boolean finished;
        private boolean cancelled;
        private boolean cancellationReported;
        ObservedEmitter(AsyncEmitter delegate, Invocation invocation) { this.delegate = delegate; this.invocation = invocation; }
        @Override public synchronized boolean tryEmit(Frame frame) {
            Objects.requireNonNull(frame, "frame");
            if (finished) return false;
            boolean accepted = delegate.tryEmit(frame);
            invocation.event(accepted ? "output" : "output_rejected", ActivityJournal.describe(frame), accepted ? "accepted" : "rejected");
            if (!accepted && delegate.isCancelled()) { cancelled = true; finished = true; retire(); }
            return accepted;
        }
        @Override public synchronized void complete() {
            if (finished) return;
            delegate.complete();
            cancelled = delegate.isCancelled();
            finished = true;
            if (!cancelled) invocation.event("async_complete", "completed");
            retire();
        }
        @Override public synchronized void fail(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            if (finished) return;
            boolean alreadyCancelled = delegate.isCancelled();
            delegate.fail(failure);
            cancelled = delegate.isCancelled();
            finished = true;
            // fail() has no acceptance result; this records the report, not a model/task conclusion.
            if (!alreadyCancelled) invocation.event("async_fail", "requested");
            retire();
        }
        @Override public synchronized void onCancel(AutoCloseable action) {
            Objects.requireNonNull(action, "cancellation");
            if (delegate == null) throw new IllegalStateException("async operation is no longer registering cancellation");
            Invocation metadata = invocation;
            // Do not consume the provider's one registration slot. The real emitter validates owner/thread/duplicates.
            delegate.onCancel(() -> {
                synchronized (ObservedEmitter.this) {
                    cancelled = true; finished = true;
                    if (!cancellationReported) { metadata.event("cancel", "callback"); cancellationReported = true; }
                    retire();
                }
                // Never hold the monitor while a provider close waits for its callback thread.
                action.close();
            });
        }
        @Override public synchronized boolean isCancelled() { return cancelled || (!finished && delegate.isCancelled()); }
        synchronized void invocationFailed() {
            if (!finished) { cancelled = true; finished = true; }
            retire();
        }
        synchronized void endRegistration() { registrationOpen = false; retire(); }
        private void retire() { if (finished && !registrationOpen) { delegate = null; invocation = null; } }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error fatal) throw fatal;
        if (failure instanceof Exception exception) throw exception;
        throw new IllegalStateException("Processor invocation failed", failure);
    }
}

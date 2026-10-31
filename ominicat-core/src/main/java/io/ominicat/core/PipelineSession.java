package io.ominicat.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded FIFO and one virtual-thread worker. The worker serializes all stage
 * callbacks. Cancellation is cooperative: stages must honor interruption for prompt
 * shutdown, and a callback already entered cannot have its external effects revoked.
 * Completion means all stages have been closed, including on failure or cancellation.
 */
public final class PipelineSession implements AutoCloseable {
    private enum State { OPEN, DRAINING, CANCELLED, FAILED, TERMINATED }
    private record Pending(Frame frame, long generation, int nextStage) { }

    private final Pipeline pipeline;
    private final int capacity;
    private final FrameSink output;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Set<Handle> asyncHandles = new LinkedHashSet<>();
    private final ArrayDeque<AutoCloseable> cancellations = new ArrayDeque<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final Thread worker;
    private State state = State.OPEN;
    private long generation;
    private boolean interruptPending;
    private long accepted;
    private long processed;
    private long rejected;
    private long failed;
    private long cancelled;
    private long totalProcessingNanos;
    private Throwable terminalFailure;

    PipelineSession(Pipeline pipeline, SessionOptions options, FrameSink output) {
        this.pipeline = pipeline;
        this.capacity = options.queueCapacity();
        this.output = output;
        worker = Thread.ofVirtual().name("ominicat-session-", 0).unstarted(this::run);
        worker.start();
    }

    /** Enqueue immediately, or return false when capacity is exhausted or intake has closed. */
    public boolean trySubmit(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        lock.lock();
        try {
            if (state != State.OPEN || queue.size() == capacity) { rejected++; return false; }
            enqueue(frame);
            return true;
        } finally { lock.unlock(); }
    }

    /**
     * Wait up to {@code timeout} for capacity. Returns false on timeout or closed
     * intake. A caller interrupted while waiting has not submitted its frame.
     */
    public boolean submit(Frame frame, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        long remaining;
        try { remaining = timeout.toNanos(); }
        catch (ArithmeticException overflow) { remaining = Long.MAX_VALUE; }
        lock.lockInterruptibly();
        try {
            while (state == State.OPEN && queue.size() == capacity) {
                if (remaining <= 0) { rejected++; return false; }
                remaining = changed.awaitNanos(remaining);
            }
            if (state != State.OPEN) { rejected++; return false; }
            enqueue(frame);
            return true;
        } finally { lock.unlock(); }
    }

    private void enqueue(Frame frame) {
        queue.addLast(new Pending(frame, generation, 0));
        accepted++;
        changed.signalAll();
    }

    /**
     * Drop pending frames, fence emissions from the active frame, and interrupt
     * blocking stage I/O. Intake remains open, so a new turn can be submitted.
     */
    public void interrupt() {
        lock.lock();
        try {
            if (state != State.OPEN) return;
            generation++;
            interruptPending = true;
            cancelHandlesLocked();
            cancelled += queue.size();
            queue.clear();
            worker.interrupt();
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    /**
     * Stop external intake and drain accepted work before closing stages. Streaming
     * continuations remain accepted until their AsyncEmitters complete or fail.
     */
    public void finish() {
        lock.lock();
        try {
            if (state == State.OPEN) { state = State.DRAINING; changed.signalAll(); }
        } finally { lock.unlock(); }
    }

    /** A future completed only after worker termination and reverse-order stage cleanup. */
    public CompletableFuture<Void> completion() { return completion.copy(); }

    /** Return an internally consistent metrics snapshot. */
    public SessionMetrics metrics() {
        lock.lock();
        try {
            return new SessionMetrics(accepted, processed, rejected, failed, cancelled,
                    queue.size(), totalProcessingNanos);
        } finally { lock.unlock(); }
    }

    /**
     * Cancel intake, pending work, and active output immediately. Returns without
     * waiting for cooperative worker shutdown; await {@link #completion()} to join.
     */
    @Override public void close() {
        lock.lock();
        try {
            if (state == State.CANCELLED || state == State.FAILED || state == State.TERMINATED) return;
            state = State.CANCELLED;
            terminalFailure = new CancellationException("session closed");
            generation++;
            cancelHandlesLocked();
            cancelled += queue.size();
            queue.clear();
            worker.interrupt();
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    private boolean valid(long frameGeneration) {
        lock.lock();
        try {
            return generation == frameGeneration && state != State.CANCELLED && state != State.FAILED;
        } finally { lock.unlock(); }
    }

    private void startAsync(AsyncProcessor processor, Frame frame, int nextStage,
                            long frameGeneration) throws Exception {
        Handle handle;
        lock.lock();
        try {
            if (frameGeneration != generation || state == State.CANCELLED || state == State.FAILED) return;
            if (asyncHandles.size() == capacity) {
                throw new RejectedExecutionException("maximum live async operations reached: " + capacity);
            }
            handle = new Handle(frameGeneration, nextStage);
            asyncHandles.add(handle);
        } finally { lock.unlock(); }
        try { processor.processAsync(frame, handle); }
        finally {
            lock.lock();
            try { handle.registrationOpen = false; }
            finally { lock.unlock(); }
        }
    }

    private void cancelHandlesLocked() {
        for (Handle handle : asyncHandles) {
            handle.cancelledHandle = true;
            handle.done = true;
            if (handle.cancellation != null) {
                cancellations.addLast(handle.cancellation);
                handle.cancellation = null;
            }
        }
        asyncHandles.clear();
    }

    private void failLocked(Throwable failure) {
        state = State.FAILED;
        terminalFailure = failure;
        generation++;
        cancelHandlesLocked();
        cancelled += queue.size();
        queue.clear();
        changed.signalAll();
        worker.interrupt();
    }

    private void drainCancellations() throws Throwable {
        Throwable failure = null;
        while (true) {
            AutoCloseable action;
            lock.lock();
            try { action = cancellations.pollFirst(); }
            finally { lock.unlock(); }
            if (action == null) break;
            try { action.close(); }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private void recordCleanupFailure(Throwable failure) {
        lock.lock();
        try {
            if (terminalFailure == null) terminalFailure = failure;
            else if (terminalFailure != failure) terminalFailure.addSuppressed(failure);
        } finally { lock.unlock(); }
    }

    private final class Handle implements AsyncEmitter {
        private final long frameGeneration;
        private final int nextStage;
        private boolean done;
        private boolean cancelledHandle;
        private boolean registrationOpen = true;
        private AutoCloseable cancellation;
        private boolean cancellationRegistered;
        private Handle(long frameGeneration, int nextStage) {
            this.frameGeneration = frameGeneration;
            this.nextStage = nextStage;
        }
        @Override public boolean tryEmit(Frame frame) {
            Objects.requireNonNull(frame, "frame");
            lock.lock();
            try {
                if (done || frameGeneration != generation || (state != State.OPEN && state != State.DRAINING)
                        || queue.size() == capacity) { rejected++; return false; }
                queue.addLast(new Pending(frame, frameGeneration, nextStage));
                accepted++;
                changed.signalAll();
                return true;
            } finally { lock.unlock(); }
        }
        @Override public void complete() {
            lock.lock();
            try {
                if (done) return;
                done = true;
                cancellation = null;
                asyncHandles.remove(this);
                changed.signalAll();
            } finally { lock.unlock(); }
        }
        @Override public void fail(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            lock.lock();
            try {
                if (done || frameGeneration != generation || (state != State.OPEN && state != State.DRAINING)) return;
                failLocked(failure);
            } finally { lock.unlock(); }
        }
        @Override public void onCancel(AutoCloseable action) {
            Objects.requireNonNull(action, "cancellation");
            lock.lock();
            try {
                if (Thread.currentThread() != worker || !registrationOpen) {
                    throw new IllegalStateException("register cancellation during processAsync on the session worker");
                }
                if (cancellationRegistered) throw new IllegalStateException("cancellation already registered");
                cancellationRegistered = true;
                if (cancelledHandle || frameGeneration != generation) cancellations.addLast(action);
                else if (!done) cancellation = action;
            } finally { lock.unlock(); }
        }
        @Override public boolean isCancelled() {
            lock.lock();
            try { return cancelledHandle || frameGeneration != generation || state == State.CANCELLED || state == State.FAILED; }
            finally { lock.unlock(); }
        }
    }

    private Pending take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() && !interruptPending
                    && (state == State.OPEN || (state == State.DRAINING && !asyncHandles.isEmpty()))) changed.await();
            if (state == State.CANCELLED || state == State.FAILED) return null;
            if (interruptPending) {
                interruptPending = false;
                return new Pending(null, generation, 0);
            }
            if (queue.isEmpty()) return null;
            Pending next = queue.removeFirst();
            changed.signalAll();
            return next;
        } finally { lock.unlock(); }
    }

    private void run() {
        try {
            while (true) {
                Pending pending;
                try { pending = take(); }
                catch (InterruptedException interrupted) { continue; }
                if (pending == null) break;
                Throwable error = null;
                long started = System.nanoTime();
                try {
                    if (pending.frame() == null) {
                        drainCancellations();
                        pipeline.interruptProcessors();
                    } else if (pipeline.hasAsync()) {
                        pipeline.dispatchAsyncSession(pending.nextStage(), pending.frame(), output,
                                () -> valid(pending.generation()),
                                (processor, frame, nextStage) -> startAsync(processor, frame, nextStage, pending.generation()));
                    } else pipeline.dispatchSession(0, pending.frame(), output, () -> valid(pending.generation()));
                } catch (Throwable failure) { error = failure; }
                finally { Thread.interrupted(); }
                lock.lock();
                try {
                    totalProcessingNanos += System.nanoTime() - started;
                    if (pending.generation() != generation || state == State.CANCELLED) {
                        if (pending.frame() != null) cancelled++;
                    } else if (error != null) {
                        if (pending.frame() != null) failed++;
                        failLocked(error);
                        break;
                    } else if (pending.frame() != null) processed++;
                } finally { lock.unlock(); }
            }
        } catch (Throwable failure) {
            lock.lock();
            try {
                if (terminalFailure == null) failLocked(failure);
            } finally { lock.unlock(); }
        } finally {
            // Clear the cancellation interrupt before closing clients that may perform I/O.
            Thread.interrupted();
            lock.lock();
            try { cancelHandlesLocked(); }
            finally { lock.unlock(); }
            try { drainCancellations(); }
            catch (Throwable cancellationFailure) { recordCleanupFailure(cancellationFailure); }
            try { pipeline.closeProcessors(); }
            catch (Throwable cleanupFailure) {
                recordCleanupFailure(cleanupFailure);
            }
            Throwable result;
            lock.lock();
            try { state = State.TERMINATED; result = terminalFailure; changed.signalAll(); }
            finally { lock.unlock(); }
            if (result == null) completion.complete(null);
            else completion.completeExceptionally(result);
        }
    }
}

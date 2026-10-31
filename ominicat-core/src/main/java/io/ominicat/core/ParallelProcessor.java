package io.ominicat.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Run named branches concurrently and join in declaration order. No branch output
 * is emitted until all branches succeed; each branch has a strict frame-buffer
 * bound. A branch failure or deadline cancels sibling tasks. Cancellation remains
 * cooperative, so stages must honor interruption. Per-branch locks serialize
 * process, interruption reset, and cleanup even when a stage stops slowly. Branches
 * use the synchronous Processor contract; callback-based AsyncProcessor branches
 * must be composed as independent sessions instead.
 */
public final class ParallelProcessor implements Processor {
    /** A named, privately owned processor instance. */
    public record Branch(String name, Processor processor) {
        public Branch {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(processor, "processor");
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("invalid branch name: " + name);
            }
        }
    }
    private record Joined(int index, List<Frame> frames) { }
    private record Owned(Branch branch, ReentrantLock lock) { }

    private final List<Owned> branches;
    private final long timeoutNanos;
    private final int maximumFrames;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param branches ordered branches (1..64); names and processor identities must be unique
     * @param timeout one shared deadline for the entire fork, including scheduling
     * @param maxBufferedFramesPerBranch maximum emitted frames retained from each branch
     */
    public ParallelProcessor(List<Branch> branches, Duration timeout, int maxBufferedFramesPerBranch) {
        Objects.requireNonNull(branches, "branches");
        Objects.requireNonNull(timeout, "timeout");
        if (branches.isEmpty() || branches.size() > 64) throw new IllegalArgumentException("branch count must be in 1..64");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        if (maxBufferedFramesPerBranch < 1 || maxBufferedFramesPerBranch > 1_000_000) {
            throw new IllegalArgumentException("maxBufferedFramesPerBranch must be in 1..1000000");
        }
        long nanos;
        try { nanos = timeout.toNanos(); }
        catch (ArithmeticException overflow) { nanos = Long.MAX_VALUE; }
        timeoutNanos = nanos;
        maximumFrames = maxBufferedFramesPerBranch;
        var names = new HashSet<String>();
        var identities = new IdentityHashMap<Processor, Boolean>();
        var owned = new ArrayList<Owned>();
        for (Branch branch : branches) {
            Objects.requireNonNull(branch, "branch");
            if (branch.processor() instanceof AsyncProcessor) {
                throw new IllegalArgumentException("parallel branches require synchronous processors; use independent sessions for AsyncProcessor");
            }
            if (!names.add(branch.name())) throw new IllegalArgumentException("duplicate branch name: " + branch.name());
            if (identities.put(branch.processor(), Boolean.TRUE) != null) {
                throw new IllegalArgumentException("branches must own distinct processor instances");
            }
            owned.add(new Owned(branch, new ReentrantLock()));
        }
        this.branches = List.copyOf(owned);
        executor = WorkerThreads.executor("ominicat-branch-", branches.size());
    }

    @Override public void process(Frame frame, FrameSink next) throws Exception {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(next, "next");
        if (closed.get()) throw new IllegalStateException("parallel processor is closed");
        CompletionService<Joined> completed = new ExecutorCompletionService<>(executor);
        List<Future<Joined>> tasks = new ArrayList<>();
        List<List<Frame>> results = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) results.add(null);
        long started = System.nanoTime();
        try {
            for (int index = 0; index < branches.size(); index++) {
                final int position = index;
                tasks.add(completed.submit(() -> runBranch(position, frame)));
            }
            for (int remaining = branches.size(); remaining > 0; remaining--) {
                long budget = timeoutNanos - (System.nanoTime() - started);
                if (budget <= 0) throw new TimeoutException("parallel branches exceeded their shared deadline");
                Future<Joined> finished = completed.poll(budget, TimeUnit.NANOSECONDS);
                if (finished == null) throw new TimeoutException("parallel branches exceeded their shared deadline");
                Joined result;
                try { result = finished.get(); }
                catch (ExecutionException failure) { throw branchFailure(failure.getCause()); }
                results.set(result.index(), result.frames());
            }
            for (List<Frame> result : results) {
                for (Frame emitted : result) {
                    if (closed.get()) throw new CancellationException("parallel processor closed during join");
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("parallel join interrupted");
                    next.accept(emitted);
                }
            }
        } finally {
            for (Future<Joined> task : tasks) if (!task.isDone()) task.cancel(true);
        }
    }

    private Joined runBranch(int position, Frame frame) throws Exception {
        Owned owned = branches.get(position);
        owned.lock().lockInterruptibly();
        try {
            if (closed.get()) throw new CancellationException("parallel processor closed");
            List<Frame> output = new ArrayList<>();
            SynchronousSink invocation = new SynchronousSink(emitted -> {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("branch interrupted");
                if (output.size() == maximumFrames) {
                    throw new IllegalStateException("branch '" + owned.branch().name() + "' exceeded max buffered frames " + maximumFrames);
                }
                output.add(Objects.requireNonNull(emitted, "branch emitted null frame"));
            });
            try { owned.branch().processor().process(frame, invocation); }
            finally { invocation.invalidate(); }
            return new Joined(position, List.copyOf(output));
        } catch (Exception failure) {
            throw new Exception("parallel branch '" + owned.branch().name() + "' failed", failure);
        } finally { owned.lock().unlock(); }
    }

    private static Exception branchFailure(Throwable cause) {
        if (cause instanceof Exception exception) return exception;
        if (cause instanceof Error error) throw error;
        return new Exception("parallel branch failed", cause);
    }

    @Override public void onInterrupt() throws Exception {
        Throwable failure = null;
        for (Owned owned : branches) {
            owned.lock().lockInterruptibly();
            try { owned.branch().processor().onInterrupt(); }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            } finally { owned.lock().unlock(); }
        }
        if (failure != null) throw branchFailure(failure);
    }

    @Override public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        Throwable failure = null;
        for (int index = branches.size() - 1; index >= 0; index--) {
            Owned owned = branches.get(index);
            owned.lock().lock();
            try { owned.branch().processor().close(); }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            } finally { owned.lock().unlock(); }
        }
        if (failure != null) throw branchFailure(failure);
    }
}

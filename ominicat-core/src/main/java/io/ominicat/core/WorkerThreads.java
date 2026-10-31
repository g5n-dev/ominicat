package io.ominicat.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java 17-compatible worker creation. Java 21+ automatically uses the public
 * virtual-thread factory when available; Java 17 uses named daemon platform
 * threads. Reflection is confined to capability discovery, not frame dispatch.
 */
public final class WorkerThreads {
    private static final AtomicLong THREAD_IDS = new AtomicLong();
    private static final VirtualSupport VIRTUAL = discoverVirtualSupport();
    private WorkerThreads() { }

    /** Create named workers using the available runtime's preferred thread backend. */
    public static ThreadFactory factory(String prefix) {
        String checked = prefix(prefix);
        if (VIRTUAL == null) return platformFactory(checked);
        return task -> {
            Thread thread = VIRTUAL.factory().newThread(Objects.requireNonNull(task, "task"));
            thread.setName(checked + THREAD_IDS.getAndIncrement());
            return thread;
        };
    }

    /** Create named daemon platform threads, including on virtual-thread-capable runtimes. */
    public static ThreadFactory platformFactory(String prefix) {
        String checked = prefix(prefix);
        return task -> {
            Thread thread = new Thread(Objects.requireNonNull(task, "task"), checked + THREAD_IDS.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Create an unstarted preferred-backend worker. */
    public static Thread unstarted(String prefix, Runnable task) { return factory(prefix).newThread(task); }

    /** Create and start a preferred-backend worker. */
    public static Thread start(String prefix, Runnable task) {
        Thread thread = unstarted(prefix, task);
        thread.start();
        return thread;
    }

    /** Whether Java 21's non-preview public virtual-thread factory is available. */
    public static boolean virtualThreadsAvailable() { return VIRTUAL != null; }

    /** Inspect a thread without statically linking to Java 21's Thread API. */
    public static boolean isVirtual(Thread thread) {
        Objects.requireNonNull(thread, "thread");
        if (VIRTUAL == null) return false;
        try { return (Boolean) VIRTUAL.isVirtual().invoke(thread); }
        catch (ReflectiveOperationException failure) { throw reflectionFailure("inspect worker thread", failure); }
    }

    /** Create a closeable bounded executor with a queue equal to the parallelism limit. */
    public static TaskExecutor executor(String prefix, int parallelism) {
        return executor(prefix, parallelism, parallelism);
    }

    /**
     * Create a fixed-parallelism, bounded-queue executor. Saturation rejects with
     * RejectedExecutionException. Limits apply on both platform and virtual backends.
     */
    public static TaskExecutor executor(String prefix, int parallelism, int queueCapacity) {
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be positive");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        return new TaskExecutor(parallelism, queueCapacity, factory(prefix));
    }

    /**
     * ExecutorService with a Java 17 try-with-resources boundary. Close drains accepted
     * tasks; interruption cancels outstanding tasks, waits for cooperative termination,
     * and restores the closing thread's interrupt status. Uncooperative tasks can delay close.
     */
    public static final class TaskExecutor extends AbstractExecutorService implements AutoCloseable {
        private final ThreadPoolExecutor delegate;
        private TaskExecutor(int parallelism, int queueCapacity, ThreadFactory factory) {
            delegate = new ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
        }
        @Override protected <T> RunnableFuture<T> newTaskFor(Callable<T> task) { return new SubmittedTask<>(task); }
        @Override protected <T> RunnableFuture<T> newTaskFor(Runnable task, T value) { return new SubmittedTask<>(task, value); }
        @Override public void execute(Runnable task) { delegate.execute(task); }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() {
            List<Runnable> discarded = delegate.shutdownNow();
            // Cancel only our directly submitted tasks. Cancelling an external
            // ExecutorCompletionService wrapper would report its unrun inner task
            // as completed, violating that service's completion-queue contract.
            for (Runnable task : discarded) if (task instanceof SubmittedTask<?> submitted) submitted.cancel(false);
            return discarded;
        }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
        @Override public void close() {
            shutdown();
            boolean interrupted = false;
            try {
                while (!isTerminated()) {
                    try { awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException interruption) { interrupted = true; shutdownNow(); }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static final class SubmittedTask<T> extends FutureTask<T> {
        SubmittedTask(Callable<T> task) { super(task); }
        SubmittedTask(Runnable task, T value) { super(task, value); }
    }

    private static String prefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isBlank() || prefix.length() > 128) throw new IllegalArgumentException("worker prefix must contain 1..128 characters");
        return prefix;
    }

    private record VirtualSupport(ThreadFactory factory, Method isVirtual) { }
    private static VirtualSupport discoverVirtualSupport() {
        if (Runtime.version().feature() < 21) return null;
        try {
            Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
            Class<?> builderType = Class.forName("java.lang.Thread$Builder");
            ThreadFactory factory = (ThreadFactory) builderType.getMethod("factory").invoke(builder);
            return new VirtualSupport(factory, Thread.class.getMethod("isVirtual"));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException unavailable) {
            return null;
        } catch (InvocationTargetException failure) { throw reflectionFailure("create virtual worker factory", failure); }
    }

    private static IllegalStateException reflectionFailure(String action, ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException("Unable to " + action, cause);
    }
}

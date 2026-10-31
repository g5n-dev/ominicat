package io.ominicat.benchmarks;

import io.ominicat.core.Frame;
import io.ominicat.core.Pipeline;
import io.ominicat.core.PipelineSession;
import io.ominicat.core.SessionOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import io.ominicat.core.WorkerThreads;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Concurrent, bounded-inflight, closed-loop load generator. Latency starts before
 * submission and ends at the output sink, including ingress queue waiting. This is
 * a local kernel measurement, not a network, model, or production capacity test.
 */
public final class CoreLoad {
    private static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(30);
    private CoreLoad() { }

    private static final class Probe implements Frame {
        final long sent;
        final int sample;
        final long[] samples;
        final CountDownLatch completed;
        final Semaphore permits;
        long value;
        Probe(int sample, long[] samples, CountDownLatch completed, Semaphore permits) {
            this.sent = System.nanoTime();
            this.sample = sample;
            this.samples = samples;
            this.completed = completed;
            this.permits = permits;
            this.value = sample;
        }
    }

    /** Options: --sessions, --frames, --warmup, --inflight, --stages, and --output. */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        int sessionCount = number(options, "sessions", 16, 1, 4096);
        int frameCount = number(options, "frames", 1_000_000, 1, 50_000_000);
        int warmup = number(options, "warmup", 100_000, 0, 5_000_000);
        int inflight = number(options, "inflight", 32, 1, 8192);
        int stages = number(options, "stages", 4, 0, 128);
        long[] latencies = new long[frameCount];
        List<PipelineSession> sessions = new ArrayList<>();
        List<Semaphore> permits = new ArrayList<>();
        long elapsed;
        try {
            for (int id = 0; id < sessionCount; id++) {
                Pipeline.Builder builder = Pipeline.builder();
                for (int index = 0; index < stages; index++) {
                    long salt = 0x9e3779b97f4a7c15L + index;
                    builder.add((frame, next) -> {
                        Probe probe = (Probe) frame;
                        probe.value = Long.rotateLeft(probe.value ^ salt, 7);
                        next.accept(probe);
                    });
                }
                sessions.add(builder.build().openSession(new SessionOptions(inflight), frame -> {
                    Probe probe = (Probe) frame;
                    if (probe.samples != null) probe.samples[probe.sample] = System.nanoTime() - probe.sent;
                    // Read transformed content so work remains observable without extra allocation.
                    if (probe.value == Long.MIN_VALUE) Thread.onSpinWait();
                    probe.permits.release();
                    probe.completed.countDown();
                }));
                permits.add(new Semaphore(inflight));
            }
            runPhase(sessions, permits, warmup, null);
            long started = System.nanoTime();
            runPhase(sessions, permits, frameCount, latencies);
            elapsed = System.nanoTime() - started;
            for (PipelineSession session : sessions) session.finish();
            for (PipelineSession session : sessions) session.completion().get(30, TimeUnit.SECONDS);
        } finally {
            for (PipelineSession session : sessions) session.close();
        }
        Arrays.sort(latencies);
        long rejected = sessions.stream().mapToLong(session -> session.metrics().rejected()).sum();
        long processed = sessions.stream().mapToLong(session -> session.metrics().processed()).sum();
        if (processed != (long) frameCount + warmup || rejected != 0) {
            throw new IllegalStateException("incomplete run: processed=" + processed + ", rejected=" + rejected);
        }
        String json = String.format(Locale.ROOT, """
                {
                  "schemaVersion": 1,
                  "recordedAt": "%s",
                  "workload": "local bounded-inflight closed-loop kernel load; no model or network",
                  "latencyScope": "before submit to output sink; includes ingress queue waiting; excludes permit waiting",
                  "sessions": %d,
                  "stages": %d,
                  "inflightPerSession": %d,
                  "warmupFrames": %d,
                  "measuredFrames": %d,
                  "processedIncludingWarmup": %d,
                  "rejected": %d,
                  "elapsedSeconds": %.9f,
                  "throughputFramesPerSecond": %.3f,
                  "latencyMicros": {"p50": %.3f, "p95": %.3f, "p99": %.3f, "max": %.3f},
                  "environment": {
                    "javaVersion": "%s", "javaVendor": "%s", "workerBackend": "%s",
                    "os": "%s", "arch": "%s", "processors": %d, "maxHeapBytes": %d
                  }
                }
                """, Instant.now(), sessionCount, stages, inflight, warmup, frameCount,
                processed, rejected, elapsed / 1_000_000_000d,
                frameCount * 1_000_000_000d / elapsed,
                percentile(latencies, .50) / 1000d, percentile(latencies, .95) / 1000d,
                percentile(latencies, .99) / 1000d, latencies[latencies.length - 1] / 1000d,
                escape(System.getProperty("java.version")), escape(System.getProperty("java.vendor")),
                WorkerThreads.virtualThreadsAvailable() ? "virtual" : "platform",
                escape(System.getProperty("os.name")), escape(System.getProperty("os.arch")),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory());
        String destination = options.get("output");
        if (destination != null) {
            Path path = Path.of(destination);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
        }
        System.out.print(json);
    }

    private static void runPhase(List<PipelineSession> sessions, List<Semaphore> permits,
                                 int frames, long[] samples) throws Exception {
        CountDownLatch completed = new CountDownLatch(frames);
        try (var executor = WorkerThreads.executor("ominicat-load-", sessions.size())) {
            var producers = new ArrayList<java.util.concurrent.Future<?>>();
            for (int id = 0; id < sessions.size(); id++) {
                int sessionId = id;
                producers.add(executor.submit(() -> {
                    PipelineSession session = sessions.get(sessionId);
                    Semaphore budget = permits.get(sessionId);
                    for (int index = sessionId; index < frames; index += sessions.size()) {
                        try {
                            if (!budget.tryAcquire(30, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("timed out waiting for in-flight capacity");
                            }
                            Probe probe = new Probe(index, samples, completed, budget);
                            if (!session.submit(probe, SUBMIT_TIMEOUT)) {
                                budget.release();
                                throw new IllegalStateException("session rejected frame " + index);
                            }
                        } catch (InterruptedException interruption) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("load producer interrupted", interruption);
                        }
                    }
                }));
            }
            for (var producer : producers) producer.get(60, TimeUnit.SECONDS);
            if (!completed.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("timed out waiting for outputs");
        }
    }

    private static long percentile(long[] sorted, double fraction) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
    }

    private static int number(Map<String, String> options, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(options.getOrDefault(key, String.valueOf(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be in " + min + ".." + max);
        return value;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        List<String> names = List.of("sessions", "frames", "warmup", "inflight", "stages", "output");
        if (args.length % 2 != 0) throw new IllegalArgumentException("each --option requires a value");
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !names.contains(args[i].substring(2))) {
                throw new IllegalArgumentException("unknown option " + args[i]);
            }
            if (options.putIfAbsent(args[i].substring(2), args[i + 1]) != null) {
                throw new IllegalArgumentException("duplicate option " + args[i]);
            }
        }
        return options;
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (character == '"' || character == '\\') result.append('\\').append(character);
            else if (character < 32) result.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            else result.append(character);
        }
        return result.toString();
    }
}

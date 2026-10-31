package io.ominicat.comparison;

import com.sun.management.OperatingSystemMXBean;
import io.ominicat.comparison.NumericWorkload.Phase;
import io.ominicat.comparison.NumericWorkload.Terminal;
import io.ominicat.comparison.NumericWorkload.Value;
import io.ominicat.core.WorkerThreads;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Whole-loop W=1 comparison; see comparison/protocol.md for the timing boundary. */
public final class BenchmarkMain {
    private BenchmarkMain() { }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        if (Runtime.version().feature() != 17) throw new IllegalStateException("protocol 1 measurements require Java 17");
        if (Files.exists(options.output())) throw new IllegalArgumentException("output already exists");
        Path parent = options.output().toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        if (!(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean operatingSystem)) {
            throw new IllegalStateException("process CPU clock is unavailable");
        }
        Terminal terminal = new Terminal(options.stages(), options.maxSamples());
        PhaseResult warmup;
        PhaseResult measured;
        String version;
        try (QueuedAdapter adapter = QueuedAdapter.create(options.engine(), options.stages(), terminal)) {
            version = adapter.version();
            if (options.engine().equals("camel") && !version.equals("4.22.2")) {
                throw new IllegalStateException("unexpected Camel version");
            }
            long started = System.nanoTime();
            adapter.invoke(new Value(1, 1, started, 0, Phase.SETUP));
            adapter.assertDrained();
            terminal.assertWorker(Thread.currentThread());
            if (WorkerThreads.isVirtual(terminal.worker())) throw new IllegalStateException("protocol 1 requires a platform worker");

            phase("warmup");
            terminal.begin(Phase.WARMUP);
            warmup = runPhase(adapter, terminal, Phase.WARMUP, options.warmupSeconds(), 1, operatingSystem);
            adapter.assertDrained();

            phase("measure");
            terminal.begin(Phase.MEASURE);
            measured = runPhase(adapter, terminal, Phase.MEASURE, options.measureSeconds(), 1 + warmup.frames(), operatingSystem);
            adapter.assertDrained();
            if (terminal.count() != 1L + warmup.frames() + measured.frames()) {
                throw new IllegalStateException("total terminal output count mismatch");
            }
            phase("teardown");
        }

        // Sorting, JSON encoding, filesystem I/O, and teardown are outside both timers.
        double[] latency = terminal.percentiles(measured.frames());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("engine", options.engine());
        result.put("version", version);
        result.put("mode", "queued-w1");
        result.put("stages", options.stages());
        result.put("sessions", 1);
        result.put("inflight", 1);
        result.put("warmupSeconds", warmup.elapsedSeconds());
        result.put("warmupFrames", warmup.frames());
        result.put("measuredFrames", measured.frames());
        result.put("elapsedSeconds", measured.elapsedSeconds());
        result.put("throughputFramesPerSecond", measured.frames() / measured.elapsedSeconds());
        result.put("latencyMicros", Map.of("p50", latency[0], "p95", latency[1], "p99", latency[2], "max", latency[3]));
        result.put("processCpuSeconds", measured.cpuSeconds());
        result.put("checks", Map.of("allOutputsValidated", true, "exactOutputCount", true,
                "noRejections", true, "drained", true, "cleanupComplete", true));
        result.put("sampleCapacity", options.maxSamples());
        result.put("harnessBufferBytes", (long) NumericWorkload.BUFFER_LONGS * Long.BYTES);
        result.put("environment", Map.of("runtime", "Java", "version", System.getProperty("java.runtime.version"),
                "vendor", System.getProperty("java.vendor"), "backend", "platform",
                "os", System.getProperty("os.name"), "architecture", System.getProperty("os.arch"),
                "availableProcessors", Runtime.getRuntime().availableProcessors(),
                "maxHeapBytes", Runtime.getRuntime().maxMemory()));
        String json = json(result);
        Files.writeString(options.output(), json + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println(json);
        System.out.flush();
    }

    static PhaseResult runPhase(QueuedAdapter adapter, Terminal terminal, Phase phase, double seconds,
                                long previousSequence, OperatingSystemMXBean operatingSystem) throws Exception {
        long duration = (long) (seconds * 1_000_000_000L);
        long cpuStart = operatingSystem.getProcessCpuTime();
        if (cpuStart < 0) throw new IllegalStateException("process CPU clock is unavailable");
        long phaseStart = System.nanoTime();
        int index = 0;
        while (System.nanoTime() - phaseStart < duration) {
            if (phase == Phase.MEASURE && index >= terminal.maxSamples()) {
                throw new IllegalStateException("sample capacity exceeded");
            }
            long sequence = previousSequence + index + 1;
            long started = System.nanoTime();
            // Input construction and all per-stage allocations are inside terminal latency.
            Value input = new Value(sequence, sequence, started, index, phase);
            adapter.invoke(input);
            index++;
        }
        long elapsed = System.nanoTime() - phaseStart;
        long cpuEnd = operatingSystem.getProcessCpuTime();
        if (cpuEnd < cpuStart || elapsed <= 0 || index == 0 || terminal.phaseCount() != index) {
            throw new IllegalStateException("invalid phase result");
        }
        return new PhaseResult(index, elapsed / 1_000_000_000d, (cpuEnd - cpuStart) / 1_000_000_000d);
    }

    private static void phase(String phase) {
        System.out.println("{\"event\":\"phase\",\"phase\":\"" + phase + "\"}");
        System.out.flush();
    }

    /** Only final primitive metadata is encoded; this serializer never enters frame processing. */
    static String json(Object value) {
        if (value instanceof String string) {
            StringBuilder output = new StringBuilder("\"");
            for (int index = 0; index < string.length(); index++) {
                char character = string.charAt(index);
                switch (character) {
                    case '\\' -> output.append("\\\\");
                    case '"' -> output.append("\\\"");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> {
                        if (character < 0x20) output.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                        else output.append(character);
                    }
                }
            }
            return output.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder output = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) output.append(',');
                output.append(json(entry.getKey())).append(':').append(json(entry.getValue()));
                first = false;
            }
            return output.append('}').toString();
        }
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("nonfinite JSON number");
            return number.toString();
        }
        if (value instanceof Boolean bool) return bool.toString();
        throw new IllegalArgumentException("unsupported result metadata");
    }

    record PhaseResult(int frames, double elapsedSeconds, double cpuSeconds) { }

    record Options(String engine, int stages, double warmupSeconds, double measureSeconds, int maxSamples, Path output) {
        static Options parse(String[] arguments) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < arguments.length; index += 2) {
                if (index + 1 >= arguments.length || !arguments[index].startsWith("--")
                        || values.putIfAbsent(arguments[index], arguments[index + 1]) != null) {
                    throw new IllegalArgumentException("expected distinct --option value pairs");
                }
            }
            for (String key : values.keySet()) {
                if (!java.util.Set.of("--engine", "--stages", "--warmup-seconds", "--measure-seconds", "--max-samples", "--output").contains(key)) {
                    throw new IllegalArgumentException("unknown option");
                }
            }
            String engine = values.get("--engine");
            if (!java.util.Set.of("ominicat", "camel").contains(engine == null ? "" : engine)) throw new IllegalArgumentException("engine is required");
            int stages = Integer.parseInt(values.getOrDefault("--stages", "4"));
            if (stages != 1 && stages != 4 && stages != 16) throw new IllegalArgumentException("stages must be 1, 4, or 16");
            double warmup = seconds(values.getOrDefault("--warmup-seconds", "3"));
            double measure = seconds(values.getOrDefault("--measure-seconds", "5"));
            int samples = Integer.parseInt(values.getOrDefault("--max-samples", "2000000"));
            if (samples < 1 || samples > NumericWorkload.MAX_SAMPLES) throw new IllegalArgumentException("invalid sample capacity");
            String output = values.get("--output");
            if (output == null || output.isBlank()) throw new IllegalArgumentException("output is required");
            return new Options(engine, stages, warmup, measure, samples, Path.of(output));
        }

        private static double seconds(String value) {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || seconds < .001 || seconds > 3600) throw new IllegalArgumentException("invalid phase duration");
            return seconds;
        }
    }
}

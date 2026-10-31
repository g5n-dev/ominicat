package io.ominicat.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ominicat.core.WorkerThreads;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/** Read-only, aggregate console telemetry. Sampling is requested by HTTP, never by frame dispatch. */
final class RuntimeSnapshot {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode blueprint;

    RuntimeSnapshot(RuntimeConfig config, boolean bearerRequired) {
        blueprint = mapper.createObjectNode().put("schemaVersion", 1).put("status", "running");
        var pipeline = blueprint.putObject("pipeline").put("editable", false).put("nodeTracing", false);
        var stages = pipeline.putArray("stages");
        for (int index = 0; index < config.stages().size(); index++) {
            stages.addObject().put("id", "stage-" + index).put("index", index)
                    .put("type", config.stages().get(index).type());
        }
        // Explicit allowlist: never serialize RuntimeConfig, its options, or environment references.
        blueprint.putObject("config").put("queueCapacity", config.queueCapacity())
                .put("maxSessions", config.maxSessions()).put("maxConnections", config.maxConnections())
                .put("maxFrameBytes", config.maxFrameBytes()).put("idleTimeoutSeconds", config.idleTimeoutSeconds())
                .put("maxPendingOutputBytes", 4L * config.maxFrameBytes());
        blueprint.putObject("security").put("bearerRequired", bearerRequired);
        blueprint.putObject("activity").put("supported", true).put("mode", "connection-opt-in");
    }

    String json(OminiCatServer.Resources resources, Counters counters) throws JsonProcessingException {
        ObjectNode snapshot = blueprint.deepCopy();
        snapshot.putObject("runtime").put("javaVersion", System.getProperty("java.version"))
                .put("workerBackend", WorkerThreads.virtualThreadsAvailable() ? "virtual" : "platform")
                .put("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime())
                .put("availableProcessors", Runtime.getRuntime().availableProcessors());
        snapshot.putObject("metrics").put("connections", resources.connections())
                .put("activeSessions", resources.activeSessions()).put("admittedSessions", resources.admittedSessions())
                .put("pipelineWorkers", resources.pipelineWorkers()).put("pendingOutputBytes", resources.pendingOutputBytes())
                .put("sessionsTotal", counters.sessionsTotal()).put("framesInTotal", counters.framesInTotal())
                .put("framesOutTotal", counters.framesOutTotal()).put("rejectedTotal", counters.rejectedTotal())
                .put("failuresTotal", counters.failuresTotal()).put("bytesOutTotal", counters.bytesOutTotal());
        var memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
        var memory = snapshot.putObject("memory").put("heapUsedBytes", heap.getUsed())
                .put("heapCommittedBytes", heap.getCommitted()).put("heapMaxBytes", heap.getMax())
                .put("nonHeapUsedBytes", nonHeap.getUsed()).put("nonHeapCommittedBytes", nonHeap.getCommitted())
                .put("directUsedBytes", -1L).put("directCapacityBytes", -1L).put("directBufferCount", -1L);
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (pool.getName().equals("direct")) {
                // MXBean values may be -1 when unavailable. They do not describe total native memory.
                memory.put("directUsedBytes", pool.getMemoryUsed()).put("directCapacityBytes", pool.getTotalCapacity())
                        .put("directBufferCount", pool.getCount());
            }
        }
        var threadBean = ManagementFactory.getThreadMXBean();
        snapshot.putObject("threads").put("scope", "platform").put("live", threadBean.getThreadCount())
                .put("daemon", threadBean.getDaemonThreadCount()).put("peak", threadBean.getPeakThreadCount());
        return mapper.writeValueAsString(snapshot);
    }

    record Counters(long sessionsTotal, long framesInTotal, long framesOutTotal, long rejectedTotal,
                    long failuresTotal, long bytesOutTotal) { }
}

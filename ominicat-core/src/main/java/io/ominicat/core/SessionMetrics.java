package io.ominicat.core;

/**
 * A consistent session snapshot. Async continuation work items count as accepted
 * frames, alongside external submissions. Every accepted frame ultimately becomes processed,
 * failed, or cancelled; rejected frames were never enqueued. Processing time includes
 * processor and output callback time, but excludes queue waiting and cleanup.
 */
public record SessionMetrics(long accepted, long processed, long rejected, long failed,
                             long cancelled, int queueDepth, long totalProcessingNanos) { }

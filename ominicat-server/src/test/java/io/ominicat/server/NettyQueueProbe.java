package io.ominicat.server;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.AbstractScheduledEventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/** Test-only inspection of pinned Netty internals, sampled on their owning event loops. */
final class NettyQueueProbe {
    private NettyQueueProbe() { }

    static Queues sample(OminiCatServer server) throws Exception {
        Field workers = OminiCatServer.class.getDeclaredField("ioWorkers"); workers.setAccessible(true);
        Field scheduled = AbstractScheduledEventExecutor.class.getDeclaredField("scheduledTaskQueue"); scheduled.setAccessible(true);
        long tasks = 0, timers = 0;
        for (var executor : (EventLoopGroup) workers.get(server)) {
            var loop = (SingleThreadEventExecutor) executor;
            Queues count = loop.submit(() -> {
                Object queue = scheduled.get(loop);
                return new Queues(loop.pendingTasks(), queue == null ? 0 : ((Collection<?>) queue).size());
            }).get(2, TimeUnit.SECONDS);
            tasks += count.pendingTasks(); timers += count.scheduledTasks();
        }
        return new Queues(tasks, timers);
    }

    record Queues(long pendingTasks, long scheduledTasks) { }
}

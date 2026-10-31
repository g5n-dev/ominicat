package io.ominicat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class ParallelModelStressTest {
    private record BranchResult(int branch, int sequence) implements Frame { }

    @Test void randomizedForkJoinMatchesOrderedAllOrNothingOracleAndReleasesEveryChild() throws Exception {
        int seeds = Integer.getInteger("ominicat.stress.seeds", 64);
        for (int seed = 0; seed < seeds; seed++) {
            SplittableRandom random = new SplittableRandom(0xB4A6C4_2026L + seed);
            int count = random.nextInt(1, 5);
            int bound = random.nextInt(1, 5);
            CountDownLatch allEntered = new CountDownLatch(count);
            ArrayList<ParallelProcessor.Branch> branches = new ArrayList<>();
            ArrayList<Frame> expected = new ArrayList<>();
            ArrayList<AtomicInteger> active = new ArrayList<>();
            ArrayList<AtomicInteger> closed = new ArrayList<>();
            ArrayList<Integer> closeOrder = new ArrayList<>();
            boolean failureExpected = false;
            for (int branch = 0; branch < count; branch++) {
                int identity = branch;
                int emissions = random.nextInt(7);
                boolean fail = random.nextInt(9) == 0;
                failureExpected |= fail || emissions > bound;
                AtomicInteger running = new AtomicInteger(); active.add(running);
                AtomicInteger closes = new AtomicInteger(); closed.add(closes);
                for (int index = 0; index < emissions; index++) expected.add(new BranchResult(identity, index));
                branches.add(new ParallelProcessor.Branch("branch-" + identity, new Processor() {
                    @Override public void process(Frame frame, FrameSink next) throws Exception {
                        assertTrue(Thread.currentThread().isVirtual());
                        assertEquals(1, running.incrementAndGet()); allEntered.countDown();
                        try {
                            assertTrue(allEntered.await(2, TimeUnit.SECONDS));
                            if (fail) throw new IOException("expected branch failure");
                            for (int index = 0; index < emissions; index++) {
                                if ((index & 1) == 0) Thread.yield();
                                next.accept(new BranchResult(identity, index));
                            }
                        } finally { assertEquals(0, running.decrementAndGet()); }
                    }
                    @Override public void close() {
                        assertEquals(0, running.get(), "cleanup overlapped active branch");
                        assertEquals(1, closes.incrementAndGet()); closeOrder.add(identity);
                    }
                }));
            }
            List<Frame> actual = new ArrayList<>();
            try (ParallelProcessor parallel = new ParallelProcessor(branches, Duration.ofSeconds(3), bound)) {
                if (failureExpected) {
                    assertThrows(Exception.class, () -> parallel.process(new Frame.Text("request"), actual::add));
                    assertTrue(actual.isEmpty(), "partial branch results escaped seed=" + seed);
                } else {
                    parallel.process(new Frame.Text("request"), actual::add);
                    assertEquals(expected, actual, "join order seed=" + seed);
                }
            }
            for (AtomicInteger running : active) assertEquals(0, running.get());
            for (AtomicInteger closes : closed) assertEquals(1, closes.get());
            ArrayList<Integer> expectedCloseOrder = new ArrayList<>();
            for (int branch = count - 1; branch >= 0; branch--) expectedCloseOrder.add(branch);
            assertEquals(expectedCloseOrder, closeOrder);
        }
        System.out.printf("Parallel fork/join resource oracle: baseSeed=%d, seeds=%d, branches=1..4, buffers=1..4%n", 0xB4A6C4_2026L, seeds);
    }
}

package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Invariant 2, as a test rather than as a claim.
 *
 * <p>The zero-allocation property of {@code submit} / {@code cancel} /
 * {@code modify} was only ever proved by a hand-run {@code -prof gc}, which
 * means it was proved on the day someone remembered to run it. This drives a
 * fixed command loop and asserts two things afterwards: the order pool never
 * had to allocate, and the thread's own allocation counter barely moved.
 *
 * <p>The loop deliberately never opens or closes a price level — every order
 * rests at a price that already has other orders behind it. Level churn
 * allocates a red-black tree node and is a known, documented cost (see the
 * {@link OrderBook} javadoc); mixing it in here would set the threshold so high
 * that the test could no longer see a real regression. What is measured is the
 * steady state a long-running engine is actually in.
 *
 * <p>{@code getCurrentThreadAllocatedBytes} is a HotSpot extension. Where it is
 * unavailable the byte assertion is skipped and the pool assertion still runs.
 */
class AllocationTest {

    private static final int SYMBOL = 1;

    /** Price levels seeded either side, each kept permanently populated. */
    private static final int LEVELS = 8;
    private static final long MID = 100_000L;

    private static final int WARMUP = 200_000;
    private static final int MEASURED = 200_000;

    /**
     * Bytes per command the steady state is allowed. Not zero, because the
     * counter itself is sampled and JIT deoptimisation can land inside the
     * window — but far below the 24–36 B/op the boxed ladder used to cost, so a
     * regression of that kind cannot hide under it.
     */
    private static final long MAX_BYTES_PER_COMMAND = 8L;

    private MatchingEngine engine;
    private OrderPool pool;

    @BeforeEach
    void setUp() {
        pool = new OrderPool(1 << 16);
        pool.preallocate(1 << 16);
        engine = new MatchingEngine(ExecutionSink.NO_OP, pool);
        engine.registerSymbol(SYMBOL);

        // Two permanent orders per level, so nothing the loop does can empty
        // one and force the ladder to grow or shrink.
        long id = 1L;
        for (int level = 1; level <= LEVELS; level++) {
            for (int n = 0; n < 2; n++) {
                engine.submit(id++, SYMBOL, Side.BUY, TimeInForce.DAY, MID - level, 100L);
                engine.submit(id++, SYMBOL, Side.SELL, TimeInForce.DAY, MID + level, 100L);
            }
        }
    }

    /**
     * One net-neutral round: rest an order on an existing level, shrink it,
     * grow it back, cancel it. Four commands, and the book is exactly as it
     * was.
     */
    private void round(long orderId, int level) {
        boolean buy = (orderId & 1L) == 0L;
        long price = buy ? MID - level : MID + level;
        Side side = buy ? Side.BUY : Side.SELL;

        engine.submit(orderId, SYMBOL, side, TimeInForce.DAY, price, 10L);
        engine.modify(orderId, price, 6L);
        engine.modify(orderId, price, 10L);
        engine.cancel(orderId);
    }

    @Test
    void theSteadyStateNeitherGrowsThePoolNorAllocates() {
        long id = 1_000_000L;
        for (int i = 0; i < WARMUP; i++) {
            round(id++, (i % LEVELS) + 1);
        }

        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean hotspot =
                bean instanceof com.sun.management.ThreadMXBean sun ? sun : null;
        boolean bytesAvailable = hotspot != null && hotspot.isThreadAllocatedMemorySupported();

        long allocationsBefore = pool.allocations();
        long bytesBefore = bytesAvailable ? hotspot.getCurrentThreadAllocatedBytes() : 0L;

        for (int i = 0; i < MEASURED; i++) {
            round(id++, (i % LEVELS) + 1);
        }

        long poolGrowth = pool.allocations() - allocationsBefore;
        long bytes = bytesAvailable
                ? hotspot.getCurrentThreadAllocatedBytes() - bytesBefore : 0L;

        // The pool was preallocated past anything this loop holds at once, so
        // a single allocation here means an order escaped being recycled.
        assertThat(poolGrowth)
                .as("orders the pool had to allocate during the measured window")
                .isZero();

        Assumptions.assumeTrue(bytesAvailable, "thread allocation counter unavailable");
        long commands = MEASURED * 4L;
        assertThat(bytes / commands)
                .as("bytes per command over %d commands (%d bytes total)", commands, bytes)
                .isLessThanOrEqualTo(MAX_BYTES_PER_COMMAND);
    }
}

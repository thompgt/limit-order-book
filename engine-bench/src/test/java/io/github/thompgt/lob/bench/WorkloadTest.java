package io.github.thompgt.lob.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thompgt.lob.core.ExecutionSink;
import io.github.thompgt.lob.core.MatchingEngine;
import io.github.thompgt.lob.core.Order;
import io.github.thompgt.lob.core.OrderBook;
import io.github.thompgt.lob.core.RejectReason;
import io.github.thompgt.lob.core.Side;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The benchmark's two load-bearing assumptions, checked.
 *
 * <p>A workload that quietly grew the book would report throughput averaged
 * over depths nobody chose. A workload that quietly tripped rejects would
 * report the cost of the reject path — a validation check and an early return —
 * as though it were the cost of matching. Either would produce a number that
 * looks fine and means nothing, which is the failure mode worth testing for.
 */
class WorkloadTest {

    private static final int LEVELS = 8;
    private static final int ORDERS_PER_LEVEL = 4;
    private static final int SIZE = 4_096;
    private static final long SEED = 20260805L;

    /** Counts the rejects the engine would otherwise absorb silently. */
    private static final class RejectCounter implements ExecutionSink {
        final List<String> rejects = new ArrayList<>();

        @Override
        public void rejected(long orderId, int symbolId, RejectReason reason) {
            rejects.add(orderId + ":" + reason);
        }
    }

    private static List<String> depth(OrderBook book, Side side) {
        List<String> levels = new ArrayList<>();
        book.snapshot(side, Integer.MAX_VALUE, (price, qty, count) ->
                levels.add(price + "x" + qty + "/" + count));
        return levels;
    }

    @ParameterizedTest
    @EnumSource(Workload.Mix.class)
    void aFullPassLeavesTheBookExactlyAsItFoundIt(Workload.Mix mix) {
        RejectCounter sink = new RejectCounter();
        MatchingEngine engine = new MatchingEngine(sink);
        Workload workload = Workload.generate(mix, LEVELS, ORDERS_PER_LEVEL, SIZE, SEED);
        workload.seed(engine);
        OrderBook book = engine.book(Workload.SYMBOL);

        List<String> bidsBefore = depth(book, Side.BUY);
        List<String> asksBefore = depth(book, Side.SELL);
        int liveBefore = engine.liveOrderCount();

        for (int i = 0; i < workload.size(); i++) {
            workload.apply(engine, i);
        }

        assertThat(depth(book, Side.BUY)).as("bids").isEqualTo(bidsBefore);
        assertThat(depth(book, Side.SELL)).as("asks").isEqualTo(asksBefore);
        assertThat(engine.liveOrderCount()).as("live orders").isEqualTo(liveBefore);
    }

    @ParameterizedTest
    @EnumSource(Workload.Mix.class)
    void noCommandIsEverRejected(Workload.Mix mix) {
        RejectCounter sink = new RejectCounter();
        MatchingEngine engine = new MatchingEngine(sink);
        Workload workload = Workload.generate(mix, LEVELS, ORDERS_PER_LEVEL, SIZE, SEED);
        workload.seed(engine);

        for (int i = 0; i < workload.size(); i++) {
            workload.apply(engine, i);
        }

        assertThat(sink.rejects).isEmpty();
    }

    @Test
    void wrappingAroundTheScriptStaysValid() {
        // The benchmark cycles the script for the whole iteration, so the state
        // at the end of a pass has to be a legal starting state for the next —
        // including every order id being free again.
        RejectCounter sink = new RejectCounter();
        MatchingEngine engine = new MatchingEngine(sink);
        Workload workload = Workload.generate(
                Workload.Mix.MIXED, LEVELS, ORDERS_PER_LEVEL, SIZE, SEED);
        workload.seed(engine);
        OrderBook book = engine.book(Workload.SYMBOL);
        List<String> bidsBefore = depth(book, Side.BUY);

        for (int pass = 0; pass < 5; pass++) {
            for (int i = 0; i < workload.size(); i++) {
                workload.apply(engine, i);
            }
        }

        assertThat(sink.rejects).isEmpty();
        assertThat(depth(book, Side.BUY)).isEqualTo(bidsBefore);
    }

    @Test
    void theSeededBookHasTheDepthItClaims() {
        MatchingEngine engine = new MatchingEngine();
        Workload workload = Workload.generate(
                Workload.Mix.MIXED, LEVELS, ORDERS_PER_LEVEL, SIZE, SEED);
        workload.seed(engine);
        OrderBook book = engine.book(Workload.SYMBOL);

        assertThat(book.levelCount(Side.BUY)).isEqualTo(LEVELS);
        assertThat(book.levelCount(Side.SELL)).isEqualTo(LEVELS);
        assertThat(engine.liveOrderCount()).isEqualTo(LEVELS * ORDERS_PER_LEVEL * 2);
        assertThat(book.isCrossed()).isFalse();
    }

    @Test
    void replayingACommandDoesNotAllocateOnceTheEngineIsWarm() {
        // The point of the whole exercise: the measured path must not allocate.
        // -prof gc is the real proof; this is the cheap check that catches an
        // obvious regression without waiting for a benchmark run.
        MatchingEngine engine = new MatchingEngine();
        Workload workload = Workload.generate(
                Workload.Mix.MIXED, LEVELS, ORDERS_PER_LEVEL, SIZE, SEED);
        workload.seed(engine);

        for (int warmup = 0; warmup < 20; warmup++) {
            for (int i = 0; i < workload.size(); i++) {
                workload.apply(engine, i);
            }
        }
        long allocationsBefore = engine.pool().allocations();

        for (int i = 0; i < workload.size(); i++) {
            workload.apply(engine, i);
        }

        assertThat(engine.pool().allocations()).isEqualTo(allocationsBefore);
    }

    @Test
    void everyOrderIsStillReachableByIdAfterAPass() {
        MatchingEngine engine = new MatchingEngine();
        Workload workload = Workload.generate(
                Workload.Mix.MIXED, LEVELS, ORDERS_PER_LEVEL, SIZE, SEED);
        workload.seed(engine);

        for (int i = 0; i < workload.size(); i++) {
            workload.apply(engine, i);
        }

        int seeded = LEVELS * ORDERS_PER_LEVEL * 2;
        for (long id = 1; id <= seeded; id++) {
            Order order = engine.order(id);
            assertThat(order).as("seeded order %d", id).isNotNull();
            assertThat(order.isResting()).isTrue();
        }
    }
}

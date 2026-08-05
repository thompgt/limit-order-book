package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Modify semantics — the table in {@code CLAUDE.md}, one test per rule.
 *
 * <p>Whether a modify keeps its place in the queue is the single thing most
 * implementations get wrong, and it is invisible from the outside until real
 * money is queued behind it. So the tests here do not assert on flags: they
 * modify an order and then send an aggressor, because who gets filled first is
 * the only observation that actually proves where in the queue an order sits.
 */
class ModifyTest {

    private static final int SYMBOL = 1;

    private RecordingSink sink;
    private MatchingEngine engine;
    private OrderBook book;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        engine = new MatchingEngine(sink);
        book = engine.registerSymbol(SYMBOL);
    }

    private SubmitResult submit(long id, Side side, long price, long quantity) {
        return engine.submit(id, SYMBOL, side, TimeInForce.DAY, price, quantity);
    }

    private List<String> depth(Side side) {
        List<String> levels = new ArrayList<>();
        book.snapshot(side, 16, (price, qty, count) -> levels.add(price + "x" + qty));
        return levels;
    }

    // ------------------------------------------------------- the three rules

    @Test
    void modifyDecreasingQuantityKeepsTimePriority() {
        submit(1L, Side.SELL, 100L, 30L);
        submit(2L, Side.SELL, 100L, 30L);

        engine.modify(1L, 100L, 10L);
        submit(3L, Side.BUY, 100L, 10L);

        // Order 1 shrank but did not move, so it is still the one at the front.
        assertThat(sink.hitOrderIds()).containsExactly(1L);
        assertThat(book.quantityAt(Side.SELL, 100L)).isEqualTo(30L);
    }

    @Test
    void modifyIncreasingQuantityLosesTimePriority() {
        submit(1L, Side.SELL, 100L, 10L);
        submit(2L, Side.SELL, 100L, 10L);

        engine.modify(1L, 100L, 20L);
        submit(3L, Side.BUY, 100L, 10L);

        // Asking for more than you queued for is a new request: order 1 went to
        // the back, so order 2 is now the head.
        assertThat(sink.hitOrderIds()).containsExactly(2L);
    }

    @Test
    void modifyChangingPriceLosesTimePriority() {
        submit(1L, Side.SELL, 100L, 10L);
        submit(2L, Side.SELL, 100L, 10L);

        // Away to 101 and straight back to 100 — same price as it started, but
        // it left the level, so it queues behind order 2.
        engine.modify(1L, 101L, 10L);
        engine.modify(1L, 100L, 10L);
        submit(3L, Side.BUY, 100L, 10L);

        assertThat(sink.hitOrderIds()).containsExactly(2L);
    }

    @Test
    void modifyChangingPriceDownwardsAlsoLosesTimePriority() {
        // "Any direction". Improving your price is still leaving the queue you
        // were in, and there is no queue at the new price to have been in.
        submit(1L, Side.BUY, 100L, 10L);
        submit(2L, Side.BUY, 99L, 10L);

        engine.modify(1L, 99L, 10L);

        RecordingSink.Replacement replacement = sink.lastReplacement();
        assertThat(replacement.priorityLost()).isTrue();
        assertThat(replacement.newSequence()).isGreaterThan(2L);
    }

    @Test
    void modifyDecreasingQuantityKeepsTheOriginalSequenceNumber() {
        submit(1L, Side.BUY, 100L, 30L);
        long sequence = engine.order(1L).sequence();

        SubmitResult result = engine.modify(1L, 100L, 10L);

        assertThat(result.sequence()).isEqualTo(sequence);
        assertThat(engine.order(1L).sequence()).isEqualTo(sequence);
        assertThat(sink.lastReplacement().priorityLost()).isFalse();
    }

    @Test
    void modifyLosingPriorityTakesAFreshSequenceNumber() {
        submit(1L, Side.BUY, 100L, 10L);
        submit(2L, Side.BUY, 100L, 10L);

        SubmitResult result = engine.modify(1L, 101L, 10L);

        assertThat(result.sequence()).isEqualTo(3L);
        assertThat(engine.order(1L).sequence()).isEqualTo(3L);
    }

    // ------------------------------------------------------- book bookkeeping

    @Test
    void modifyMovesTheOrderToTheNewPriceLevel() {
        submit(1L, Side.BUY, 100L, 20L);

        engine.modify(1L, 98L, 20L);

        assertThat(book.bestBid()).isEqualTo(98L);
        assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
        assertThat(depth(Side.BUY)).containsExactly("98x20");
    }

    @Test
    void modifyingTheLastOrderOffALevelRemovesTheEmptyLevel() {
        submit(1L, Side.SELL, 100L, 10L);
        submit(2L, Side.SELL, 101L, 10L);

        engine.modify(1L, 102L, 10L);

        assertThat(book.bestAsk()).isEqualTo(101L);
        assertThat(book.quantityAt(Side.SELL, 100L)).isZero();
        assertThat(depth(Side.SELL)).containsExactly("101x10", "102x10");
    }

    @Test
    void aDecreaseInPlaceKeepsTheLevelsDepthInStep() {
        // The level caches its own total for O(1) depth, so a shrink that
        // forgot to update it would report quantity that is not there.
        submit(1L, Side.BUY, 100L, 40L);
        submit(2L, Side.BUY, 100L, 10L);

        engine.modify(1L, 100L, 15L);

        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(25L);
        assertThat(depth(Side.BUY)).containsExactly("100x25");
    }

    @Test
    void modifyingAnOrderDoesNotChangeHowManyAreLive() {
        submit(1L, Side.BUY, 100L, 10L);

        engine.modify(1L, 101L, 25L);

        assertThat(engine.liveOrderCount()).isEqualTo(1);
        assertThat(engine.order(1L).price()).isEqualTo(101L);
        assertThat(engine.order(1L).quantity()).isEqualTo(25L);
        assertThat(engine.order(1L).remainingQuantity()).isEqualTo(25L);
    }

    // ------------------------------------------------- modify that now crosses

    @Test
    void aModifyThatCrossesTheBookTradesLikeAFreshAggressor() {
        submit(1L, Side.SELL, 105L, 10L);
        submit(2L, Side.BUY, 100L, 10L);

        SubmitResult result = engine.modify(2L, 105L, 10L);

        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(result.filledQuantity()).isEqualTo(10L);
        assertThat(sink.tradePrices()).containsExactly(105L);
        assertThat(book.isEmpty()).isTrue();
        assertThat(engine.order(2L)).isNull();
    }

    @Test
    void aModifyThatCrossesOnlyPartWayRestsTheRemainder() {
        submit(1L, Side.SELL, 105L, 6L);
        submit(2L, Side.BUY, 100L, 20L);

        SubmitResult result = engine.modify(2L, 105L, 20L);

        assertThat(result.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(result.filledQuantity()).isEqualTo(6L);
        assertThat(result.restingQuantity()).isEqualTo(14L);
        assertThat(book.bestBid()).isEqualTo(105L);
        assertThat(depth(Side.BUY)).containsExactly("105x14");
    }

    @Test
    void aModifiedOrderNeverTradesWithItself() {
        // It comes off the book before it matches. If it did not, it would find
        // its own resting quantity sitting at the price it is moving to.
        submit(1L, Side.BUY, 100L, 10L);

        SubmitResult result = engine.modify(1L, 100L, 20L);

        assertThat(result.filledQuantity()).isZero();
        assertThat(engine.tradeCount()).isZero();
        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(20L);
    }

    @Test
    void theReplacedEventFiresBeforeTheTradesItCauses() {
        submit(1L, Side.SELL, 105L, 10L);
        submit(2L, Side.BUY, 100L, 10L);
        sink.clear();

        engine.modify(2L, 105L, 10L);

        assertThat(sink.events()).containsExactly(
                "replaced:2:10@100->10@105:lost",
                "trade:10@105",
                "filled:1",
                "filled:2");
    }

    // --------------------------------------------------- partially filled base

    @Nested
    class OnAPartiallyFilledOrder {

        @BeforeEach
        void restAnOrderThatIsHalfDone() {
            submit(1L, Side.BUY, 100L, 40L);
            submit(2L, Side.SELL, 100L, 15L);
            // Order 1: 40 submitted, 15 filled, 25 still open.
        }

        @Test
        void quantityIsTheNewTotalNotTheNewRemainder() {
            engine.modify(1L, 100L, 30L);

            assertThat(engine.order(1L).quantity()).isEqualTo(30L);
            assertThat(engine.order(1L).filledQuantity()).isEqualTo(15L);
            assertThat(engine.order(1L).remainingQuantity()).isEqualTo(15L);
        }

        @Test
        void aTotalThatShrinksTheRemainderStillCountsAsADecrease() {
            long sequence = engine.order(1L).sequence();

            engine.modify(1L, 100L, 30L);

            assertThat(engine.order(1L).sequence()).isEqualTo(sequence);
            assertThat(sink.lastReplacement().priorityLost()).isFalse();
        }

        @Test
        void aTotalThatGrowsTheRemainderCountsAsAnIncrease() {
            engine.modify(1L, 100L, 60L);

            assertThat(engine.order(1L).remainingQuantity()).isEqualTo(45L);
            assertThat(sink.lastReplacement().priorityLost()).isTrue();
        }

        @Test
        void aTotalAtOrBelowWhatAlreadyTradedIsRejected() {
            // 15 units are gone. Asking for a 15-unit order is asking to undo
            // a trade, and quietly reading it as a cancel would be a different
            // instruction than the one sent.
            assertThat(engine.modify(1L, 100L, 15L).rejectReason())
                    .isEqualTo(RejectReason.QUANTITY_BELOW_FILLED);
            assertThat(engine.modify(1L, 100L, 14L).rejectReason())
                    .isEqualTo(RejectReason.QUANTITY_BELOW_FILLED);
            assertThat(engine.order(1L).quantity()).isEqualTo(40L);
        }

        @Test
        void aRejectedModifyLeavesTheOrderExactlyAsItWas() {
            engine.modify(1L, 100L, 0L);

            assertThat(engine.order(1L).price()).isEqualTo(100L);
            assertThat(engine.order(1L).quantity()).isEqualTo(40L);
            assertThat(engine.order(1L).remainingQuantity()).isEqualTo(25L);
            assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(25L);
        }
    }

    // -------------------------------------------------------------- rejects

    @Test
    void modifyingAnUnknownIdIsRejected() {
        SubmitResult result = engine.modify(99L, 100L, 10L);

        assertThat(result.rejectReason()).isEqualTo(RejectReason.UNKNOWN_ORDER_ID);
        assertThat(result.symbolId()).isEqualTo(MatchingEngine.NO_SYMBOL);
    }

    @Test
    void modifyingAFilledOrderIsRejected() {
        submit(1L, Side.SELL, 100L, 10L);
        submit(2L, Side.BUY, 100L, 10L);

        assertThat(engine.modify(1L, 101L, 10L).rejectReason())
                .isEqualTo(RejectReason.UNKNOWN_ORDER_ID);
    }

    @Test
    void modifyingToANonPositiveQuantityIsRejected() {
        submit(1L, Side.BUY, 100L, 10L);

        assertThat(engine.modify(1L, 100L, 0L).rejectReason())
                .isEqualTo(RejectReason.NON_POSITIVE_QUANTITY);
        assertThat(engine.modify(1L, 100L, -5L).rejectReason())
                .isEqualTo(RejectReason.NON_POSITIVE_QUANTITY);
    }

    @Test
    void modifyingToTheMarketSentinelPriceIsRejected() {
        submit(1L, Side.BUY, 100L, 10L);

        assertThat(engine.modify(1L, Side.BUY.marketPrice(), 10L).rejectReason())
                .isEqualTo(RejectReason.RESERVED_PRICE);
        assertThat(book.bestBid()).isEqualTo(100L);
    }

    @Test
    void aRejectedModifyConsumesNoSequenceNumber() {
        submit(1L, Side.BUY, 100L, 10L);
        engine.modify(99L, 100L, 10L);

        SubmitResult next = submit(2L, Side.BUY, 99L, 10L);

        assertThat(next.sequence()).isEqualTo(2L);
    }

    @Test
    void aDecreaseInPlaceConsumesNoSequenceNumber() {
        // It never left the queue, so there is no new arrival to number.
        submit(1L, Side.BUY, 100L, 10L);
        engine.modify(1L, 100L, 5L);

        SubmitResult next = submit(2L, Side.BUY, 99L, 10L);

        assertThat(next.sequence()).isEqualTo(2L);
    }

    // ---------------------------------------------------------------- pooling

    @Test
    void aModifyThatFillsCompletelyReturnsTheOrderToThePool() {
        OrderPool pool = new OrderPool(64);
        pool.preallocate(8);
        MatchingEngine pooled = new MatchingEngine(sink, pool);
        pooled.registerSymbol(SYMBOL);
        pooled.submit(1L, SYMBOL, Side.SELL, TimeInForce.DAY, 105L, 10L);
        pooled.submit(2L, SYMBOL, Side.BUY, TimeInForce.DAY, 100L, 10L);
        assertThat(pool.available()).isEqualTo(6);

        pooled.modify(2L, 105L, 10L);

        assertThat(pool.available()).isEqualTo(8);
        assertThat(pooled.liveOrderCount()).isZero();
        assertThat(pool.allocations()).isEqualTo(8L);
    }

    @Test
    void repeatedModifyingLeavesTheBookConsistent() {
        submit(1L, Side.BUY, 100L, 100L);
        submit(2L, Side.SELL, 200L, 100L);

        for (long i = 0; i < 2_000; i++) {
            engine.modify(1L, 90L + i % 10, 50L + i % 40);
            engine.modify(2L, 190L + i % 10, 50L + i % 40);
        }

        assertThat(engine.liveOrderCount()).isEqualTo(2);
        assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
        assertThat(book.levelCount(Side.SELL)).isEqualTo(1);
        assertThat(book.quantityAt(Side.BUY, engine.order(1L).price()))
                .isEqualTo(engine.order(1L).remainingQuantity());
        assertThat(engine.tradeCount()).isZero();
    }
}

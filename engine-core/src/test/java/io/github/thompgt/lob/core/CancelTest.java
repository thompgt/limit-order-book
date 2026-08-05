package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cancel semantics.
 *
 * <p>The interesting cases are all about what a cancel does <em>not</em> touch:
 * it takes off open quantity and nothing else, it leaves the rest of the queue
 * in the order it was in, and it cannot reach an order that has already gone.
 */
class CancelTest {

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

    // ------------------------------------------------------------ the basics

    @Test
    void cancellingARestingOrderTakesItOffTheBook() {
        submit(1L, Side.BUY, 100L, 25L);

        CancelResult result = engine.cancel(1L);

        assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(result.canceledQuantity()).isEqualTo(25L);
        assertThat(result.symbolId()).isEqualTo(SYMBOL);
        assertThat(book.bestBid()).isEqualTo(OrderBook.NO_BID);
        assertThat(engine.order(1L)).isNull();
        assertThat(engine.liveOrderCount()).isZero();
    }

    @Test
    void cancellingTheLastOrderAtAPriceRemovesTheLevel() {
        submit(1L, Side.SELL, 100L, 10L);
        submit(2L, Side.SELL, 101L, 10L);

        engine.cancel(1L);

        assertThat(book.levelCount(Side.SELL)).isEqualTo(1);
        assertThat(book.bestAsk()).isEqualTo(101L);
        assertThat(book.quantityAt(Side.SELL, 100L)).isZero();
    }

    @Test
    void cancellingOneOrderLeavesTheRestOfTheLevelIntact() {
        submit(1L, Side.BUY, 100L, 10L);
        submit(2L, Side.BUY, 100L, 20L);
        submit(3L, Side.BUY, 100L, 30L);

        engine.cancel(2L);

        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(40L);
        assertThat(depth(Side.BUY)).containsExactly("100x40");
        assertThat(engine.liveOrderCount()).isEqualTo(2);
    }

    @Test
    void cancellingFromTheMiddleDoesNotDisturbTheQueueOrder() {
        // The queue is intrusively linked, so an unlink that got a pointer wrong
        // would show up here as the wrong order being hit next.
        submit(1L, Side.SELL, 100L, 10L);
        submit(2L, Side.SELL, 100L, 10L);
        submit(3L, Side.SELL, 100L, 10L);

        engine.cancel(2L);
        submit(4L, Side.BUY, 100L, 20L);

        assertThat(sink.hitOrderIds()).containsExactly(1L, 3L);
    }

    @Test
    void cancellingTheHeadPromotesTheOrderBehindIt() {
        submit(1L, Side.SELL, 100L, 10L);
        submit(2L, Side.SELL, 100L, 10L);

        engine.cancel(1L);
        submit(3L, Side.BUY, 100L, 10L);

        assertThat(sink.hitOrderIds()).containsExactly(2L);
    }

    // --------------------------------------------------------- partial fills

    @Test
    void cancellingAPartiallyFilledOrderTakesOnlyWhatIsLeft() {
        // Traded quantity is done. A cancel cannot reach back and undo it.
        submit(1L, Side.SELL, 100L, 40L);
        submit(2L, Side.BUY, 100L, 15L);

        CancelResult result = engine.cancel(1L);

        assertThat(result.canceledQuantity()).isEqualTo(25L);
        assertThat(engine.tradeCount()).isEqualTo(1L);
    }

    // -------------------------------------------------------------- rejects

    @Test
    void cancellingAnUnknownIdIsRejected() {
        CancelResult result = engine.cancel(99L);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectReason()).isEqualTo(RejectReason.UNKNOWN_ORDER_ID);
        assertThat(result.symbolId()).isEqualTo(MatchingEngine.NO_SYMBOL);
    }

    @Test
    void cancellingAnOrderThatAlreadyFilledIsRejected() {
        // Not an error the client could have avoided — the fill and the cancel
        // simply crossed — but the engine must not pretend it removed anything.
        submit(1L, Side.SELL, 100L, 10L);
        submit(2L, Side.BUY, 100L, 10L);

        CancelResult result = engine.cancel(1L);

        assertThat(result.rejectReason()).isEqualTo(RejectReason.UNKNOWN_ORDER_ID);
    }

    @Test
    void cancellingTwiceRejectsTheSecondTime() {
        submit(1L, Side.BUY, 100L, 10L);

        assertThat(engine.cancel(1L).isRejected()).isFalse();
        assertThat(engine.cancel(1L).rejectReason()).isEqualTo(RejectReason.UNKNOWN_ORDER_ID);
    }

    @Test
    void aCancelOfAnUnknownIdEmitsARejectAndNothingElse() {
        engine.cancel(99L);

        assertThat(sink.events()).containsExactly("rejected:99:UNKNOWN_ORDER_ID");
    }

    // ------------------------------------------------------------- reporting

    @Test
    void aCancelEmitsTheOpenQuantityItRemoved() {
        submit(1L, Side.BUY, 100L, 30L);
        sink.clear();

        engine.cancel(1L);

        assertThat(sink.events()).containsExactly("canceled:1:30:USER");
        assertThat(sink.cancelReason(1L)).isEqualTo(CancelReason.USER);
    }

    // ------------------------------------------------------------------ pool

    @Test
    void aCancelledOrderGoesBackToThePool() {
        OrderPool pool = new OrderPool(64);
        pool.preallocate(8);
        MatchingEngine pooled = new MatchingEngine(sink, pool);
        pooled.registerSymbol(SYMBOL);
        pooled.submit(1L, SYMBOL, Side.BUY, TimeInForce.DAY, 100L, 10L);
        assertThat(pool.available()).isEqualTo(7);

        pooled.cancel(1L);

        assertThat(pool.available()).isEqualTo(8);
        assertThat(pool.allocations()).isEqualTo(8L);
    }

    @Test
    void anIdIsFreeAgainOnceItsOrderIsCancelled() {
        submit(1L, Side.BUY, 100L, 10L);
        engine.cancel(1L);

        SubmitResult reused = submit(1L, Side.SELL, 105L, 10L);

        assertThat(reused.isRejected()).isFalse();
        assertThat(book.bestAsk()).isEqualTo(105L);
    }

    @Test
    void cancellingEveryOrderEmptiesTheBookCompletely() {
        for (long id = 1L; id <= 50L; id++) {
            // Bids well below the offers, so nothing trades and every id is
            // still cancellable when the second loop comes round.
            Side side = id % 2 == 0 ? Side.BUY : Side.SELL;
            submit(id, side, (side == Side.BUY ? 90L : 100L) + id % 5, 10L);
        }
        for (long id = 1L; id <= 50L; id++) {
            engine.cancel(id);
        }

        assertThat(book.isEmpty()).isTrue();
        assertThat(engine.liveOrderCount()).isZero();
        assertThat(depth(Side.BUY)).isEmpty();
        assertThat(depth(Side.SELL)).isEmpty();
    }
}

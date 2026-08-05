package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What happens to the part of an order that does not trade: rest it (DAY),
 * kill it (IOC), or refuse to have started (FOK). Plus market orders, which
 * cannot rest whatever their time-in-force says.
 *
 * <p>The load-bearing case is FOK. An FOK that cannot fill in full must emit
 * <em>no</em> trades — not trades that are later reversed — so several tests
 * here assert on the absence of events rather than on the result object.
 */
class TimeInForceTest {

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

    private SubmitResult rest(long id, Side side, long price, long quantity) {
        return engine.submit(id, SYMBOL, side, TimeInForce.DAY, price, quantity);
    }

    private List<String> depth(Side side) {
        List<String> levels = new ArrayList<>();
        book.snapshot(side, 16, (price, qty, count) -> levels.add(price + "x" + qty));
        return levels;
    }

    // ------------------------------------------------------------------- IOC

    @Nested
    class ImmediateOrCancel {

        @Test
        void takesWhatIsAvailableAndKillsTheRest() {
            rest(1L, Side.SELL, 100L, 6L);

            SubmitResult result =
                    engine.submit(2L, SYMBOL, Side.BUY, TimeInForce.IOC, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(result.filledQuantity()).isEqualTo(6L);
            assertThat(result.restingQuantity()).isZero();
            assertThat(book.isEmpty()).isTrue();
        }

        @Test
        void neverRestsEvenWhenNothingTrades() {
            SubmitResult result =
                    engine.submit(1L, SYMBOL, Side.BUY, TimeInForce.IOC, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(result.filledQuantity()).isZero();
            assertThat(book.isEmpty()).isTrue();
            assertThat(engine.liveOrderCount()).isZero();
        }

        @Test
        void fillingCompletelyIsAnOrdinaryFillNotACancel() {
            rest(1L, Side.SELL, 100L, 10L);

            SubmitResult result =
                    engine.submit(2L, SYMBOL, Side.BUY, TimeInForce.IOC, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(sink.canceled()).isEmpty();
        }

        @Test
        void sweepsSeveralLevelsBeforeGivingUpOnTheRemainder() {
            rest(1L, Side.SELL, 100L, 5L);
            rest(2L, Side.SELL, 101L, 5L);
            rest(3L, Side.SELL, 103L, 5L);

            SubmitResult result =
                    engine.submit(4L, SYMBOL, Side.BUY, TimeInForce.IOC, 101L, 20L);

            // 101 is the limit, so the 103 offer is out of reach.
            assertThat(result.filledQuantity()).isEqualTo(10L);
            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(depth(Side.SELL)).containsExactly("103x5");
        }

        @Test
        void doesNotHoldOntoItsId() {
            rest(1L, Side.SELL, 100L, 6L);
            engine.submit(2L, SYMBOL, Side.BUY, TimeInForce.IOC, 100L, 10L);

            SubmitResult reused = rest(2L, Side.BUY, 99L, 10L);

            assertThat(reused.isRejected()).isFalse();
        }

        @Test
        void reportsTheKilledQuantityOnTheCancelEvent() {
            rest(1L, Side.SELL, 100L, 6L);
            sink.clear();

            engine.submit(2L, SYMBOL, Side.BUY, TimeInForce.IOC, 100L, 10L);

            assertThat(sink.events()).containsExactly(
                    "accepted:2",
                    "trade:6@100",
                    "filled:1",
                    "canceled:2:4:IMMEDIATE_OR_CANCEL");
            assertThat(sink.cancelReason(2L)).isEqualTo(CancelReason.IMMEDIATE_OR_CANCEL);
        }
    }

    // ------------------------------------------------------------------- FOK

    @Nested
    class FillOrKill {

        @Test
        void fillsEntirelyWhenTheBookCanCoverIt() {
            rest(1L, Side.SELL, 100L, 4L);
            rest(2L, Side.SELL, 101L, 6L);

            SubmitResult result =
                    engine.submit(3L, SYMBOL, Side.BUY, TimeInForce.FOK, 101L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(result.filledQuantity()).isEqualTo(10L);
            assertThat(result.tradeCount()).isEqualTo(2);
            assertThat(book.isEmpty()).isTrue();
        }

        @Test
        void emitsNoTradesAtAllWhenItCannotFillInFull() {
            // The whole point: not "trades that get reversed", but no trades.
            // Anything published to a consumer cannot be taken back.
            rest(1L, Side.SELL, 100L, 6L);
            sink.clear();

            SubmitResult result =
                    engine.submit(2L, SYMBOL, Side.BUY, TimeInForce.FOK, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(result.filledQuantity()).isZero();
            assertThat(sink.trades()).isEmpty();
            assertThat(engine.tradeCount()).isZero();
        }

        @Test
        void leavesTheBookUntouchedWhenItCannotFill() {
            rest(1L, Side.SELL, 100L, 6L);

            engine.submit(2L, SYMBOL, Side.BUY, TimeInForce.FOK, 100L, 10L);

            assertThat(depth(Side.SELL)).containsExactly("100x6");
            assertThat(engine.order(1L).remainingQuantity()).isEqualTo(6L);
            assertThat(engine.liveOrderCount()).isEqualTo(1);
        }

        @Test
        void countsOnlyQuantityItsLimitCanReach() {
            // 10 units are on the book, but only 4 of them at a price this
            // buyer will pay. Counting the whole book would let it start and
            // then strand itself half-filled.
            rest(1L, Side.SELL, 100L, 4L);
            rest(2L, Side.SELL, 105L, 6L);

            SubmitResult result =
                    engine.submit(3L, SYMBOL, Side.BUY, TimeInForce.FOK, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(sink.trades()).isEmpty();
        }

        @Test
        void anExactlyCoveredQuantityFills() {
            rest(1L, Side.SELL, 100L, 10L);

            SubmitResult result =
                    engine.submit(2L, SYMBOL, Side.BUY, TimeInForce.FOK, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        }

        @Test
        void oneUnitShortIsStillAKill() {
            rest(1L, Side.SELL, 100L, 9L);

            SubmitResult result =
                    engine.submit(2L, SYMBOL, Side.BUY, TimeInForce.FOK, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(sink.trades()).isEmpty();
        }

        @Test
        void anEmptyBookKillsItImmediately() {
            SubmitResult result =
                    engine.submit(1L, SYMBOL, Side.BUY, TimeInForce.FOK, 100L, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(sink.events()).containsExactly(
                    "accepted:1", "canceled:1:10:FILL_OR_KILL");
        }

        @Test
        void isStillAcceptedBeforeItIsKilled() {
            // A kill is not a reject. The order was valid; the book was not
            // deep enough, which is a different thing to tell a client.
            SubmitResult result =
                    engine.submit(1L, SYMBOL, Side.BUY, TimeInForce.FOK, 100L, 10L);

            assertThat(result.isRejected()).isFalse();
            assertThat(result.sequence()).isEqualTo(1L);
            assertThat(sink.cancelReason(1L)).isEqualTo(CancelReason.FILL_OR_KILL);
        }
    }

    // --------------------------------------------------------- market orders

    @Nested
    class MarketOrders {

        @Test
        void sweepEveryPriceUntilTheirQuantityIsGone() {
            rest(1L, Side.SELL, 100L, 5L);
            rest(2L, Side.SELL, 200L, 5L);
            rest(3L, Side.SELL, 9_999L, 5L);

            SubmitResult result =
                    engine.submitMarket(4L, SYMBOL, Side.BUY, TimeInForce.DAY, 15L);

            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(sink.tradePrices()).containsExactly(100L, 200L, 9_999L);
            assertThat(book.isEmpty()).isTrue();
        }

        @Test
        void stillTakeThePricesInOrderBestFirst() {
            rest(1L, Side.BUY, 90L, 5L);
            rest(2L, Side.BUY, 100L, 5L);
            rest(3L, Side.BUY, 95L, 5L);

            engine.submitMarket(4L, SYMBOL, Side.SELL, TimeInForce.DAY, 15L);

            assertThat(sink.tradePrices()).containsExactly(100L, 95L, 90L);
        }

        @Test
        void neverRestEvenOnADayTimeInForce() {
            // There is no price to rest at. DAY cannot change that, so the
            // remainder is cancelled exactly as an IOC's would be.
            rest(1L, Side.SELL, 100L, 4L);

            SubmitResult result =
                    engine.submitMarket(2L, SYMBOL, Side.BUY, TimeInForce.DAY, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(result.filledQuantity()).isEqualTo(4L);
            assertThat(book.isEmpty()).isTrue();
            assertThat(sink.cancelReason(2L)).isEqualTo(CancelReason.IMMEDIATE_OR_CANCEL);
        }

        @Test
        void againstAnEmptyBookTradeNothingAndDisappear() {
            SubmitResult result =
                    engine.submitMarket(1L, SYMBOL, Side.SELL, TimeInForce.DAY, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(result.filledQuantity()).isZero();
            assertThat(engine.liveOrderCount()).isZero();
            assertThat(book.isEmpty()).isTrue();
        }

        @Test
        void withFillOrKillDemandTheWholeBookOrNothing() {
            rest(1L, Side.SELL, 100L, 9L);

            SubmitResult result =
                    engine.submitMarket(2L, SYMBOL, Side.BUY, TimeInForce.FOK, 10L);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(sink.cancelReason(2L)).isEqualTo(CancelReason.FILL_OR_KILL);
            assertThat(sink.trades()).isEmpty();
            assertThat(depth(Side.SELL)).containsExactly("100x9");
        }

        @Test
        void neverLeaveTheBookCrossed() {
            rest(1L, Side.SELL, 100L, 5L);
            rest(2L, Side.BUY, 98L, 5L);

            engine.submitMarket(3L, SYMBOL, Side.BUY, TimeInForce.DAY, 5L);

            assertThat(book.isCrossed()).isFalse();
            assertThat(book.bestBid()).isEqualTo(98L);
            assertThat(book.bestAsk()).isEqualTo(OrderBook.NO_ASK);
        }

        @Test
        void areStillRejectedForTheOrdinaryReasons() {
            assertThat(engine.submitMarket(1L, SYMBOL, Side.BUY, TimeInForce.DAY, 0L)
                    .rejectReason()).isEqualTo(RejectReason.NON_POSITIVE_QUANTITY);
            assertThat(engine.submitMarket(1L, 77, Side.BUY, TimeInForce.DAY, 10L)
                    .rejectReason()).isEqualTo(RejectReason.UNKNOWN_SYMBOL);
        }
    }

    // ---------------------------------------------------------------- pooling

    @Test
    void aKilledOrderGoesStraightBackToThePool() {
        OrderPool pool = new OrderPool(64);
        pool.preallocate(8);
        MatchingEngine pooled = new MatchingEngine(sink, pool);
        pooled.registerSymbol(SYMBOL);

        pooled.submit(1L, SYMBOL, Side.BUY, TimeInForce.IOC, 100L, 10L);
        pooled.submit(2L, SYMBOL, Side.BUY, TimeInForce.FOK, 100L, 10L);
        pooled.submitMarket(3L, SYMBOL, Side.BUY, TimeInForce.DAY, 10L);

        assertThat(pool.available()).isEqualTo(8);
        assertThat(pool.allocations()).isEqualTo(8L);
        assertThat(pooled.liveOrderCount()).isZero();
    }

    @Test
    void aSteadyStreamOfIocsAllocatesNothingAndLeavesNothingBehind() {
        OrderPool pool = new OrderPool(64);
        pool.preallocate(8);
        MatchingEngine pooled = new MatchingEngine(ExecutionSink.NO_OP, pool);
        OrderBook pooledBook = pooled.registerSymbol(SYMBOL);
        pooled.submit(1L, SYMBOL, Side.SELL, TimeInForce.DAY, 100L, 100_000L);

        for (long id = 2L; id < 10_000L; id++) {
            pooled.submit(id, SYMBOL, Side.BUY, TimeInForce.IOC, 100L, 5L);
        }

        assertThat(pooled.liveOrderCount()).isEqualTo(1);
        assertThat(pooledBook.quantityAt(Side.SELL, 100L)).isEqualTo(100_000L - 5L * 9_998L);
        assertThat(pool.allocations()).isEqualTo(8L);
    }
}

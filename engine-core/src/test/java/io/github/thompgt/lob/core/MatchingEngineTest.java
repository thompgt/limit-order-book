package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 semantics: crossing, price-time priority, partial fills, and the
 * execution reports that describe them.
 *
 * <p>Tests hold order <em>ids</em>, never {@link Order} references — a filled
 * order goes back to the pool and may be handed out again, so a retained
 * reference is a bug waiting to look like a passing test.
 */
class MatchingEngineTest {

    private static final int SYMBOL = 1;

    private RecordingSink sink;
    private MatchingEngine engine;
    private OrderBook book;
    private long nextOrderId;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        engine = new MatchingEngine(sink);
        book = engine.registerSymbol(SYMBOL);
        nextOrderId = 1L;
    }

    private SubmitResult buy(long price, long quantity) {
        return engine.submit(nextOrderId++, SYMBOL, Side.BUY, TimeInForce.DAY, price, quantity);
    }

    private SubmitResult sell(long price, long quantity) {
        return engine.submit(nextOrderId++, SYMBOL, Side.SELL, TimeInForce.DAY, price, quantity);
    }

    /** Submits with an explicit id, for the cases where the id matters. */
    private SubmitResult submit(long id, Side side, long price, long quantity) {
        return engine.submit(id, SYMBOL, side, TimeInForce.DAY, price, quantity);
    }

    private List<String> depth(Side side) {
        List<String> levels = new ArrayList<>();
        book.snapshot(side, 16, (price, qty, count) -> levels.add(price + "x" + qty));
        return levels;
    }

    // ---------------------------------------------------------------- resting

    @Test
    void anOrderThatCrossesNothingRestsInFull() {
        SubmitResult result = buy(100L, 50L);

        assertThat(result.status()).isEqualTo(OrderStatus.RESTING);
        assertThat(result.filledQuantity()).isZero();
        assertThat(result.restingQuantity()).isEqualTo(50L);
        assertThat(result.tradeCount()).isZero();
        assertThat(book.bestBid()).isEqualTo(100L);
        assertThat(sink.trades()).isEmpty();
    }

    @Test
    void aBidBelowTheBestAskDoesNotTrade() {
        sell(105L, 50L);
        SubmitResult result = buy(104L, 50L);

        assertThat(result.status()).isEqualTo(OrderStatus.RESTING);
        assertThat(sink.trades()).isEmpty();
        assertThat(book.spread()).isEqualTo(1L);
    }

    @Test
    void anOfferAboveTheBestBidDoesNotTrade() {
        buy(100L, 50L);
        SubmitResult result = sell(101L, 50L);

        assertThat(result.status()).isEqualTo(OrderStatus.RESTING);
        assertThat(sink.trades()).isEmpty();
        assertThat(book.spread()).isEqualTo(1L);
    }

    // --------------------------------------------------------------- crossing

    @Test
    void aBidThatMeetsTheAskTrades() {
        sell(100L, 50L);
        SubmitResult result = buy(100L, 50L);

        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(result.filledQuantity()).isEqualTo(50L);
        assertThat(result.restingQuantity()).isZero();
        assertThat(result.tradeCount()).isEqualTo(1);
        assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void anOfferThatMeetsTheBidTrades() {
        buy(100L, 50L);
        SubmitResult result = sell(100L, 50L);

        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(result.filledQuantity()).isEqualTo(50L);
        assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void aTradeExecutesAtTheRestingPriceNotTheAggressorsLimit() {
        sell(100L, 50L);
        buy(105L, 50L);

        // The buyer was willing to pay 105 and paid 100: the improvement goes
        // to the aggressor, because the resting order set the terms.
        assertThat(sink.tradePrices()).containsExactly(100L);
    }

    @Test
    void aSellerWhoUndercutsStillGetsTheRestingBidPrice() {
        buy(100L, 50L);
        sell(95L, 50L);

        assertThat(sink.tradePrices()).containsExactly(100L);
    }

    // -------------------------------------------------------- price priority

    @Test
    void anAggressiveBuyTakesTheCheapestOfferFirst() {
        long expensive = nextOrderId;
        sell(102L, 10L);
        long cheap = nextOrderId;
        sell(100L, 10L);

        buy(102L, 10L);

        assertThat(sink.hitOrderIds()).containsExactly(cheap);
        assertThat(book.bestAsk()).isEqualTo(102L);
        assertThat(engine.order(expensive)).isNotNull();
    }

    @Test
    void anAggressiveSellTakesTheHighestBidFirst() {
        long low = nextOrderId;
        buy(98L, 10L);
        long high = nextOrderId;
        buy(100L, 10L);

        sell(98L, 10L);

        assertThat(sink.hitOrderIds()).containsExactly(high);
        assertThat(book.bestBid()).isEqualTo(98L);
        assertThat(engine.order(low)).isNotNull();
    }

    @Test
    void aBuySweepsAskLevelsFromCheapestUpwards() {
        sell(100L, 10L);
        sell(101L, 10L);
        sell(102L, 10L);

        SubmitResult result = buy(102L, 30L);

        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(result.tradeCount()).isEqualTo(3);
        assertThat(sink.tradePrices()).containsExactly(100L, 101L, 102L);
        assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void aSellSweepsBidLevelsFromHighestDownwards() {
        buy(100L, 10L);
        buy(99L, 10L);
        buy(98L, 10L);

        SubmitResult result = sell(98L, 30L);

        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(sink.tradePrices()).containsExactly(100L, 99L, 98L);
        assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void aSweepStopsAtTheLimitPriceAndRestsTheRemainder() {
        sell(100L, 10L);
        sell(101L, 10L);
        sell(105L, 10L);

        SubmitResult result = buy(101L, 50L);

        assertThat(result.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(result.filledQuantity()).isEqualTo(20L);
        assertThat(result.restingQuantity()).isEqualTo(30L);
        assertThat(sink.tradePrices()).containsExactly(100L, 101L);
        // The 105 offer was never in reach, and the remainder is now the bid.
        assertThat(book.bestAsk()).isEqualTo(105L);
        assertThat(book.bestBid()).isEqualTo(101L);
        assertThat(book.quantityAt(Side.BUY, 101L)).isEqualTo(30L);
    }

    // --------------------------------------------------------- time priority

    @Test
    void withinALevelTheOldestOrderFillsFirst() {
        long first = nextOrderId;
        sell(100L, 10L);
        long second = nextOrderId;
        sell(100L, 10L);
        long third = nextOrderId;
        sell(100L, 10L);

        buy(100L, 30L);

        assertThat(sink.hitOrderIds()).containsExactly(first, second, third);
    }

    @Test
    void aLaterOrderAtTheSamePriceWaitsBehindAnEarlierOne() {
        long first = nextOrderId;
        sell(100L, 10L);
        long second = nextOrderId;
        sell(100L, 10L);

        buy(100L, 10L);

        assertThat(sink.hitOrderIds()).containsExactly(first);
        assertThat(engine.order(first)).isNull();
        assertThat(engine.order(second)).isNotNull();
        assertThat(book.quantityAt(Side.SELL, 100L)).isEqualTo(10L);
    }

    @Test
    void aBetterPriceBeatsAnEarlierArrival() {
        // Price outranks time: the later, cheaper offer trades first.
        long early = nextOrderId;
        sell(101L, 10L);
        long lateButCheaper = nextOrderId;
        sell(100L, 10L);

        buy(101L, 10L);

        assertThat(sink.hitOrderIds()).containsExactly(lateButCheaper);
        assertThat(engine.order(early)).isNotNull();
    }

    @Test
    void aPartiallyFilledRestingOrderKeepsItsPlaceAtTheFrontOfTheQueue() {
        long first = nextOrderId;
        sell(100L, 100L);
        long second = nextOrderId;
        sell(100L, 100L);

        buy(100L, 40L);
        sink.clear();
        buy(100L, 40L);

        // The first order was reduced, not re-queued: it is still ahead.
        assertThat(sink.hitOrderIds()).containsExactly(first);
        assertThat(engine.order(first).remainingQuantity()).isEqualTo(20L);
        assertThat(engine.order(second).remainingQuantity()).isEqualTo(100L);
    }

    // ---------------------------------------------------------- partial fills

    @Test
    void anAggressorLargerThanTheBookFillsWhatItCanAndRestsTheRest() {
        sell(100L, 30L);

        SubmitResult result = buy(100L, 50L);

        assertThat(result.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(result.filledQuantity()).isEqualTo(30L);
        assertThat(result.restingQuantity()).isEqualTo(20L);
        assertThat(book.bestBid()).isEqualTo(100L);
        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(20L);
        assertThat(book.levelCount(Side.SELL)).isZero();
    }

    @Test
    void anAggressorSmallerThanTheRestingOrderLeavesTheRemainderOnTheBook() {
        long resting = nextOrderId;
        sell(100L, 50L);

        SubmitResult result = buy(100L, 30L);

        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(engine.order(resting).remainingQuantity()).isEqualTo(20L);
        assertThat(book.quantityAt(Side.SELL, 100L)).isEqualTo(20L);
    }

    @Test
    void anExactFillLeavesNothingOnEitherSide() {
        sell(100L, 50L);

        buy(100L, 50L);

        assertThat(book.isEmpty()).isTrue();
        assertThat(engine.liveOrderCount()).isZero();
    }

    @Test
    void oneAggressorCanFillSeveralOrdersAtOnePrice() {
        sell(100L, 10L);
        sell(100L, 10L);
        sell(100L, 5L);

        SubmitResult result = buy(100L, 25L);

        assertThat(result.tradeCount()).isEqualTo(3);
        assertThat(result.filledQuantity()).isEqualTo(25L);
        assertThat(sink.trades()).extracting(RecordingSink.Trade::quantity)
                .containsExactly(10L, 10L, 5L);
    }

    @Test
    void aSweepAcrossLevelsSplitsTheLastLevelPartially() {
        sell(100L, 10L);
        long straddled = nextOrderId;
        sell(101L, 40L);

        SubmitResult result = buy(101L, 25L);

        assertThat(result.filledQuantity()).isEqualTo(25L);
        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(engine.order(straddled).remainingQuantity()).isEqualTo(25L);
        assertThat(book.quantityAt(Side.SELL, 101L)).isEqualTo(25L);
    }

    // ------------------------------------------------------ book consistency

    @Test
    void aBuyThroughTheAskLeavesNoCrossBecauseItTradesFirst() {
        sell(100L, 10L);
        sell(101L, 10L);

        // A bid at 105 is above both offers, so it cannot rest above them: it
        // consumes everything it crosses on the way, and only then rests.
        buy(105L, 15L);

        assertThat(book.isCrossed()).isFalse();
        assertThat(book.bestBid()).isEqualTo(OrderBook.NO_BID);
        assertThat(book.bestAsk()).isEqualTo(101L);
        assertThat(book.quantityAt(Side.SELL, 101L)).isEqualTo(5L);
    }

    @Test
    void anAggressorThatEatsTheWholeOppositeSideRestsWithoutCrossing() {
        sell(100L, 10L);
        sell(101L, 10L);

        SubmitResult result = buy(105L, 30L);

        assertThat(result.filledQuantity()).isEqualTo(20L);
        assertThat(result.restingQuantity()).isEqualTo(10L);
        assertThat(book.bestAsk()).isEqualTo(OrderBook.NO_ASK);
        assertThat(book.bestBid()).isEqualTo(105L);
        assertThat(book.isCrossed()).isFalse();
    }

    @Test
    void depthReflectsEveryFillImmediately() {
        sell(100L, 10L);
        sell(101L, 20L);
        sell(102L, 30L);

        buy(101L, 25L);

        assertThat(depth(Side.SELL)).containsExactly("101x5", "102x30");
        assertThat(depth(Side.BUY)).isEmpty();
    }

    @Test
    void emptiedLevelsDisappearFromTheLadder() {
        sell(100L, 10L);
        sell(101L, 10L);

        buy(101L, 20L);

        assertThat(book.levelCount(Side.SELL)).isZero();
        assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void filledOrdersLeaveTheIndexAndRestingOnesStay() {
        long taken = nextOrderId;
        sell(100L, 10L);
        long left = nextOrderId;
        sell(101L, 10L);

        buy(100L, 10L);

        assertThat(engine.order(taken)).isNull();
        assertThat(engine.order(left)).isNotNull();
        assertThat(engine.liveOrderCount()).isEqualTo(1);
    }

    // --------------------------------------------------------------- rejects

    @Test
    void aZeroQuantityOrderIsRejected() {
        SubmitResult result = buy(100L, 0L);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectReason()).isEqualTo(RejectReason.NON_POSITIVE_QUANTITY);
        assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void aNegativeQuantityOrderIsRejected() {
        SubmitResult result = buy(100L, -5L);

        assertThat(result.rejectReason()).isEqualTo(RejectReason.NON_POSITIVE_QUANTITY);
    }

    @Test
    void anOrderForAnUnregisteredSymbolIsRejected() {
        SubmitResult result =
                engine.submit(99L, 42, Side.BUY, TimeInForce.DAY, 100L, 10L);

        assertThat(result.rejectReason()).isEqualTo(RejectReason.UNKNOWN_SYMBOL);
        assertThat(engine.book(42)).isNull();
    }

    @Test
    void aDuplicateOrderIdIsRejectedWhileTheFirstIsStillLive() {
        submit(7L, Side.BUY, 100L, 10L);

        SubmitResult result = submit(7L, Side.BUY, 101L, 10L);

        assertThat(result.rejectReason()).isEqualTo(RejectReason.DUPLICATE_ORDER_ID);
        assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(10L);
    }

    @Test
    void anIdIsFreeAgainOnceTheOrderHasFilled() {
        // Ids identify live orders, not history. Once an order is gone the id
        // may be reused; the API layer is where a stricter policy belongs.
        submit(7L, Side.SELL, 100L, 10L);
        submit(8L, Side.BUY, 100L, 10L);

        SubmitResult result = submit(7L, Side.BUY, 99L, 10L);

        assertThat(result.isRejected()).isFalse();
        assertThat(result.status()).isEqualTo(OrderStatus.RESTING);
    }

    @Test
    void immediateOrCancelIsRejectedUntilPhaseThree() {
        SubmitResult result =
                engine.submit(1L, SYMBOL, Side.BUY, TimeInForce.IOC, 100L, 10L);

        assertThat(result.rejectReason()).isEqualTo(RejectReason.UNSUPPORTED_TIME_IN_FORCE);
    }

    @Test
    void fillOrKillIsRejectedUntilPhaseThree() {
        SubmitResult result =
                engine.submit(1L, SYMBOL, Side.BUY, TimeInForce.FOK, 100L, 10L);

        assertThat(result.rejectReason()).isEqualTo(RejectReason.UNSUPPORTED_TIME_IN_FORCE);
    }

    @Test
    void aRejectedOrderConsumesNoSequenceNumber() {
        buy(0L, -1L);
        SubmitResult accepted = buy(100L, 10L);

        assertThat(accepted.sequence()).isEqualTo(1L);
    }

    @Test
    void aRejectedOrderEmitsOnlyARejectReport() {
        buy(100L, 0L);

        assertThat(sink.rejects()).hasSize(1);
        assertThat(sink.accepted()).isEmpty();
        assertThat(sink.rested()).isEmpty();
        assertThat(sink.trades()).isEmpty();
    }

    // ----------------------------------------------------- execution reports

    @Test
    void anAcceptIsReportedBeforeTheFillsItCauses() {
        sell(100L, 10L);
        sink.clear();

        buy(100L, 10L);

        assertThat(sink.events()).containsExactly(
                "accepted:2", "trade:10@100", "filled:1", "filled:2");
    }

    @Test
    void aRestingOrderReportsAcceptThenRest() {
        buy(100L, 10L);

        assertThat(sink.events()).containsExactly("accepted:1", "rested:1:10@100");
    }

    @Test
    void aPartialFillReportsTheTradeThenTheRestingRemainder() {
        sell(100L, 10L);
        sink.clear();

        buy(100L, 25L);

        assertThat(sink.events()).containsExactly(
                "accepted:2", "trade:10@100", "filled:1", "rested:2:15@100");
    }

    @Test
    void bothSidesOfACompleteTradeAreReportedFilled() {
        long resting = nextOrderId;
        sell(100L, 10L);
        long aggressor = nextOrderId;
        buy(100L, 10L);

        assertThat(sink.filled()).containsExactly(resting, aggressor);
    }

    @Test
    void aRestingOrderThatIsOnlyPartlyHitIsNotReportedFilled() {
        sell(100L, 50L);
        buy(100L, 10L);

        assertThat(sink.filled()).containsExactly(2L);
    }

    @Test
    void tradeIdsAreMonotonicAcrossOrdersAndSymbols() {
        engine.registerSymbol(2);
        sell(100L, 10L);
        buy(100L, 10L);
        engine.submit(50L, 2, Side.SELL, TimeInForce.DAY, 200L, 10L);
        engine.submit(51L, 2, Side.BUY, TimeInForce.DAY, 200L, 10L);

        assertThat(sink.trades()).extracting(RecordingSink.Trade::tradeId)
                .containsExactly(1L, 2L);
        assertThat(engine.tradeCount()).isEqualTo(2L);
    }

    @Test
    void aTradeReportCarriesBothSidesOfTheMatch() {
        long resting = nextOrderId;
        sell(100L, 10L);
        long aggressor = nextOrderId;
        buy(105L, 10L);

        RecordingSink.Trade trade = sink.trades().get(0);
        assertThat(trade.aggressorId()).isEqualTo(aggressor);
        assertThat(trade.aggressorSide()).isEqualTo(Side.BUY);
        assertThat(trade.restingId()).isEqualTo(resting);
        assertThat(trade.restingSide()).isEqualTo(Side.SELL);
        assertThat(trade.price()).isEqualTo(100L);
        assertThat(trade.quantity()).isEqualTo(10L);
        assertThat(trade.symbolId()).isEqualTo(SYMBOL);
    }

    @Test
    void sequenceNumbersFollowArrivalOrder() {
        assertThat(buy(100L, 10L).sequence()).isEqualTo(1L);
        assertThat(buy(101L, 10L).sequence()).isEqualTo(2L);
        assertThat(sell(105L, 10L).sequence()).isEqualTo(3L);
    }

    // ---------------------------------------------------------- multi-symbol

    @Test
    void ordersOnDifferentSymbolsDoNotMatchEachOther() {
        OrderBook other = engine.registerSymbol(2);
        engine.submit(1L, SYMBOL, Side.SELL, TimeInForce.DAY, 100L, 10L);

        SubmitResult result = engine.submit(2L, 2, Side.BUY, TimeInForce.DAY, 100L, 10L);

        assertThat(result.status()).isEqualTo(OrderStatus.RESTING);
        assertThat(sink.trades()).isEmpty();
        assertThat(book.quantityAt(Side.SELL, 100L)).isEqualTo(10L);
        assertThat(other.quantityAt(Side.BUY, 100L)).isEqualTo(10L);
    }

    @Test
    void registeringASymbolTwiceKeepsTheExistingBook() {
        engine.submit(1L, SYMBOL, Side.BUY, TimeInForce.DAY, 100L, 10L);

        assertThat(engine.registerSymbol(SYMBOL)).isSameAs(book);
        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(10L);
    }

    @Test
    void anUnregisteredSymbolHasNoBook() {
        assertThat(engine.hasSymbol(7)).isFalse();
        assertThat(engine.book(7)).isNull();
    }

    // ---------------------------------------------------------------- pooling

    @Nested
    class Pooling {

        @Test
        void bothSidesOfACompletedTradeGoBackToThePool() {
            OrderPool pool = engine.pool();
            sell(100L, 10L);
            int before = pool.available();

            buy(100L, 10L);

            // The aggressor filled and the resting order emptied, so neither is
            // referenced any more and both are recyclable.
            assertThat(pool.available()).isEqualTo(before + 2);
        }

        @Test
        void aRestingOrderIsHeldOutOfThePoolWhileItIsOnTheBook() {
            OrderPool pool = new OrderPool(64);
            pool.preallocate(8);
            MatchingEngine pooled = new MatchingEngine(sink, pool);
            pooled.registerSymbol(SYMBOL);

            pooled.submit(1L, SYMBOL, Side.BUY, TimeInForce.DAY, 100L, 10L);

            assertThat(pool.available()).isEqualTo(7);
            assertThat(pool.allocations()).isEqualTo(8L);
        }

        @Test
        void recycledOrdersDoNotLeakStateIntoLaterOnes() {
            // Fill an order, then submit a new one that must reuse its object.
            sell(100L, 10L);
            buy(100L, 10L);

            SubmitResult result = submit(500L, Side.BUY, 99L, 7L);

            Order reused = engine.order(500L);
            assertThat(reused.orderId()).isEqualTo(500L);
            assertThat(reused.price()).isEqualTo(99L);
            assertThat(reused.quantity()).isEqualTo(7L);
            assertThat(reused.remainingQuantity()).isEqualTo(7L);
            assertThat(reused.side()).isEqualTo(Side.BUY);
            assertThat(reused.filledQuantity()).isZero();
            assertThat(result.filledQuantity()).isZero();
        }

        @Test
        void heavyRecyclingLeavesTheBookConsistent() {
            // Every pair fully trades, so the pool churns hard. If a recycled
            // order were handed out while still linked, the ladder would drift.
            for (long i = 0; i < 5_000; i++) {
                sell(100L, 10L);
                buy(100L, 10L);
            }

            assertThat(book.isEmpty()).isTrue();
            assertThat(engine.liveOrderCount()).isZero();
            assertThat(engine.tradeCount()).isEqualTo(5_000L);
            assertThat(engine.pool().allocations()).isLessThan(100L);
        }
    }

    // ------------------------------------------------------------- scenarios

    @Test
    void aSessionOfInterleavedOrdersConservesQuantity() {
        sell(102L, 40L);
        sell(101L, 30L);
        sell(100L, 20L);
        buy(98L, 25L);
        buy(99L, 35L);

        buy(101L, 45L);   // takes 20@100, then 25 of the 30 at 101
        sell(99L, 50L);   // takes the whole 35 at 99; 98 is below its limit

        assertThat(sink.totalFilledOn(Side.BUY)).isEqualTo(sink.totalFilledOn(Side.SELL));
        assertThat(book.isCrossed()).isFalse();
        // The sell's own limit stopped it: 15 unfilled units rest at 99, and
        // the 98 bid survives because no seller was willing to go that low.
        assertThat(depth(Side.SELL)).containsExactly("99x15", "101x5", "102x40");
        assertThat(depth(Side.BUY)).containsExactly("98x25");
        assertThat(book.spread()).isEqualTo(1L);
    }

    @Test
    void aMarketableOrderNeverRestsInsideTheSpread() {
        sell(100L, 10L);
        sell(100L, 10L);
        sell(100L, 10L);

        buy(100L, 100L);

        // Everything at or better than the limit was taken before resting.
        assertThat(book.bestAsk()).isEqualTo(OrderBook.NO_ASK);
        assertThat(book.bestBid()).isEqualTo(100L);
        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(70L);
    }
}

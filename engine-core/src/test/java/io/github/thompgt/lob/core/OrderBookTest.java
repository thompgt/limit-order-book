package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private OrderBook book;
    private long nextOrderId;
    private long nextSequence;

    @BeforeEach
    void setUp() {
        book = new OrderBook(1);
        nextOrderId = 1L;
        nextSequence = 1L;
    }

    private Order rest(Side side, long price, long quantity) {
        Order order = new Order()
                .reset(nextOrderId++, 1, side, TimeInForce.DAY, price, quantity, nextSequence++);
        book.add(order);
        return order;
    }

    /** Collects a depth snapshot as "price x quantity" strings, best first. */
    private List<String> depth(Side side, int maxLevels) {
        List<String> levels = new ArrayList<>();
        book.snapshot(side, maxLevels, (price, qty, count) -> levels.add(price + "x" + qty));
        return levels;
    }

    @Test
    void anEmptyBookHasNoBidAndNoAsk() {
        assertThat(book.isEmpty()).isTrue();
        assertThat(book.bestBid()).isEqualTo(OrderBook.NO_BID);
        assertThat(book.bestAsk()).isEqualTo(OrderBook.NO_ASK);
        assertThat(book.bestLevel(Side.BUY)).isNull();
        assertThat(book.spread()).isEqualTo(-1L);
        assertThat(book.isCrossed()).isFalse();
    }

    @Test
    void theBestBidIsTheHighestBidPrice() {
        rest(Side.BUY, 100L, 10L);
        rest(Side.BUY, 102L, 10L);
        rest(Side.BUY, 101L, 10L);

        assertThat(book.bestBid()).isEqualTo(102L);
    }

    @Test
    void theBestAskIsTheLowestAskPrice() {
        rest(Side.SELL, 110L, 10L);
        rest(Side.SELL, 108L, 10L);
        rest(Side.SELL, 109L, 10L);

        assertThat(book.bestAsk()).isEqualTo(108L);
    }

    @Test
    void ordersAtOnePriceShareALevelInArrivalOrder() {
        Order first = rest(Side.BUY, 100L, 10L);
        Order second = rest(Side.BUY, 100L, 25L);

        assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(35L);
        assertThat(book.bestLevel(Side.BUY).head()).isSameAs(first);
        assertThat(book.bestLevel(Side.BUY).tail()).isSameAs(second);
    }

    @Test
    void spreadIsTheGapBetweenBestBidAndBestAsk() {
        rest(Side.BUY, 100L, 10L);
        rest(Side.SELL, 103L, 10L);

        assertThat(book.spread()).isEqualTo(3L);
        assertThat(book.isCrossed()).isFalse();
    }

    @Test
    void spreadIsUndefinedWhileEitherSideIsEmpty() {
        rest(Side.BUY, 100L, 10L);

        assertThat(book.spread()).isEqualTo(-1L);
    }

    @Test
    void removingAnOrderSubtractsItsQuantityFromTheLevel() {
        rest(Side.BUY, 100L, 10L);
        Order second = rest(Side.BUY, 100L, 25L);

        assertThat(book.remove(second)).isTrue();

        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(10L);
        assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
    }

    @Test
    void anEmptiedLevelLeavesTheLadderSoTheNextPriceBecomesBest() {
        Order top = rest(Side.BUY, 102L, 10L);
        rest(Side.BUY, 101L, 10L);

        book.remove(top);

        assertThat(book.bestBid()).isEqualTo(101L);
        assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
        assertThat(book.quantityAt(Side.BUY, 102L)).isZero();
    }

    @Test
    void removingTheLastOrderEmptiesTheBook() {
        Order only = rest(Side.SELL, 100L, 10L);

        book.remove(only);

        assertThat(book.isEmpty()).isTrue();
        assertThat(book.bestAsk()).isEqualTo(OrderBook.NO_ASK);
    }

    @Test
    void removingAnOrderThatIsNotRestingIsANoOp() {
        Order loose = new Order().reset(99L, 1, Side.BUY, TimeInForce.DAY, 100L, 10L, 99L);

        assertThat(book.remove(loose)).isFalse();
        assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void aPartialFillLeavesTheOrderInPlaceWithLessQuantity() {
        Order first = rest(Side.SELL, 100L, 100L);
        rest(Side.SELL, 100L, 50L);

        book.reduce(first, 40L);

        assertThat(first.remainingQuantity()).isEqualTo(60L);
        assertThat(first.isResting()).isTrue();
        assertThat(book.bestLevel(Side.SELL).head()).isSameAs(first);
        assertThat(book.quantityAt(Side.SELL, 100L)).isEqualTo(110L);
    }

    @Test
    void aFullFillTakesTheOrderOffTheBook() {
        Order first = rest(Side.SELL, 100L, 100L);
        Order second = rest(Side.SELL, 100L, 50L);

        book.reduce(first, 100L);

        assertThat(first.isFilled()).isTrue();
        assertThat(first.isResting()).isFalse();
        assertThat(book.bestLevel(Side.SELL).head()).isSameAs(second);
        assertThat(book.quantityAt(Side.SELL, 100L)).isEqualTo(50L);
    }

    @Test
    void fullyFillingTheOnlyOrderAtAPriceRemovesTheLevel() {
        Order only = rest(Side.SELL, 100L, 100L);
        rest(Side.SELL, 101L, 10L);

        book.reduce(only, 100L);

        assertThat(book.bestAsk()).isEqualTo(101L);
        assertThat(book.levelCount(Side.SELL)).isEqualTo(1);
    }

    @Test
    void bidDepthIsReportedHighestPriceFirst() {
        rest(Side.BUY, 100L, 10L);
        rest(Side.BUY, 102L, 20L);
        rest(Side.BUY, 101L, 30L);

        assertThat(depth(Side.BUY, 10)).containsExactly("102x20", "101x30", "100x10");
    }

    @Test
    void askDepthIsReportedLowestPriceFirst() {
        rest(Side.SELL, 110L, 10L);
        rest(Side.SELL, 108L, 20L);
        rest(Side.SELL, 109L, 30L);

        assertThat(depth(Side.SELL, 10)).containsExactly("108x20", "109x30", "110x10");
    }

    @Test
    void depthAggregatesEveryOrderAtAPriceIntoOneLevel() {
        rest(Side.BUY, 100L, 10L);
        rest(Side.BUY, 100L, 15L);
        rest(Side.BUY, 100L, 5L);

        List<String> levels = new ArrayList<>();
        book.snapshot(Side.BUY, 10, (price, qty, count) -> levels.add(price + "x" + qty + "/" + count));

        assertThat(levels).containsExactly("100x30/3");
    }

    @Test
    void depthStopsAtTheRequestedNumberOfLevels() {
        rest(Side.BUY, 100L, 10L);
        rest(Side.BUY, 101L, 10L);
        rest(Side.BUY, 102L, 10L);

        assertThat(depth(Side.BUY, 2)).containsExactly("102x10", "101x10");
    }

    @Test
    void depthOfAnEmptySideEmitsNothing() {
        assertThat(depth(Side.SELL, 10)).isEmpty();
    }

    @Test
    void theTwoSidesAreIndependentLadders() {
        rest(Side.BUY, 100L, 10L);
        rest(Side.SELL, 100L, 20L);

        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(10L);
        assertThat(book.quantityAt(Side.SELL, 100L)).isEqualTo(20L);
        assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
        assertThat(book.levelCount(Side.SELL)).isEqualTo(1);
    }

    @Test
    void aBookIsCrossedWhenTheBestBidReachesTheBestAsk() {
        // Only reachable by resting orders directly, bypassing matching — this
        // pins what isCrossed() reports so the phase 2 property test can rely
        // on the engine never producing this state.
        rest(Side.BUY, 105L, 10L);
        rest(Side.SELL, 105L, 10L);

        assertThat(book.isCrossed()).isTrue();
    }

    @Test
    void aLevelIsReusedAfterItEmptiesAndThePriceTradesAgain() {
        Order first = rest(Side.BUY, 100L, 10L);
        book.remove(first);

        rest(Side.BUY, 100L, 42L);

        assertThat(book.quantityAt(Side.BUY, 100L)).isEqualTo(42L);
        assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
        assertThat(book.bestLevel(Side.BUY).orderCount()).isEqualTo(1);
    }

    // The best level of each side is a maintained field rather than a tree
    // lookup, so these pin the cases where maintaining it could go wrong.

    @Test
    void aBetterPriceArrivingBecomesTheNewBestOnBothSides() {
        rest(Side.BUY, 100L, 10L);
        rest(Side.SELL, 110L, 10L);

        rest(Side.BUY, 101L, 5L);
        rest(Side.SELL, 109L, 5L);

        assertThat(book.bestBid()).isEqualTo(101L);
        assertThat(book.bestAsk()).isEqualTo(109L);
    }

    @Test
    void aWorsePriceArrivingLeavesTheBestAlone() {
        rest(Side.BUY, 100L, 10L);
        rest(Side.SELL, 110L, 10L);

        rest(Side.BUY, 98L, 5L);
        rest(Side.SELL, 112L, 5L);

        assertThat(book.bestBid()).isEqualTo(100L);
        assertThat(book.bestAsk()).isEqualTo(110L);
    }

    @Test
    void emptyingALevelBehindTheBestDoesNotChangeTheBest() {
        rest(Side.BUY, 100L, 10L);
        Order behind = rest(Side.BUY, 99L, 10L);

        book.remove(behind);

        assertThat(book.bestBid()).isEqualTo(100L);
        assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
    }

    @Test
    void emptyingTheBookOneLevelAtATimeWalksTheBestBackDown() {
        Order at102 = rest(Side.BUY, 102L, 10L);
        Order at101 = rest(Side.BUY, 101L, 10L);
        Order at100 = rest(Side.BUY, 100L, 10L);

        assertThat(book.bestBid()).isEqualTo(102L);
        book.remove(at102);
        assertThat(book.bestBid()).isEqualTo(101L);
        book.remove(at101);
        assertThat(book.bestBid()).isEqualTo(100L);
        book.remove(at100);
        assertThat(book.bestBid()).isEqualTo(OrderBook.NO_BID);
        assertThat(book.bestLevel(Side.BUY)).isNull();
    }

    @Test
    void aFullFillOfTheBestLevelPromotesTheNextPrice() {
        Order best = rest(Side.SELL, 110L, 10L);
        rest(Side.SELL, 111L, 10L);

        book.reduce(best, 10L);

        assertThat(book.bestAsk()).isEqualTo(111L);
        assertThat(book.bestLevel(Side.SELL).totalQuantity()).isEqualTo(10L);
    }

    @Test
    void aPriceThatEmptiesAndComesBackIsTheBestAgain() {
        Order only = rest(Side.SELL, 110L, 10L);
        rest(Side.SELL, 111L, 10L);
        book.remove(only);
        assertThat(book.bestAsk()).isEqualTo(111L);

        rest(Side.SELL, 110L, 7L);

        assertThat(book.bestAsk()).isEqualTo(110L);
        assertThat(book.bestLevel(Side.SELL).totalQuantity()).isEqualTo(7L);
    }
}

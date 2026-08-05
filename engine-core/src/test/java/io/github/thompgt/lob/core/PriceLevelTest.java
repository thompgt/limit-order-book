package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PriceLevelTest {

    private static final long PRICE = 10_000L;

    private PriceLevel level;
    private long nextSequence;

    @BeforeEach
    void setUp() {
        level = new PriceLevel().reset(PRICE);
        nextSequence = 0L;
    }

    private Order add(long orderId, long quantity) {
        Order order = new Order()
                .reset(orderId, 1, Side.BUY, TimeInForce.DAY, PRICE, quantity, nextSequence++);
        level.add(order);
        return order;
    }

    private List<Long> queuedOrderIds() {
        List<Long> ids = new ArrayList<>();
        for (Order o = level.head(); o != null; o = o.next) {
            ids.add(o.orderId());
        }
        return ids;
    }

    @Test
    void aFreshLevelIsEmpty() {
        assertThat(level.isEmpty()).isTrue();
        assertThat(level.head()).isNull();
        assertThat(level.tail()).isNull();
        assertThat(level.totalQuantity()).isZero();
        assertThat(level.orderCount()).isZero();
        assertThat(level.price()).isEqualTo(PRICE);
    }

    @Test
    void ordersAreServedInArrivalOrder() {
        add(1L, 10L);
        add(2L, 20L);
        add(3L, 30L);

        assertThat(queuedOrderIds()).containsExactly(1L, 2L, 3L);
        assertThat(level.head().orderId()).isEqualTo(1L);
        assertThat(level.tail().orderId()).isEqualTo(3L);
    }

    @Test
    void addingKeepsTheCachedTotalInStep() {
        add(1L, 10L);
        add(2L, 20L);

        assertThat(level.totalQuantity()).isEqualTo(30L);
        assertThat(level.orderCount()).isEqualTo(2);
    }

    @Test
    void removingFromTheMiddleRelinksTheNeighbours() {
        add(1L, 10L);
        Order middle = add(2L, 20L);
        add(3L, 30L);

        level.remove(middle);

        assertThat(queuedOrderIds()).containsExactly(1L, 3L);
        assertThat(level.totalQuantity()).isEqualTo(40L);
        assertThat(level.orderCount()).isEqualTo(2);
        assertThat(middle.isResting()).isFalse();
    }

    @Test
    void removingTheHeadPromotesTheNextOrder() {
        Order first = add(1L, 10L);
        add(2L, 20L);

        level.remove(first);

        assertThat(level.head().orderId()).isEqualTo(2L);
        assertThat(level.head().prev).isNull();
        assertThat(level.totalQuantity()).isEqualTo(20L);
    }

    @Test
    void removingTheTailMovesTheTailBack() {
        add(1L, 10L);
        Order last = add(2L, 20L);

        level.remove(last);

        assertThat(level.tail().orderId()).isEqualTo(1L);
        assertThat(level.tail().next).isNull();
        assertThat(level.totalQuantity()).isEqualTo(10L);
    }

    @Test
    void removingTheOnlyOrderEmptiesTheLevel() {
        Order only = add(1L, 10L);

        level.remove(only);

        assertThat(level.isEmpty()).isTrue();
        assertThat(level.head()).isNull();
        assertThat(level.tail()).isNull();
        assertThat(level.totalQuantity()).isZero();
        assertThat(level.orderCount()).isZero();
    }

    @Test
    void aPartialFillKeepsTimePriority() {
        Order first = add(1L, 100L);
        add(2L, 50L);

        level.reduce(first, 40L);

        assertThat(first.remainingQuantity()).isEqualTo(60L);
        assertThat(level.head().orderId()).isEqualTo(1L);
        assertThat(level.totalQuantity()).isEqualTo(110L);
    }

    @Test
    void removingAPartiallyFilledOrderSubtractsOnlyWhatIsStillOpen() {
        Order order = add(1L, 100L);
        level.reduce(order, 70L);

        level.remove(order);

        assertThat(level.totalQuantity()).isZero();
        assertThat(level.isEmpty()).isTrue();
    }

    @Test
    void aLevelCanBeRefilledAfterEmptying() {
        Order only = add(1L, 10L);
        level.remove(only);

        add(2L, 25L);

        assertThat(queuedOrderIds()).containsExactly(2L);
        assertThat(level.head()).isSameAs(level.tail());
        assertThat(level.totalQuantity()).isEqualTo(25L);
    }

    @Test
    void resetClearsTheQueueForReuse() {
        add(1L, 10L);
        add(2L, 20L);

        level.reset(20_000L);

        assertThat(level.price()).isEqualTo(20_000L);
        assertThat(level.isEmpty()).isTrue();
        assertThat(level.totalQuantity()).isZero();
        assertThat(level.orderCount()).isZero();
    }
}

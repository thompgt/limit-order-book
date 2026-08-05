package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrderPoolTest {

    @Test
    void anEmptyPoolStillHandsOutOrders() {
        OrderPool pool = new OrderPool(4);

        assertThat(pool.available()).isZero();
        assertThat(pool.acquire()).isNotNull();
        assertThat(pool.allocations()).isEqualTo(1L);
    }

    @Test
    void aReleasedOrderIsHandedOutAgainRatherThanAllocated() {
        OrderPool pool = new OrderPool(4);
        Order first = pool.acquire();

        pool.release(first);

        assertThat(pool.acquire()).isSameAs(first);
        assertThat(pool.allocations()).isEqualTo(1L);
    }

    @Test
    void preallocationMeansTheFirstOrdersCostNothing() {
        OrderPool pool = new OrderPool(64);

        pool.preallocate(16);

        assertThat(pool.available()).isEqualTo(16);
        assertThat(pool.allocations()).isEqualTo(16L);

        for (int i = 0; i < 16; i++) {
            pool.acquire();
        }
        assertThat(pool.allocations()).isEqualTo(16L);
    }

    @Test
    void preallocationNeverOverfillsThePool() {
        OrderPool pool = new OrderPool(8);

        pool.preallocate(1_000);

        assertThat(pool.available()).isEqualTo(8);
        assertThat(pool.allocations()).isEqualTo(8L);
    }

    @Test
    void releasingScrubsReferencesSoNothingIsHeldAlive() {
        OrderPool pool = new OrderPool(4);
        PriceLevel level = new PriceLevel().reset(100L);
        Order order = pool.acquire().reset(1L, 1, Side.BUY, TimeInForce.DAY, 100L, 10L, 1L);
        level.add(order);
        level.remove(order);
        order.next = new Order();

        pool.release(order);

        assertThat(order.next).isNull();
        assertThat(order.prev).isNull();
        assertThat(order.level).isNull();
        assertThat(order.side()).isNull();
        assertThat(order.timeInForce()).isNull();
    }

    @Test
    void aFullPoolDropsFurtherReleasesInsteadOfGrowing() {
        OrderPool pool = new OrderPool(2);

        pool.release(new Order());
        pool.release(new Order());
        pool.release(new Order());

        assertThat(pool.available()).isEqualTo(2);
        assertThat(pool.capacity()).isEqualTo(2);
    }

    @Test
    void aRecycledOrderCarriesNoQuantityFromItsPreviousLife() {
        OrderPool pool = new OrderPool(4);
        Order first = pool.acquire().reset(1L, 1, Side.BUY, TimeInForce.DAY, 100L, 50L, 1L);
        first.remainingQuantity = 20L;
        pool.release(first);

        Order reused = pool.acquire().reset(2L, 1, Side.SELL, TimeInForce.DAY, 99L, 5L, 2L);

        assertThat(reused).isSameAs(first);
        assertThat(reused.orderId()).isEqualTo(2L);
        assertThat(reused.quantity()).isEqualTo(5L);
        assertThat(reused.remainingQuantity()).isEqualTo(5L);
        assertThat(reused.filledQuantity()).isZero();
        assertThat(reused.side()).isEqualTo(Side.SELL);
    }

    @Test
    void steadyStateChurnStopsAllocatingOnceThePoolIsWarm() {
        OrderPool pool = new OrderPool(16);
        pool.preallocate(4);
        long warm = pool.allocations();

        for (int i = 0; i < 10_000; i++) {
            Order a = pool.acquire();
            Order b = pool.acquire();
            pool.release(a);
            pool.release(b);
        }

        assertThat(pool.allocations()).isEqualTo(warm);
    }

    @Test
    void aNonPositiveCapacityIsRefused() {
        assertThatThrownBy(() -> new OrderPool(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPool(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

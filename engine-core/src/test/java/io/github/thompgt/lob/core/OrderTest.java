package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderTest {

    private static Order order(long id, Side side, long price, long qty, long seq) {
        return new Order().reset(id, 1, side, TimeInForce.DAY, price, qty, seq);
    }

    @Test
    void resetLeavesTheOrderFullyOpen() {
        Order order = order(7L, Side.BUY, 10_050L, 500L, 42L);

        assertThat(order.orderId()).isEqualTo(7L);
        assertThat(order.symbolId()).isEqualTo(1);
        assertThat(order.side()).isEqualTo(Side.BUY);
        assertThat(order.timeInForce()).isEqualTo(TimeInForce.DAY);
        assertThat(order.price()).isEqualTo(10_050L);
        assertThat(order.quantity()).isEqualTo(500L);
        assertThat(order.remainingQuantity()).isEqualTo(500L);
        assertThat(order.filledQuantity()).isZero();
        assertThat(order.sequence()).isEqualTo(42L);
        assertThat(order.isFilled()).isFalse();
    }

    @Test
    void resetOnAPooledOrderLeaksNoStateFromThePreviousOccupant() {
        Order order = order(1L, Side.BUY, 100L, 10L, 1L);
        order.remainingQuantity = 3L;
        order.prev = new Order();
        order.next = new Order();
        order.level = new PriceLevel().reset(100L);

        order.reset(2L, 9, Side.SELL, TimeInForce.IOC, 200L, 50L, 2L);

        assertThat(order.remainingQuantity()).isEqualTo(50L);
        assertThat(order.prev).isNull();
        assertThat(order.next).isNull();
        assertThat(order.level).isNull();
        assertThat(order.isResting()).isFalse();
    }

    @Test
    void filledQuantityIsTheDifferenceBetweenOriginalAndRemaining() {
        Order order = order(1L, Side.SELL, 100L, 900L, 1L);
        order.remainingQuantity = 250L;

        assertThat(order.filledQuantity()).isEqualTo(650L);
        assertThat(order.isFilled()).isFalse();

        order.remainingQuantity = 0L;
        assertThat(order.filledQuantity()).isEqualTo(900L);
        assertThat(order.isFilled()).isTrue();
    }

    @Test
    void clearDropsReferencesSoAPooledOrderHoldsNothingAlive() {
        Order order = order(1L, Side.BUY, 100L, 10L, 1L);
        order.next = new Order();
        order.level = new PriceLevel().reset(100L);

        order.clear();

        assertThat(order.prev).isNull();
        assertThat(order.next).isNull();
        assertThat(order.level).isNull();
        assertThat(order.side).isNull();
        assertThat(order.timeInForce).isNull();
    }
}

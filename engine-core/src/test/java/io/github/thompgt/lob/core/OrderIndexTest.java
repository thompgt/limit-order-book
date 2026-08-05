package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderIndexTest {

    private OrderIndex index;

    @BeforeEach
    void setUp() {
        index = new OrderIndex(64);
    }

    private static Order order(long orderId) {
        return new Order().reset(orderId, 1, Side.BUY, TimeInForce.DAY, 100L, 10L, orderId);
    }

    @Test
    void aFreshIndexIsEmpty() {
        assertThat(index.isEmpty()).isTrue();
        assertThat(index.size()).isZero();
    }

    @Test
    void anOrderIsFoundByItsId() {
        Order order = order(7L);
        index.put(order);

        assertThat(index.get(7L)).isSameAs(order);
        assertThat(index.contains(7L)).isTrue();
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void anUnknownIdReturnsNullRatherThanThrowing() {
        assertThat(index.get(999L)).isNull();
        assertThat(index.contains(999L)).isFalse();
        assertThat(index.remove(999L)).isNull();
    }

    @Test
    void removingDeregistersTheOrder() {
        Order order = order(7L);
        index.put(order);

        assertThat(index.remove(7L)).isSameAs(order);
        assertThat(index.get(7L)).isNull();
        assertThat(index.isEmpty()).isTrue();
    }

    @Test
    void puttingADuplicateIdReturnsTheDisplacedOrder() {
        Order first = order(7L);
        Order second = order(7L);
        index.put(first);

        assertThat(index.put(second)).isSameAs(first);
        assertThat(index.get(7L)).isSameAs(second);
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void holdsManyOrdersAcrossAResizeWithoutLosingAny() {
        int count = 10_000;
        for (long id = 0; id < count; id++) {
            index.put(order(id));
        }

        assertThat(index.size()).isEqualTo(count);
        for (long id = 0; id < count; id++) {
            assertThat(index.get(id)).isNotNull();
            assertThat(index.get(id).orderId()).isEqualTo(id);
        }
    }

    @Test
    void negativeAndExtremeIdsAreHandledLikeAnyOtherKey() {
        index.put(order(Long.MIN_VALUE));
        index.put(order(Long.MAX_VALUE));
        index.put(order(0L));

        assertThat(index.get(Long.MIN_VALUE)).isNotNull();
        assertThat(index.get(Long.MAX_VALUE)).isNotNull();
        assertThat(index.get(0L)).isNotNull();
        assertThat(index.size()).isEqualTo(3);
    }

    @Test
    void clearDropsEveryEntry() {
        index.put(order(1L));
        index.put(order(2L));

        index.clear();

        assertThat(index.isEmpty()).isTrue();
        assertThat(index.get(1L)).isNull();
    }
}

package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SideTest {

    @Test
    void oppositeFlipsTheSide() {
        assertThat(Side.BUY.opposite()).isEqualTo(Side.SELL);
        assertThat(Side.SELL.opposite()).isEqualTo(Side.BUY);
    }

    @Test
    void buyCrossesWhenItsPriceIsAtOrAboveTheAsk() {
        assertThat(Side.BUY.crosses(100, 99)).isTrue();
        assertThat(Side.BUY.crosses(100, 100)).isTrue();
        assertThat(Side.BUY.crosses(100, 101)).isFalse();
    }

    @Test
    void sellCrossesWhenItsPriceIsAtOrBelowTheBid() {
        assertThat(Side.SELL.crosses(100, 101)).isTrue();
        assertThat(Side.SELL.crosses(100, 100)).isTrue();
        assertThat(Side.SELL.crosses(100, 99)).isFalse();
    }

    @Test
    void onlyDayOrdersRestOnTheBook() {
        assertThat(TimeInForce.DAY.restsOnBook()).isTrue();
        assertThat(TimeInForce.IOC.restsOnBook()).isFalse();
        assertThat(TimeInForce.FOK.restsOnBook()).isFalse();
    }
}

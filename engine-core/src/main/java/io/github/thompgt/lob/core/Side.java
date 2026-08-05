package io.github.thompgt.lob.core;

/**
 * Which side of the book an order rests on.
 *
 * <p>{@link #BUY} orders sort by descending price (highest bid first);
 * {@link #SELL} orders sort by ascending price (lowest ask first).
 */
public enum Side {
    BUY,
    SELL;

    /** The side that an aggressive order of this side matches against. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    /**
     * Whether an order on this side at {@code orderPrice} can trade against a
     * resting order at {@code restingPrice}.
     */
    public boolean crosses(long orderPrice, long restingPrice) {
        return this == BUY ? orderPrice >= restingPrice : orderPrice <= restingPrice;
    }
}

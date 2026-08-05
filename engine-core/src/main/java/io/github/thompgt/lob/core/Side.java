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

    /**
     * The price a market order on this side carries: the most aggressive value
     * there is, so {@link #crosses} says yes to every resting order.
     *
     * <p>Modelling a market order as a limit at the extreme price is what keeps
     * the matching loop free of a market special case — it sweeps until the book
     * runs out, which is exactly the definition. The two sentinels are reserved:
     * a <em>limit</em> order priced at its own side's sentinel is rejected with
     * {@link RejectReason#RESERVED_PRICE}, so the encoding stays unambiguous.
     */
    public long marketPrice() {
        return this == BUY ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
}

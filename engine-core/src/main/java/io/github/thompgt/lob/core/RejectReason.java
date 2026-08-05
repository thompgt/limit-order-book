package io.github.thompgt.lob.core;

/**
 * Why the engine refused an order outright, without touching the book.
 *
 * <p>A reject is not a cancel: a rejected order never existed as far as the
 * book is concerned, so it emits no accept and consumes no sequence number.
 */
public enum RejectReason {

    /** No book has been registered for the order's symbol. */
    UNKNOWN_SYMBOL,

    /** Quantity was zero or negative. */
    NON_POSITIVE_QUANTITY,

    /**
     * An order with this id is already live on the book. Ids are the client's
     * handle for cancel and modify, so two live orders may not share one.
     */
    DUPLICATE_ORDER_ID,

    /**
     * A cancel or modify named an order the engine does not have. Either the id
     * was never used, or the order has already left the book — filled, or
     * cancelled by an earlier message.
     */
    UNKNOWN_ORDER_ID,

    /**
     * A limit order was priced at one of the {@link Side#marketPrice()}
     * sentinels. Those two values encode "market", so a limit may not use them.
     */
    RESERVED_PRICE,

    /**
     * A modify asked for a total quantity at or below what the order has
     * already traded. There is nothing left to leave open, and silently turning
     * it into a cancel would be a different instruction than the one sent.
     */
    QUANTITY_BELOW_FILLED
}

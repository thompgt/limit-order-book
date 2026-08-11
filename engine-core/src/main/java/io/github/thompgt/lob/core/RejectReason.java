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
     *
     * <p>Note the word <em>live</em>: an id becomes free again the moment the
     * order it named fills or is cancelled, and a later order may reuse it.
     * That is intended — there is nothing left to address once an order is gone
     * — but it means an order id is not a durable identity, and a consumer
     * keying a tape by it will merge two unrelated orders. The engine
     * {@code sequence} is the durable one: monotonic, never reused, and carried
     * on every event that names an order.
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
    QUANTITY_BELOW_FILLED,

    /**
     * A limit price outside the tradeable band: zero or negative, which is not
     * a price at all. The engine checks this itself rather than trusting the
     * boundary — a missing price arriving as 0 must not become a resting bid at
     * tick 0.
     *
     * <p>Also covers a price above the engine's configured maximum.
     */
    PRICE_OUT_OF_RANGE,

    /**
     * A quantity above the engine's configured maximum. The bound is not
     * bureaucracy: a price level's cached total is a {@code long}, so two
     * orders near {@link Long#MAX_VALUE} at one price would wrap it negative
     * and every depth and fill-or-kill answer derived from it would be a lie.
     */
    QUANTITY_OUT_OF_RANGE
}

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
     * The time-in-force is understood but not yet implemented. IOC, FOK and
     * market orders land in phase 3; until then they are refused loudly rather
     * than silently treated as something else.
     */
    UNSUPPORTED_TIME_IN_FORCE
}

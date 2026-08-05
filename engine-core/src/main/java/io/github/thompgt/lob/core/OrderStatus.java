package io.github.thompgt.lob.core;

/**
 * Where an order ended up after the engine finished with it.
 *
 * <p>This is a summary of the outcome, not a substitute for the execution
 * reports: {@link SubmitResult} carries the quantities, and the
 * {@link ExecutionSink} carries the individual trades.
 */
public enum OrderStatus {

    /** Refused before reaching the book. See {@link SubmitResult#rejectReason()}. */
    REJECTED,

    /** Crossed nothing; the whole quantity now rests on the book. */
    RESTING,

    /** Traded against part of the book; the remainder rests. */
    PARTIALLY_FILLED,

    /** Traded in full; nothing rests. */
    FILLED,

    /**
     * The order left the book without its full quantity trading — cancelled by
     * the client, an IOC or market order with quantity left over, or an FOK
     * that could not fill in full.
     *
     * <p>This says nothing about whether it traded first. An IOC that filled
     * half and killed the rest is {@code CANCELED} with a non-zero
     * {@link SubmitResult#filledQuantity()}; read the quantities, not the
     * status, to know what happened.
     */
    CANCELED
}

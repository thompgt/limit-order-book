package io.github.thompgt.lob.core;

/**
 * Receives execution reports as the engine produces them.
 *
 * <p>Callbacks rather than returned collections, for the same reason
 * {@link DepthVisitor} is a callback: a busy engine would otherwise allocate a
 * list per message, and the hot path is required not to allocate at all.
 *
 * <p><strong>The {@link Order} handed to a callback is engine-owned and
 * short-lived.</strong> It is a live, mutable object that may be recycled the
 * moment the callback returns. Read what you need during the call and copy it;
 * never retain the reference. This is the price of a zero-allocation event
 * stream, and it is why the API layer projects these into immutable DTOs at the
 * boundary.
 *
 * <p>Callbacks are invoked in the order the events happened, on the engine
 * thread, synchronously. A sink that blocks stalls matching.
 *
 * <p>Every method defaults to doing nothing, so a sink only implements the
 * events it cares about — a depth-only consumer ignores trades, a benchmark
 * ignores everything.
 */
public interface ExecutionSink {

    /** A sink that discards everything. Useful for benchmarks and for tests. */
    ExecutionSink NO_OP = new ExecutionSink() {};

    /**
     * The order passed validation and is about to be matched. Fires before any
     * trade, so a consumer sees an accept before the fills that follow it.
     */
    default void accepted(Order order) {}

    /**
     * The order never reached the book.
     *
     * @param orderId the id as submitted; no engine order exists for it
     */
    default void rejected(long orderId, int symbolId, RejectReason reason) {}

    /**
     * One fill between an incoming order and a resting one.
     *
     * <p>The trade price is always the <em>resting</em> order's price: the order
     * that was there first sets the terms, and any price improvement accrues to
     * the aggressor.
     *
     * @param tradeId   monotonic, engine-wide
     * @param quantity  units traded — the smaller of the two open quantities
     * @param aggressor the incoming order, already decremented by this trade
     * @param resting   the book order, already decremented by this trade
     */
    default void trade(
            long tradeId,
            int symbolId,
            long price,
            long quantity,
            Order aggressor,
            Order resting) {}

    /** An order has no quantity left. Fires after the trade that finished it. */
    default void filled(Order order) {}

    /** An order's remainder is now resting on the book at its limit price. */
    default void rested(Order order) {}
}

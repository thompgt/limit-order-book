package io.github.thompgt.lob.core;

/**
 * Why an order left the book without having been filled.
 *
 * <p>A cancel is not a reject: a cancelled order existed, was accepted, held a
 * sequence number, and may well have traded before the remainder went away.
 * A {@link RejectReason} means the order never happened at all.
 */
public enum CancelReason {

    /** The client asked for it — {@link MatchingEngine#cancel(long)}. */
    USER,

    /**
     * The remainder could not rest, so it was killed the instant the sweep
     * finished. That is an {@link TimeInForce#IOC} by instruction, and a market
     * order by nature — a market order has no price to rest at.
     */
    IMMEDIATE_OR_CANCEL,

    /**
     * A {@link TimeInForce#FOK} order could not have been filled in full, so it
     * was killed before touching the book. No trades were emitted for it: the
     * check happens first precisely so there is nothing to unwind.
     */
    FILL_OR_KILL,

    /**
     * The order would have traded against another order from the same account,
     * and {@link SelfTradePolicy} says not to let it. Whether this names the
     * resting order, the aggressive one, or both depends on the policy.
     */
    SELF_TRADE
}

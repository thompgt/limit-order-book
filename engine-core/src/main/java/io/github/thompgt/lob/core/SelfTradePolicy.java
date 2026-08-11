package io.github.thompgt.lob.core;

/**
 * What the engine does when an aggressive order reaches a resting order from
 * the same account.
 *
 * <p>A self-trade is not a matching error — price-time priority says the two
 * orders cross, and they do. It is an accounting and market-conduct problem:
 * the trade moves nothing between participants, prints a price and a volume to
 * everyone watching, and on a real venue is something a firm is expected to
 * avoid. Every exchange therefore offers some form of prevention, and every one
 * of them is a policy choice rather than a rule of the book, which is why this
 * is configurable rather than baked in.
 *
 * <p>The default is {@link #OFF}: an order carries no account unless the
 * boundary supplies one, so the check has nothing to compare and the matching
 * loop keeps the shape the benchmarks measure.
 *
 * <h2>Interaction with fill-or-kill</h2>
 *
 * {@link OrderBook#fillableQuantity} counts depth, not ownership, so a
 * {@link TimeInForce#FOK} order passes its check on quantity that includes the
 * sender's own resting orders. Under {@link #CANCEL_AGGRESSOR} such an order
 * can therefore trade against other accounts and then be cancelled on reaching
 * its own — a partial fill on an all-or-nothing instruction. Making the check
 * account-aware would put the policy on the pre-trade path as well; until there
 * is a reason to, do not combine FOK with an aggressor-cancelling policy.
 *
 * @see Order#accountId()
 */
public enum SelfTradePolicy {

    /** No check at all. An account may lift its own quote. */
    OFF,

    /**
     * Cancel the resting order and let the aggressor carry on into the book.
     * The aggressor gets its fill; the account loses the quote it was about to
     * hit. Sometimes called "cancel oldest".
     */
    CANCEL_RESTING,

    /**
     * Cancel the aggressive order's remainder and leave the book untouched.
     * Whatever it traded against other accounts before reaching its own order
     * stands. Sometimes called "cancel newest".
     */
    CANCEL_AGGRESSOR,

    /** Cancel both: the resting order leaves the book and the aggressor stops. */
    CANCEL_BOTH;

    boolean cancelsResting() {
        return this == CANCEL_RESTING || this == CANCEL_BOTH;
    }

    boolean cancelsAggressor() {
        return this == CANCEL_AGGRESSOR || this == CANCEL_BOTH;
    }
}

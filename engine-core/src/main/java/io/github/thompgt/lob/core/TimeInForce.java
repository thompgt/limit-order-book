package io.github.thompgt.lob.core;

/**
 * What happens to the unfilled remainder of an aggressive order.
 */
public enum TimeInForce {
    /** Rest the remainder on the book until cancelled. */
    DAY,
    /** Immediate-or-cancel: take what is available, cancel the remainder. */
    IOC,
    /** Fill-or-kill: fill the entire quantity in one shot or cancel it all. */
    FOK;

    /** Whether an unfilled remainder is allowed to rest on the book. */
    public boolean restsOnBook() {
        return this == DAY;
    }
}

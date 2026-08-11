package io.github.thompgt.lob.core;

/**
 * A single resting or in-flight order.
 *
 * <p>Deliberately mutable and reusable. Orders are the highest-churn object in
 * the engine, so they are pooled and re-initialised via
 * {@link #reset(long, int, Side, TimeInForce, long, long, long)} rather than
 * allocated per message. Nothing here is thread-safe; the engine is
 * single-threaded by design.
 *
 * <p>The {@code prev}/{@code next} fields make this an <em>intrusive</em> list
 * node: an order carries its own links instead of being wrapped in a container
 * node. That is what makes cancel O(1) — given the order, we can unlink it
 * without scanning the price level's queue, and without allocating.
 *
 * <p>Fields are package-private so {@link PriceLevel} and {@link OrderBook} can
 * touch them directly on the hot path. Outside the package, use the getters.
 */
public final class Order {

    /** No participant identity was supplied. Never matches another account. */
    public static final long NO_ACCOUNT = 0L;

    /** Client-visible identifier. Unique across the engine. */
    long orderId;

    /** Which book this order belongs to. */
    int symbolId;

    /**
     * Who sent it. {@link #NO_ACCOUNT} when the boundary does not identify
     * participants, which is the default — the engine is happy not to know, but
     * without it there is nothing for {@link SelfTradePolicy} to compare, and a
     * client can lift its own quote.
     */
    long accountId;

    Side side;
    TimeInForce timeInForce;

    /** Limit price in ticks. Never a floating-point value. */
    long price;

    /** Quantity as originally submitted. */
    long quantity;

    /** Quantity still open: {@code quantity} minus everything filled so far. */
    long remainingQuantity;

    /**
     * Monotonic arrival sequence, assigned by the engine. This is the "time" in
     * price-time priority: within a price level, a lower sequence is ahead.
     * A modify that loses priority is given a fresh sequence.
     */
    long sequence;

    /** Intrusive FIFO links within the owning {@link PriceLevel}. */
    Order prev;
    Order next;

    /**
     * The level this order currently rests in, or {@code null} if it is not on
     * the book. Held so cancel can find the level without a price lookup.
     */
    PriceLevel level;

    /**
     * Re-initialises this instance for a new order. Used both for fresh orders
     * and for pooled ones being handed back out; every field is overwritten, so
     * no state leaks from a previous occupant.
     */
    public Order reset(
            long orderId,
            int symbolId,
            Side side,
            TimeInForce timeInForce,
            long price,
            long quantity,
            long sequence) {
        return reset(orderId, symbolId, NO_ACCOUNT, side, timeInForce, price, quantity, sequence);
    }

    /** As above, for an order whose sender is known. */
    public Order reset(
            long orderId,
            int symbolId,
            long accountId,
            Side side,
            TimeInForce timeInForce,
            long price,
            long quantity,
            long sequence) {
        this.orderId = orderId;
        this.symbolId = symbolId;
        this.accountId = accountId;
        this.side = side;
        this.timeInForce = timeInForce;
        this.price = price;
        this.quantity = quantity;
        this.remainingQuantity = quantity;
        this.sequence = sequence;
        this.prev = null;
        this.next = null;
        this.level = null;
        return this;
    }

    /**
     * Drops references so a pooled order does not keep a book, a level or its
     * queue neighbours alive. Leaves the primitive fields alone — they are
     * overwritten by the next {@code reset}.
     */
    public void clear() {
        this.prev = null;
        this.next = null;
        this.level = null;
        this.side = null;
        this.timeInForce = null;
    }

    public long orderId() {
        return orderId;
    }

    public int symbolId() {
        return symbolId;
    }

    /** Who sent it, or {@link #NO_ACCOUNT} if the boundary did not say. */
    public long accountId() {
        return accountId;
    }

    public Side side() {
        return side;
    }

    public TimeInForce timeInForce() {
        return timeInForce;
    }

    public long price() {
        return price;
    }

    public long quantity() {
        return quantity;
    }

    public long remainingQuantity() {
        return remainingQuantity;
    }

    public long filledQuantity() {
        return quantity - remainingQuantity;
    }

    public long sequence() {
        return sequence;
    }

    /** True once every unit has been filled. */
    public boolean isFilled() {
        return remainingQuantity == 0;
    }

    /** True while this order sits in a price level on the book. */
    public boolean isResting() {
        return level != null;
    }

    /**
     * True if this is a market order — one carrying its side's
     * {@link Side#marketPrice() sentinel} rather than a real limit.
     *
     * <p>A market order can never rest: there is no price to rest at, so any
     * remainder left after the sweep is cancelled regardless of time-in-force.
     */
    public boolean isMarket() {
        return price == side.marketPrice();
    }

    @Override
    public String toString() {
        // Diagnostics only — never called on the hot path.
        return "Order{id=" + orderId
                + ", sym=" + symbolId
                + ", " + side
                + ' ' + remainingQuantity + '/' + quantity
                + " @" + price
                + ", tif=" + timeInForce
                + ", seq=" + sequence
                + '}';
    }
}

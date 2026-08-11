package io.github.thompgt.lob.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * The matching engine: turns incoming orders into trades against the book.
 *
 * <p>One engine owns every symbol's {@link OrderBook}, the {@link OrderIndex}
 * that finds an order by id, the {@link OrderPool}, and the monotonic counters
 * that supply sequence numbers and trade ids. Keeping them together is what
 * makes the whole thing single-threaded and therefore lock-free.
 *
 * <h2>How an order is matched</h2>
 *
 * An incoming order walks the opposite side of the book from the best price
 * outwards, taking each level in full before moving to the next, and taking
 * each level's orders in arrival order. That is price-time priority, and the
 * walk falls straight out of the data structures: the ladder is sorted so the
 * first level is the best one, and each level is a FIFO so its head is the
 * longest-waiting order.
 *
 * <p>The walk stops when the aggressive order runs out of quantity, when the
 * opposite side runs out of levels, or when the next level is beyond the
 * order's limit price. Whatever quantity is left over then rests on the book,
 * which is why a correct engine can never leave the book crossed: an order that
 * could have traded would have.
 *
 * <p>Trades happen at the <em>resting</em> price, not the incoming one. The
 * order that was there first set the terms; a buyer willing to pay 105 who
 * finds an offer at 103 pays 103 and keeps the improvement.
 *
 * <h2>Order types and time-in-force</h2>
 *
 * A market order is a limit order priced at {@link Side#marketPrice()}, so it
 * crosses everything and the matching loop needs no special case for it. It
 * never rests — there is no price to rest at — so any remainder is cancelled.
 *
 * <p>{@link TimeInForce#IOC} kills its remainder the moment the sweep ends.
 * {@link TimeInForce#FOK} is checked against
 * {@link OrderBook#fillableQuantity} <em>before</em> it touches the book: a FOK
 * that cannot fill in full must emit no trades at all, and the only reliable
 * way to guarantee that is never to start.
 *
 * <h2>Cancel and modify</h2>
 *
 * Cancel is O(1): the id lookup is a primitive-keyed map, and the order carries
 * its own queue links so unlinking it needs no scan. Modify follows the rules in
 * {@code CLAUDE.md} — a price change or a quantity increase costs time
 * priority, a pure quantity decrease keeps it — and a modify that ends up
 * crossing the book trades like any other aggressive order.
 *
 * <p>Not thread-safe, deliberately. See invariant 4 in {@code CLAUDE.md}.
 */
public final class MatchingEngine {

    /** Reported when a result has no symbol, because no order was found. */
    public static final int NO_SYMBOL = -1;

    /**
     * Default ceiling on a limit price and on an order quantity, in ticks and
     * units respectively.
     *
     * <p>A trillion is far past any real instrument and still leaves nine
     * million maximum-sized orders' worth of headroom before a level's cached
     * {@code totalQuantity} could overflow. Without a ceiling, two orders at
     * {@link Long#MAX_VALUE} on one price wrap that total negative, and depth,
     * {@link OrderBook#fillableQuantity} and therefore fill-or-kill all start
     * answering wrongly with no error anywhere.
     */
    public static final long DEFAULT_MAX_PRICE = 1_000_000_000_000L;

    /** @see #DEFAULT_MAX_PRICE */
    public static final long DEFAULT_MAX_QUANTITY = 1_000_000_000_000L;

    private final ExecutionSink sink;
    private final OrderIndex index;
    private final OrderPool pool;
    private final Int2ObjectOpenHashMap<OrderBook> books = new Int2ObjectOpenHashMap<>();

    /** Reused across calls; see the ownership note on {@link SubmitResult}. */
    private final SubmitResult result = new SubmitResult();

    private final CancelResult cancelResult = new CancelResult();

    /** Arrival order — the "time" in price-time priority. */
    private long nextSequence;

    private long nextTradeId;

    private final long maxPrice;
    private final long maxQuantity;

    public MatchingEngine() {
        this(ExecutionSink.NO_OP);
    }

    public MatchingEngine(ExecutionSink sink) {
        this(sink, new OrderPool());
    }

    public MatchingEngine(ExecutionSink sink, OrderPool pool) {
        this(sink, pool, DEFAULT_MAX_PRICE, DEFAULT_MAX_QUANTITY);
    }

    /**
     * @param maxPrice    highest acceptable limit price in ticks
     * @param maxQuantity largest acceptable order quantity
     * @see #DEFAULT_MAX_PRICE
     */
    public MatchingEngine(ExecutionSink sink, OrderPool pool, long maxPrice, long maxQuantity) {
        if (maxPrice <= 0 || maxQuantity <= 0) {
            throw new IllegalArgumentException("maxPrice and maxQuantity must be positive");
        }
        this.sink = sink;
        this.pool = pool;
        this.index = new OrderIndex();
        this.maxPrice = maxPrice;
        this.maxQuantity = maxQuantity;
    }

    /** Highest limit price this engine will accept, in ticks. */
    public long maxPrice() {
        return maxPrice;
    }

    /** Largest order quantity this engine will accept. */
    public long maxQuantity() {
        return maxQuantity;
    }

    /**
     * Creates the book for a symbol. Orders for an unregistered symbol are
     * rejected rather than implicitly opening a market.
     *
     * @return the new book, or the existing one if the symbol was already open
     */
    public OrderBook registerSymbol(int symbolId) {
        OrderBook existing = books.get(symbolId);
        if (existing != null) {
            return existing;
        }
        OrderBook book = new OrderBook(symbolId);
        books.put(symbolId, book);
        return book;
    }

    /** The book for a symbol, or {@code null} if it is not registered. */
    public OrderBook book(int symbolId) {
        return books.get(symbolId);
    }

    public boolean hasSymbol(int symbolId) {
        return books.containsKey(symbolId);
    }

    /** A live order by id, or {@code null}. Filled orders are no longer live. */
    public Order order(long orderId) {
        return index.get(orderId);
    }

    /** How many orders are currently resting across every book. */
    public int liveOrderCount() {
        return index.size();
    }

    /** Trades executed since startup. Also the id of the most recent trade. */
    public long tradeCount() {
        return nextTradeId;
    }

    public OrderPool pool() {
        return pool;
    }

    // ------------------------------------------------------------- submit

    /**
     * Submits a limit order: match it against the book, then rest, kill or
     * fill what is left according to its time-in-force.
     *
     * @param orderId  client-supplied; must not collide with a live order
     * @param price    limit price in ticks
     * @param quantity units to trade; must be positive
     * @return the reused result object, valid until the next call
     */
    public SubmitResult submit(
            long orderId,
            int symbolId,
            Side side,
            TimeInForce timeInForce,
            long price,
            long quantity) {

        return submitOrder(orderId, symbolId, side, timeInForce, price, quantity, true);
    }

    /**
     * Submits a market order: take whatever the book will give, at whatever
     * price it asks, and cancel anything left over.
     *
     * <p>The time-in-force only decides whether a partial fill is acceptable.
     * {@link TimeInForce#DAY} and {@link TimeInForce#IOC} behave identically
     * here, since a market order cannot rest either way; {@link TimeInForce#FOK}
     * demands the whole quantity or nothing.
     */
    public SubmitResult submitMarket(
            long orderId, int symbolId, Side side, TimeInForce timeInForce, long quantity) {
        return submitOrder(
                orderId, symbolId, side, timeInForce, side.marketPrice(), quantity, false);
    }

    private SubmitResult submitOrder(
            long orderId,
            int symbolId,
            Side side,
            TimeInForce timeInForce,
            long price,
            long quantity,
            boolean limitOrder) {

        result.reset(orderId, symbolId);

        RejectReason reason = validate(orderId, symbolId, side, price, quantity, limitOrder);
        if (reason != null) {
            sink.rejected(orderId, symbolId, reason);
            return result.rejected(reason);
        }

        OrderBook book = books.get(symbolId);
        long sequence = ++nextSequence;
        Order order = pool.acquire()
                .reset(orderId, symbolId, side, timeInForce, price, quantity, sequence);
        sink.accepted(order);

        if (timeInForce == TimeInForce.FOK
                && book.fillableQuantity(side, price, quantity) < quantity) {
            // Killed before a single trade is emitted. Checking first is the
            // whole design: there is no partial fill to unwind afterwards.
            return kill(order, CancelReason.FILL_OR_KILL, sequence);
        }

        match(book, order);

        if (order.remainingQuantity == 0) {
            sink.filled(order);
            result.complete(OrderStatus.FILLED, 0L, sequence);
            pool.release(order);
            return result;
        }

        if (!timeInForce.restsOnBook() || order.isMarket()) {
            return kill(order, CancelReason.IMMEDIATE_OR_CANCEL, sequence);
        }

        index.put(order);
        book.add(order);
        sink.rested(order);
        result.complete(
                order.filledQuantity() > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.RESTING,
                order.remainingQuantity,
                sequence);
        return result;
    }

    /**
     * Retires an order that will not rest. The cancel event fires while the
     * order still carries the quantity being killed, and the order goes back to
     * the pool only once every consumer has seen it.
     */
    private SubmitResult kill(Order order, CancelReason reason, long sequence) {
        sink.canceled(order, reason);
        result.complete(OrderStatus.CANCELED, 0L, sequence);
        pool.release(order);
        return result;
    }

    private RejectReason validate(
            long orderId,
            int symbolId,
            Side side,
            long price,
            long quantity,
            boolean limitOrder) {
        if (quantity <= 0) {
            return RejectReason.NON_POSITIVE_QUANTITY;
        }
        if (quantity > maxQuantity) {
            return RejectReason.QUANTITY_OUT_OF_RANGE;
        }
        if (!books.containsKey(symbolId)) {
            return RejectReason.UNKNOWN_SYMBOL;
        }
        if (index.contains(orderId)) {
            return RejectReason.DUPLICATE_ORDER_ID;
        }
        if (limitOrder && price == side.marketPrice()) {
            // The sentinel is reserved. Refusing keeps the encoding honest:
            // otherwise a limit at Long.MAX_VALUE would silently sweep the book.
            return RejectReason.RESERVED_PRICE;
        }
        if (limitOrder && price > maxPrice) {
            return RejectReason.PRICE_OUT_OF_RANGE;
        }
        if (limitOrder && price <= 0) {
            // Not a price. Checked here and not only at the API boundary,
            // because a boundary that forgets to send one would otherwise rest
            // an order at tick 0 rather than being told no.
            return RejectReason.PRICE_OUT_OF_RANGE;
        }
        return null;
    }

    // ------------------------------------------------------------- cancel

    /**
     * Removes a resting order from the book. O(1): the index finds it without
     * boxing and the order's own links unlink it without a queue scan.
     *
     * <p>Only open quantity is cancelled. Anything the order already traded is
     * done and cannot be taken back, which is why cancelling a partially filled
     * order reports only what was left.
     *
     * @return the reused result object, valid until the next call
     */
    public CancelResult cancel(long orderId) {
        cancelResult.reset(orderId);

        Order order = index.get(orderId);
        if (order == null) {
            sink.rejected(orderId, NO_SYMBOL, RejectReason.UNKNOWN_ORDER_ID);
            return cancelResult.rejected(RejectReason.UNKNOWN_ORDER_ID);
        }

        int symbolId = order.symbolId;
        long canceledQuantity = order.remainingQuantity;

        books.get(symbolId).remove(order);
        index.remove(orderId);
        sink.canceled(order, CancelReason.USER);
        pool.release(order);

        return cancelResult.canceled(symbolId, canceledQuantity);
    }

    // ------------------------------------------------------------- modify

    /**
     * Changes a resting order's price, its quantity, or both.
     *
     * <p>Time priority follows the rules in {@code CLAUDE.md}, and they are the
     * point of this method:
     *
     * <ul>
     *   <li>price changed, either direction — priority <strong>lost</strong>,
     *       re-queued at the back of the new level with a fresh sequence;
     *   <li>quantity increased — priority <strong>lost</strong>, re-queued at
     *       the back of the current level;
     *   <li>quantity decreased only — priority <strong>kept</strong>, the order
     *       does not move.
     * </ul>
     *
     * The asymmetry is not arbitrary: asking for more than you queued for is a
     * new request and has to go to the back, but giving some of it up takes
     * nothing from anyone behind you, so there is no reason to charge for it.
     *
     * <p>A modify whose new price crosses the book trades immediately, exactly
     * like a fresh aggressive order — which is why this returns a
     * {@link SubmitResult}.
     *
     * @param newQuantity the new <em>total</em> order quantity, as originally
     *                    submitted — not the remainder. Must exceed what the
     *                    order has already filled.
     */
    public SubmitResult modify(long orderId, long newPrice, long newQuantity) {
        Order order = index.get(orderId);
        result.reset(orderId, order == null ? NO_SYMBOL : order.symbolId);

        RejectReason reason = validateModify(order, newPrice, newQuantity);
        if (reason != null) {
            sink.rejected(orderId, result.symbolId(), reason);
            return result.rejected(reason);
        }

        long previousPrice = order.price;
        long previousQuantity = order.quantity;
        long newRemaining = newQuantity - order.filledQuantity();
        boolean priorityLost = newPrice != previousPrice || newRemaining > order.remainingQuantity;

        if (!priorityLost) {
            // A pure decrease: shrink it where it stands. Going through the book
            // keeps the level's cached depth total in step, and the order cannot
            // hit zero here because newQuantity is validated above the fill.
            books.get(order.symbolId).reduce(order, order.remainingQuantity - newRemaining);
            order.quantity = newQuantity;
            sink.replaced(order, previousPrice, previousQuantity, false);
            result.complete(
                    order.filledQuantity() > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.RESTING,
                    order.remainingQuantity,
                    order.sequence);
            return result;
        }

        OrderBook book = books.get(order.symbolId);
        book.remove(order);

        long sequence = ++nextSequence;
        order.price = newPrice;
        order.quantity = newQuantity;
        order.remainingQuantity = newRemaining;
        order.sequence = sequence;
        sink.replaced(order, previousPrice, previousQuantity, true);

        match(book, order);

        if (order.remainingQuantity == 0) {
            sink.filled(order);
            index.remove(orderId);
            result.complete(OrderStatus.FILLED, 0L, sequence);
            pool.release(order);
            return result;
        }

        // Still in the index throughout — the id never stopped being live.
        book.add(order);
        sink.rested(order);
        result.complete(
                order.filledQuantity() > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.RESTING,
                order.remainingQuantity,
                sequence);
        return result;
    }

    private RejectReason validateModify(Order order, long newPrice, long newQuantity) {
        if (order == null) {
            return RejectReason.UNKNOWN_ORDER_ID;
        }
        if (newQuantity <= 0) {
            return RejectReason.NON_POSITIVE_QUANTITY;
        }
        if (newQuantity > maxQuantity) {
            return RejectReason.QUANTITY_OUT_OF_RANGE;
        }
        if (newPrice == order.side.marketPrice()) {
            return RejectReason.RESERVED_PRICE;
        }
        if (newPrice <= 0 || newPrice > maxPrice) {
            return RejectReason.PRICE_OUT_OF_RANGE;
        }
        if (newQuantity <= order.filledQuantity()) {
            return RejectReason.QUANTITY_BELOW_FILLED;
        }
        return null;
    }

    // -------------------------------------------------------------- match

    /**
     * Sweeps the opposite side while the incoming order still has quantity and
     * the next level is within its limit.
     *
     * <p>The inner loop always re-reads {@link PriceLevel#head()} rather than
     * following {@code next}: a fully filled order is unlinked by
     * {@link OrderBook#reduce} and its links nulled, so the head <em>is</em> the
     * next order to fill. Progress is guaranteed because every iteration trades
     * a positive quantity.
     *
     * <p>It stops touching a level the moment {@code reduce} reports the level
     * gone. Emptying a level returns it to the free list, so reading it again
     * is a use-after-release — harmless only for as long as nothing re-acquires
     * a level mid-sweep, which is not a property worth depending on.
     */
    private void match(OrderBook book, Order aggressor) {
        Side passiveSide = aggressor.side.opposite();
        int symbolId = aggressor.symbolId;

        while (aggressor.remainingQuantity > 0) {
            PriceLevel level = book.bestLevel(passiveSide);
            if (level == null) {
                return;
            }
            long tradePrice = level.price();
            if (!aggressor.side.crosses(aggressor.price, tradePrice)) {
                return;
            }

            while (aggressor.remainingQuantity > 0) {
                Order resting = level.head();
                if (resting == null) {
                    break;
                }

                long quantity = Math.min(aggressor.remainingQuantity, resting.remainingQuantity);
                aggressor.remainingQuantity -= quantity;
                boolean levelSurvived = book.reduce(resting, quantity);
                result.recordTrade(quantity);

                sink.trade(++nextTradeId, symbolId, tradePrice, quantity, aggressor, resting);

                if (resting.remainingQuantity == 0) {
                    sink.filled(resting);
                    index.remove(resting.orderId);
                    pool.release(resting);
                }

                if (!levelSurvived) {
                    // The level is back in the free list; do not read it again.
                    break;
                }
            }
        }
    }

    @Override
    public String toString() {
        // Diagnostics only — never called on the hot path.
        return "MatchingEngine{symbols=" + books.size()
                + ", live=" + index.size()
                + ", trades=" + nextTradeId
                + '}';
    }
}

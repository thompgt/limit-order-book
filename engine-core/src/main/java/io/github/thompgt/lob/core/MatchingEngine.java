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
 * <h2>Scope</h2>
 *
 * Phase 2 handles limit orders that rest ({@link TimeInForce#DAY}). IOC, FOK,
 * market orders, cancel and modify are phase 3 — until then they are rejected
 * with {@link RejectReason#UNSUPPORTED_TIME_IN_FORCE} rather than quietly
 * treated as something they are not.
 *
 * <p>Not thread-safe, deliberately. See invariant 4 in {@code CLAUDE.md}.
 */
public final class MatchingEngine {

    private final ExecutionSink sink;
    private final OrderIndex index;
    private final OrderPool pool;
    private final Int2ObjectOpenHashMap<OrderBook> books = new Int2ObjectOpenHashMap<>();

    /** Reused across calls; see the ownership note on {@link SubmitResult}. */
    private final SubmitResult result = new SubmitResult();

    /** Arrival order — the "time" in price-time priority. */
    private long nextSequence;

    private long nextTradeId;

    public MatchingEngine() {
        this(ExecutionSink.NO_OP);
    }

    public MatchingEngine(ExecutionSink sink) {
        this(sink, new OrderPool());
    }

    public MatchingEngine(ExecutionSink sink, OrderPool pool) {
        this.sink = sink;
        this.pool = pool;
        this.index = new OrderIndex();
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

    /**
     * Submits an order: match it against the book, then rest what is left.
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

        result.reset(orderId, symbolId);

        RejectReason reason = validate(orderId, symbolId, timeInForce, quantity);
        if (reason != null) {
            sink.rejected(orderId, symbolId, reason);
            return result.rejected(reason);
        }

        OrderBook book = books.get(symbolId);
        long sequence = ++nextSequence;
        Order order = pool.acquire()
                .reset(orderId, symbolId, side, timeInForce, price, quantity, sequence);
        sink.accepted(order);

        match(book, order);

        if (order.remainingQuantity == 0) {
            sink.filled(order);
            result.complete(OrderStatus.FILLED, 0L, sequence);
            pool.release(order);
            return result;
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

    private RejectReason validate(
            long orderId, int symbolId, TimeInForce timeInForce, long quantity) {
        if (quantity <= 0) {
            return RejectReason.NON_POSITIVE_QUANTITY;
        }
        if (!books.containsKey(symbolId)) {
            return RejectReason.UNKNOWN_SYMBOL;
        }
        if (index.contains(orderId)) {
            return RejectReason.DUPLICATE_ORDER_ID;
        }
        if (timeInForce != TimeInForce.DAY) {
            return RejectReason.UNSUPPORTED_TIME_IN_FORCE;
        }
        return null;
    }

    /**
     * Sweeps the opposite side while the incoming order still has quantity and
     * the next level is within its limit.
     *
     * <p>The inner loop always re-reads {@link PriceLevel#head()} rather than
     * following {@code next}: a fully filled order is unlinked by
     * {@link OrderBook#reduce} and its links nulled, so the head <em>is</em> the
     * next order to fill. Progress is guaranteed because every iteration trades
     * a positive quantity.
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
                book.reduce(resting, quantity);
                result.recordTrade(quantity);

                sink.trade(++nextTradeId, symbolId, tradePrice, quantity, aggressor, resting);

                if (resting.remainingQuantity == 0) {
                    sink.filled(resting);
                    index.remove(resting.orderId);
                    pool.release(resting);
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

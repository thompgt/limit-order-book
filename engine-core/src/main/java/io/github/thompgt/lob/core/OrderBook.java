package io.github.thompgt.lob.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectSortedMap;
import it.unimi.dsi.fastutil.longs.LongComparators;

/**
 * The book for one instrument: two price ladders of {@link PriceLevel}.
 *
 * <p>This is the "price" half of price-time priority. Bids are ordered
 * descending and asks ascending, so in both cases the first entry of the ladder
 * is the side's best price — the level an incoming aggressive order must trade
 * against first. Time priority within a level is the {@link PriceLevel} FIFO.
 *
 * <p>Matching is not here; this class only maintains book state. Crossing lives
 * in the matching engine, which drives the book through {@link #add},
 * {@link #reduce} and {@link #remove}.
 *
 * <p>The ladders are primitive-keyed red-black trees. They began as
 * {@link java.util.TreeMap}s, on the principle that a benchmark rather than a
 * hunch should say when to replace them — and in phase 4 the benchmark did.
 * {@code -prof gc} showed 24–36 B/op on every path that touched a ladder,
 * against 0.7 B/op on the one path that does not (an in-place quantity
 * decrease, which never looks a price up). Two causes, found in that order:
 *
 * <ul>
 *   <li>{@code TreeMap<Long, PriceLevel>} boxed a {@code Long} per lookup, and
 *       {@code firstEntry()} allocated a fresh immutable entry every call — so
 *       simply <em>asking for the best price</em> allocated. fastutil's
 *       primitive map removed the boxing and took the ladder paths to
 *       ~17–24 B/op.</li>
 *   <li>What remained was tree <em>node</em> churn: resting the first order at
 *       a new price allocates an entry, and cancelling the last order at that
 *       price throws it away. This is real, not measurement error, and it is
 *       proportional to how often trading opens and closes price levels rather
 *       than to order rate. Removing it means giving up the tree — an
 *       array-indexed ladder over a bounded price band — which is a larger
 *       change than the numbers currently justify.</li>
 * </ul>
 *
 * <p>Asking for the best price no longer costs anything either way: it is a
 * maintained field, see {@link #bestBidLevel}.
 *
 * <p>Emptied levels are recycled through a small free list so steady-state
 * trading around a stable price does not churn level objects.
 *
 * <p>Not thread-safe; the engine is single-threaded by design.
 */
public final class OrderBook {

    /** Returned by {@link #bestBid()} when there is no bid. Never crosses. */
    public static final long NO_BID = Long.MIN_VALUE;

    /** Returned by {@link #bestAsk()} when there is no ask. Never crosses. */
    public static final long NO_ASK = Long.MAX_VALUE;

    private static final int LEVEL_POOL_CAPACITY = 256;

    private final int symbolId;

    /** Highest price first: the best bid is the first entry. */
    private final Long2ObjectSortedMap<PriceLevel> bids =
            new Long2ObjectRBTreeMap<>(LongComparators.OPPOSITE_COMPARATOR);

    /** Lowest price first: the best ask is the first entry. */
    private final Long2ObjectSortedMap<PriceLevel> asks = new Long2ObjectRBTreeMap<>();

    /**
     * The front of each ladder, held directly.
     *
     * <p>{@link #bestLevel} is the single most-called method in the engine — the
     * match loop asks for it on every pass — and no tree offers a
     * both-cheap-and-allocation-free way to answer it: the JDK's
     * {@code firstEntry()} allocates, and reaching the same answer without it
     * costs a {@code firstLongKey()} descent plus a {@code get()} descent. So
     * the answer is maintained instead of computed. Both fields are null exactly
     * when their ladder is empty; that correspondence is what
     * {@link #add} and {@link #discardIfEmpty} exist to preserve.
     */
    private PriceLevel bestBidLevel;
    private PriceLevel bestAskLevel;

    private final PriceLevel[] levelPool = new PriceLevel[LEVEL_POOL_CAPACITY];
    private int pooledLevels;

    public OrderBook(int symbolId) {
        this.symbolId = symbolId;
    }

    public int symbolId() {
        return symbolId;
    }

    /**
     * Rests an order on the book at its own price, at the back of that price's
     * queue. The level is created if this is the first order at that price.
     *
     * <p>No crossing check happens here: by the time an order rests, the engine
     * has already matched away whatever it could.
     */
    public void add(Order order) {
        Long2ObjectSortedMap<PriceLevel> ladder = ladderFor(order.side);
        PriceLevel level = ladder.get(order.price);
        if (level == null) {
            level = acquireLevel(order.price);
            ladder.put(order.price, level);
            promoteIfBest(order.side, level);
        }
        level.add(order);
    }

    /**
     * Unlinks a resting order — a cancel, or the losing half of a modify that
     * gives up its priority. O(1): the order knows its own level, so there is
     * no ladder lookup and no queue scan.
     *
     * @return {@code true} if the order was resting and has been removed
     */
    public boolean remove(Order order) {
        PriceLevel level = order.level;
        if (level == null) {
            return false;
        }
        Side side = order.side;
        level.remove(order);
        discardIfEmpty(side, level);
        return true;
    }

    /**
     * Applies a fill against a resting order, keeping the level's cached total
     * in step. The order holds its place in the queue while any quantity
     * remains; once it is fully filled it leaves the book.
     */
    public void reduce(Order order, long quantity) {
        PriceLevel level = order.level;
        Side side = order.side;
        level.reduce(order, quantity);
        if (order.remainingQuantity == 0) {
            level.remove(order);
            discardIfEmpty(side, level);
        }
    }

    /**
     * The level an aggressive order would hit first on the given side, or
     * {@code null} if that side is empty. A field read — no tree walk, no
     * allocation.
     */
    public PriceLevel bestLevel(Side side) {
        return side == Side.BUY ? bestBidLevel : bestAskLevel;
    }

    /** @return the highest bid price, or {@link #NO_BID} if there is no bid */
    public long bestBid() {
        PriceLevel level = bestLevel(Side.BUY);
        return level == null ? NO_BID : level.price();
    }

    /** @return the lowest ask price, or {@link #NO_ASK} if there is no ask */
    public long bestAsk() {
        PriceLevel level = bestLevel(Side.SELL);
        return level == null ? NO_ASK : level.price();
    }

    /**
     * @return the bid-ask spread in ticks, or {@code -1} when either side is
     *         empty and no spread exists
     */
    public long spread() {
        long bid = bestBid();
        long ask = bestAsk();
        if (bid == NO_BID || ask == NO_ASK) {
            return -1L;
        }
        return ask - bid;
    }

    /**
     * True if the best bid is at or above the best ask — a state a correct
     * engine never leaves the book in, since such an order would have matched.
     * Asserted by the property tests rather than trusted.
     */
    public boolean isCrossed() {
        long bid = bestBid();
        long ask = bestAsk();
        return bid != NO_BID && ask != NO_ASK && bid >= ask;
    }

    /** Aggregated open quantity at one price, or 0 if nothing rests there. */
    public long quantityAt(Side side, long price) {
        PriceLevel level = ladderFor(side).get(price);
        return level == null ? 0L : level.totalQuantity();
    }

    /**
     * How much an aggressive order could trade right now, without trading it.
     *
     * <p>This is what makes fill-or-kill possible: FOK has to know the answer
     * <em>before</em> touching the book, because a partial fill it then had to
     * unwind would already have been published to every consumer.
     *
     * <p>Walks the opposite ladder from the best price outwards, exactly as the
     * matching loop would, and stops as soon as it has seen {@code cap} units —
     * a deep book is not worth counting past the point the answer is decided.
     *
     * @param aggressorSide the side of the incoming order, not the resting one
     * @param limitPrice    the incoming order's limit; use
     *                      {@link Side#marketPrice()} for a market order
     * @param cap           stop counting here
     * @return reachable open quantity, never more than {@code cap}
     */
    public long fillableQuantity(Side aggressorSide, long limitPrice, long cap) {
        long total = 0L;
        for (PriceLevel level : ladderFor(aggressorSide.opposite()).values()) {
            if (!aggressorSide.crosses(limitPrice, level.price())) {
                break; // The ladder is sorted, so nothing further can cross either.
            }
            total += level.totalQuantity();
            if (total >= cap) {
                return cap;
            }
        }
        return total;
    }

    /** Number of distinct populated price levels on a side. */
    public int levelCount(Side side) {
        return ladderFor(side).size();
    }

    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }

    /**
     * Walks up to {@code maxLevels} of one side, best price first, handing each
     * level to the visitor. This is the L2 depth snapshot: aggregated quantity
     * per price, no individual orders exposed.
     */
    public void snapshot(Side side, int maxLevels, DepthVisitor visitor) {
        int emitted = 0;
        for (PriceLevel level : ladderFor(side).values()) {
            if (emitted == maxLevels) {
                return;
            }
            visitor.level(level.price(), level.totalQuantity(), level.orderCount());
            emitted++;
        }
    }

    private Long2ObjectSortedMap<PriceLevel> ladderFor(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /**
     * Drops a level from its ladder once the last order leaves. An empty level
     * left in place would make {@link #bestBid()} report a price with nothing
     * behind it.
     */
    private void discardIfEmpty(Side side, PriceLevel level) {
        if (!level.isEmpty()) {
            return;
        }
        Long2ObjectSortedMap<PriceLevel> ladder = ladderFor(side);
        ladder.remove(level.price());
        if (bestLevel(side) == level) {
            // Only the front of the ladder forces a recompute, and only when it
            // is the level that just went away. Every other cancel is untouched.
            setBest(side, ladder.isEmpty() ? null : ladder.get(ladder.firstLongKey()));
        }
        releaseLevel(level);
    }

    /** Makes a newly created level the front of its ladder if it belongs there. */
    private void promoteIfBest(Side side, PriceLevel level) {
        PriceLevel current = bestLevel(side);
        if (current == null || side.isBetterPrice(level.price(), current.price())) {
            setBest(side, level);
        }
    }

    private void setBest(Side side, PriceLevel level) {
        if (side == Side.BUY) {
            bestBidLevel = level;
        } else {
            bestAskLevel = level;
        }
    }

    private PriceLevel acquireLevel(long price) {
        if (pooledLevels > 0) {
            return levelPool[--pooledLevels].reset(price);
        }
        return new PriceLevel().reset(price);
    }

    private void releaseLevel(PriceLevel level) {
        if (pooledLevels < levelPool.length) {
            levelPool[pooledLevels++] = level;
        }
        // Pool full: let this one be collected rather than growing without bound.
    }

    @Override
    public String toString() {
        // Diagnostics only — never called on the hot path.
        return "OrderBook{symbol=" + symbolId
                + ", bid=" + (bestBid() == NO_BID ? "-" : bestBid())
                + ", ask=" + (bestAsk() == NO_ASK ? "-" : bestAsk())
                + ", levels=" + bids.size() + '/' + asks.size()
                + '}';
    }
}

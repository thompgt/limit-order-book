package io.github.thompgt.lob.core;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

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
 * <p>The ladders are {@link TreeMap}s for now. That boxes a {@code Long} key on
 * lookup, which will eventually show up on the hot path — the deliberate order
 * of work is to get the semantics right and let a benchmark, not a hunch, say
 * when to replace this with a primitive-keyed structure. Emptied levels are
 * recycled through a small free list so steady-state trading around a stable
 * price does not churn level objects.
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
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());

    /** Lowest price first: the best ask is the first entry. */
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();

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
        TreeMap<Long, PriceLevel> ladder = ladderFor(order.side);
        PriceLevel level = ladder.get(order.price);
        if (level == null) {
            level = acquireLevel(order.price);
            ladder.put(order.price, level);
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

    /** The level an aggressive order would hit first on the given side. */
    public PriceLevel bestLevel(Side side) {
        Map.Entry<Long, PriceLevel> best = ladderFor(side).firstEntry();
        return best == null ? null : best.getValue();
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

    private TreeMap<Long, PriceLevel> ladderFor(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /**
     * Drops a level from its ladder once the last order leaves. An empty level
     * left in place would make {@link #bestBid()} report a price with nothing
     * behind it.
     */
    private void discardIfEmpty(Side side, PriceLevel level) {
        if (level.isEmpty()) {
            ladderFor(side).remove(level.price());
            releaseLevel(level);
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

package io.github.thompgt.lob.api.dto;

import io.github.thompgt.lob.core.DepthVisitor;
import io.github.thompgt.lob.core.OrderBook;
import io.github.thompgt.lob.core.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable buffer for taking an L2 snapshot without allocating on the
 * matching thread.
 *
 * <p>The split matters more than it looks. Building the {@link DepthResponse}
 * inside the dispatcher's command meant a list, a capturing lambda and a record
 * per level were allocated <em>on the engine thread</em> — once per
 * {@code GET /book}, plus once per subscribed symbol every depth interval. That
 * is GC pressure planted in the one path whose tail latency this project claims
 * to have measured.
 *
 * <p>So the work is cut in two. {@link #capture} runs on the engine thread and
 * writes into arrays this object already owns; {@link #toResponse} runs on
 * whatever thread asked, and does all the allocating there. One instance is
 * reused by one thread — the request thread that made it, or the ticker thread
 * that owns it per symbol. It is not thread-safe and is not meant to be.
 */
public final class DepthSnapshot {

    private final int maxLevels;

    private final long[] bidPrices;
    private final long[] bidQuantities;
    private final int[] bidOrders;
    private int bidCount;

    private final long[] askPrices;
    private final long[] askQuantities;
    private final int[] askOrders;
    private int askCount;

    private long bestBid = OrderBook.NO_BID;
    private long bestAsk = OrderBook.NO_ASK;

    /** The visitors are held, not created per call: a lambda is an allocation. */
    private final DepthVisitor bidVisitor = this::addBid;
    private final DepthVisitor askVisitor = this::addAsk;

    public DepthSnapshot(int maxLevels) {
        this.maxLevels = maxLevels;
        this.bidPrices = new long[maxLevels];
        this.bidQuantities = new long[maxLevels];
        this.bidOrders = new int[maxLevels];
        this.askPrices = new long[maxLevels];
        this.askQuantities = new long[maxLevels];
        this.askOrders = new int[maxLevels];
    }

    /** Levels per side this buffer can hold. */
    public int maxLevels() {
        return maxLevels;
    }

    /**
     * Reads the book into this buffer. Runs on the engine thread and allocates
     * nothing.
     *
     * @param levels levels per side to take, capped at {@link #maxLevels()}
     */
    public void capture(OrderBook book, int levels) {
        bidCount = 0;
        askCount = 0;
        bestBid = book.bestBid();
        bestAsk = book.bestAsk();
        int wanted = Math.min(levels, maxLevels);
        book.snapshot(Side.BUY, wanted, bidVisitor);
        book.snapshot(Side.SELL, wanted, askVisitor);
    }

    /**
     * Projects what was captured into the wire DTO. Runs on the calling thread,
     * which is the whole point of the split.
     */
    public DepthResponse toResponse(String symbol) {
        Long spread = bestBid == OrderBook.NO_BID || bestAsk == OrderBook.NO_ASK
                ? null
                : bestAsk - bestBid;
        return new DepthResponse(
                symbol,
                // The sentinels mean "no side", which is null over the wire
                // rather than Long.MIN_VALUE leaking into a client's chart.
                bestBid == OrderBook.NO_BID ? null : bestBid,
                bestAsk == OrderBook.NO_ASK ? null : bestAsk,
                spread,
                levels(bidPrices, bidQuantities, bidOrders, bidCount),
                levels(askPrices, askQuantities, askOrders, askCount));
    }

    private void addBid(long price, long quantity, int orders) {
        if (bidCount == maxLevels) {
            return;
        }
        bidPrices[bidCount] = price;
        bidQuantities[bidCount] = quantity;
        bidOrders[bidCount] = orders;
        bidCount++;
    }

    private void addAsk(long price, long quantity, int orders) {
        if (askCount == maxLevels) {
            return;
        }
        askPrices[askCount] = price;
        askQuantities[askCount] = quantity;
        askOrders[askCount] = orders;
        askCount++;
    }

    private static List<DepthResponse.Level> levels(
            long[] prices, long[] quantities, int[] orders, int count) {
        List<DepthResponse.Level> levels = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            levels.add(new DepthResponse.Level(prices[i], quantities[i], orders[i]));
        }
        return levels;
    }
}

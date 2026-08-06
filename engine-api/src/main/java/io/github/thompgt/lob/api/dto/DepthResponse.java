package io.github.thompgt.lob.api.dto;

import io.github.thompgt.lob.core.OrderBook;
import io.github.thompgt.lob.core.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * An L2 depth snapshot: aggregated quantity per price, no individual orders.
 *
 * <p>Taken on the engine thread, so it is a consistent view of one instant
 * rather than a book read while it was being changed underneath.
 */
public record DepthResponse(
        String symbol,
        Long bestBid,
        Long bestAsk,
        Long spread,
        List<Level> bids,
        List<Level> asks) {

    /** One price level. {@code orders} is how many orders make up the quantity. */
    public record Level(long price, long quantity, int orders) {}

    public static DepthResponse from(OrderBook book, String symbol, int maxLevels) {
        long bid = book.bestBid();
        long ask = book.bestAsk();
        return new DepthResponse(
                symbol,
                // The sentinels mean "no side", which is null over the wire
                // rather than Long.MIN_VALUE leaking into a client's chart.
                bid == OrderBook.NO_BID ? null : bid,
                ask == OrderBook.NO_ASK ? null : ask,
                book.spread() < 0 ? null : book.spread(),
                levels(book, Side.BUY, maxLevels),
                levels(book, Side.SELL, maxLevels));
    }

    private static List<Level> levels(OrderBook book, Side side, int maxLevels) {
        List<Level> levels = new ArrayList<>();
        book.snapshot(side, maxLevels, (price, quantity, orders) ->
                levels.add(new Level(price, quantity, orders)));
        return levels;
    }
}

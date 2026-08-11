package io.github.thompgt.lob.api.dto;

import java.util.List;

/**
 * An L2 depth snapshot: aggregated quantity per price, no individual orders.
 *
 * <p>Taken on the engine thread, so it is a consistent view of one instant
 * rather than a book read while it was being changed underneath — but
 * <em>built</em> off it. See {@link DepthSnapshot}, which does the reading
 * there and the allocating here.
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
}

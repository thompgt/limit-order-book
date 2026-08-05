package io.github.thompgt.lob.core;

/**
 * Receives one callback per price level while an L2 depth snapshot is walked.
 *
 * <p>A callback rather than a returned collection: the book hands out the
 * numbers without building a list, so a snapshot allocates nothing beyond
 * whatever the caller chooses to keep. Levels arrive best-price-first.
 */
@FunctionalInterface
public interface DepthVisitor {

    /**
     * @param price       the level's price, in ticks
     * @param quantity    aggregated open quantity resting at that price
     * @param orderCount  how many orders make up that quantity
     */
    void level(long price, long quantity, int orderCount);
}

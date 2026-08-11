package io.github.thompgt.lob.api;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about this service that is a deployment choice rather than a
 * matching rule. Bound from the {@code lob.*} block of {@code application.yaml}.
 *
 * @param symbols           instruments to open a book for at startup. Orders
 *                          for anything else are rejected; the engine does not
 *                          implicitly open a market
 * @param queueCapacity     bound on the command queue. Reached means shedding
 *                          load, which is the intent — see
 *                          {@link io.github.thompgt.lob.api.engine.EngineDispatcher}
 * @param commandTimeoutMs  how long a request thread waits for the engine
 *                          before giving up on it
 * @param maxDepthLevels    levels per side in a depth snapshot. Caps the work a
 *                          single client request can ask of the engine thread
 * @param orderPoolCapacity orders preallocated at startup, so steady-state
 *                          trading allocates nothing
 * @param streamCapacity    bound on the market-data queue. Overflowing drops
 *                          events rather than stalling the engine — matching
 *                          outranks the feed
 * @param depthIntervalMs   how often a depth snapshot is pushed to subscribers
 * @param metricsIntervalMs how often the engine-derived gauges are sampled.
 *                          Read by {@code @Scheduled} on
 *                          {@link EngineMetrics#sample()}; declared here so the
 *                          knob is documented with the rest of them
 * @param maxPrice          highest acceptable limit price, in ticks
 * @param maxQuantity       largest acceptable order quantity. Both ceilings
 *                          exist so a level's cached total cannot be driven to
 *                          overflow — see
 *                          {@link io.github.thompgt.lob.core.MatchingEngine#DEFAULT_MAX_PRICE}
 */
@ConfigurationProperties(prefix = "lob")
public record LobProperties(
        List<String> symbols,
        int queueCapacity,
        long commandTimeoutMs,
        int maxDepthLevels,
        int orderPoolCapacity,
        int streamCapacity,
        long depthIntervalMs,
        long metricsIntervalMs,
        long maxPrice,
        long maxQuantity) {

    private static final List<String> DEFAULT_SYMBOLS = List.of("AAPL", "MSFT", "NVDA");

    /**
     * Applies defaults for anything unset. Spring passes 0 for absent numeric
     * properties, so "unset" and "nonsensical" get the same treatment — there is
     * no useful reading of a zero-capacity queue or a zero-order pool.
     */
    public LobProperties {
        symbols = symbols == null || symbols.isEmpty() ? DEFAULT_SYMBOLS : symbols;
        queueCapacity = queueCapacity <= 0 ? 8_192 : queueCapacity;
        commandTimeoutMs = commandTimeoutMs <= 0 ? 1_000L : commandTimeoutMs;
        maxDepthLevels = maxDepthLevels <= 0 ? 20 : maxDepthLevels;
        orderPoolCapacity = orderPoolCapacity <= 0 ? 1 << 16 : orderPoolCapacity;
        streamCapacity = streamCapacity <= 0 ? 1 << 14 : streamCapacity;
        depthIntervalMs = depthIntervalMs <= 0 ? 250L : depthIntervalMs;
        metricsIntervalMs = metricsIntervalMs <= 0 ? 1_000L : metricsIntervalMs;
        maxPrice = maxPrice <= 0
                ? io.github.thompgt.lob.core.MatchingEngine.DEFAULT_MAX_PRICE : maxPrice;
        maxQuantity = maxQuantity <= 0
                ? io.github.thompgt.lob.core.MatchingEngine.DEFAULT_MAX_QUANTITY : maxQuantity;
    }
}

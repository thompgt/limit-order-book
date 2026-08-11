package io.github.thompgt.lob.api;

import io.github.thompgt.lob.api.engine.EngineDispatcher;
import io.github.thompgt.lob.api.stream.MarketDataBroadcaster;
import io.github.thompgt.lob.core.MatchingEngine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the handful of numbers that actually say whether this service is
 * healthy under load.
 *
 * <p>All gauges, no timers on the command path: a timer per command would put
 * measurement overhead inside the thing being measured. Latency is measured
 * properly and separately by the benchmark harness, which paces against a fixed
 * schedule and is coordinated-omission safe. A Micrometer timer here would
 * suffer exactly the omission problem that harness exists to avoid — it only
 * records commands that got to run.
 *
 * <p>What these are for:
 *
 * <ul>
 *   <li>{@code lob.engine.queue.depth} — the first thing to move when the engine
 *       is the bottleneck. Sustained non-zero means requests are waiting.</li>
 *   <li>{@code lob.stream.dropped} — market data discarded to protect matching.
 *       Should be flat; a rising line means subscribers are being outrun.</li>
 *   <li>{@code lob.pool.allocations} — orders the pool had to allocate because
 *       it ran dry. Should be constant after startup; a rising line is the
 *       zero-allocation invariant failing in production rather than in a
 *       benchmark.</li>
 * </ul>
 *
 * <h2>Engine-derived gauges are sampled, not scraped</h2>
 *
 * Four of these read engine state, and the engine can only be read from its own
 * thread. Doing that per scrape meant a lambda, a command and a future handed to
 * the matching thread <em>four times</em> for every Prometheus poll — a
 * monitoring system quietly allocating inside the thing it monitors. Instead one
 * queued command per interval reads all four at once and publishes them to
 * volatile fields, and a scrape is a field read. The cost of the sampling
 * approach is that a gauge can be up to one interval stale, which for a value
 * scraped every 15s is not a cost at all.
 */
@Component
public class EngineMetrics implements MeterBinder {

    private final EngineDispatcher dispatcher;
    private final MarketDataBroadcaster broadcaster;
    private final LobProperties properties;

    /** Last sampled engine state. Written by the sampler, read by scrapes. */
    private volatile double liveOrders = Double.NaN;
    private volatile double trades = Double.NaN;
    private volatile double poolAllocations = Double.NaN;
    private volatile double poolAvailable = Double.NaN;

    /** Held, not created per sample: a capturing lambda is an allocation. */
    private final java.util.function.Function<MatchingEngine, EngineSample> sampler =
            engine -> new EngineSample(
                    engine.liveOrderCount(),
                    engine.tradeCount(),
                    engine.pool().allocations(),
                    engine.pool().available());

    public EngineMetrics(
            EngineDispatcher dispatcher,
            MarketDataBroadcaster broadcaster,
            LobProperties properties) {
        this.dispatcher = dispatcher;
        this.broadcaster = broadcaster;
        this.properties = properties;
    }

    /** The four engine-derived numbers, read in one pass on the engine thread. */
    private record EngineSample(long live, long trades, long allocations, long available) {}

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("lob.engine.queue.depth", dispatcher, EngineDispatcher::queueDepth)
                .description("commands waiting for the engine thread")
                .register(registry);

        Gauge.builder("lob.engine.queue.capacity", properties, LobProperties::queueCapacity)
                .description("bound on the command queue; reaching it sheds load")
                .register(registry);

        Gauge.builder("lob.stream.queue.depth", broadcaster, MarketDataBroadcaster::queueDepth)
                .description("market data events waiting to be published")
                .register(registry);

        Gauge.builder("lob.stream.dropped", broadcaster, MarketDataBroadcaster::droppedEvents)
                .description("events discarded because publishing fell behind")
                .register(registry);

        Gauge.builder("lob.stream.published", broadcaster, MarketDataBroadcaster::publishedEvents)
                .description("events written to subscribers")
                .register(registry);

        // These four come from engine state, which only the engine thread may
        // read. They are sampled on a clock rather than on scrape - see the
        // class note - so here they are ordinary field reads.
        Gauge.builder("lob.orders.live", this, m -> m.liveOrders)
                .description("orders currently resting across every book")
                .register(registry);

        Gauge.builder("lob.trades.total", this, m -> m.trades)
                .description("trades executed since startup")
                .register(registry);

        Gauge.builder("lob.pool.allocations", this, m -> m.poolAllocations)
                .description("orders the pool had to allocate; flat after startup is the goal")
                .register(registry);

        Gauge.builder("lob.pool.available", this, m -> m.poolAvailable)
                .description("pooled orders ready for reuse")
                .register(registry);

        // Publish something before the first tick, so a scrape arriving in the
        // first interval reads a number rather than NaN.
        sample();
    }

    /**
     * Reads the four engine-derived numbers in a single queued command.
     *
     * <p>A short timeout and no retry: sampling must never be able to hold up
     * trading, and a gap in a graph is cheaper than a stall. If the engine is
     * too busy to answer, the previous values stand rather than being wiped —
     * a stale point is more informative than a missing one, and the queue-depth
     * gauge (which needs no engine access) will already be showing why.
     */
    @Scheduled(fixedDelayString = "${lob.metrics-interval-ms:1000}")
    public void sample() {
        try {
            EngineSample sample = dispatcher.call(sampler, 100L);
            liveOrders = sample.live();
            trades = sample.trades();
            poolAllocations = sample.allocations();
            poolAvailable = sample.available();
        } catch (RuntimeException e) {
            // Engine busy or shutting down. Keep the last known values.
        }
    }
}

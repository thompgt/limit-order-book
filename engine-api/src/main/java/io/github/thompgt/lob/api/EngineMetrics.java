package io.github.thompgt.lob.api;

import io.github.thompgt.lob.api.engine.EngineDispatcher;
import io.github.thompgt.lob.api.stream.MarketDataBroadcaster;
import io.github.thompgt.lob.core.MatchingEngine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
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
 */
@Component
public class EngineMetrics implements MeterBinder {

    private final EngineDispatcher dispatcher;
    private final MarketDataBroadcaster broadcaster;
    private final LobProperties properties;

    public EngineMetrics(
            EngineDispatcher dispatcher,
            MarketDataBroadcaster broadcaster,
            LobProperties properties) {
        this.dispatcher = dispatcher;
        this.broadcaster = broadcaster;
        this.properties = properties;
    }

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

        // These read engine state, so they go through the queue like everything
        // else - a gauge scrape must not be the one thing that touches the
        // engine from a second thread.
        Gauge.builder("lob.orders.live", this, m -> m.onEngine(MatchingEngine::liveOrderCount))
                .description("orders currently resting across every book")
                .register(registry);

        Gauge.builder("lob.trades.total", this, m -> m.onEngine(e -> (double) e.tradeCount()))
                .description("trades executed since startup")
                .register(registry);

        Gauge.builder("lob.pool.allocations", this,
                        m -> m.onEngine(e -> (double) e.pool().allocations()))
                .description("orders the pool had to allocate; flat after startup is the goal")
                .register(registry);

        Gauge.builder("lob.pool.available", this,
                        m -> m.onEngine(e -> (double) e.pool().available()))
                .description("pooled orders ready for reuse")
                .register(registry);
    }

    /**
     * Reads a value off the engine thread, or reports NaN if the engine is too
     * busy to answer. A scrape must never be able to hold up trading, so it gets
     * a short timeout and no retry — a gap in a graph is cheaper than a stall.
     */
    private double onEngine(java.util.function.Function<MatchingEngine, Number> read) {
        try {
            return dispatcher.call(engine -> read.apply(engine).doubleValue(), 100L).doubleValue();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}

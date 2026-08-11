package io.github.thompgt.lob.api.stream;

import io.github.thompgt.lob.core.CancelReason;
import io.github.thompgt.lob.core.ExecutionSink;
import io.github.thompgt.lob.core.Order;
import io.github.thompgt.lob.core.RejectReason;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engine's event sink, and the seam between matching and the network.
 *
 * <p>Every method here runs <em>on the engine thread</em>, between two matching
 * decisions. So each does exactly two things: copy the flyweight
 * {@link Order} into an immutable {@link MarketDataEvent}, and hand it to a
 * queue. Serialising JSON or writing a socket here would put a client's network
 * behaviour inside the matching path, and the tail latency this project measures
 * would become a measure of the slowest subscriber.
 *
 * <h2>Events are dropped, not queued forever</h2>
 *
 * If the publisher thread falls behind, new events are discarded and counted.
 * That is a deliberate ranking: market data is a best-effort fan-out, matching
 * is not. The alternatives are blocking the engine on a socket or growing a
 * queue until the process dies, and both trade a correct book for a complete
 * feed. {@link #droppedEvents()} makes the trade visible rather than silent.
 */
public final class MarketDataBroadcaster implements ExecutionSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MarketDataBroadcaster.class);

    private static final long POLL_TIMEOUT_MS = 100L;

    private final MarketDataHandler handler;
    private final BlockingQueue<MarketDataEvent> queue;
    private final Thread thread;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile boolean running = true;

    public MarketDataBroadcaster(MarketDataHandler handler, int capacity) {
        this.handler = handler;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.thread = new Thread(this::publish, "market-data");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    // --- ExecutionSink. All of this runs on the engine thread. ---

    @Override
    public void accepted(Order order) {
        offer(MarketDataEvent.accepted(order));
    }

    @Override
    public void rejected(long orderId, int symbolId, RejectReason reason) {
        offer(MarketDataEvent.rejected(orderId, symbolId, reason));
    }

    @Override
    public void trade(
            long tradeId, int symbolId, long price, long quantity, Order aggressor, Order resting) {
        offer(MarketDataEvent.trade(tradeId, symbolId, price, quantity, aggressor, resting));
    }

    @Override
    public void filled(Order order) {
        offer(MarketDataEvent.filled(order));
    }

    @Override
    public void rested(Order order) {
        offer(MarketDataEvent.rested(order));
    }

    @Override
    public void canceled(Order order, CancelReason reason) {
        offer(MarketDataEvent.canceled(order, reason));
    }

    @Override
    public void replaced(
            Order order, long previousPrice, long previousQuantity, boolean priorityLost) {
        offer(MarketDataEvent.replaced(order, previousPrice, previousQuantity, priorityLost));
    }

    // --- Publisher thread ---

    private void offer(MarketDataEvent event) {
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    private void publish() {
        while (running) {
            try {
                MarketDataEvent event = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (event != null) {
                    handler.broadcast(event);
                    published.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // One bad subscriber must not end the feed for everyone else -
                // but a write that threw is not a write that happened. Counting
                // it as published made a broadcaster failing every single event
                // look perfectly healthy.
                failed.incrementAndGet();
                log.warn("dropping a market data event: publishing it failed", e);
            }
        }
        drainOnShutdown();
    }

    /**
     * Fails fast on whatever is still queued at shutdown. The events are gone
     * either way; counting them keeps the totals honest rather than leaving a
     * silent gap between what the engine emitted and what was published.
     */
    private void drainOnShutdown() {
        int remaining = queue.size();
        queue.clear();
        if (remaining > 0) {
            dropped.addAndGet(remaining);
            log.info("discarded {} market data events at shutdown", remaining);
        }
    }

    /** Events discarded because the publisher could not keep up. */
    public long droppedEvents() {
        return dropped.get();
    }

    public long publishedEvents() {
        return published.get();
    }

    /** Events whose write threw. A rising line is a subscriber, not the engine. */
    public long failedEvents() {
        return failed.get();
    }

    public int queueDepth() {
        return queue.size();
    }

    /**
     * Stops the publisher and accounts for whatever it did not get to.
     *
     * <p>Joins the thread rather than only interrupting it, as the dispatcher
     * does: returning while the publisher is still writing means shutdown races
     * the socket layer being torn down underneath it.
     */
    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainOnShutdown();
    }
}

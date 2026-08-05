package io.github.thompgt.lob.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link ExecutionSink} that keeps everything it is told, for tests.
 *
 * <p>The engine hands out live, recyclable {@link Order} objects, so this copies
 * the fields it wants during each callback rather than retaining references —
 * exactly what a real consumer has to do. Retaining them would produce tests
 * that pass against a broken engine, since a recycled order silently changes
 * underneath the holder.
 *
 * <p>Test code, so allocation here is free.
 */
final class RecordingSink implements ExecutionSink {

    /** One immutable copy of a trade, taken while the callback was running. */
    record Trade(
            long tradeId,
            int symbolId,
            long price,
            long quantity,
            long aggressorId,
            Side aggressorSide,
            long aggressorPrice,
            long restingId,
            Side restingSide,
            long restingSequence) {}

    private final List<Trade> trades = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<Long> accepted = new ArrayList<>();
    private final List<Long> rested = new ArrayList<>();
    private final List<Long> filled = new ArrayList<>();
    private final List<String> rejects = new ArrayList<>();

    /** orderId -> total quantity that order has traded. */
    private final Map<Long, Long> filledByOrder = new HashMap<>();

    /** orderId -> the side it was submitted on. */
    private final Map<Long, Side> sideByOrder = new HashMap<>();

    @Override
    public void accepted(Order order) {
        accepted.add(order.orderId());
        sideByOrder.put(order.orderId(), order.side());
        events.add("accepted:" + order.orderId());
    }

    @Override
    public void rejected(long orderId, int symbolId, RejectReason reason) {
        rejects.add(orderId + ":" + reason);
        events.add("rejected:" + orderId + ":" + reason);
    }

    @Override
    public void trade(
            long tradeId,
            int symbolId,
            long price,
            long quantity,
            Order aggressor,
            Order resting) {
        trades.add(new Trade(
                tradeId,
                symbolId,
                price,
                quantity,
                aggressor.orderId(),
                aggressor.side(),
                aggressor.price(),
                resting.orderId(),
                resting.side(),
                resting.sequence()));
        filledByOrder.merge(aggressor.orderId(), quantity, Long::sum);
        filledByOrder.merge(resting.orderId(), quantity, Long::sum);
        events.add("trade:" + quantity + "@" + price);
    }

    @Override
    public void filled(Order order) {
        filled.add(order.orderId());
        events.add("filled:" + order.orderId());
    }

    @Override
    public void rested(Order order) {
        rested.add(order.orderId());
        events.add("rested:" + order.orderId() + ":" + order.remainingQuantity()
                + "@" + order.price());
    }

    List<Trade> trades() {
        return trades;
    }

    List<String> events() {
        return events;
    }

    List<Long> accepted() {
        return accepted;
    }

    List<Long> rested() {
        return rested;
    }

    List<Long> filled() {
        return filled;
    }

    List<String> rejects() {
        return rejects;
    }

    long filledQuantity(long orderId) {
        return filledByOrder.getOrDefault(orderId, 0L);
    }

    /** Total traded quantity across every order submitted on one side. */
    long totalFilledOn(Side side) {
        long total = 0L;
        for (Map.Entry<Long, Long> entry : filledByOrder.entrySet()) {
            if (sideByOrder.get(entry.getKey()) == side) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /** Every trade price, in the order the trades happened. */
    List<Long> tradePrices() {
        return trades.stream().map(Trade::price).toList();
    }

    /** Every resting order that was hit, in the order it was hit. */
    List<Long> hitOrderIds() {
        return trades.stream().map(Trade::restingId).toList();
    }

    void clear() {
        trades.clear();
        events.clear();
        accepted.clear();
        rested.clear();
        filled.clear();
        rejects.clear();
        filledByOrder.clear();
        sideByOrder.clear();
    }
}

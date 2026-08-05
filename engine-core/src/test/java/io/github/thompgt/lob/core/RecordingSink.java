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

    /** One modify, before and after, captured while the callback was running. */
    record Replacement(
            long orderId,
            long previousPrice,
            long previousQuantity,
            long newPrice,
            long newQuantity,
            long newSequence,
            boolean priorityLost) {}

    private final List<Trade> trades = new ArrayList<>();
    private final List<Long> canceled = new ArrayList<>();
    private final List<Replacement> replacements = new ArrayList<>();
    private final Map<Long, CancelReason> cancelReasons = new HashMap<>();
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

    @Override
    public void canceled(Order order, CancelReason reason) {
        canceled.add(order.orderId());
        cancelReasons.put(order.orderId(), reason);
        events.add("canceled:" + order.orderId() + ":" + order.remainingQuantity()
                + ":" + reason);
    }

    @Override
    public void replaced(
            Order order, long previousPrice, long previousQuantity, boolean priorityLost) {
        replacements.add(new Replacement(
                order.orderId(),
                previousPrice,
                previousQuantity,
                order.price(),
                order.quantity(),
                order.sequence(),
                priorityLost));
        events.add("replaced:" + order.orderId()
                + ":" + previousQuantity + "@" + previousPrice
                + "->" + order.quantity() + "@" + order.price()
                + (priorityLost ? ":lost" : ":kept"));
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

    List<Long> canceled() {
        return canceled;
    }

    CancelReason cancelReason(long orderId) {
        return cancelReasons.get(orderId);
    }

    List<Replacement> replacements() {
        return replacements;
    }

    Replacement lastReplacement() {
        return replacements.get(replacements.size() - 1);
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
        canceled.clear();
        cancelReasons.clear();
        replacements.clear();
        filledByOrder.clear();
        sideByOrder.clear();
    }
}

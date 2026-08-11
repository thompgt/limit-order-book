package io.github.thompgt.lob.api.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.thompgt.lob.core.CancelReason;
import io.github.thompgt.lob.core.Order;
import io.github.thompgt.lob.core.RejectReason;

/**
 * One execution report, flattened into something that can outlive the engine
 * call that produced it.
 *
 * <p>The engine hands its sink a live {@link Order} that may be back in the pool
 * the instant the callback returns. Everything here is therefore read and copied
 * <em>during</em> that callback — the factories below are the only place the API
 * layer ever touches an engine-owned object, which is what keeps that rule
 * enforceable by reading one file.
 *
 * <p>One flat record rather than a type per event, because the consumer is a
 * browser: it switches on {@code type} and reads the fields that came with it.
 * Absent fields are omitted from the JSON rather than sent as nulls.
 *
 * <h2>{@code sequence} is the durable identity, not {@code orderId}</h2>
 *
 * An order id is unique only among <em>live</em> orders: the moment an order
 * fills or is cancelled the id is free again, and the engine will accept
 * another order under it. That is deliberate — an id is the client's handle for
 * cancel and modify, and there is nothing to address once the order is gone —
 * but it means a consumer stitching a tape together by {@code orderId} will
 * merge two unrelated orders. Every event that names an order therefore carries
 * the engine {@code sequence} that was assigned to it, including
 * {@link #restingSequence} for the passive side of a trade. Sequences are
 * monotonic and never reused, so a (symbol, sequence) pair identifies an order
 * for as long as the tape exists.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketDataEvent(
        String type,
        int symbolId,
        String symbol,
        Long orderId,
        String side,
        Long price,
        Long quantity,
        Long remainingQuantity,
        Long sequence,
        Long tradeId,
        Long restingOrderId,
        Long restingSequence,
        Long previousPrice,
        Long previousQuantity,
        Boolean priorityLost,
        String reason) {

    private static MarketDataEvent of(String type, Order order) {
        return new MarketDataEvent(
                type,
                order.symbolId(),
                null,
                order.orderId(),
                order.side().name(),
                order.isMarket() ? null : order.price(),
                order.quantity(),
                order.remainingQuantity(),
                order.sequence(),
                null, null, null, null, null, null, null);
    }

    static MarketDataEvent accepted(Order order) {
        return of("accepted", order);
    }

    static MarketDataEvent rested(Order order) {
        return of("rested", order);
    }

    static MarketDataEvent filled(Order order) {
        return of("filled", order);
    }

    static MarketDataEvent canceled(Order order, CancelReason reason) {
        MarketDataEvent base = of("canceled", order);
        return base.withReason(reason.name());
    }

    static MarketDataEvent rejected(long orderId, int symbolId, RejectReason reason) {
        return new MarketDataEvent(
                "rejected", symbolId, null, orderId,
                null, null, null, null, null, null, null, null, null, null, null,
                reason.name());
    }

    static MarketDataEvent replaced(
            Order order, long previousPrice, long previousQuantity, boolean priorityLost) {
        MarketDataEvent base = of("replaced", order);
        return new MarketDataEvent(
                base.type, base.symbolId, null, base.orderId, base.side, base.price,
                base.quantity, base.remainingQuantity, base.sequence, null, null, null,
                previousPrice, previousQuantity, priorityLost, null);
    }

    /**
     * A trade, seen from the aggressor's side. {@code price} is the resting
     * order's price — the order that was there first sets the terms.
     */
    static MarketDataEvent trade(
            long tradeId, int symbolId, long price, long quantity, Order aggressor, Order resting) {
        return new MarketDataEvent(
                "trade", symbolId, null, aggressor.orderId(), aggressor.side().name(),
                price, quantity, aggressor.remainingQuantity(), aggressor.sequence(),
                tradeId, resting.orderId(), resting.sequence(), null, null, null, null);
    }

    /** Fills in the display name once the symbol id can be translated. */
    MarketDataEvent withSymbol(String symbol) {
        return new MarketDataEvent(
                type, symbolId, symbol, orderId, side, price, quantity, remainingQuantity,
                sequence, tradeId, restingOrderId, restingSequence, previousPrice,
                previousQuantity, priorityLost, reason);
    }

    private MarketDataEvent withReason(String reason) {
        return new MarketDataEvent(
                type, symbolId, symbol, orderId, side, price, quantity, remainingQuantity,
                sequence, tradeId, restingOrderId, restingSequence, previousPrice,
                previousQuantity, priorityLost, reason);
    }
}

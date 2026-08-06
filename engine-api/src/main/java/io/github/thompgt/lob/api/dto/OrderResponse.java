package io.github.thompgt.lob.api.dto;

import io.github.thompgt.lob.core.SubmitResult;

/**
 * What became of a submitted or modified order.
 *
 * <p>Built from {@link SubmitResult} <em>on the engine thread</em>. That result
 * object is reused and overwritten by the very next command, so copying it here
 * is not defensive style, it is the only correct thing to do.
 */
public record OrderResponse(
        long orderId,
        String symbol,
        String status,
        String rejectReason,
        long filledQuantity,
        long restingQuantity,
        int tradeCount,
        long sequence) {

    public static OrderResponse from(SubmitResult result, String symbol) {
        return new OrderResponse(
                result.orderId(),
                symbol,
                result.status().name(),
                result.rejectReason() == null ? null : result.rejectReason().name(),
                result.filledQuantity(),
                result.restingQuantity(),
                result.tradeCount(),
                result.sequence());
    }
}

package io.github.thompgt.lob.api.dto;

import io.github.thompgt.lob.core.CancelResult;

/**
 * What became of a cancel. Copied off the engine's reused result object on the
 * engine thread, for the same reason as {@link OrderResponse}.
 *
 * @param canceledQuantity open units removed. Excludes anything the order had
 *                         already traded — that is done and cannot be undone
 */
public record CancelResponse(
        long orderId,
        String symbol,
        String status,
        String rejectReason,
        long canceledQuantity) {

    public static CancelResponse from(CancelResult result, String symbol) {
        return new CancelResponse(
                result.orderId(),
                symbol,
                result.status().name(),
                result.rejectReason() == null ? null : result.rejectReason().name(),
                result.canceledQuantity());
    }
}

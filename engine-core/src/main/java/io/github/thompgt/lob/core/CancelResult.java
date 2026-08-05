package io.github.thompgt.lob.core;

/**
 * The outcome of one {@link MatchingEngine#cancel(long)} call.
 *
 * <p><strong>Reused</strong>, for the same reason {@link SubmitResult} is: a
 * fresh object per cancel would allocate on a path required not to. Valid until
 * the next call into the engine.
 */
public final class CancelResult {

    private long orderId;
    private int symbolId;
    private OrderStatus status;
    private RejectReason rejectReason;
    private long canceledQuantity;

    CancelResult reset(long orderId) {
        this.orderId = orderId;
        this.symbolId = MatchingEngine.NO_SYMBOL;
        this.status = null;
        this.rejectReason = null;
        this.canceledQuantity = 0L;
        return this;
    }

    CancelResult rejected(RejectReason reason) {
        this.status = OrderStatus.REJECTED;
        this.rejectReason = reason;
        return this;
    }

    CancelResult canceled(int symbolId, long canceledQuantity) {
        this.symbolId = symbolId;
        this.status = OrderStatus.CANCELED;
        this.canceledQuantity = canceledQuantity;
        return this;
    }

    public long orderId() {
        return orderId;
    }

    /** {@link MatchingEngine#NO_SYMBOL} when the order was not found. */
    public int symbolId() {
        return symbolId;
    }

    public OrderStatus status() {
        return status;
    }

    /** Non-null only when {@link #status()} is {@link OrderStatus#REJECTED}. */
    public RejectReason rejectReason() {
        return rejectReason;
    }

    /**
     * Open units taken off the book. Excludes anything the order had already
     * traded — that quantity is gone and cannot be cancelled.
     */
    public long canceledQuantity() {
        return canceledQuantity;
    }

    public boolean isRejected() {
        return status == OrderStatus.REJECTED;
    }

    @Override
    public String toString() {
        // Diagnostics only — never called on the hot path.
        return "CancelResult{id=" + orderId
                + ", " + status
                + (rejectReason == null ? "" : "(" + rejectReason + ")")
                + ", qty=" + canceledQuantity
                + '}';
    }
}

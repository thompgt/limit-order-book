package io.github.thompgt.lob.core;

/**
 * The outcome of one {@link MatchingEngine#submit} call.
 *
 * <p><strong>Reused.</strong> The engine hands back the same instance every
 * time, overwritten in place, because returning a fresh result per order would
 * allocate on the hot path. It is valid until the next call into the engine —
 * read it, or copy what you need, before submitting again.
 */
public final class SubmitResult {

    private long orderId;
    private int symbolId;
    private OrderStatus status;
    private RejectReason rejectReason;
    private long filledQuantity;
    private long restingQuantity;
    private int tradeCount;
    private long sequence;

    SubmitResult reset(long orderId, int symbolId) {
        this.orderId = orderId;
        this.symbolId = symbolId;
        this.status = null;
        this.rejectReason = null;
        this.filledQuantity = 0L;
        this.restingQuantity = 0L;
        this.tradeCount = 0;
        this.sequence = 0L;
        return this;
    }

    SubmitResult rejected(RejectReason reason) {
        this.status = OrderStatus.REJECTED;
        this.rejectReason = reason;
        return this;
    }

    void recordTrade(long quantity) {
        this.filledQuantity += quantity;
        this.tradeCount++;
    }

    void complete(OrderStatus status, long restingQuantity, long sequence) {
        this.status = status;
        this.restingQuantity = restingQuantity;
        this.sequence = sequence;
    }

    public long orderId() {
        return orderId;
    }

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

    /** Units traded by this order across every level it swept. */
    public long filledQuantity() {
        return filledQuantity;
    }

    /** Units left on the book. Zero for a fill, a reject, or a killed order. */
    public long restingQuantity() {
        return restingQuantity;
    }

    /** How many separate resting orders this one traded against. */
    public int tradeCount() {
        return tradeCount;
    }

    /** The arrival sequence the engine assigned. Zero for a reject. */
    public long sequence() {
        return sequence;
    }

    public boolean isRejected() {
        return status == OrderStatus.REJECTED;
    }

    @Override
    public String toString() {
        // Diagnostics only — never called on the hot path.
        return "SubmitResult{id=" + orderId
                + ", " + status
                + (rejectReason == null ? "" : "(" + rejectReason + ")")
                + ", filled=" + filledQuantity
                + ", resting=" + restingQuantity
                + ", trades=" + tradeCount
                + '}';
    }
}

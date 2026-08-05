package io.github.thompgt.lob.core;

/**
 * Every resting order at one price, held in arrival order.
 *
 * <p>This is the "time" half of price-time priority. {@link #head} is the next
 * order to be filled; new orders join at {@link #tail}. Matching always walks
 * forward from the head, so an order that has been waiting longer is always
 * served first.
 *
 * <p>The queue is an intrusive doubly-linked list built from the {@code prev}/
 * {@code next} fields on {@link Order} itself. That buys two things the hot
 * path cares about: adding and removing allocate nothing, and
 * {@link #remove(Order)} is O(1) given the order — no scan to find it.
 *
 * <p>{@link #totalQuantity()} is maintained incrementally on every mutation so
 * an L2 depth snapshot costs O(1) per level rather than a walk of the queue.
 */
public final class PriceLevel {

    private long price;

    private Order head;
    private Order tail;

    /** Sum of {@code remainingQuantity} over every order in the queue. */
    private long totalQuantity;

    private int orderCount;

    /** Re-initialises this level for a price. Levels are pooled like orders. */
    public PriceLevel reset(long price) {
        this.price = price;
        this.head = null;
        this.tail = null;
        this.totalQuantity = 0L;
        this.orderCount = 0;
        return this;
    }

    /**
     * Appends an order at the back of the queue — the only way an order may
     * enter a level, since joining anywhere else would jump the time-priority
     * queue.
     */
    public void add(Order order) {
        order.level = this;
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        totalQuantity += order.remainingQuantity;
        orderCount++;
    }

    /**
     * Unlinks an order from this level in O(1). The order must currently rest
     * here; passing an order that does not would corrupt the queue, so this is
     * only ever reached via the order's own {@code level} pointer.
     */
    public void remove(Order order) {
        Order prev = order.prev;
        Order next = order.next;

        if (prev == null) {
            head = next;
        } else {
            prev.next = next;
        }
        if (next == null) {
            tail = prev;
        } else {
            next.prev = prev;
        }

        order.prev = null;
        order.next = null;
        order.level = null;
        totalQuantity -= order.remainingQuantity;
        orderCount--;
    }

    /**
     * Records a fill against an order resting here, keeping the cached total in
     * step. The order keeps its place in the queue: a partial fill does not
     * cost time priority.
     */
    public void reduce(Order order, long quantity) {
        order.remainingQuantity -= quantity;
        totalQuantity -= quantity;
    }

    /** The next order to be filled, or {@code null} if the level is empty. */
    public Order head() {
        return head;
    }

    /** The most recently added order, or {@code null} if the level is empty. */
    public Order tail() {
        return tail;
    }

    public long price() {
        return price;
    }

    /** Aggregated open quantity — the number shown in an L2 depth snapshot. */
    public long totalQuantity() {
        return totalQuantity;
    }

    public int orderCount() {
        return orderCount;
    }

    public boolean isEmpty() {
        return head == null;
    }

    @Override
    public String toString() {
        // Diagnostics only — never called on the hot path.
        return "PriceLevel{price=" + price
                + ", qty=" + totalQuantity
                + ", orders=" + orderCount
                + '}';
    }
}

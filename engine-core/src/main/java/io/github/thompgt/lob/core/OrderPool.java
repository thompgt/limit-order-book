package io.github.thompgt.lob.core;

/**
 * A free list of {@link Order} objects.
 *
 * <p>Orders are the highest-churn object in the engine: one per message, and
 * most of them are gone within microseconds. Allocating them per message is
 * exactly the pattern that turns a clean p99 into a bad p99.9, because the
 * garbage they make is what eventually stops the world. Recycling them keeps
 * the steady state at zero allocation.
 *
 * <p>An order is released only once it is off the book and every execution
 * report mentioning it has been delivered. Releasing early would hand a live
 * order back out from underneath a sink still reading it — see the ownership
 * note on {@link ExecutionSink}.
 *
 * <p>The pool is bounded. Past its capacity, released orders are simply dropped
 * for the collector, which is the right trade: an unbounded pool would grow to
 * the high-water mark of a burst and hold that memory forever.
 */
public final class OrderPool {

    private static final int DEFAULT_CAPACITY = 1 << 14;

    private final Order[] free;
    private int size;

    /** Tracked so a benchmark can prove the steady state stops allocating. */
    private long allocations;

    public OrderPool() {
        this(DEFAULT_CAPACITY);
    }

    public OrderPool(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.free = new Order[capacity];
    }

    /**
     * Fills the pool up front so the first orders through the engine do not pay
     * for allocation. Benchmarks call this during warmup.
     */
    public void preallocate(int count) {
        int target = Math.min(count, free.length);
        while (size < target) {
            free[size++] = new Order();
            allocations++;
        }
    }

    /**
     * Hands out an order, recycled if one is available. The caller must
     * {@code reset} it before use; the fields still hold the last occupant's
     * values.
     */
    public Order acquire() {
        if (size > 0) {
            return free[--size];
        }
        allocations++;
        return new Order();
    }

    /** Returns an order to the pool, scrubbing its references first. */
    public void release(Order order) {
        order.clear();
        if (size < free.length) {
            free[size++] = order;
        }
        // Pool full: let this one be collected rather than growing without bound.
    }

    /** How many orders are sitting idle and ready to be handed out. */
    public int available() {
        return size;
    }

    public int capacity() {
        return free.length;
    }

    /**
     * Total {@code new Order()} calls this pool has made. A steady-state
     * workload should leave this flat.
     */
    public long allocations() {
        return allocations;
    }

    @Override
    public String toString() {
        // Diagnostics only — never called on the hot path.
        return "OrderPool{available=" + size
                + '/' + free.length
                + ", allocated=" + allocations
                + '}';
    }
}

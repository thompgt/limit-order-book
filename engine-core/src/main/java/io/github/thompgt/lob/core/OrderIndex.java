package io.github.thompgt.lob.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Maps {@code orderId} to the live {@link Order}, across every symbol.
 *
 * <p>Cancel and modify arrive carrying only an order id, so this lookup sits
 * directly on the hot path. It is backed by fastutil's open-addressing map
 * rather than {@code HashMap<Long, Order>} for one reason: a {@code long} key
 * stays a {@code long}. A JDK map would box every id into a {@code Long} on
 * both put and get, allocating on a path that is required not to allocate.
 *
 * <p>Sized up front and never trimmed, so a steady-state workload never
 * rehashes. Not thread-safe; the engine is single-threaded by design.
 */
public final class OrderIndex {

    /**
     * Load factor below fastutil's 0.75 default. Open addressing degrades
     * sharply as a table fills, and the tail latency of a probe sequence is
     * exactly what this project measures — trading some memory for shorter
     * probes is the right side of that bargain.
     */
    private static final float LOAD_FACTOR = 0.5f;

    private final Long2ObjectOpenHashMap<Order> byOrderId;

    public OrderIndex() {
        this(1 << 16);
    }

    /**
     * @param expectedOrders capacity to preallocate, so a run at expected size
     *                       never pays for a rehash mid-flight
     */
    public OrderIndex(int expectedOrders) {
        this.byOrderId = new Long2ObjectOpenHashMap<>(expectedOrders, LOAD_FACTOR);
    }

    /**
     * Registers a live order.
     *
     * @return the order previously registered under this id, or {@code null} if
     *         the id was free. A non-null result means a duplicate id — the
     *         caller is expected to have rejected it before reaching here.
     */
    public Order put(Order order) {
        return byOrderId.put(order.orderId, order);
    }

    /** @return the live order, or {@code null} if unknown or already gone */
    public Order get(long orderId) {
        return byOrderId.get(orderId);
    }

    /**
     * Deregisters an order that is no longer live — filled, cancelled or
     * rejected.
     *
     * @return the removed order, or {@code null} if the id was unknown
     */
    public Order remove(long orderId) {
        return byOrderId.remove(orderId);
    }

    public boolean contains(long orderId) {
        return byOrderId.containsKey(orderId);
    }

    public int size() {
        return byOrderId.size();
    }

    public boolean isEmpty() {
        return byOrderId.isEmpty();
    }

    /** Drops every entry, keeping the allocated table for reuse. */
    public void clear() {
        byOrderId.clear();
    }
}

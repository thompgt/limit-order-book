package io.github.thompgt.lob.api.dto;

/**
 * A request to change a resting order.
 *
 * @param price    the new limit price in ticks
 * @param quantity the new <em>total</em> order quantity, as originally
 *                 submitted — not the new remainder. A value at or below what
 *                 the order has already traded is rejected rather than quietly
 *                 read as a cancel
 */
public record ModifyOrderRequest(long price, long quantity) {}

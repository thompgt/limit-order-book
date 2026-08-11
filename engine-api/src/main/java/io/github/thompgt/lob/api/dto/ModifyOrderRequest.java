package io.github.thompgt.lob.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * A request to change a resting order.
 *
 * <p>Both fields are required and boxed so that they can be. As primitives, an
 * omitted field arrived as 0 and the engine answered
 * {@code NON_POSITIVE_QUANTITY} — a true statement about a number the client
 * never sent, and a confusing one to debug. Missing is now missing.
 *
 * @param price    the new limit price in ticks
 * @param quantity the new <em>total</em> order quantity, as originally
 *                 submitted — not the new remainder. A value at or below what
 *                 the order has already traded is rejected rather than quietly
 *                 read as a cancel
 */
public record ModifyOrderRequest(
        @NotNull(message = "price is required") Long price,
        @NotNull(message = "quantity is required") Long quantity) {}

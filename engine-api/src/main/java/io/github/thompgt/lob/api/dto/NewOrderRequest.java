package io.github.thompgt.lob.api.dto;

import io.github.thompgt.lob.core.Side;
import io.github.thompgt.lob.core.TimeInForce;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * A request to submit an order.
 *
 * <p>What can be said declaratively is said here, not in an accessor. A
 * constraint that lives in a getter only runs if some handler happens to call
 * that getter, so which checks a request actually gets depends on the code path
 * it takes — and adding a handler quietly removes validation. The enum parsing
 * below still throws, because "one of these three words" is not a constraint
 * Jakarta expresses without a custom validator.
 *
 * <p>The division of labour: the boundary declares what must be <em>present</em>
 * and well formed, the engine decides what is <em>tradeable</em>. So price and
 * quantity are deliberately not annotated with bounds — the engine owns those,
 * and it answers with a {@code rejectReason} a client can switch on rather than
 * a validation message it has to read.
 *
 * @param symbol       instrument name, e.g. {@code AAPL}
 * @param side         {@code BUY} or {@code SELL}
 * @param type         {@code LIMIT} or {@code MARKET}; defaults to {@code LIMIT}
 * @param timeInForce  {@code DAY}, {@code IOC} or {@code FOK}; defaults to {@code DAY}
 * @param price        limit price in ticks; ignored for a market order
 * @param quantity     units to trade
 * @param orderId      optional client-supplied id. Omit and the service assigns
 *                     one — ids must be unique among live orders, so letting the
 *                     client choose is what makes a duplicate-id reject possible
 * @param accountId    optional participant identity. Only orders that carry one
 *                     can be kept from trading with each other — see
 *                     {@link io.github.thompgt.lob.core.SelfTradePolicy}
 */
public record NewOrderRequest(
        @NotBlank(message = "symbol is required")
        String symbol,

        @NotBlank(message = "side is required")
        String side,

        String type,
        String timeInForce,

        Long price,
        long quantity,

        @Positive(message = "orderId must be positive")
        Long orderId,

        @Positive(message = "accountId must be positive")
        Long accountId) {

    public enum OrderType { LIMIT, MARKET }

    /** @return the account, or {@code NO_ACCOUNT} when the client is anonymous */
    public long accountOrNone() {
        return accountId == null ? io.github.thompgt.lob.core.Order.NO_ACCOUNT : accountId;
    }

    public Side parsedSide() {
        return parseEnum(Side.class, side, "side", null);
    }

    public OrderType parsedType() {
        return parseEnum(OrderType.class, type, "type", OrderType.LIMIT);
    }

    public TimeInForce parsedTimeInForce() {
        return parseEnum(TimeInForce.class, timeInForce, "timeInForce", TimeInForce.DAY);
    }

    /**
     * The limit price for a LIMIT order.
     *
     * <p>There is no default. A missing price used to become tick 0, which the
     * engine happily rested a bid at; a price is the one thing a limit order
     * cannot be submitted without, so its absence is a bad request.
     *
     * @throws BadRequestException if no price was supplied
     */
    public long limitPrice() {
        if (price == null) {
            throw new BadRequestException("price is required for a LIMIT order");
        }
        return price;
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, String raw, String field, E fallback) {
        if (raw == null || raw.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            throw new BadRequestException(field + " is required");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "unknown " + field + ": '" + raw + "'; expected one of "
                            + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }
}

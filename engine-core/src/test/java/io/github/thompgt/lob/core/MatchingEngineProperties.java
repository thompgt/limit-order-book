package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Invariants that must hold after <em>any</em> sequence of orders, not just the
 * ones someone thought to write a test for.
 *
 * <p>Unit tests pin the behaviour you can name. These pin the behaviour you
 * cannot: conservation of quantity, a book that never ends up crossed, and a
 * matching order that never violates price-time priority — checked against a
 * few hundred thousand randomly generated sequences per run, with jqwik
 * shrinking any counterexample down to a minimal one.
 *
 * <p>Prices are drawn from a narrow band so that orders actually cross. A wide
 * random price range would produce a book that almost never trades, and the
 * properties would pass while testing nothing.
 */
class MatchingEngineProperties {

    private static final int SYMBOL = 1;

    record OrderSpec(Side side, long price, long quantity) {}

    /** One executed sequence, plus everything needed to check it afterwards. */
    private record Run(MatchingEngine engine, OrderBook book, RecordingSink sink,
                       List<Long> orderIds, List<OrderSpec> specs) {}

    @Provide
    Arbitrary<List<OrderSpec>> orderSequences() {
        Arbitrary<Side> sides = Arbitraries.of(Side.BUY, Side.SELL);
        Arbitrary<Long> prices = Arbitraries.longs().between(95L, 105L);
        Arbitrary<Long> quantities = Arbitraries.longs().between(1L, 40L);
        return Combinators.combine(sides, prices, quantities)
                .as(OrderSpec::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(120);
    }

    /**
     * The same shape, but with quantities drawn around and past the engine's
     * ceiling — including {@link Long#MAX_VALUE}, the value that would wrap a
     * level's cached total negative if it were ever allowed onto the book.
     */
    @Provide
    Arbitrary<List<OrderSpec>> extremeQuantitySequences() {
        Arbitrary<Side> sides = Arbitraries.of(Side.BUY, Side.SELL);
        Arbitrary<Long> prices = Arbitraries.longs().between(95L, 105L);
        Arbitrary<Long> quantities = Arbitraries.oneOf(
                Arbitraries.longs().between(1L, 40L),
                Arbitraries.longs().between(
                        MatchingEngine.DEFAULT_MAX_QUANTITY - 10L,
                        MatchingEngine.DEFAULT_MAX_QUANTITY + 10L),
                Arbitraries.longs().between(Long.MAX_VALUE - 10L, Long.MAX_VALUE));
        return Combinators.combine(sides, prices, quantities)
                .as(OrderSpec::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(120);
    }

    private static Run execute(List<OrderSpec> specs) {
        RecordingSink sink = new RecordingSink();
        MatchingEngine engine = new MatchingEngine(sink);
        OrderBook book = engine.registerSymbol(SYMBOL);
        List<Long> ids = new ArrayList<>(specs.size());

        long id = 1L;
        for (OrderSpec spec : specs) {
            SubmitResult result = engine.submit(
                    id, SYMBOL, spec.side(), TimeInForce.DAY, spec.price(), spec.quantity());
            // Ids are unique and quantities positive, so nothing may be refused.
            assertThat(result.isRejected()).isFalse();
            ids.add(id);
            id++;
        }
        return new Run(engine, book, sink, ids, specs);
    }

    /** Live orders on one side, keyed by price. */
    private static Map<Long, Long> restingByPrice(Run run, Side side) {
        Map<Long, Long> byPrice = new HashMap<>();
        for (long id : run.orderIds()) {
            Order order = run.engine().order(id);
            if (order != null && order.side() == side) {
                byPrice.merge(order.price(), order.remainingQuantity(), Long::sum);
            }
        }
        return byPrice;
    }

    private static long submitted(List<OrderSpec> specs, Side side) {
        long total = 0L;
        for (OrderSpec spec : specs) {
            if (spec.side() == side) {
                total += spec.quantity();
            }
        }
        return total;
    }

    // -------------------------------------------------------- conservation

    @Property(tries = 500)
    void everyUnitBoughtIsAUnitSold(@ForAll("orderSequences") List<OrderSpec> specs) {
        Run run = execute(specs);

        assertThat(run.sink().totalFilledOn(Side.BUY))
                .isEqualTo(run.sink().totalFilledOn(Side.SELL));
    }

    @Property(tries = 500)
    void whatWasSubmittedEitherTradedOrIsStillOnTheBook(
            @ForAll("orderSequences") List<OrderSpec> specs) {
        Run run = execute(specs);

        for (Side side : Side.values()) {
            long resting = restingByPrice(run, side).values().stream()
                    .mapToLong(Long::longValue).sum();
            assertThat(run.sink().totalFilledOn(side) + resting)
                    .as("side %s", side)
                    .isEqualTo(submitted(specs, side));
        }
    }

    @Property(tries = 500)
    void noOrderEverTradesMoreThanItsOwnQuantity(
            @ForAll("orderSequences") List<OrderSpec> specs) {
        Run run = execute(specs);

        for (int i = 0; i < specs.size(); i++) {
            long id = run.orderIds().get(i);
            assertThat(run.sink().filledQuantity(id))
                    .as("order %d", id)
                    .isLessThanOrEqualTo(specs.get(i).quantity());
        }
    }

    // ------------------------------------------------------ book integrity

    @Property(tries = 500)
    void theBookIsNeverLeftCrossed(@ForAll("orderSequences") List<OrderSpec> specs) {
        Run run = execute(specs);

        assertThat(run.book().isCrossed())
                .as("best bid %d vs best ask %d",
                        run.book().bestBid(), run.book().bestAsk())
                .isFalse();
    }

    @Property(tries = 500)
    void depthAlwaysMatchesTheOrdersBehindIt(
            @ForAll("orderSequences") List<OrderSpec> specs) {
        Run run = execute(specs);

        for (Side side : Side.values()) {
            Map<Long, Long> expected = restingByPrice(run, side);
            Map<Long, Long> reported = new HashMap<>();
            run.book().snapshot(side, Integer.MAX_VALUE,
                    (price, qty, count) -> reported.put(price, qty));

            assertThat(reported).as("side %s", side).isEqualTo(expected);
            assertThat(run.book().levelCount(side)).isEqualTo(expected.size());
        }
    }

    @Property(tries = 500)
    void cachedDepthNeverGoesNegativeHoweverLargeTheOrdersAre(
            @ForAll("extremeQuantitySequences") List<OrderSpec> specs) {
        MatchingEngine engine = new MatchingEngine(new RecordingSink());
        OrderBook book = engine.registerSymbol(SYMBOL);

        long id = 1L;
        for (OrderSpec spec : specs) {
            SubmitResult result = engine.submit(
                    id++, SYMBOL, spec.side(), TimeInForce.DAY, spec.price(), spec.quantity());
            assertThat(result.isRejected())
                    .as("quantity %d", spec.quantity())
                    .isEqualTo(spec.quantity() > engine.maxQuantity());
        }

        for (Side side : Side.values()) {
            book.snapshot(side, Integer.MAX_VALUE, (price, qty, count) ->
                    assertThat(qty).as("depth on %s at %d", side, price).isNotNegative());
            assertThat(book.fillableQuantity(side, side.marketPrice(), Long.MAX_VALUE))
                    .as("fillable on %s", side)
                    .isNotNegative();
        }
    }

    @Property(tries = 500)
    void nothingEmptyIsLeftRestingOnTheBook(
            @ForAll("orderSequences") List<OrderSpec> specs) {
        Run run = execute(specs);

        for (long id : run.orderIds()) {
            Order order = run.engine().order(id);
            if (order != null) {
                assertThat(order.remainingQuantity()).as("order %d", id).isPositive();
                assertThat(order.isResting()).as("order %d", id).isTrue();
            }
        }
        // The index holds exactly the live orders, no more and no fewer.
        long live = run.orderIds().stream().filter(id -> run.engine().order(id) != null).count();
        assertThat(run.engine().liveOrderCount()).isEqualTo((int) live);
    }

    // ----------------------------------------------------- price priority

    @Property(tries = 500)
    void noTradeEverBreachesTheAggressorsLimit(
            @ForAll("orderSequences") List<OrderSpec> specs) {
        Run run = execute(specs);

        for (RecordingSink.Trade trade : run.sink().trades()) {
            assertThat(trade.aggressorSide().crosses(trade.aggressorPrice(), trade.price()))
                    .as("trade %d at %d for a %s limit of %d",
                            trade.tradeId(), trade.price(),
                            trade.aggressorSide(), trade.aggressorPrice())
                    .isTrue();
            assertThat(trade.quantity()).isPositive();
        }
    }

    @Property(tries = 500)
    void aSweepNeverSkipsABetterPrice(@ForAll("orderSequences") List<OrderSpec> specs) {
        Run run = execute(specs);

        // Within one aggressive order, each successive trade must be at a price
        // no better than the last: a buyer works up the offers, a seller works
        // down the bids. A price out of order means a level was jumped.
        long currentAggressor = Long.MIN_VALUE;
        long previousPrice = 0L;
        for (RecordingSink.Trade trade : run.sink().trades()) {
            if (trade.aggressorId() != currentAggressor) {
                currentAggressor = trade.aggressorId();
                previousPrice = trade.price();
                continue;
            }
            if (trade.aggressorSide() == Side.BUY) {
                assertThat(trade.price()).isGreaterThanOrEqualTo(previousPrice);
            } else {
                assertThat(trade.price()).isLessThanOrEqualTo(previousPrice);
            }
            previousPrice = trade.price();
        }
    }

    // ------------------------------------------------------ time priority

    @Property(tries = 500)
    void withinAPriceTheOldestRestingOrderIsAlwaysHitFirst(
            @ForAll("orderSequences") List<OrderSpec> specs) {
        Run run = execute(specs);

        // Consecutive trades by one aggressor at one price walk that level's
        // queue, so the resting sequence numbers must strictly increase. A
        // decrease would mean a newer order jumped an older one.
        long currentAggressor = Long.MIN_VALUE;
        long currentPrice = Long.MIN_VALUE;
        long previousSequence = Long.MIN_VALUE;
        for (RecordingSink.Trade trade : run.sink().trades()) {
            boolean sameQueue =
                    trade.aggressorId() == currentAggressor && trade.price() == currentPrice;
            if (sameQueue) {
                assertThat(trade.restingSequence())
                        .as("resting order %d jumped the queue", trade.restingId())
                        .isGreaterThan(previousSequence);
            }
            currentAggressor = trade.aggressorId();
            currentPrice = trade.price();
            previousSequence = trade.restingSequence();
        }
    }

    @Property(tries = 500)
    void anAggressorAlwaysTradesAgainstTheOppositeSide(
            @ForAll("orderSequences") List<OrderSpec> specs) {
        Run run = execute(specs);

        for (RecordingSink.Trade trade : run.sink().trades()) {
            assertThat(trade.restingSide()).isEqualTo(trade.aggressorSide().opposite());
            assertThat(trade.restingId()).isNotEqualTo(trade.aggressorId());
            assertThat(trade.restingSequence()).isLessThan((long) run.specs().size() + 1);
        }
    }

    // ---------------------------------------------------------- determinism

    @Property(tries = 200)
    void theSameSequenceAlwaysProducesTheSameExecutionLog(
            @ForAll("orderSequences") List<OrderSpec> specs) {
        // Deterministic replay is the cheapest regression detector there is: a
        // priority bug that only shows up on one ordering still changes the log.
        Run first = execute(specs);
        Run second = execute(specs);

        assertThat(second.sink().events()).isEqualTo(first.sink().events());
        assertThat(second.engine().tradeCount()).isEqualTo(first.engine().tradeCount());
    }
}

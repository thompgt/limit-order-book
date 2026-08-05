package io.github.thompgt.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Invariants over a <em>mixed</em> command stream: limits, markets, IOCs, FOKs,
 * cancels and modifies, interleaved at random.
 *
 * <p>{@link MatchingEngineProperties} covers submit-only sequences. Everything
 * added in phase 3 mutates orders that already exist, and that is where an
 * engine breaks in ways no single unit test is shaped to catch: a modify that
 * forgets to fix a level's cached depth, a cancel that unlinks the wrong
 * neighbour, an order recycled while the book still points at it. Those show up
 * as a book that disagrees with itself a few hundred commands later, which is
 * exactly what these properties look for.
 *
 * <p>Cancels and modifies deliberately aim at ids that may already be gone.
 * A stale id is the normal case in a real feed — the fill and the cancel simply
 * crossed — so a rejected command is a valid outcome here, not a failed run.
 */
class LifecycleProperties {

    private static final int SYMBOL = 1;

    enum Kind { LIMIT_DAY, LIMIT_IOC, LIMIT_FOK, MARKET, CANCEL, MODIFY }

    /** {@code target} indexes previously issued ids, wrapped into range. */
    record Command(Kind kind, Side side, long price, long quantity, int target) {}

    private record Run(MatchingEngine engine, OrderBook book, RecordingSink sink,
                       OrderPool pool, List<Long> issued, Map<Long, Long> largestQuantity) {}

    @Provide
    Arbitrary<List<Command>> commandStreams() {
        Arbitrary<Kind> kinds = Arbitraries.frequency(
                Tuple.of(8, Kind.LIMIT_DAY),
                Tuple.of(2, Kind.LIMIT_IOC),
                Tuple.of(2, Kind.LIMIT_FOK),
                Tuple.of(2, Kind.MARKET),
                Tuple.of(4, Kind.CANCEL),
                Tuple.of(5, Kind.MODIFY));
        Arbitrary<Side> sides = Arbitraries.of(Side.BUY, Side.SELL);
        // A narrow band so orders actually cross. Spread them wide and the book
        // never trades, and every property below passes while testing nothing.
        Arbitrary<Long> prices = Arbitraries.longs().between(95L, 105L);
        Arbitrary<Long> quantities = Arbitraries.longs().between(1L, 40L);
        Arbitrary<Integer> targets = Arbitraries.integers().between(0, 255);

        return Combinators.combine(kinds, sides, prices, quantities, targets)
                .as(Command::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(150);
    }

    private static Run execute(List<Command> commands) {
        RecordingSink sink = new RecordingSink();
        OrderPool pool = new OrderPool(1 << 10);
        MatchingEngine engine = new MatchingEngine(sink, pool);
        OrderBook book = engine.registerSymbol(SYMBOL);

        List<Long> issued = new ArrayList<>();
        Map<Long, Long> largestQuantity = new HashMap<>();
        long nextId = 1L;

        for (Command command : commands) {
            switch (command.kind()) {
                case LIMIT_DAY, LIMIT_IOC, LIMIT_FOK -> {
                    TimeInForce tif = switch (command.kind()) {
                        case LIMIT_IOC -> TimeInForce.IOC;
                        case LIMIT_FOK -> TimeInForce.FOK;
                        default -> TimeInForce.DAY;
                    };
                    engine.submit(nextId, SYMBOL, command.side(), tif,
                            command.price(), command.quantity());
                    largestQuantity.merge(nextId, command.quantity(), Math::max);
                    issued.add(nextId++);
                }
                case MARKET -> {
                    engine.submitMarket(nextId, SYMBOL, command.side(),
                            TimeInForce.DAY, command.quantity());
                    largestQuantity.merge(nextId, command.quantity(), Math::max);
                    issued.add(nextId++);
                }
                case CANCEL -> {
                    if (!issued.isEmpty()) {
                        engine.cancel(issued.get(command.target() % issued.size()));
                    }
                }
                case MODIFY -> {
                    if (!issued.isEmpty()) {
                        long id = issued.get(command.target() % issued.size());
                        SubmitResult result =
                                engine.modify(id, command.price(), command.quantity());
                        if (!result.isRejected()) {
                            largestQuantity.merge(id, command.quantity(), Math::max);
                        }
                    }
                }
            }
        }
        return new Run(engine, book, sink, pool, issued, largestQuantity);
    }

    /** Live orders on one side, keyed by price. */
    private static Map<Long, Long> restingByPrice(Run run, Side side) {
        Map<Long, Long> byPrice = new HashMap<>();
        for (long id : run.issued()) {
            Order order = run.engine().order(id);
            if (order != null && order.side() == side) {
                byPrice.merge(order.price(), order.remainingQuantity(), Long::sum);
            }
        }
        return byPrice;
    }

    // -------------------------------------------------------- conservation

    @Property(tries = 400)
    void everyUnitBoughtIsStillAUnitSold(@ForAll("commandStreams") List<Command> commands) {
        Run run = execute(commands);

        assertThat(run.sink().totalFilledOn(Side.BUY))
                .isEqualTo(run.sink().totalFilledOn(Side.SELL));
    }

    @Property(tries = 400)
    void noOrderEverTradesMoreThanItWasEverAskedFor(
            @ForAll("commandStreams") List<Command> commands) {
        // A modify changes an order's quantity, so the bound is the largest
        // total it ever carried — not the one it was submitted with.
        Run run = execute(commands);

        for (long id : run.issued()) {
            assertThat(run.sink().filledQuantity(id))
                    .as("order %d", id)
                    .isLessThanOrEqualTo(run.largestQuantity().get(id));
        }
    }

    // ------------------------------------------------------ book integrity

    @Property(tries = 400)
    void theBookIsNeverLeftCrossed(@ForAll("commandStreams") List<Command> commands) {
        Run run = execute(commands);

        assertThat(run.book().isCrossed())
                .as("best bid %d vs best ask %d", run.book().bestBid(), run.book().bestAsk())
                .isFalse();
    }

    @Property(tries = 400)
    void depthStillMatchesTheOrdersBehindIt(@ForAll("commandStreams") List<Command> commands) {
        // The level's cached total is maintained incrementally on every add,
        // fill, cancel and shrink. One missed adjustment and depth reports
        // quantity that nobody can trade against.
        Run run = execute(commands);

        for (Side side : Side.values()) {
            Map<Long, Long> expected = restingByPrice(run, side);
            Map<Long, Long> reported = new HashMap<>();
            run.book().snapshot(side, Integer.MAX_VALUE,
                    (price, qty, count) -> reported.put(price, qty));

            assertThat(reported).as("side %s", side).isEqualTo(expected);
            assertThat(run.book().levelCount(side)).isEqualTo(expected.size());
        }
    }

    @Property(tries = 400)
    void everyLiveOrderIsRestingAndNonEmpty(@ForAll("commandStreams") List<Command> commands) {
        Run run = execute(commands);

        long live = 0L;
        for (long id : run.issued()) {
            Order order = run.engine().order(id);
            if (order == null) {
                continue;
            }
            live++;
            assertThat(order.remainingQuantity()).as("order %d", id).isPositive();
            assertThat(order.isResting()).as("order %d", id).isTrue();
            assertThat(order.orderId()).as("recycled under a live id").isEqualTo(id);
        }
        assertThat(run.engine().liveOrderCount()).isEqualTo((int) live);
    }

    @Property(tries = 400)
    void nothingThatCannotRestIsEverLeftOnTheBook(
            @ForAll("commandStreams") List<Command> commands) {
        Run run = execute(commands);

        for (long id : run.issued()) {
            Order order = run.engine().order(id);
            if (order != null) {
                assertThat(order.timeInForce()).as("order %d", id).isEqualTo(TimeInForce.DAY);
                assertThat(order.isMarket()).as("order %d", id).isFalse();
            }
        }
    }

    // ------------------------------------------------------ price and time

    @Property(tries = 400)
    void noTradeEverBreachesTheAggressorsLimit(
            @ForAll("commandStreams") List<Command> commands) {
        Run run = execute(commands);

        for (RecordingSink.Trade trade : run.sink().trades()) {
            assertThat(trade.aggressorSide().crosses(trade.aggressorPrice(), trade.price()))
                    .as("trade %d at %d for a %s limit of %d",
                            trade.tradeId(), trade.price(),
                            trade.aggressorSide(), trade.aggressorPrice())
                    .isTrue();
            assertThat(trade.quantity()).isPositive();
        }
    }

    @Property(tries = 400)
    void withinAPriceTheOldestRestingOrderIsStillHitFirst(
            @ForAll("commandStreams") List<Command> commands) {
        // Consecutive trades by one aggressor at one price walk that level's
        // queue, so the resting sequence numbers must strictly increase — even
        // when some of those sequences were handed out by a modify.
        Run run = execute(commands);

        long currentAggressor = Long.MIN_VALUE;
        long currentPrice = Long.MIN_VALUE;
        long previousSequence = Long.MIN_VALUE;
        for (RecordingSink.Trade trade : run.sink().trades()) {
            if (trade.aggressorId() == currentAggressor && trade.price() == currentPrice) {
                assertThat(trade.restingSequence())
                        .as("resting order %d jumped the queue", trade.restingId())
                        .isGreaterThan(previousSequence);
            }
            currentAggressor = trade.aggressorId();
            currentPrice = trade.price();
            previousSequence = trade.restingSequence();
        }
    }

    @Property(tries = 400)
    void aReplacementAlwaysGetsAFreshSequenceOrKeepsItsOwn(
            @ForAll("commandStreams") List<Command> commands) {
        Run run = execute(commands);

        for (RecordingSink.Replacement replacement : run.sink().replacements()) {
            if (replacement.priorityLost()) {
                assertThat(replacement.newPrice() != replacement.previousPrice()
                        || replacement.newQuantity() > replacement.previousQuantity())
                        .as("order %d lost priority for no reason", replacement.orderId())
                        .isTrue();
            } else {
                assertThat(replacement.newPrice()).isEqualTo(replacement.previousPrice());
                assertThat(replacement.newQuantity())
                        .isLessThanOrEqualTo(replacement.previousQuantity());
            }
        }
    }

    // ----------------------------------------------------------- no leaks

    @Property(tries = 400)
    void cancellingEverythingLeftEmptiesTheBookAndReturnsEveryOrder(
            @ForAll("commandStreams") List<Command> commands) {
        // The sharpest check there is on a pooled engine. If any order was
        // dropped without being released, or released twice, the free list will
        // not add back up to the number ever allocated.
        Run run = execute(commands);

        Set<Long> seen = new HashSet<>();
        for (long id : run.issued()) {
            if (seen.add(id)) {
                run.engine().cancel(id);
            }
        }

        assertThat(run.engine().liveOrderCount()).isZero();
        assertThat(run.book().isEmpty()).isTrue();
        assertThat(run.book().levelCount(Side.BUY)).isZero();
        assertThat(run.book().levelCount(Side.SELL)).isZero();
        assertThat(run.pool().available()).isEqualTo((int) run.pool().allocations());
    }

    // --------------------------------------------------------- determinism

    @Property(tries = 200)
    void theSameCommandStreamAlwaysProducesTheSameExecutionLog(
            @ForAll("commandStreams") List<Command> commands) {
        Run first = execute(commands);
        Run second = execute(commands);

        assertThat(second.sink().events()).isEqualTo(first.sink().events());
        assertThat(second.engine().tradeCount()).isEqualTo(first.engine().tradeCount());
    }
}

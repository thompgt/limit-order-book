package io.github.thompgt.lob.bench;

import io.github.thompgt.lob.core.MatchingEngine;
import io.github.thompgt.lob.core.Side;
import io.github.thompgt.lob.core.TimeInForce;
import java.util.Random;

/**
 * A precomputed, replayable script of engine commands.
 *
 * <h2>Why the script is net-neutral</h2>
 *
 * Commands come in pairs that leave the book exactly as they found it: an order
 * that rests is cancelled by its partner, an aggressor that lifts an offer is
 * followed by a replacement at the same price and quantity, a modify down is
 * followed by a modify back up.
 *
 * <p>That is not a stylistic choice, it is the only way the number means
 * anything. A JMH iteration pushes millions of orders through the engine; a
 * script that rested even a small fraction of them would end the iteration
 * measuring a book orders of magnitude deeper than the one it started with, and
 * the reported figure would be an average over book sizes nobody chose. Holding
 * depth fixed is what makes "throughput at depth N" a statement about N.
 *
 * <h2>Why the commands are in primitive arrays</h2>
 *
 * The measured path is required not to allocate. A {@code List<Command>} of
 * objects would be fine for correctness and would quietly put an object header
 * and a pointer chase between the benchmark and the engine, which is precisely
 * the cost this project exists to measure. So the script is six parallel
 * primitive arrays and {@link #apply} is a switch.
 *
 * <p>Generation itself allocates freely — it happens once, in setup.
 */
public final class Workload {

    /** Enum arrays are cloned by {@code values()}; hoist it so replay cannot allocate. */
    private static final Side[] SIDES = Side.values();

    public static final byte LIMIT = 0;
    public static final byte CANCEL = 1;
    public static final byte MODIFY = 2;
    public static final byte MARKET = 3;

    /** Every order carries the same size, so a crossing pair always fills exactly one. */
    public static final long UNIT = 10L;

    public static final int SYMBOL = 1;

    /** Mid price in ticks. Levels are seeded either side of it. */
    private static final long MID = 100_000L;

    /** Ids at or below this belong to the seeded book; script ids start above. */
    private static final long SCRIPT_ID_BASE = 1_000_000L;

    /** What kind of pressure the script puts on the engine. */
    public enum Mix {
        /** Rest an order away from the touch, then cancel it. Never trades. */
        RESTING,
        /** Lift the best offer, then replace it. Every command trades. */
        CROSSING,
        /** Shrink a deep resting order, then grow it back. */
        MODIFYING,
        /** Equal parts of the three above. The closest to a real feed. */
        MIXED
    }

    public final byte[] kind;
    public final byte[] side;
    public final long[] price;
    public final long[] quantity;
    public final long[] orderId;
    public final long[] target;

    private final int levels;
    private final int ordersPerLevel;

    private Workload(int size, int levels, int ordersPerLevel) {
        this.kind = new byte[size];
        this.side = new byte[size];
        this.price = new long[size];
        this.quantity = new long[size];
        this.orderId = new long[size];
        this.target = new long[size];
        this.levels = levels;
        this.ordersPerLevel = ordersPerLevel;
    }

    public int size() {
        return kind.length;
    }

    /**
     * Builds the resting book the script runs against: {@code levels} price
     * levels each side of the mid, {@code ordersPerLevel} orders on each.
     *
     * <p>Called once per measurement iteration, on a fresh engine.
     */
    public void seed(MatchingEngine engine) {
        engine.registerSymbol(SYMBOL);
        long id = 1L;
        for (int level = 1; level <= levels; level++) {
            for (int n = 0; n < ordersPerLevel; n++) {
                engine.submit(id++, SYMBOL, Side.BUY, TimeInForce.DAY, MID - level, UNIT);
                engine.submit(id++, SYMBOL, Side.SELL, TimeInForce.DAY, MID + level, UNIT);
            }
        }
    }

    /** Applies command {@code i}. Allocation-free: this is the measured path. */
    public void apply(MatchingEngine engine, int i) {
        switch (kind[i]) {
            case LIMIT -> engine.submit(
                    orderId[i], SYMBOL, SIDES[side[i]], TimeInForce.DAY, price[i], quantity[i]);
            case CANCEL -> engine.cancel(target[i]);
            case MODIFY -> engine.modify(target[i], price[i], quantity[i]);
            case MARKET -> engine.submitMarket(
                    orderId[i], SYMBOL, SIDES[side[i]], TimeInForce.DAY, quantity[i]);
            default -> throw new IllegalStateException("unknown kind " + kind[i]);
        }
    }

    /**
     * Generates a script.
     *
     * @param size must be even — commands are emitted in net-neutral pairs
     * @param seed fixed by the caller, so a run is reproducible
     */
    public static Workload generate(Mix mix, int levels, int ordersPerLevel, int size, long seed) {
        if (size % 2 != 0) {
            throw new IllegalArgumentException("size must be even: " + size);
        }
        Workload workload = new Workload(size, levels, ordersPerLevel);
        Random random = new Random(seed);
        long nextId = SCRIPT_ID_BASE;

        for (int i = 0; i < size; i += 2) {
            Mix pair = mix == Mix.MIXED ? pairFor(i) : mix;
            switch (pair) {
                case RESTING -> nextId = workload.restThenCancel(i, nextId, random);
                case CROSSING -> nextId = workload.restThenCrossIt(i, nextId, random);
                case MODIFYING -> workload.shrinkThenGrow(i, random);
                default -> throw new IllegalStateException();
            }
        }
        return workload;
    }

    private static Mix pairFor(int i) {
        return switch ((i / 2) % 3) {
            case 0 -> Mix.RESTING;
            case 1 -> Mix.CROSSING;
            default -> Mix.MODIFYING;
        };
    }

    /**
     * Rest an order well beyond the seeded levels, then cancel it. Its price is
     * out of reach of anything the script trades, so it is a pure add/remove of
     * a price level — the path a quoting strategy spends its life on.
     */
    private long restThenCancel(int i, long nextId, Random random) {
        boolean buy = random.nextBoolean();
        // Beyond the seeded ladder, so it neither crosses nor is crossed.
        long offset = levels + 1 + random.nextInt(16);
        long p = buy ? MID - offset : MID + offset;

        kind[i] = LIMIT;
        side[i] = (byte) (buy ? Side.BUY.ordinal() : Side.SELL.ordinal());
        price[i] = p;
        quantity[i] = UNIT;
        orderId[i] = nextId;

        kind[i + 1] = CANCEL;
        target[i + 1] = nextId;
        return nextId + 1;
    }

    /**
     * Quote inside the spread, then hit that quote. Both orders are gone by the
     * end of the pair — one filled, one filled against it — so the book and the
     * set of live ids both come back to where they started.
     *
     * <p>The pair trades at the mid, on a level that did not exist a moment
     * ago, which exercises the whole crossing path: ladder lookup for the best
     * level, the fill itself, and tearing the emptied level back down. What it
     * deliberately does <em>not</em> do is sweep several resting orders at once
     * — a multi-level sweep cannot be undone in one partner command, and a
     * script that cannot undo itself does not hold depth fixed.
     */
    private long restThenCrossIt(int i, long nextId, Random random) {
        boolean quoteIsBid = random.nextBoolean();
        Side quoteSide = quoteIsBid ? Side.BUY : Side.SELL;

        // MID sits between the seeded ladders, so this rests rather than trades.
        kind[i] = LIMIT;
        side[i] = (byte) quoteSide.ordinal();
        price[i] = MID;
        quantity[i] = UNIT;
        orderId[i] = nextId;

        kind[i + 1] = LIMIT;
        side[i + 1] = (byte) quoteSide.opposite().ordinal();
        price[i + 1] = MID;
        quantity[i + 1] = UNIT;
        orderId[i + 1] = nextId + 1;
        return nextId + 2;
    }

    /**
     * Shrink a deep resting order and grow it back. The target sits in the
     * deepest seeded level, which nothing in the script can reach, so it is
     * always live — a modify that rejected would measure the reject path.
     */
    private void shrinkThenGrow(int i, Random random) {
        long targetId = deepestLevelOrderId(random);
        boolean buy = targetId % 2 == 1;
        long p = buy ? MID - levels : MID + levels;

        kind[i] = MODIFY;
        target[i] = targetId;
        price[i] = p;
        quantity[i] = UNIT - 1; // decrease: keeps its place in the queue

        kind[i + 1] = MODIFY;
        target[i + 1] = targetId;
        price[i + 1] = p;
        quantity[i + 1] = UNIT; // increase: goes to the back of the level
    }

    /**
     * An id from the deepest seeded level. Seeding interleaves the sides, so
     * odd ids are bids and even ids are offers.
     */
    private long deepestLevelOrderId(Random random) {
        long firstBidOfLevel = (long) (levels - 1) * ordersPerLevel * 2L + 1L;
        return firstBidOfLevel + random.nextInt(ordersPerLevel) * 2L
                + (random.nextBoolean() ? 1L : 0L);
    }
}

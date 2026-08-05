package io.github.thompgt.lob.bench;

import io.github.thompgt.lob.core.ExecutionSink;
import io.github.thompgt.lob.core.MatchingEngine;
import io.github.thompgt.lob.core.OrderPool;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;

/**
 * Per-command latency at a fixed offered rate, measured three ways.
 *
 * <h2>Coordinated omission, and why there are three numbers</h2>
 *
 * The naive way to measure latency is a loop: time an operation, record it,
 * start the next one. When the engine stalls, that loop stalls with it — so the
 * requests that <em>would</em> have arrived during the stall are never issued,
 * and the samples that would have been terrible are never taken. The result is
 * a tail that looks excellent precisely when the system behaves worst. Gil Tene
 * named this coordinated omission, and a p99.9 measured that way is not a
 * conservative estimate, it is a wrong one.
 *
 * <p>This harness paces submissions against a fixed schedule instead: command
 * {@code i} is <em>due</em> at {@code start + i * interval} whether or not the
 * engine is ready for it. That gives three different questions, and the run
 * prints all three because the distance between them is the finding:
 *
 * <ul>
 *   <li><b>service time</b> — how long the call itself took. The flattering
 *       number, reported here only so the gap is visible.
 *   <li><b>service time, corrected</b> — the same samples through
 *       {@code recordValueWithExpectedInterval}, which back-fills the samples a
 *       stall swallowed.
 *   <li><b>response time</b> — from when the command was <em>due</em> to when it
 *       finished, including any wait for the engine to catch up. This is the
 *       honest number and the one the README quotes.
 * </ul>
 *
 * If the engine keeps up with the offered rate, all three agree. They diverge
 * exactly when the engine is falling behind, which is when the truth matters.
 *
 * <pre>
 * java -cp engine-bench/target/benchmarks.jar \
 *      io.github.thompgt.lob.bench.LatencyHarness --rate 500000 --seconds 20
 * </pre>
 */
public final class LatencyHarness {

    private static final int LEVELS = 8;
    private static final int ORDERS_PER_LEVEL = 4;
    private static final int SCRIPT_SIZE = 1 << 16;
    private static final long SEED = 20260805L;

    /** Unpaced commands run first, so measurement starts against compiled code. */
    private static final long WARMUP_COMMANDS = 20_000_000L;

    private LatencyHarness() {}

    public static void main(String[] args) throws IOException {
        long rate = longArg(args, "--rate", 500_000L);
        long seconds = longArg(args, "--seconds", 20L);
        Path out = stringArg(args, "--out", null) == null
                ? null
                : Path.of(stringArg(args, "--out", null));

        Workload workload =
                Workload.generate(Workload.Mix.MIXED, LEVELS, ORDERS_PER_LEVEL, SCRIPT_SIZE, SEED);

        OrderPool pool = new OrderPool(1 << 17);
        pool.preallocate(1 << 16);
        MatchingEngine engine = new MatchingEngine(ExecutionSink.NO_OP, pool);
        workload.seed(engine);

        System.out.printf(Locale.ROOT,
                "warming up: %,d commands...%n", WARMUP_COMMANDS);
        int cursor = 0;
        for (long i = 0; i < WARMUP_COMMANDS; i++) {
            workload.apply(engine, cursor);
            if (++cursor == SCRIPT_SIZE) {
                cursor = 0;
            }
        }
        long allocationsAfterWarmup = pool.allocations();

        long intervalNs = Math.max(1L, TimeUnit.SECONDS.toNanos(1) / rate);
        long commands = rate * seconds;
        long highest = TimeUnit.SECONDS.toNanos(10);

        Histogram service = new Histogram(1L, highest, 3);
        Histogram serviceCorrected = new Histogram(1L, highest, 3);
        Histogram response = new Histogram(1L, highest, 3);

        System.out.printf(Locale.ROOT,
                "measuring: %,d commands at %,d/sec (interval %,d ns)...%n",
                commands, rate, intervalNs);

        long start = System.nanoTime();
        for (long i = 0; i < commands; i++) {
            long due = start + i * intervalNs;
            // Spin rather than sleep: park granularity is milliseconds, which
            // would swamp the very intervals being measured.
            while (System.nanoTime() < due) {
                Thread.onSpinWait();
            }

            long before = System.nanoTime();
            workload.apply(engine, cursor);
            long after = System.nanoTime();

            if (++cursor == SCRIPT_SIZE) {
                cursor = 0;
            }

            service.recordValue(after - before);
            serviceCorrected.recordValueWithExpectedInterval(after - before, intervalNs);
            response.recordValue(Math.max(1L, after - due));
        }
        long elapsed = System.nanoTime() - start;

        double achieved = commands / (elapsed / 1e9);
        System.out.printf(Locale.ROOT,
                "%nachieved %,.0f commands/sec over %.1fs (offered %,d/sec)%n",
                achieved, elapsed / 1e9, rate);
        System.out.printf(Locale.ROOT,
                "pool allocations during measurement: %d%n",
                pool.allocations() - allocationsAfterWarmup);

        report(System.out, service, serviceCorrected, response);

        if (out != null) {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            try (PrintStream file = new PrintStream(Files.newOutputStream(out))) {
                file.printf(Locale.ROOT, "offered rate: %d/sec%n", rate);
                file.printf(Locale.ROOT, "achieved:     %.0f/sec%n", achieved);
                report(file, service, serviceCorrected, response);
                file.println();
                file.println("--- full response-time distribution ---");
                response.outputPercentileDistribution(file, 1000.0);
            }
            System.out.println("wrote " + out.toAbsolutePath());
        }
    }

    private static void report(
            PrintStream out, Histogram service, Histogram corrected, Histogram response) {
        out.printf(Locale.ROOT, "%n%-12s %12s %12s %12s%n",
                "percentile", "service", "corrected", "response");
        out.printf(Locale.ROOT, "%-12s %12s %12s %12s%n",
                "", "(us)", "(us)", "(us)");
        for (double p : new double[] {50, 90, 99, 99.9, 99.99}) {
            out.printf(Locale.ROOT, "p%-11s %12.3f %12.3f %12.3f%n",
                    trim(p),
                    micros(service.getValueAtPercentile(p)),
                    micros(corrected.getValueAtPercentile(p)),
                    micros(response.getValueAtPercentile(p)));
        }
        out.printf(Locale.ROOT, "%-12s %12.3f %12.3f %12.3f%n",
                "max",
                micros(service.getMaxValue()),
                micros(corrected.getMaxValue()),
                micros(response.getMaxValue()));
        out.printf(Locale.ROOT, "%-12s %12.3f %12.3f %12.3f%n",
                "mean",
                micros((long) service.getMean()),
                micros((long) corrected.getMean()),
                micros((long) response.getMean()));
    }

    private static double micros(long nanos) {
        return nanos / 1000.0;
    }

    private static String trim(double p) {
        return p == Math.rint(p) ? String.valueOf((long) p) : String.valueOf(p);
    }

    private static long longArg(String[] args, String name, long fallback) {
        String value = stringArg(args, name, null);
        return value == null ? fallback : Long.parseLong(value);
    }

    private static String stringArg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}

package io.github.thompgt.lob.bench;

import io.github.thompgt.lob.core.ExecutionSink;
import io.github.thompgt.lob.core.MatchingEngine;
import io.github.thompgt.lob.core.OrderPool;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Throughput of the engine, in commands per second.
 *
 * <p>One {@code @Benchmark} invocation is one command — a submit, a cancel or a
 * modify — so the reported figure is directly "orders per second" rather than a
 * batch size that has to be divided out.
 *
 * <p>The book is held at a fixed depth for the whole run: see {@link Workload}
 * for why that is the only way "throughput at depth N" says anything about N.
 * The order pool is preallocated in setup so that steady-state measurement
 * never pays for pool growth, which is the state a long-running engine is
 * actually in.
 *
 * <p>Run it:
 *
 * <pre>./mvnw -pl engine-bench -am -Pbench verify</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class MatchingEngineBenchmark {

    private static final int SCRIPT_SIZE = 1 << 16;
    private static final int ORDERS_PER_LEVEL = 4;
    private static final long SEED = 20260805L;

    @Param({"MIXED", "RESTING", "CROSSING", "MODIFYING"})
    public Workload.Mix mix;

    /** Price levels seeded on each side. The ladder lookup grows with this. */
    @Param({"8", "256"})
    public int levels;

    private MatchingEngine engine;
    private Workload workload;
    private int cursor;

    @Setup(Level.Iteration)
    public void setUp() {
        workload = Workload.generate(mix, levels, ORDERS_PER_LEVEL, SCRIPT_SIZE, SEED);

        OrderPool pool = new OrderPool(1 << 17);
        pool.preallocate(1 << 16);
        engine = new MatchingEngine(ExecutionSink.NO_OP, pool);
        workload.seed(engine);
        cursor = 0;
    }

    @Benchmark
    public void command() {
        workload.apply(engine, cursor);
        if (++cursor == SCRIPT_SIZE) {
            cursor = 0;
        }
    }
}

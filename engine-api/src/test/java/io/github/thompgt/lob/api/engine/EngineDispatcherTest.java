package io.github.thompgt.lob.api.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thompgt.lob.core.MatchingEngine;
import io.github.thompgt.lob.core.Side;
import io.github.thompgt.lob.core.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineDispatcherTest {

    private static final int SYMBOL = 1;
    private static final long TIMEOUT_MS = 5_000L;

    private MatchingEngine engine;
    private EngineDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        engine.registerSymbol(SYMBOL);
        dispatcher = new EngineDispatcher(engine, 1_024);
        dispatcher.start();
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
    }

    @Test
    void aCommandRunsOnTheEngineThreadNotTheCallers() {
        String caller = Thread.currentThread().getName();

        String ranOn = dispatcher.call(e -> Thread.currentThread().getName(), TIMEOUT_MS);

        assertThat(ranOn).isEqualTo("matching-engine").isNotEqualTo(caller);
    }

    @Test
    void everyCommandRunsOnTheSameThreadHoweverManyCallersThereAre() throws Exception {
        int threads = 8;
        int perThread = 200;
        ExecutorService callers = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<String> observed = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            callers.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    observed.add(dispatcher.call(
                            e -> Thread.currentThread().getName(), TIMEOUT_MS));
                }
                return null;
            });
        }
        start.countDown();
        callers.shutdown();
        assertThat(callers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // The whole reason the dispatcher exists: engine-core never sees a
        // second thread, whatever the servlet container is doing.
        assertThat(observed).hasSize(threads * perThread).containsOnly("matching-engine");
    }

    @Test
    void concurrentSubmissionsAllLandAndTheBookAgreesWithThem() throws Exception {
        int threads = 8;
        int perThread = 250;
        ExecutorService callers = Executors.newFixedThreadPool(threads);
        AtomicLong ids = new AtomicLong(1L);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            callers.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    long id = ids.getAndIncrement();
                    dispatcher.call(e -> e.submit(
                            id, SYMBOL, Side.BUY, TimeInForce.DAY, 100L, 5L).status(),
                            TIMEOUT_MS);
                }
                return null;
            });
        }
        start.countDown();
        callers.shutdown();
        assertThat(callers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int total = threads * perThread;
        long resting = dispatcher.call(
                e -> e.book(SYMBOL).quantityAt(Side.BUY, 100L), TIMEOUT_MS);
        assertThat(resting).isEqualTo(total * 5L);
        assertThat(dispatcher.call(MatchingEngine::liveOrderCount, TIMEOUT_MS)).isEqualTo(total);
    }

    @Test
    void sequenceNumbersComeOutStrictlyIncreasingUnderConcurrentCallers() throws Exception {
        int threads = 6;
        int perThread = 100;
        ExecutorService callers = Executors.newFixedThreadPool(threads);
        List<Long> sequences = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicLong ids = new AtomicLong(1L);

        for (int t = 0; t < threads; t++) {
            callers.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    long id = ids.getAndIncrement();
                    sequences.add(dispatcher.call(e -> e.submit(
                            id, SYMBOL, Side.BUY, TimeInForce.DAY, 100L, 1L).sequence(),
                            TIMEOUT_MS));
                }
                return null;
            });
        }
        callers.shutdown();
        assertThat(callers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Which caller got which sequence is a race, but the set must be
        // exactly 1..n with nothing repeated: time priority is decided by
        // arrival in the queue, and two orders cannot share a place in it.
        assertThat(sequences).hasSize(threads * perThread).doesNotHaveDuplicates();
        assertThat(sequences.stream().sorted().toList())
                .isEqualTo(java.util.stream.LongStream.rangeClosed(1, threads * perThread)
                        .boxed().toList());
    }

    @Test
    void aFullQueueIsRefusedRatherThanQueuedOrBlocked() throws Exception {
        MatchingEngine idle = new MatchingEngine();
        try (EngineDispatcher tiny = new EngineDispatcher(idle, 1)) {
            CountDownLatch release = new CountDownLatch(1);
            tiny.start();
            // Occupy the single engine thread so nothing can drain.
            tiny.execute(e -> {
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });

            boolean refused = false;
            for (int i = 0; i < 100 && !refused; i++) {
                try {
                    tiny.call(e -> null, 50L);
                } catch (EngineBusyException e) {
                    refused = true;
                }
            }
            release.countDown();

            assertThat(refused)
                    .as("a saturated dispatcher must shed load, not absorb it")
                    .isTrue();
        }
    }

    @Test
    void anExceptionInOneCommandDoesNotTakeTheEngineThreadDownWithIt() {
        assertThatThrownBy(() -> dispatcher.call(e -> {
            throw new IllegalStateException("boom");
        }, TIMEOUT_MS)).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        // The next caller would otherwise wait out its whole timeout forever.
        assertThat(dispatcher.isRunning()).isTrue();
        String afterTheFailure = dispatcher.call(e -> "still here", TIMEOUT_MS);
        assertThat(afterTheFailure).isEqualTo("still here");
    }

    @Test
    void aCommandThatTimedOutIsNeverApplied() throws Exception {
        MatchingEngine idle = new MatchingEngine();
        idle.registerSymbol(SYMBOL);
        try (EngineDispatcher blocked = new EngineDispatcher(idle, 16)) {
            CountDownLatch release = new CountDownLatch(1);
            blocked.start();
            // Hold the engine thread so the next command has to wait behind it.
            blocked.execute(e -> {
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });

            // The caller gives up. It is told the order did not happen, so a
            // client retrying on the 503 must not end up submitting twice.
            assertThatThrownBy(() -> blocked.call(
                    e -> e.submit(1L, SYMBOL, Side.BUY, TimeInForce.DAY, 100L, 5L).status(),
                    50L))
                    .isInstanceOf(EngineBusyException.class);

            release.countDown();
            int live = blocked.call(MatchingEngine::liveOrderCount, TIMEOUT_MS);
            long resting =
                    blocked.call(e -> e.book(SYMBOL).quantityAt(Side.BUY, 100L), TIMEOUT_MS);
            assertThat(live).isZero();
            assertThat(resting).isZero();
        }
    }

    @Test
    void shuttingDownFailsWhateverIsStillWaitingInsteadOfLeavingItHanging() {
        MatchingEngine idle = new MatchingEngine();
        EngineDispatcher closing = new EngineDispatcher(idle, 16);
        closing.start();
        closing.close();

        assertThat(closing.isRunning()).isFalse();
        assertThatThrownBy(() -> closing.call(e -> "never runs", 200L))
                .isInstanceOf(EngineBusyException.class);
    }
}

package io.github.thompgt.lob.api.engine;

import io.github.thompgt.lob.core.MatchingEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The only door into the matching engine.
 *
 * <p>{@code engine-core} is single-threaded by design — invariant 4 in
 * {@code CLAUDE.md} — and Tomcat is emphatically not. Rather than putting locks
 * inside the engine and trading away the thing this project measures, every
 * command is handed to one dedicated thread that owns the engine outright. The
 * request thread parks on a future until that thread has done the work.
 *
 * <p>So the engine never sees a second thread, and the ordering the book
 * enforces is the ordering commands arrive in this queue. Sequence numbers, and
 * therefore time priority, are decided here.
 *
 * <h2>Results must be copied on the engine thread</h2>
 *
 * The engine returns reused, mutable objects: {@code SubmitResult} is
 * overwritten by the next call, and an {@code Order} handed to a sink may be
 * back in the pool a moment later. A command function must therefore project
 * whatever it needs into an immutable value <em>before it returns</em>, because
 * by the time the waiting request thread wakes up, the objects it might have
 * referenced belong to somebody else's order. Returning an engine object from
 * one of these functions is a race that will read as a rare, inexplicable wrong
 * price in production.
 *
 * <h2>Backpressure</h2>
 *
 * The queue is bounded. When it fills, submission fails immediately rather than
 * blocking the request thread or growing without limit — a queue that absorbs
 * unbounded load converts a throughput problem into a latency problem and then
 * into an out-of-memory one.
 */
public final class EngineDispatcher implements AutoCloseable {

    /** How long the engine thread waits for work before re-checking shutdown. */
    private static final long POLL_TIMEOUT_MS = 100L;

    private final MatchingEngine engine;
    private final BlockingQueue<Command<?>> queue;
    private final Thread thread;

    private volatile boolean running = true;

    public EngineDispatcher(MatchingEngine engine, int queueCapacity) {
        this.engine = engine;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.thread = new Thread(this::consume, "matching-engine");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /**
     * Queues a command and returns a future completed on the engine thread.
     *
     * @param command must return an immutable projection; see the class note
     * @return a future that fails with {@link EngineBusyException} if the queue
     *         is full
     */
    public <T> CompletableFuture<T> execute(Function<MatchingEngine, T> command) {
        Command<T> queued = new Command<>(command, new CompletableFuture<>());
        if (!queue.offer(queued)) {
            queued.future.completeExceptionally(
                    new EngineBusyException("engine command queue is full"));
        }
        return queued.future;
    }

    /**
     * Queues a command and waits for it.
     *
     * <p>Giving up abandons the command as well as the wait. Telling a caller
     * "this did not happen" while the command sat in the queue waiting to be
     * applied is the worst of both answers: a client that retries on the 503 —
     * which the {@code Retry-After} invites it to do — would submit twice and
     * the book would hold two orders. So the future is failed here, and the
     * engine thread skips any command whose future is already settled.
     *
     * @throws EngineBusyException if the queue is full or the wait times out
     */
    public <T> T call(Function<MatchingEngine, T> command, long timeoutMs) {
        CompletableFuture<T> future = execute(command);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(
                    new EngineBusyException("interrupted waiting for the engine"));
            throw new EngineBusyException("interrupted waiting for the engine");
        } catch (java.util.concurrent.TimeoutException e) {
            String message = "engine did not respond within " + timeoutMs + "ms";
            future.completeExceptionally(new EngineBusyException(message));
            throw new EngineBusyException(message);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** Approximate depth of the queue. Diagnostics and metrics only. */
    public int queueDepth() {
        return queue.size();
    }

    public boolean isRunning() {
        return running && thread.isAlive();
    }

    private void consume() {
        while (running) {
            try {
                Command<?> command = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (command != null) {
                    command.runOn(engine);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        drainOnShutdown();
    }

    /**
     * Fails anything still queued. A caller parked on a future would otherwise
     * wait out its whole timeout for a command that will never run.
     */
    private void drainOnShutdown() {
        List<Command<?>> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        for (Command<?> command : remaining) {
            command.future.completeExceptionally(
                    new EngineBusyException("engine is shutting down"));
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainOnShutdown();
    }

    /** One queued unit of work and the future waiting on it. */
    private record Command<T>(
            Function<MatchingEngine, T> function, CompletableFuture<T> future) {

        void runOn(MatchingEngine engine) {
            if (future.isDone()) {
                // The caller already timed out or was failed on shutdown, and
                // has been told the command did not happen. Applying it now
                // would make that answer a lie. The check is not a fence — a
                // timeout landing mid-apply still applies — but it closes the
                // window that matters, which is a command queued behind a
                // backlog longer than the caller's timeout.
                return;
            }
            try {
                future.complete(function.apply(engine));
            } catch (RuntimeException e) {
                // Never let one bad command kill the engine thread: every
                // subsequent request would hang until its timeout.
                future.completeExceptionally(e);
            }
        }
    }
}

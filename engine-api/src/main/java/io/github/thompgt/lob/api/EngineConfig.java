package io.github.thompgt.lob.api;

import io.github.thompgt.lob.api.engine.EngineDispatcher;
import io.github.thompgt.lob.api.engine.SymbolRegistry;
import io.github.thompgt.lob.core.ExecutionSink;
import io.github.thompgt.lob.core.MatchingEngine;
import io.github.thompgt.lob.core.OrderPool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the engine into the application context.
 *
 * <p>Note what is <em>not</em> here: no Spring annotation appears on any
 * {@code engine-core} type, because none exists there to annotate. The engine is
 * constructed by hand, as an ordinary object, exactly as the benchmarks
 * construct it. That is the whole point of invariant 1 — the thing being
 * measured and the thing being served are the same object graph.
 */
@Configuration
@EnableConfigurationProperties(LobProperties.class)
public class EngineConfig {

    @Bean
    public SymbolRegistry symbolRegistry(LobProperties properties) {
        return new SymbolRegistry(properties.symbols());
    }

    /**
     * The event sink the engine publishes to. Defaults to discarding events, so
     * the engine runs with or without a market-data layer in front of it; the
     * WebSocket broadcaster replaces this bean when it is present.
     */
    @Bean
    @ConditionalOnMissingBean(ExecutionSink.class)
    public ExecutionSink executionSink() {
        return ExecutionSink.NO_OP;
    }

    /**
     * The engine, its pool, and the single thread that owns them.
     *
     * <p>The pool is filled at startup rather than on demand: growing it later
     * would allocate on the hot path, which is the one thing the design does not
     * do. {@code destroyMethod} stops the thread on shutdown and fails whatever
     * is still queued, so no request hangs waiting for a command that will never
     * run.
     */
    @Bean(destroyMethod = "close")
    public EngineDispatcher engineDispatcher(
            LobProperties properties, SymbolRegistry symbols, ExecutionSink sink) {
        OrderPool pool = new OrderPool(properties.orderPoolCapacity());
        pool.preallocate(properties.orderPoolCapacity());

        MatchingEngine engine = new MatchingEngine(sink, pool);
        for (int symbolId : symbols.ids()) {
            engine.registerSymbol(symbolId);
        }

        EngineDispatcher dispatcher = new EngineDispatcher(engine, properties.queueCapacity());
        dispatcher.start();
        return dispatcher;
    }
}

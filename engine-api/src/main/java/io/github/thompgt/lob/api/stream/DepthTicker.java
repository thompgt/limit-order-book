package io.github.thompgt.lob.api.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thompgt.lob.api.LobProperties;
import io.github.thompgt.lob.api.dto.DepthResponse;
import io.github.thompgt.lob.api.dto.DepthSnapshot;
import io.github.thompgt.lob.api.engine.EngineBusyException;
import io.github.thompgt.lob.api.engine.EngineDispatcher;
import io.github.thompgt.lob.api.engine.SymbolRegistry;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes a depth snapshot to each symbol's subscribers on a fixed interval.
 *
 * <p>Depth is sent on a clock rather than on every book change on purpose. A
 * busy book changes thousands of times a second and no screen can show that;
 * what a viewer wants is the current state, often enough to look live. Sampling
 * turns an unbounded event rate into a bounded one, and it does it without the
 * engine knowing anything about it.
 *
 * <p>Snapshots are taken through the dispatcher like any other read, so each one
 * is a consistent view of a single instant rather than a book read while it was
 * changing. The engine thread only fills a buffer this class already owns, one
 * per symbol; the DTO and its JSON are built here. Only this thread touches
 * those buffers, which is what makes reusing them safe.
 */
@Component
public class DepthTicker {

    private static final Logger log = LoggerFactory.getLogger(DepthTicker.class);

    private final EngineDispatcher dispatcher;
    private final MarketDataHandler handler;
    private final SymbolRegistry symbols;
    private final LobProperties properties;
    private final ObjectMapper json;

    /** One reusable capture buffer per symbol, touched only by this thread. */
    private final Map<Integer, DepthSnapshot> buffers = new HashMap<>();

    public DepthTicker(
            EngineDispatcher dispatcher,
            MarketDataHandler handler,
            SymbolRegistry symbols,
            LobProperties properties,
            ObjectMapper json) {
        this.dispatcher = dispatcher;
        this.handler = handler;
        this.symbols = symbols;
        this.properties = properties;
        this.json = json;
        for (int symbolId : symbols.ids()) {
            buffers.put(symbolId, new DepthSnapshot(properties.maxDepthLevels()));
        }
    }

    @Scheduled(fixedDelayString = "${lob.depth-interval-ms:250}")
    public void publishDepth() {
        for (int symbolId : symbols.ids()) {
            if (handler.subscriberCount(symbolId) == 0) {
                continue; // Nobody is watching; do not spend an engine slot.
            }
            try {
                String name = symbols.nameOf(symbolId);
                DepthSnapshot buffer = buffers.get(symbolId);
                dispatcher.call(
                        engine -> {
                            buffer.capture(engine.book(symbolId), buffer.maxLevels());
                            return Boolean.TRUE;
                        },
                        properties.commandTimeoutMs());
                DepthResponse depth = buffer.toResponse(name);
                handler.broadcastDepth(symbolId, json.writeValueAsString(new Snapshot(depth)));
            } catch (EngineBusyException e) {
                // A busy engine is exactly when depth matters least: the next
                // tick will carry a fresher picture anyway.
                log.debug("skipped a depth tick for symbol {}", symbolId);
            } catch (Exception e) {
                log.warn("could not publish depth for symbol {}", symbolId, e);
            }
        }
    }

    /** Wraps the snapshot so a client can switch on {@code type} as it does for events. */
    private record Snapshot(String type, DepthResponse depth) {
        Snapshot(DepthResponse depth) {
            this("depth", depth);
        }
    }
}

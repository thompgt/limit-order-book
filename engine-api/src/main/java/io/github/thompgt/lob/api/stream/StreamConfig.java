package io.github.thompgt.lob.api.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thompgt.lob.api.LobProperties;
import io.github.thompgt.lob.api.engine.SymbolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Wires the market-data feed: {@code ws://host/stream/{symbol}}.
 *
 * <p>The broadcaster is registered as the engine's {@code ExecutionSink} simply
 * by being one — {@code EngineConfig} asks for that interface and gets this.
 *
 * <p>No {@code setAllowedOrigins("*")} here. The UI is served by this same
 * application, so same-origin is all it needs, and an order-entry socket that
 * any page on the internet can open is not a default worth having.
 */
@Configuration
@EnableWebSocket
public class StreamConfig implements WebSocketConfigurer {

    private final MarketDataHandler handler;

    public StreamConfig(MarketDataHandler handler) {
        this.handler = handler;
    }

    @Bean
    public static MarketDataHandler marketDataHandler(SymbolRegistry symbols, ObjectMapper json) {
        return new MarketDataHandler(symbols, json);
    }

    @Bean(destroyMethod = "close")
    public static MarketDataBroadcaster marketDataBroadcaster(
            MarketDataHandler handler, LobProperties properties) {
        MarketDataBroadcaster broadcaster =
                new MarketDataBroadcaster(handler, properties.streamCapacity());
        broadcaster.start();
        return broadcaster;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/stream/*");
    }
}

package io.github.thompgt.lob.api.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thompgt.lob.api.engine.SymbolRegistry;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Holds the subscribers of {@code /stream/{symbol}} and writes to them.
 *
 * <p>Subscriptions are per symbol, so a client watching one instrument is not
 * woken by every trade in the system. The connecting URL decides: the last path
 * segment is the symbol, resolved once at connect time so the hot path compares
 * an int.
 *
 * <p>Nothing here runs on the engine thread — {@link MarketDataBroadcaster} owns
 * that boundary. Writes happen on its publisher thread, which is why a slow or
 * dead socket can cost a syscall here without costing a matched order there.
 */
public class MarketDataHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataHandler.class);

    private final SymbolRegistry symbols;
    private final ObjectMapper json;

    /** symbolId → live sessions. Copy-on-write: reads dominate by orders of magnitude. */
    private final Map<Integer, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();

    public MarketDataHandler(SymbolRegistry symbols, ObjectMapper json) {
        this.symbols = symbols;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        String symbol = symbolOf(session);
        if (symbol == null || !symbols.isKnown(symbol)) {
            // Closing with a reason beats a client staring at a socket that is
            // open but will never say anything.
            session.close(CloseStatus.BAD_DATA.withReason("unknown symbol: " + symbol));
            return;
        }
        subscribers
                .computeIfAbsent(symbols.idOf(symbol), id -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscribers.values().forEach(sessions -> sessions.remove(session));
    }

    /**
     * Writes one event to everyone watching its symbol.
     *
     * <p>A failed write drops that subscriber rather than propagating: one
     * client's broken pipe is not a reason to stop publishing to the others, and
     * certainly not a reason to disturb the engine.
     */
    void broadcast(MarketDataEvent event) {
        Set<WebSocketSession> sessions = subscribers.get(event.symbolId());
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = json.writeValueAsString(event.withSymbol(symbols.nameOf(event.symbolId())));
        } catch (IOException e) {
            log.warn("could not serialise {} event", event.type(), e);
            return;
        }
        send(sessions, payload);
    }

    /** Sends an already-serialised depth snapshot. Called from the depth ticker. */
    void broadcastDepth(int symbolId, String payload) {
        Set<WebSocketSession> sessions = subscribers.get(symbolId);
        if (sessions != null && !sessions.isEmpty()) {
            send(sessions, payload);
        }
    }

    private void send(Set<WebSocketSession> sessions, String payload) {
        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    // Synchronized because a WebSocketSession is not safe for
                    // concurrent writes, and the depth ticker publishes from a
                    // different thread than the event stream.
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } else {
                    sessions.remove(session);
                }
            } catch (IOException | IllegalStateException e) {
                sessions.remove(session);
                log.debug("dropped a subscriber after a failed write", e);
            }
        }
    }

    /** How many sockets are watching a symbol. Metrics and tests. */
    public int subscriberCount(int symbolId) {
        Set<WebSocketSession> sessions = subscribers.get(symbolId);
        return sessions == null ? 0 : sessions.size();
    }

    private static String symbolOf(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        String path = session.getUri().getPath();
        int slash = path.lastIndexOf('/');
        String symbol = slash < 0 ? path : path.substring(slash + 1);
        return symbol.isBlank() ? null : symbol.toUpperCase(Locale.ROOT);
    }
}

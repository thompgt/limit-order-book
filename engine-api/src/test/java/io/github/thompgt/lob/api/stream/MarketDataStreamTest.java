package io.github.thompgt.lob.api.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The market-data feed end to end: a real socket, a real server, real orders.
 *
 * <p>What matters here is not that JSON arrives but <em>what</em> arrives — the
 * events are built from engine-owned flyweight orders that are recycled the
 * moment the callback returns, so a copying bug shows up as a message carrying
 * some later order's quantity, not as an exception.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketDataStreamTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MarketDataHandler handler;

    @Autowired
    private io.github.thompgt.lob.api.engine.SymbolRegistry symbols;

    /** Collects everything a subscriber is sent. */
    private static final class Collector extends TextWebSocketHandler {
        private final List<JsonNode> messages = new ArrayList<>();

        @Override
        public void handleTextMessage(WebSocketSession session, TextMessage message)
                throws Exception {
            synchronized (messages) {
                messages.add(JSON.readTree(message.getPayload()));
            }
        }

        List<JsonNode> ofType(String type) {
            synchronized (messages) {
                return messages.stream()
                        .filter(m -> type.equals(m.path("type").asText()))
                        .toList();
            }
        }
    }

    private WebSocketSession subscribe(String symbol, Collector collector) throws Exception {
        return new StandardWebSocketClient()
                .execute(collector, null, URI.create("ws://localhost:" + port + "/stream/" + symbol))
                .get(10, TimeUnit.SECONDS);
    }

    private void submit(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity("/api/v1/orders", new HttpEntity<>(body, headers), String.class);
    }

    /** Waits for a condition rather than sleeping a guessed interval. */
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("condition was never met within 10s");
    }

    @Test
    void aSubscriberSeesAnOrderAcceptedAndThenResting() throws Exception {
        Collector collector = new Collector();
        try (WebSocketSession session = subscribe("AAPL", collector)) {
            submit("""
                    {"symbol":"AAPL","side":"BUY","price":7000,"quantity":9,"orderId":7001}""");

            await(() -> !collector.ofType("rested").isEmpty());

            JsonNode accepted = collector.ofType("accepted").getFirst();
            assertThat(accepted.get("orderId").asLong()).isEqualTo(7001L);
            assertThat(accepted.get("symbol").asText()).isEqualTo("AAPL");
            assertThat(accepted.get("side").asText()).isEqualTo("BUY");
            assertThat(accepted.get("price").asLong()).isEqualTo(7000L);

            JsonNode rested = collector.ofType("rested").getFirst();
            assertThat(rested.get("remainingQuantity").asLong()).isEqualTo(9L);
        }
    }

    @Test
    void aTradeIsPublishedWithTheRestingOrdersPriceAndBothOrderIds() throws Exception {
        Collector collector = new Collector();
        try (WebSocketSession session = subscribe("MSFT", collector)) {
            submit("""
                    {"symbol":"MSFT","side":"SELL","price":7100,"quantity":10,"orderId":7101}""");
            await(() -> !collector.ofType("rested").isEmpty());

            // Buying at 7105 against an ask of 7100: the resting order sets the
            // terms, so the trade must print at 7100 and the improvement is the
            // aggressor's.
            submit("""
                    {"symbol":"MSFT","side":"BUY","price":7105,"quantity":4,"orderId":7102}""");
            await(() -> !collector.ofType("trade").isEmpty());

            JsonNode trade = collector.ofType("trade").getFirst();
            assertThat(trade.get("price").asLong()).isEqualTo(7100L);
            assertThat(trade.get("quantity").asLong()).isEqualTo(4L);
            assertThat(trade.get("orderId").asLong()).isEqualTo(7102L);
            assertThat(trade.get("restingOrderId").asLong()).isEqualTo(7101L);
        }
    }

    @Test
    void eventsForOtherSymbolsAreNotDeliveredHere() throws Exception {
        Collector nvda = new Collector();
        try (WebSocketSession session = subscribe("NVDA", nvda)) {
            submit("""
                    {"symbol":"AAPL","side":"BUY","price":7200,"quantity":1,"orderId":7201}""");
            submit("""
                    {"symbol":"NVDA","side":"BUY","price":7210,"quantity":1,"orderId":7211}""");

            await(() -> !nvda.ofType("rested").isEmpty());

            // A subscriber to one instrument must not be woken by every trade in
            // the system, so the AAPL order must be absent, not merely later.
            assertThat(nvda.ofType("accepted"))
                    .allMatch(m -> m.get("symbol").asText().equals("NVDA"));
        }
    }

    @Test
    void depthSnapshotsArriveOnTheirOwnWithoutAnyOrderActivity() throws Exception {
        Collector collector = new Collector();
        try (WebSocketSession session = subscribe("AAPL", collector)) {
            await(() -> !collector.ofType("depth").isEmpty());

            JsonNode depth = collector.ofType("depth").getFirst().get("depth");
            assertThat(depth.get("symbol").asText()).isEqualTo("AAPL");
            assertThat(depth.has("bids")).isTrue();
            assertThat(depth.has("asks")).isTrue();
        }
    }

    @Test
    void subscribingToASymbolThatIsNotTradedClosesTheSocket() throws Exception {
        Collector collector = new Collector();
        WebSocketSession session = subscribe("NOTREAL", collector);

        await(() -> !session.isOpen());
        assertThat(session.isOpen()).isFalse();
    }

    @Test
    void aCancelIsPublishedWithTheReasonItHappened() throws Exception {
        Collector collector = new Collector();
        try (WebSocketSession session = subscribe("NVDA", collector)) {
            submit("""
                    {"symbol":"NVDA","side":"BUY","price":7300,"quantity":5,"orderId":7301}""");
            await(() -> !collector.ofType("rested").isEmpty());

            rest.delete("/api/v1/orders/7301");
            await(() -> !collector.ofType("canceled").isEmpty());

            JsonNode canceled = collector.ofType("canceled").getFirst();
            assertThat(canceled.get("orderId").asLong()).isEqualTo(7301L);
            assertThat(canceled.get("reason").asText()).isEqualTo("USER");
        }
    }

    @Test
    void aClosedSubscriberIsForgottenRatherThanWrittenToForever() throws Exception {
        Collector collector = new Collector();
        int symbolId = symbols.idOf("AAPL");

        WebSocketSession session = subscribe("AAPL", collector);
        await(() -> handler.subscriberCount(symbolId) > 0);
        session.close(CloseStatus.NORMAL);

        await(() -> handler.subscriberCount(symbolId) == 0);
        assertThat(handler.subscriberCount(symbolId)).isZero();
    }
}

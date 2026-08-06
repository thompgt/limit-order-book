package io.github.thompgt.lob.api.rest;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The API end of the engine, exercised over real HTTP plumbing.
 *
 * <p>Matching semantics are pinned in {@code engine-core}'s suite and are not
 * re-tested here. What these cover is everything the translation layer can get
 * wrong: which status a reject earns, whether an order id survives the round
 * trip, and whether the book a client reads back agrees with what it sent.
 *
 * <p>Each test trades at its own price band, because the context — and so the
 * book — is shared across the class.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void aRestingLimitOrderComesBackWithItsOwnIdAndNothingFilled() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"AAPL","side":"BUY","price":9000,"quantity":10,
                                 "orderId":9001}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(9001))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.status").value("RESTING"))
                .andExpect(jsonPath("$.filledQuantity").value(0))
                .andExpect(jsonPath("$.restingQuantity").value(10))
                .andExpect(jsonPath("$.rejectReason").doesNotExist());
    }

    @Test
    void anOrderWithoutAnIdIsGivenOne() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"AAPL","side":"BUY","price":9010,"quantity":5}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(greaterThan(0)));
    }

    @Test
    void anAggressiveOrderTradesAndReportsTheFill() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"MSFT","side":"SELL","price":9100,"quantity":10,
                                 "orderId":9101}"""))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"MSFT","side":"BUY","price":9100,"quantity":4,
                                 "orderId":9102}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(4))
                .andExpect(jsonPath("$.tradeCount").value(1));
    }

    @Test
    void aDuplicateOrderIdIsAConflict() throws Exception {
        String body = """
                {"symbol":"AAPL","side":"BUY","price":9020,"quantity":1,"orderId":9200}""";
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.rejectReason").value("DUPLICATE_ORDER_ID"));
    }

    @Test
    void anUnknownSymbolIsRefusedBeforeItReachesTheEngine() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"NOTREAL","side":"BUY","price":100,"quantity":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("NOTREAL")));
    }

    @Test
    void anUnreadableSideIsRefusedWithAMessageNamingTheChoices() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"AAPL","side":"SIDEWAYS","price":100,"quantity":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("BUY")));
    }

    @Test
    void aNonPositiveQuantityIsRejected() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"AAPL","side":"BUY","price":9030,"quantity":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rejectReason").value("NON_POSITIVE_QUANTITY"));
    }

    @Test
    void cancellingAnOrderTakesTheOpenQuantityBack() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"NVDA","side":"BUY","price":9300,"quantity":7,
                                 "orderId":9301}"""))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/orders/9301"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.canceledQuantity").value(7))
                .andExpect(jsonPath("$.symbol").value("NVDA"));
    }

    @Test
    void cancellingAnOrderThatIsNotThereIsANotFound() throws Exception {
        mvc.perform(delete("/api/v1/orders/424242"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.rejectReason").value("UNKNOWN_ORDER_ID"));
    }

    @Test
    void cancellingTwiceIsANotFoundTheSecondTime() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"NVDA","side":"BUY","price":9310,"quantity":3,
                                 "orderId":9311}"""))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/orders/9311")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/orders/9311")).andExpect(status().isNotFound());
    }

    @Test
    void modifyingAnOrderChangesItsPriceAndQuantity() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"AAPL","side":"BUY","price":9400,"quantity":10,
                                 "orderId":9401}"""))
                .andExpect(status().isCreated());

        mvc.perform(patch("/api/v1/orders/9401")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":9401,"quantity":6}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESTING"))
                .andExpect(jsonPath("$.restingQuantity").value(6));

        mvc.perform(get("/api/v1/book/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids[?(@.price == 9400)]").isEmpty());
    }

    @Test
    void modifyingAnOrderThatIsNotThereIsANotFound() throws Exception {
        mvc.perform(patch("/api/v1/orders/525252")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":100,"quantity":1}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.rejectReason").value("UNKNOWN_ORDER_ID"));
    }

    @Test
    void depthReportsTheBestPricesAndAggregatesEachLevel() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"NVDA","side":"BUY","price":9500,"quantity":4,
                                 "orderId":9501}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"NVDA","side":"BUY","price":9500,"quantity":6,
                                 "orderId":9502}"""))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/book/NVDA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("NVDA"))
                // Two orders, one level, one aggregated quantity.
                .andExpect(jsonPath("$.bids[?(@.price == 9500)].quantity").value(10))
                .andExpect(jsonPath("$.bids[?(@.price == 9500)].orders").value(2));
    }

    @Test
    void anEmptySideIsReportedAsNoPriceRatherThanASentinel() throws Exception {
        // Nothing in this class ever sells NVDA, so its ask side stays empty.
        // Long.MAX_VALUE reaching a client chart would plot as a spike to
        // infinity, which is why the DTO maps the sentinels to null.
        mvc.perform(get("/api/v1/book/NVDA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestAsk").doesNotExist())
                .andExpect(jsonPath("$.spread").doesNotExist())
                .andExpect(jsonPath("$.asks").isEmpty());
    }

    @Test
    void depthOfAnUnknownSymbolIsRefused() throws Exception {
        mvc.perform(get("/api/v1/book/NOTREAL")).andExpect(status().isBadRequest());
    }

    @Test
    void theSymbolListIsWhatTheBooksWereOpenedFor() throws Exception {
        mvc.perform(get("/api/v1/symbols"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(
                        org.hamcrest.Matchers.hasItems("AAPL", "MSFT", "NVDA")));
    }

    @Test
    void aSymbolIsRecognisedWhateverCaseItIsWrittenIn() throws Exception {
        mvc.perform(get("/api/v1/book/aapl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"));
    }

    @Test
    void anIocOrderWithNothingToTradeAgainstIsCancelledNotRested() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"MSFT","side":"BUY","price":8000,"quantity":5,
                                 "timeInForce":"IOC","orderId":9601}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.filledQuantity").value(0));
    }

    @Test
    void aMarketOrderNeedsNoPriceAndNeverRests() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"MSFT","side":"SELL","price":9700,"quantity":8,
                                 "orderId":9701}"""))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"MSFT","side":"BUY","type":"MARKET","quantity":8,
                                 "orderId":9702}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(8))
                .andExpect(jsonPath("$.restingQuantity").value(0));
    }
}

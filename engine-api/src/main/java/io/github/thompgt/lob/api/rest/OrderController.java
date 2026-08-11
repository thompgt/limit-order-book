package io.github.thompgt.lob.api.rest;

import io.github.thompgt.lob.api.LobProperties;
import io.github.thompgt.lob.api.dto.CancelResponse;
import io.github.thompgt.lob.api.dto.DepthResponse;
import io.github.thompgt.lob.api.dto.DepthSnapshot;
import io.github.thompgt.lob.api.dto.ModifyOrderRequest;
import io.github.thompgt.lob.api.dto.NewOrderRequest;
import io.github.thompgt.lob.api.dto.OrderResponse;
import io.github.thompgt.lob.api.engine.EngineDispatcher;
import io.github.thompgt.lob.api.engine.SymbolRegistry;
import io.github.thompgt.lob.core.CancelResult;
import io.github.thompgt.lob.core.RejectReason;
import io.github.thompgt.lob.core.SubmitResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order-entry API.
 *
 * <p>Every handler here is a translator, not a decision-maker: parse the
 * request, hand a command to {@link EngineDispatcher}, project the engine's
 * reused result into an immutable DTO <em>on the engine thread</em>, and map it
 * to a status code. No matching logic leaks up here, and no HTTP concept leaks
 * down there.
 *
 * <p>A rejected order is a successful HTTP exchange in the sense that the engine
 * answered — but returning 200 for it would make a client's error handling
 * depend on reading the body, so rejects carry a status that matches the reason.
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final EngineDispatcher dispatcher;
    private final SymbolRegistry symbols;
    private final LobProperties properties;

    /**
     * Ids for clients that do not bring their own. Server-side ids start high
     * enough that they cannot collide with the small ids a client is likely to
     * pick by hand.
     */
    private final AtomicLong nextOrderId = new AtomicLong(1_000_000_000L);

    public OrderController(
            EngineDispatcher dispatcher, SymbolRegistry symbols, LobProperties properties) {
        this.dispatcher = dispatcher;
        this.symbols = symbols;
        this.properties = properties;
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> submit(@RequestBody NewOrderRequest request) {
        String symbol = request.symbol();
        int symbolId = symbols.idOf(symbol);
        long orderId = request.orderId() == null
                ? nextOrderId.getAndIncrement()
                : request.orderId();
        var side = request.parsedSide();
        var tif = request.parsedTimeInForce();
        long quantity = request.quantity();
        boolean market = request.parsedType() == NewOrderRequest.OrderType.MARKET;
        // Read only for a limit order: a market order has no price, and asking
        // for one would reject a request that is perfectly well formed.
        long price = market ? 0L : request.limitPrice();

        OrderResponse response = call(engine -> {
            SubmitResult result = market
                    ? engine.submitMarket(orderId, symbolId, side, tif, quantity)
                    : engine.submit(orderId, symbolId, side, tif, price, quantity);
            // Copied here, on the engine thread, before the next command
            // overwrites the result object. See EngineDispatcher.
            return OrderResponse.from(result, symbol);
        });
        return respond(response, HttpStatus.CREATED);
    }

    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<CancelResponse> cancel(@PathVariable long orderId) {
        CancelResponse response = call(engine -> {
            CancelResult result = engine.cancel(orderId);
            String symbol = symbols.nameOf(result.symbolId());
            return CancelResponse.from(result, symbol);
        });
        return ResponseEntity
                .status(statusFor(response.rejectReason(), HttpStatus.OK))
                .body(response);
    }

    /**
     * Changes a resting order. The quantity is the new <em>total</em>, not the
     * new remainder — see {@link ModifyOrderRequest}.
     */
    @PatchMapping("/orders/{orderId}")
    public ResponseEntity<OrderResponse> modify(
            @PathVariable long orderId, @RequestBody ModifyOrderRequest request) {
        OrderResponse response = call(engine -> {
            SubmitResult result = engine.modify(orderId, request.price(), request.quantity());
            return OrderResponse.from(result, symbols.nameOf(result.symbolId()));
        });
        return respond(response, HttpStatus.OK);
    }

    /** An L2 depth snapshot, taken on the engine thread so it is one instant. */
    @GetMapping("/book/{symbol}")
    public DepthResponse book(
            @PathVariable String symbol,
            @RequestParam(required = false) Integer levels) {
        int symbolId = symbols.idOf(symbol);
        String name = symbols.nameOf(symbolId);
        int maxLevels = levels == null
                ? properties.maxDepthLevels()
                // Capped, not trusted: the snapshot runs on the engine thread,
                // so an unbounded request would be one client stalling everyone.
                : Math.min(Math.max(levels, 1), properties.maxDepthLevels());

        // Allocated here, on the request thread, and filled on the engine
        // thread. Building the DTO inside the command would put a list, a
        // lambda and a record per level in the matching path.
        DepthSnapshot snapshot = new DepthSnapshot(maxLevels);
        call(engine -> {
            snapshot.capture(engine.book(symbolId), maxLevels);
            return Boolean.TRUE;
        });
        return snapshot.toResponse(name);
    }

    /** What is trading here. The frontend reads this to populate its selector. */
    @GetMapping("/symbols")
    public List<String> symbols() {
        return symbols.names();
    }

    private <T> T call(java.util.function.Function<io.github.thompgt.lob.core.MatchingEngine, T> command) {
        return dispatcher.call(command, properties.commandTimeoutMs());
    }

    private ResponseEntity<OrderResponse> respond(OrderResponse response, HttpStatus accepted) {
        return ResponseEntity.status(statusFor(response.rejectReason(), accepted)).body(response);
    }

    /**
     * Maps a reject to the status that describes it. A reject is the engine's
     * considered answer to a well-formed request, so these are 4xx — the request
     * asked for something the book cannot do, not something the service failed
     * to attempt.
     */
    private static HttpStatus statusFor(String rejectReason, HttpStatus accepted) {
        if (rejectReason == null) {
            return accepted;
        }
        return switch (RejectReason.valueOf(rejectReason)) {
            case DUPLICATE_ORDER_ID -> HttpStatus.CONFLICT;
            case UNKNOWN_SYMBOL, UNKNOWN_ORDER_ID -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}

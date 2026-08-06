package io.github.thompgt.lob.api.rest;

import io.github.thompgt.lob.api.dto.BadRequestException;
import io.github.thompgt.lob.api.engine.EngineBusyException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the two things that can go wrong at the boundary into statuses a client
 * can act on.
 *
 * <p>The distinction worth keeping: a <em>reject</em> is handled in
 * {@link OrderController} and means the engine considered the order and said no.
 * The failures here mean the engine never got the chance — either the request
 * could not be parsed into a command, or the service could not run one.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Malformed or unroutable request: bad side, unknown symbol, bad enum. */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> badRequest(BadRequestException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * The queue was full or the engine did not answer in time. 503 with
     * {@code Retry-After}, because this is a load condition that will pass, not
     * a defect in the request — retrying the same bytes later is the right move.
     */
    @ExceptionHandler(EngineBusyException.class)
    public ResponseEntity<Map<String, Object>> busy(EngineBusyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(Map.of(
                        "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "error", "engine_busy",
                        "message", String.valueOf(e.getMessage())));
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", String.valueOf(message)));
    }
}

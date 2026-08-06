package io.github.thompgt.lob.api.engine;

/**
 * The engine could not be reached in time — the command queue was full, the
 * wait timed out, or the service is shutting down.
 *
 * <p>Distinct from a rejected order on purpose. A reject is the engine's
 * considered answer about an order; this is the engine never having been asked.
 * They map to different HTTP statuses because a client should retry one and not
 * the other.
 */
public class EngineBusyException extends RuntimeException {

    public EngineBusyException(String message) {
        super(message);
    }
}

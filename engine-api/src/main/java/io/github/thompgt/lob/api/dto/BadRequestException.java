package io.github.thompgt.lob.api.dto;

/**
 * The request could not be understood well enough to reach the engine — an
 * unknown side, a symbol that is not trading, a missing field.
 *
 * <p>Separate from an engine reject, which means the request was understood
 * perfectly and the engine declined it.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}

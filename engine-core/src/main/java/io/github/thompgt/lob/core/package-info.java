/**
 * Matching engine and limit order book.
 *
 * <p>This package is plain Java by design: it must not reference Spring or any
 * other framework, and its {@code submit} / {@code cancel} / {@code modify}
 * paths must not allocate after warmup. Those two properties are what make the
 * benchmark numbers in the README worth reading. See {@code CLAUDE.md}.
 *
 * <p>Prices are {@code long} tick counts and quantities are {@code long}
 * throughout; conversion to a human-readable price happens at the API
 * boundary and nowhere else.
 */
package io.github.thompgt.lob.core;

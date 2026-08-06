package io.github.thompgt.lob.api.engine;

import io.github.thompgt.lob.api.dto.BadRequestException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates between the symbol names clients use and the {@code int} ids the
 * engine uses.
 *
 * <p>The engine deals in ints because a symbol id sits in every order and gets
 * compared on every command; a string there would mean hashing and pointer
 * chasing on the hot path. Names are a presentation concern, so they stop here.
 *
 * <p>Immutable after construction, so it is safe to read from request threads
 * without touching the engine thread at all.
 */
public final class SymbolRegistry {

    private final Map<String, Integer> idsByName = new LinkedHashMap<>();
    private final Map<Integer, String> namesById = new LinkedHashMap<>();

    /** Ids are assigned by position, starting at 1. Zero is left unused. */
    public SymbolRegistry(List<String> symbols) {
        int id = 1;
        for (String symbol : symbols) {
            String name = symbol.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (idsByName.containsKey(name)) {
                throw new IllegalArgumentException("duplicate symbol: " + name);
            }
            idsByName.put(name, id);
            namesById.put(id, name);
            id++;
        }
        if (idsByName.isEmpty()) {
            throw new IllegalArgumentException("at least one symbol must be configured");
        }
    }

    /** @throws BadRequestException if the symbol is not trading here */
    public int idOf(String symbol) {
        Integer id = symbol == null
                ? null
                : idsByName.get(symbol.trim().toUpperCase(Locale.ROOT));
        if (id == null) {
            throw new BadRequestException(
                    "unknown symbol: '" + symbol + "'; trading " + idsByName.keySet());
        }
        return id;
    }

    public String nameOf(int symbolId) {
        return namesById.get(symbolId);
    }

    public boolean isKnown(String symbol) {
        return symbol != null && idsByName.containsKey(symbol.trim().toUpperCase(Locale.ROOT));
    }

    public List<String> names() {
        return List.copyOf(idsByName.keySet());
    }

    public List<Integer> ids() {
        return List.copyOf(namesById.keySet());
    }
}

package me.golemcore.bot.domain.cli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CliContractCollections {

    private CliContractCollections() {
    }

    static <T> List<T> copyList(List<T> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return List.copyOf(source);
    }

    static Map<String, String> copyStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(orderedNonNullCopy(source));
    }

    static Map<String, Object> copyObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(orderedNonNullCopy(source));
    }

    private static <T> Map<String, T> orderedNonNullCopy(Map<String, T> source) {
        Map<String, T> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "map keys must not be null"),
                Objects.requireNonNull(value, "map values must not be null")));
        return copy;
    }
}

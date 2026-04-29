package me.golemcore.bot.domain.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return Map.copyOf(new LinkedHashMap<>(source));
    }

    static Map<String, Object> copyObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }
}

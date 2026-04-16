package me.golemcore.bot.domain.model;

import java.util.Locale;
import java.util.Set;

public final class FallbackModes {

    public static final String SEQUENTIAL = "sequential";
    public static final String ROUND_ROBIN = "round_robin";
    public static final String WEIGHTED = "weighted";

    public static final Set<String> SUPPORTED = Set.of(SEQUENTIAL, ROUND_ROBIN, WEIGHTED);

    private FallbackModes() {
    }

    public static String normalize(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return SEQUENTIAL;
        }
        String canonical = rawMode.trim().toLowerCase(Locale.ROOT);
        if (SUPPORTED.contains(canonical)) {
            return canonical;
        }
        return SEQUENTIAL;
    }
}

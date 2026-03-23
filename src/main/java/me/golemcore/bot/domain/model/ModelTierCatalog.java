package me.golemcore.bot.domain.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ModelTierCatalog {

    public static final String ROUTING_TIER = "routing";

    private static final List<String> ORDERED_EXPLICIT_TIERS = List.of(
            "balanced",
            "smart",
            "deep",
            "coding",
            "special1",
            "special2",
            "special3",
            "special4",
            "special5");

    private static final Set<String> IMPLICIT_ROUTING_TIERS = Set.of(
            "balanced",
            "smart",
            "deep",
            "coding");

    private static final Set<String> KNOWN_TIERS;

    static {
        Set<String> known = new LinkedHashSet<>();
        known.add(ROUTING_TIER);
        known.addAll(ORDERED_EXPLICIT_TIERS);
        KNOWN_TIERS = Set.copyOf(known);
    }

    private ModelTierCatalog() {
    }

    public static boolean isKnownTier(String tier) {
        String normalized = normalizeTierId(tier);
        return normalized != null && KNOWN_TIERS.contains(normalized);
    }

    public static boolean isExplicitSelectableTier(String tier) {
        String normalized = normalizeTierId(tier);
        return normalized != null && ORDERED_EXPLICIT_TIERS.contains(normalized);
    }

    public static boolean isImplicitRoutingTier(String tier) {
        String normalized = normalizeTierId(tier);
        return normalized != null && IMPLICIT_ROUTING_TIERS.contains(normalized);
    }

    public static List<String> orderedExplicitTiers() {
        return ORDERED_EXPLICIT_TIERS;
    }

    public static String normalizeTierId(String tier) {
        if (tier == null) {
            return null;
        }
        String normalized = tier.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static String explicitTierListForDisplay() {
        return String.join(", ", ORDERED_EXPLICIT_TIERS);
    }
}

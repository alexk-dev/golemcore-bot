package me.golemcore.bot.domain.system.toolloop.repeat;

import java.util.Set;

/**
 * Stable, secret-safe identity for a tool call's semantic arguments.
 */
// @formatter:off
public record ToolUseFingerprint(
        String toolName,
        ToolUseCategory category,
        String canonicalArgumentsHash,
        String stableKey,
        String debugArguments,
        Set<ToolStateDomain> observedDomains,
        Set<ToolStateDomain> invalidatedDomains) {

    public ToolUseFingerprint {
        observedDomains = sanitizeObservedDomains(category, observedDomains);
        invalidatedDomains = sanitizeInvalidatedDomains(category, invalidatedDomains);
    }

    public ToolUseFingerprint(
            String toolName,
            ToolUseCategory category,
            String canonicalArgumentsHash,
            String stableKey,
            String debugArguments) {
        this(
                toolName,
                category,
                canonicalArgumentsHash,
                stableKey,
                debugArguments,
                defaultObservedDomains(category),
                defaultInvalidatedDomains(category));
    }

    private static Set<ToolStateDomain> sanitizeObservedDomains(
            ToolUseCategory category,
            Set<ToolStateDomain> domains) {
        if (domains == null || domains.isEmpty()) {
            return defaultObservedDomains(category);
        }
        return Set.copyOf(domains);
    }

    private static Set<ToolStateDomain> sanitizeInvalidatedDomains(
            ToolUseCategory category,
            Set<ToolStateDomain> domains) {
        if (domains == null || domains.isEmpty()) {
            return defaultInvalidatedDomains(category);
        }
        return Set.copyOf(domains);
    }

    private static Set<ToolStateDomain> defaultObservedDomains(ToolUseCategory category) {
        return switch (category) {
        case OBSERVE, POLL, EXECUTE_UNKNOWN -> Set.of(ToolStateDomain.UNKNOWN);
        case MUTATE_IDEMPOTENT, MUTATE_NON_IDEMPOTENT, CONTROL -> Set.of();
        };
    }

    private static Set<ToolStateDomain> defaultInvalidatedDomains(ToolUseCategory category) {
        return switch (category) {
        case MUTATE_IDEMPOTENT, MUTATE_NON_IDEMPOTENT -> Set.of(ToolStateDomain.UNKNOWN);
        case OBSERVE, POLL, EXECUTE_UNKNOWN, CONTROL -> Set.of();
        };
    }
}
// @formatter:on

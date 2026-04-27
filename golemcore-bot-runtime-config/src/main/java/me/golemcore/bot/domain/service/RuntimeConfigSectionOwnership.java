package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Declares the owning boundary for each runtime config section.
 */
public final class RuntimeConfigSectionOwnership {

    private static final Map<RuntimeConfig.ConfigSection, SectionOwnership> OWNERS = buildOwners();

    private RuntimeConfigSectionOwnership() {
    }

    public static SectionOwnership ownerOf(RuntimeConfig.ConfigSection section) {
        return OWNERS.get(Objects.requireNonNull(section, "section must not be null"));
    }

    public static Map<RuntimeConfig.ConfigSection, SectionOwnership> all() {
        return Collections.unmodifiableMap(new EnumMap<>(OWNERS));
    }

    private static Map<RuntimeConfig.ConfigSection, SectionOwnership> buildOwners() {
        Map<RuntimeConfig.ConfigSection, SectionOwnership> owners = new EnumMap<>(RuntimeConfig.ConfigSection.class);
        for (RuntimeConfig.ConfigSection section : RuntimeConfig.ConfigSection.values()) {
            String owner = ownerService(section);
            owners.put(section, new SectionOwnership(section, owner, owner + ".defaults", owner + ".validator",
                    "RuntimeConfigSectionStore", "RuntimeConfigFacade"));
        }
        return Map.copyOf(owners);
    }

    private static String ownerService(RuntimeConfig.ConfigSection section) {
        return switch (section) {
            case MODEL_ROUTER, LLM, MODEL_REGISTRY, RESILIENCE -> "LlmConfigService";
            case TOOLS, TOOL_LOOP, MCP -> "ToolConfigService";
            case VOICE -> "VoiceConfigService";
            case RATE_LIMIT -> "RateLimitConfigService";
            case MEMORY -> "MemoryConfigService";
            case HIVE -> "HiveConfigService";
            case SELF_EVOLVING -> "SelfEvolvingConfigService";
            case TRACING, TELEMETRY, USAGE -> "ObservabilityConfigService";
            case SESSION_RETENTION, COMPACTION, TURN -> "SessionRuntimeConfigService";
            case SECURITY -> "SecurityConfigService";
            case TELEGRAM -> "TelegramConfigService";
            case AUTO_MODE -> "AutoModeConfigService";
            case UPDATE -> "UpdateConfigService";
            case SKILLS -> "SkillConfigService";
            case PLAN -> "PlanConfigService";
            case DELAYED_ACTIONS -> "DelayedActionsConfigService";
        };
    }

    public record SectionOwnership(RuntimeConfig.ConfigSection section, String ownerService, String defaultsOwner,
            String validatorOwner, String persistenceOwner, String queryAdminViewOwner) {

        public SectionOwnership {
            Objects.requireNonNull(section, "section must not be null");
            requireText(ownerService, "ownerService");
            requireText(defaultsOwner, "defaultsOwner");
            requireText(validatorOwner, "validatorOwner");
            requireText(persistenceOwner, "persistenceOwner");
            requireText(queryAdminViewOwner, "queryAdminViewOwner");
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }
}

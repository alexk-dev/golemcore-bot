package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_AUTO_REFLECTION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_AUTO_REFLECTION_FAILURE_THRESHOLD;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_CODING_MODEL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_CODING_REASONING;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_COMPACTION_DETAILS_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_COMPACTION_DETAILS_MAX_ITEMS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_COMPACTION_MODEL_THRESHOLD_RATIO;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_COMPACTION_PRESERVE_TURN_BOUNDARIES;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_COMPACTION_SUMMARY_TIMEOUT_MS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DEEP_MODEL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DEEP_REASONING;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_ALLOW_RUN_LATER;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_LEASE_DURATION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_MAX_ATTEMPTS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_MAX_DELAY;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_RETENTION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_TICK_SECONDS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DELAYED_ACTIONS_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_HIVE_AUTO_CONNECT;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_HIVE_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_HIVE_MANAGED_BY_PROPERTIES;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_HIVE_SDLC_FUNCTION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DECAY_DAYS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DECAY_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DIAGNOSTICS_VERBOSITY;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DISCLOSURE_DETAIL_MIN_SCORE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DISCLOSURE_HINTS_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DISCLOSURE_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_EPISODIC_TOP_K;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_MAX_PROMPT_BUDGET_TOKENS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_PROCEDURAL_TOP_K;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_PROMOTION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_PROMOTION_MIN_CONFIDENCE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_PROMPT_STYLE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_RERANKING_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_RERANKING_PROFILE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_RETRIEVAL_LOOKBACK_DAYS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_SEMANTIC_TOP_K;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_TOOL_EXPANSION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_VERSION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_WORKING_TOP_K;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MODEL_REGISTRY_BRANCH;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_ROUTING_MODEL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_ROUTING_REASONING;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_ALLOW_AUTO_ACCEPT;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_ARTIFACT_TYPES;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_AUTO_CREATE_REGRESSION_CASES;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_BENCHMARK_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_CANARY_REQUIRED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_CAPTURE_MODE_META_ONLY;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_EVOLUTION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_HARVEST_PRODUCTION_RUNS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_HIVE_APPROVAL_PREFERRED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_JUDGE_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_JUDGE_EVOLUTION_TIER;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_JUDGE_PRIMARY_TIER;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_JUDGE_TIEBREAKER_TIER;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_MODES;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_PROMOTION_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_PUBLISH_INSPECTION_PROJECTION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_READONLY_INSPECTION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_REQUIRE_EVIDENCE_ANCHORS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_SHADOW_REQUIRED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_BM25_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_AUTO_FALLBACK_TO_BM25;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_PROVIDER;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_AUTO_INSTALL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_BASE_URL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_FAIL_OPEN;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_INITIAL_RESTART_BACKOFF_MS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_MINIMUM_RUNTIME_VERSION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_MODEL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_PULL_ON_START;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_REQUIRE_HEALTHY_RUNTIME;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_STARTUP_TIMEOUT_MS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_NEGATIVE_MEMORY_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_PERSONALIZATION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTIC_SEARCH_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TACTICS_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_TRACE_PAYLOAD_OVERRIDE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SELF_EVOLVING_UNCERTAINTY_THRESHOLD;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SESSION_RETENTION_CLEANUP_INTERVAL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SESSION_RETENTION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SESSION_RETENTION_MAX_AGE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SESSION_RETENTION_PROTECT_ACTIVE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SESSION_RETENTION_PROTECT_DELAYED_ACTIONS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SESSION_RETENTION_PROTECT_PLANS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SMART_MODEL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SMART_REASONING;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_LOOP_MAX_LLM_CALLS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_LOOP_MAX_TOOL_EXECUTIONS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_AUTO_LEDGER_TTL_MINUTES;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_MAX_BLOCKED_REPEATS_PER_TURN;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_MAX_SAME_OBSERVE_PER_TURN;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_MAX_SAME_UNKNOWN_PER_TURN;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_MIN_POLL_INTERVAL_SECONDS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_SHADOW_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_CAPTURE_INBOUND_PAYLOADS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_CAPTURE_LLM_PAYLOADS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_CAPTURE_OUTBOUND_PAYLOADS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_CAPTURE_TOOL_PAYLOADS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_MAX_SNAPSHOT_SIZE_KB;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_MAX_SNAPSHOTS_PER_SPAN;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_MAX_TRACES_PER_SESSION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_PAYLOAD_SNAPSHOTS_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_RESILIENCE_PAYLOAD_SAMPLE_RATE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_SESSION_TRACE_BUDGET_MB;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_AUTO_RETRY_BASE_DELAY_MS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_AUTO_RETRY_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_AUTO_RETRY_MAX_ATTEMPTS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_PROGRESS_BATCH_SIZE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_PROGRESS_INTENT_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_PROGRESS_MAX_SILENCE_SECONDS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_PROGRESS_SUMMARY_TIMEOUT_MS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_PROGRESS_UPDATES_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_QUEUE_FOLLOW_UP_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_QUEUE_STEERING_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_QUEUE_STEERING_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_MAX_SKILL_TRANSITIONS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_UPDATE_AUTO_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_UPDATE_CHECK_INTERVAL_MINUTES;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_UPDATE_MAINTENANCE_WINDOW_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_UPDATE_MAINTENANCE_WINDOW_END_UTC;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_UPDATE_MAINTENANCE_WINDOW_START_UTC;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.REASONING_NONE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.SUPPORTED_SELF_EVOLVING_JUDGE_TIERS;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.golemcore.bot.domain.model.FallbackModes;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;

interface RuntimeConfigSectionService {
    void normalize(RuntimeConfig cfg);
}

final class TelegramConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getTelegram() == null) {
            cfg.setTelegram(new RuntimeConfig.TelegramConfig());
        }
        cfg.getTelegram().setAuthMode("invite_only");
        RuntimeConfigInviteCodeSupport.ensureMutableAllowedUsers(cfg.getTelegram());
        RuntimeConfigInviteCodeSupport.ensureMutableInviteCodes(cfg.getTelegram());
    }
}

final class LlmConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getModelRouter() == null) {
            cfg.setModelRouter(new RuntimeConfig.ModelRouterConfig());
        }
        normalizeModelRouterConfig(cfg.getModelRouter());
        if (cfg.getLlm() == null) {
            cfg.setLlm(RuntimeConfig.LlmConfig.builder().build());
        }
        if (cfg.getLlm().getProviders() == null) {
            cfg.getLlm().setProviders(new LinkedHashMap<>());
        }
        if (cfg.getModelRegistry() == null) {
            cfg.setModelRegistry(new RuntimeConfig.ModelRegistryConfig());
        }
        if (cfg.getModelRegistry().getBranch() == null || cfg.getModelRegistry().getBranch().isBlank()) {
            cfg.getModelRegistry().setBranch(DEFAULT_MODEL_REGISTRY_BRANCH);
        } else {
            cfg.getModelRegistry().setBranch(cfg.getModelRegistry().getBranch().trim());
        }
    }

    private void normalizeModelRouterConfig(RuntimeConfig.ModelRouterConfig modelRouter) {
        RuntimeConfig.TierBinding routingBinding = normalizeBinding(modelRouter.getRouting(), DEFAULT_ROUTING_MODEL,
                DEFAULT_ROUTING_REASONING);
        modelRouter.setRouting(routingBinding);

        Map<String, RuntimeConfig.TierBinding> normalizedTiers = new LinkedHashMap<>();
        for (String tier : ModelTierCatalog.orderedExplicitTiers()) {
            normalizedTiers.put(tier, normalizeBinding(modelRouter.getTierBinding(tier), defaultModelForTier(tier),
                    defaultReasoningForTier(tier)));
        }
        modelRouter.setTiers(normalizedTiers);

        if (modelRouter.getDynamicTierEnabled() == null) {
            modelRouter.setDynamicTierEnabled(true);
        }
    }

    private RuntimeConfig.TierBinding normalizeBinding(RuntimeConfig.TierBinding binding, String defaultModel,
            String defaultReasoning) {
        RuntimeConfig.TierBinding normalized = binding != null ? binding : RuntimeConfig.TierBinding.builder().build();
        normalized.setModelReference(RuntimeConfig.ModelReference.normalize(normalized.getModelReference()));
        normalized.setReasoning(RuntimeConfigSupport.normalizeNonBlankString(normalized.getReasoning(), null));
        if (normalized.getModelReference() == null && defaultModel != null) {
            normalized.setModel(defaultModel);
        }
        if (normalized.getReasoning() == null) {
            normalized.setReasoning(defaultReasoning);
        }
        normalized.setTemperature(normalizeTemperature(normalized.getTemperature()));
        normalized.setFallbackMode(FallbackModes.normalize(normalized.getFallbackMode()));
        normalized.setFallbacks(normalizeFallbacks(normalized.getFallbacks()));
        return normalized;
    }

    private List<RuntimeConfig.TierFallback> normalizeFallbacks(List<RuntimeConfig.TierFallback> fallbacks) {
        if (fallbacks == null || fallbacks.isEmpty()) {
            return new ArrayList<>();
        }
        List<RuntimeConfig.TierFallback> normalizedFallbacks = new ArrayList<>();
        for (RuntimeConfig.TierFallback fallback : fallbacks) {
            RuntimeConfig.TierFallback normalizedFallback = normalizeFallback(fallback);
            if (normalizedFallback != null) {
                normalizedFallbacks.add(normalizedFallback);
            }
            if (normalizedFallbacks.size() >= 5) {
                break;
            }
        }
        return normalizedFallbacks;
    }

    private RuntimeConfig.TierFallback normalizeFallback(RuntimeConfig.TierFallback fallback) {
        if (fallback == null) {
            return null;
        }
        fallback.setModelReference(RuntimeConfig.ModelReference.normalize(fallback.getModelReference()));
        fallback.setReasoning(RuntimeConfigSupport.normalizeNonBlankString(fallback.getReasoning(), null));
        fallback.setTemperature(normalizeTemperature(fallback.getTemperature()));
        return fallback.getModelReference() == null ? null : fallback;
    }

    private Double normalizeTemperature(Double temperature) {
        if (temperature == null) {
            return null;
        }
        if (temperature < 0.0d) {
            return 0.0d;
        }
        if (temperature > 2.0d) {
            return 2.0d;
        }
        return temperature;
    }

    private String defaultModelForTier(String tier) {
        return switch (tier) {
            case "balanced" -> RuntimeConfigDefaults.DEFAULT_BALANCED_MODEL;
            case "smart" -> DEFAULT_SMART_MODEL;
            case "deep" -> DEFAULT_DEEP_MODEL;
            case "coding" -> DEFAULT_CODING_MODEL;
            default -> null;
        };
    }

    private String defaultReasoningForTier(String tier) {
        return switch (tier) {
            case "balanced" -> RuntimeConfigDefaults.DEFAULT_BALANCED_REASONING;
            case "smart" -> DEFAULT_SMART_REASONING;
            case "deep" -> DEFAULT_DEEP_REASONING;
            case "coding" -> DEFAULT_CODING_REASONING;
            default -> REASONING_NONE;
        };
    }
}

final class ToolConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getTools() == null) {
            cfg.setTools(RuntimeConfig.ToolsConfig.builder().build());
        }
        cfg.getTools().setShellEnvironmentVariables(
                normalizeShellEnvironmentVariables(cfg.getTools().getShellEnvironmentVariables()));
        if (cfg.getMcp() == null) {
            cfg.setMcp(new RuntimeConfig.McpConfig());
        }
    }

    private List<RuntimeConfig.ShellEnvironmentVariable> normalizeShellEnvironmentVariables(
            List<RuntimeConfig.ShellEnvironmentVariable> variables) {
        if (variables == null || variables.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, RuntimeConfig.ShellEnvironmentVariable> deduplicated = new LinkedHashMap<>();
        for (RuntimeConfig.ShellEnvironmentVariable variable : variables) {
            if (variable == null || variable.getName() == null || variable.getName().isBlank()) {
                continue;
            }
            String normalizedName = variable.getName().trim();
            String normalizedValue = variable.getValue() != null ? variable.getValue() : "";
            deduplicated.put(normalizedName, RuntimeConfig.ShellEnvironmentVariable.builder().name(normalizedName)
                    .value(normalizedValue).build());
        }
        return new ArrayList<>(deduplicated.values());
    }
}

final class VoiceConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getVoice() == null) {
            cfg.setVoice(new RuntimeConfig.VoiceConfig());
        }
    }
}

final class RateLimitConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getRateLimit() == null) {
            cfg.setRateLimit(new RuntimeConfig.RateLimitConfig());
        }
    }
}

final class SecurityConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getSecurity() == null) {
            cfg.setSecurity(new RuntimeConfig.SecurityConfig());
        }
    }
}

final class AutoModeConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getAutoMode() == null) {
            cfg.setAutoMode(new RuntimeConfig.AutoModeConfig());
        }
        if (cfg.getAutoMode().getReflectionEnabled() == null) {
            cfg.getAutoMode().setReflectionEnabled(DEFAULT_AUTO_REFLECTION_ENABLED);
        }
        if (cfg.getAutoMode().getReflectionFailureThreshold() == null) {
            cfg.getAutoMode().setReflectionFailureThreshold(DEFAULT_AUTO_REFLECTION_FAILURE_THRESHOLD);
        }
    }
}

final class PlanConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getPlan() == null) {
            cfg.setPlan(new RuntimeConfig.PlanConfig());
        }
        cfg.getPlan().setModelTier(RuntimeConfigSupport.normalizeOptionalModelTier(cfg.getPlan().getModelTier()));
    }
}

final class SkillConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getSkills() == null) {
            cfg.setSkills(new RuntimeConfig.SkillsConfig());
        }
    }
}

final class UpdateConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getUpdate() == null) {
            cfg.setUpdate(new RuntimeConfig.UpdateConfig());
        }
        if (cfg.getUpdate().getAutoEnabled() == null) {
            cfg.getUpdate().setAutoEnabled(DEFAULT_UPDATE_AUTO_ENABLED);
        }
        Integer updateCheckIntervalMinutes = cfg.getUpdate().getCheckIntervalMinutes();
        if (updateCheckIntervalMinutes == null || updateCheckIntervalMinutes < 1) {
            cfg.getUpdate().setCheckIntervalMinutes(DEFAULT_UPDATE_CHECK_INTERVAL_MINUTES);
        }
        if (cfg.getUpdate().getMaintenanceWindowEnabled() == null) {
            cfg.getUpdate().setMaintenanceWindowEnabled(DEFAULT_UPDATE_MAINTENANCE_WINDOW_ENABLED);
        }
        cfg.getUpdate().setMaintenanceWindowStartUtc(RuntimeConfigSupport.normalizeUtcTimeValue(
                cfg.getUpdate().getMaintenanceWindowStartUtc(), DEFAULT_UPDATE_MAINTENANCE_WINDOW_START_UTC));
        cfg.getUpdate().setMaintenanceWindowEndUtc(RuntimeConfigSupport.normalizeUtcTimeValue(
                cfg.getUpdate().getMaintenanceWindowEndUtc(), DEFAULT_UPDATE_MAINTENANCE_WINDOW_END_UTC));
    }
}

final class ObservabilityConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getUsage() == null) {
            cfg.setUsage(new RuntimeConfig.UsageConfig());
        }
        if (cfg.getTelemetry() == null) {
            cfg.setTelemetry(new RuntimeConfig.TelemetryConfig());
        }
        if (cfg.getTracing() == null) {
            cfg.setTracing(new RuntimeConfig.TracingConfig());
        }
        normalizeTracing(cfg.getTracing());
    }

    private void normalizeTracing(RuntimeConfig.TracingConfig tracing) {
        if (tracing.getEnabled() == null) {
            tracing.setEnabled(DEFAULT_TRACING_ENABLED);
        }
        if (tracing.getPayloadSnapshotsEnabled() == null) {
            tracing.setPayloadSnapshotsEnabled(DEFAULT_TRACING_PAYLOAD_SNAPSHOTS_ENABLED);
        }
        Integer sessionTraceBudgetMb = tracing.getSessionTraceBudgetMb();
        if (sessionTraceBudgetMb == null || sessionTraceBudgetMb < 1) {
            tracing.setSessionTraceBudgetMb(DEFAULT_TRACING_SESSION_TRACE_BUDGET_MB);
        }
        Integer maxSnapshotSizeKb = tracing.getMaxSnapshotSizeKb();
        if (maxSnapshotSizeKb == null || maxSnapshotSizeKb < 1) {
            tracing.setMaxSnapshotSizeKb(DEFAULT_TRACING_MAX_SNAPSHOT_SIZE_KB);
        }
        Integer maxSnapshotsPerSpan = tracing.getMaxSnapshotsPerSpan();
        if (maxSnapshotsPerSpan == null || maxSnapshotsPerSpan < 1) {
            tracing.setMaxSnapshotsPerSpan(DEFAULT_TRACING_MAX_SNAPSHOTS_PER_SPAN);
        }
        Integer maxTracesPerSession = tracing.getMaxTracesPerSession();
        if (maxTracesPerSession == null || maxTracesPerSession < 1) {
            tracing.setMaxTracesPerSession(DEFAULT_TRACING_MAX_TRACES_PER_SESSION);
        }
        if (tracing.getCaptureInboundPayloads() == null) {
            tracing.setCaptureInboundPayloads(DEFAULT_TRACING_CAPTURE_INBOUND_PAYLOADS);
        }
        if (tracing.getCaptureOutboundPayloads() == null) {
            tracing.setCaptureOutboundPayloads(DEFAULT_TRACING_CAPTURE_OUTBOUND_PAYLOADS);
        }
        if (tracing.getCaptureToolPayloads() == null) {
            tracing.setCaptureToolPayloads(DEFAULT_TRACING_CAPTURE_TOOL_PAYLOADS);
        }
        if (tracing.getCaptureLlmPayloads() == null) {
            tracing.setCaptureLlmPayloads(DEFAULT_TRACING_CAPTURE_LLM_PAYLOADS);
        }
        Double resiliencePayloadSampleRate = tracing.getResiliencePayloadSampleRate();
        if (resiliencePayloadSampleRate == null || resiliencePayloadSampleRate.isNaN()
                || resiliencePayloadSampleRate < 0.0d || resiliencePayloadSampleRate > 1.0d) {
            tracing.setResiliencePayloadSampleRate(DEFAULT_TRACING_RESILIENCE_PAYLOAD_SAMPLE_RATE);
        }
    }
}

final class SessionRuntimeConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getCompaction() == null) {
            cfg.setCompaction(new RuntimeConfig.CompactionConfig());
        }
        normalizeCompaction(cfg.getCompaction());
        if (cfg.getTurn() == null) {
            cfg.setTurn(new RuntimeConfig.TurnConfig());
        }
        normalizeTurn(cfg.getTurn());
        if (cfg.getToolLoop() == null) {
            cfg.setToolLoop(new RuntimeConfig.ToolLoopConfig());
        }
        normalizeToolLoop(cfg.getToolLoop());
        if (cfg.getSessionRetention() == null) {
            cfg.setSessionRetention(new RuntimeConfig.SessionRetentionConfig());
        }
        normalizeSessionRetention(cfg.getSessionRetention());
    }

    private void normalizeCompaction(RuntimeConfig.CompactionConfig compaction) {
        compaction.setTriggerMode(RuntimeConfigSupport.normalizeCompactionTriggerMode(compaction.getTriggerMode()));
        Double modelThresholdRatio = compaction.getModelThresholdRatio();
        if (modelThresholdRatio == null || modelThresholdRatio <= 0.0d || modelThresholdRatio > 1.0d) {
            compaction.setModelThresholdRatio(DEFAULT_COMPACTION_MODEL_THRESHOLD_RATIO);
        }
        if (compaction.getPreserveTurnBoundaries() == null) {
            compaction.setPreserveTurnBoundaries(DEFAULT_COMPACTION_PRESERVE_TURN_BOUNDARIES);
        }
        if (compaction.getDetailsEnabled() == null) {
            compaction.setDetailsEnabled(DEFAULT_COMPACTION_DETAILS_ENABLED);
        }
        if (compaction.getDetailsMaxItemsPerCategory() == null) {
            compaction.setDetailsMaxItemsPerCategory(DEFAULT_COMPACTION_DETAILS_MAX_ITEMS);
        }
        if (compaction.getSummaryTimeoutMs() == null) {
            compaction.setSummaryTimeoutMs(DEFAULT_COMPACTION_SUMMARY_TIMEOUT_MS);
        }
    }

    private void normalizeTurn(RuntimeConfig.TurnConfig turn) {
        Integer maxSkillTransitions = turn.getMaxSkillTransitions();
        if (maxSkillTransitions == null || maxSkillTransitions < 1) {
            turn.setMaxSkillTransitions(DEFAULT_TURN_MAX_SKILL_TRANSITIONS);
        }
        if (turn.getAutoRetryEnabled() == null) {
            turn.setAutoRetryEnabled(DEFAULT_TURN_AUTO_RETRY_ENABLED);
        }
        if (turn.getAutoRetryMaxAttempts() == null) {
            turn.setAutoRetryMaxAttempts(DEFAULT_TURN_AUTO_RETRY_MAX_ATTEMPTS);
        }
        if (turn.getAutoRetryBaseDelayMs() == null) {
            turn.setAutoRetryBaseDelayMs(DEFAULT_TURN_AUTO_RETRY_BASE_DELAY_MS);
        }
        if (turn.getQueueSteeringEnabled() == null) {
            turn.setQueueSteeringEnabled(DEFAULT_TURN_QUEUE_STEERING_ENABLED);
        }
        if (turn.getQueueSteeringMode() == null || turn.getQueueSteeringMode().isBlank()) {
            turn.setQueueSteeringMode(DEFAULT_TURN_QUEUE_STEERING_MODE);
        } else {
            turn.setQueueSteeringMode(RuntimeConfigSupport.normalizeQueueMode(turn.getQueueSteeringMode()));
        }
        if (turn.getQueueFollowUpMode() == null || turn.getQueueFollowUpMode().isBlank()) {
            turn.setQueueFollowUpMode(DEFAULT_TURN_QUEUE_FOLLOW_UP_MODE);
        } else {
            turn.setQueueFollowUpMode(RuntimeConfigSupport.normalizeQueueMode(turn.getQueueFollowUpMode()));
        }
        if (turn.getProgressUpdatesEnabled() == null) {
            turn.setProgressUpdatesEnabled(DEFAULT_TURN_PROGRESS_UPDATES_ENABLED);
        }
        if (turn.getProgressIntentEnabled() == null) {
            turn.setProgressIntentEnabled(DEFAULT_TURN_PROGRESS_INTENT_ENABLED);
        }
        Integer progressBatchSize = turn.getProgressBatchSize();
        if (progressBatchSize == null || progressBatchSize < 1) {
            turn.setProgressBatchSize(DEFAULT_TURN_PROGRESS_BATCH_SIZE);
        }
        Integer progressMaxSilenceSeconds = turn.getProgressMaxSilenceSeconds();
        if (progressMaxSilenceSeconds == null || progressMaxSilenceSeconds < 1) {
            turn.setProgressMaxSilenceSeconds(DEFAULT_TURN_PROGRESS_MAX_SILENCE_SECONDS);
        }
        Integer progressSummaryTimeoutMs = turn.getProgressSummaryTimeoutMs();
        if (progressSummaryTimeoutMs == null || progressSummaryTimeoutMs < 1000) {
            turn.setProgressSummaryTimeoutMs(DEFAULT_TURN_PROGRESS_SUMMARY_TIMEOUT_MS);
        }
    }

    private void normalizeToolLoop(RuntimeConfig.ToolLoopConfig toolLoop) {
        toolLoop.setMaxLlmCalls(positiveOrDefault(toolLoop.getMaxLlmCalls(), DEFAULT_TOOL_LOOP_MAX_LLM_CALLS));
        toolLoop.setMaxToolExecutions(
                positiveOrDefault(toolLoop.getMaxToolExecutions(), DEFAULT_TOOL_LOOP_MAX_TOOL_EXECUTIONS));
        if (toolLoop.getRepeatGuardEnabled() == null) {
            toolLoop.setRepeatGuardEnabled(DEFAULT_TOOL_REPEAT_GUARD_ENABLED);
        }
        if (toolLoop.getRepeatGuardShadowMode() == null) {
            toolLoop.setRepeatGuardShadowMode(DEFAULT_TOOL_REPEAT_GUARD_SHADOW_MODE);
        }
        toolLoop.setRepeatGuardMaxSameObservePerTurn(positiveOrDefault(
                toolLoop.getRepeatGuardMaxSameObservePerTurn(), DEFAULT_TOOL_REPEAT_GUARD_MAX_SAME_OBSERVE_PER_TURN));
        toolLoop.setRepeatGuardMaxSameUnknownPerTurn(positiveOrDefault(
                toolLoop.getRepeatGuardMaxSameUnknownPerTurn(), DEFAULT_TOOL_REPEAT_GUARD_MAX_SAME_UNKNOWN_PER_TURN));
        toolLoop.setRepeatGuardMaxBlockedRepeatsPerTurn(positiveOrDefault(
                toolLoop.getRepeatGuardMaxBlockedRepeatsPerTurn(),
                DEFAULT_TOOL_REPEAT_GUARD_MAX_BLOCKED_REPEATS_PER_TURN));
        toolLoop.setRepeatGuardMinPollIntervalSeconds(positiveOrDefault(
                toolLoop.getRepeatGuardMinPollIntervalSeconds(), DEFAULT_TOOL_REPEAT_GUARD_MIN_POLL_INTERVAL_SECONDS));
        toolLoop.setRepeatGuardAutoLedgerTtlMinutes(positiveOrDefault(
                toolLoop.getRepeatGuardAutoLedgerTtlMinutes(), DEFAULT_TOOL_REPEAT_GUARD_AUTO_LEDGER_TTL_MINUTES));
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value != null && value >= 1 ? value : defaultValue;
    }

    private long positiveOrDefault(Long value, long defaultValue) {
        return value != null && value >= 1L ? value : defaultValue;
    }

    private void normalizeSessionRetention(RuntimeConfig.SessionRetentionConfig sessionRetention) {
        if (sessionRetention.getEnabled() == null) {
            sessionRetention.setEnabled(DEFAULT_SESSION_RETENTION_ENABLED);
        }
        if (sessionRetention.getMaxAge() == null || sessionRetention.getMaxAge().isBlank()
                || !RuntimeConfigSupport.isValidDuration(sessionRetention.getMaxAge())) {
            sessionRetention.setMaxAge(DEFAULT_SESSION_RETENTION_MAX_AGE.toString());
        }
        if (sessionRetention.getCleanupInterval() == null || sessionRetention.getCleanupInterval().isBlank()
                || !RuntimeConfigSupport.isValidDuration(sessionRetention.getCleanupInterval())) {
            sessionRetention.setCleanupInterval(DEFAULT_SESSION_RETENTION_CLEANUP_INTERVAL.toString());
        }
        if (sessionRetention.getProtectActiveSessions() == null) {
            sessionRetention.setProtectActiveSessions(DEFAULT_SESSION_RETENTION_PROTECT_ACTIVE);
        }
        if (sessionRetention.getProtectSessionsWithPlans() == null) {
            sessionRetention.setProtectSessionsWithPlans(DEFAULT_SESSION_RETENTION_PROTECT_PLANS);
        }
        if (sessionRetention.getProtectSessionsWithDelayedActions() == null) {
            sessionRetention.setProtectSessionsWithDelayedActions(DEFAULT_SESSION_RETENTION_PROTECT_DELAYED_ACTIONS);
        }
    }
}

final class DelayedActionsConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getDelayedActions() == null) {
            cfg.setDelayedActions(new RuntimeConfig.DelayedActionsConfig());
        }
        RuntimeConfig.DelayedActionsConfig delayedActions = cfg.getDelayedActions();
        if (delayedActions.getEnabled() == null) {
            delayedActions.setEnabled(DEFAULT_DELAYED_ACTIONS_ENABLED);
        }
        Integer delayedTickSeconds = delayedActions.getTickSeconds();
        if (delayedTickSeconds == null || delayedTickSeconds < 1) {
            delayedActions.setTickSeconds(DEFAULT_DELAYED_ACTIONS_TICK_SECONDS);
        }
        Integer delayedMaxPending = delayedActions.getMaxPendingPerSession();
        if (delayedMaxPending == null || delayedMaxPending < 1
                || delayedMaxPending > DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION) {
            delayedActions.setMaxPendingPerSession(DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION);
        }
        if (delayedActions.getMaxDelay() == null || delayedActions.getMaxDelay().isBlank()
                || !RuntimeConfigSupport.isValidDuration(delayedActions.getMaxDelay())) {
            delayedActions.setMaxDelay(DEFAULT_DELAYED_ACTIONS_MAX_DELAY.toString());
        }
        Integer delayedMaxAttempts = delayedActions.getDefaultMaxAttempts();
        if (delayedMaxAttempts == null || delayedMaxAttempts < 1) {
            delayedActions.setDefaultMaxAttempts(DEFAULT_DELAYED_ACTIONS_MAX_ATTEMPTS);
        }
        if (delayedActions.getLeaseDuration() == null || delayedActions.getLeaseDuration().isBlank()
                || !RuntimeConfigSupport.isValidDuration(delayedActions.getLeaseDuration())) {
            delayedActions.setLeaseDuration(DEFAULT_DELAYED_ACTIONS_LEASE_DURATION.toString());
        }
        if (delayedActions.getRetentionAfterCompletion() == null
                || delayedActions.getRetentionAfterCompletion().isBlank()
                || !RuntimeConfigSupport.isValidDuration(delayedActions.getRetentionAfterCompletion())) {
            delayedActions.setRetentionAfterCompletion(DEFAULT_DELAYED_ACTIONS_RETENTION.toString());
        }
        if (delayedActions.getAllowRunLater() == null) {
            delayedActions.setAllowRunLater(DEFAULT_DELAYED_ACTIONS_ALLOW_RUN_LATER);
        }
    }
}

final class MemoryConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getMemory() == null) {
            cfg.setMemory(new RuntimeConfig.MemoryConfig());
        }
        RuntimeConfig.MemoryConfig memoryConfig = cfg.getMemory();
        if (!Integer.valueOf(DEFAULT_MEMORY_VERSION).equals(memoryConfig.getVersion())) {
            memoryConfig.setVersion(DEFAULT_MEMORY_VERSION);
        }
        if (memoryConfig.getSoftPromptBudgetTokens() == null) {
            memoryConfig.setSoftPromptBudgetTokens(DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS);
        }
        if (memoryConfig.getMaxPromptBudgetTokens() == null) {
            memoryConfig.setMaxPromptBudgetTokens(DEFAULT_MEMORY_MAX_PROMPT_BUDGET_TOKENS);
        }
        if (memoryConfig.getWorkingTopK() == null) {
            memoryConfig.setWorkingTopK(DEFAULT_MEMORY_WORKING_TOP_K);
        }
        if (memoryConfig.getEpisodicTopK() == null) {
            memoryConfig.setEpisodicTopK(DEFAULT_MEMORY_EPISODIC_TOP_K);
        }
        if (memoryConfig.getSemanticTopK() == null) {
            memoryConfig.setSemanticTopK(DEFAULT_MEMORY_SEMANTIC_TOP_K);
        }
        if (memoryConfig.getProceduralTopK() == null) {
            memoryConfig.setProceduralTopK(DEFAULT_MEMORY_PROCEDURAL_TOP_K);
        }
        if (memoryConfig.getPromotionEnabled() == null) {
            memoryConfig.setPromotionEnabled(DEFAULT_MEMORY_PROMOTION_ENABLED);
        }
        if (memoryConfig.getPromotionMinConfidence() == null) {
            memoryConfig.setPromotionMinConfidence(DEFAULT_MEMORY_PROMOTION_MIN_CONFIDENCE);
        }
        if (memoryConfig.getDecayEnabled() == null) {
            memoryConfig.setDecayEnabled(DEFAULT_MEMORY_DECAY_ENABLED);
        }
        if (memoryConfig.getDecayDays() == null) {
            memoryConfig.setDecayDays(DEFAULT_MEMORY_DECAY_DAYS);
        }
        if (memoryConfig.getRetrievalLookbackDays() == null) {
            memoryConfig.setRetrievalLookbackDays(DEFAULT_MEMORY_RETRIEVAL_LOOKBACK_DAYS);
        }
        if (memoryConfig.getCodeAwareExtractionEnabled() == null) {
            memoryConfig.setCodeAwareExtractionEnabled(DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED);
        }
        normalizeNestedMemoryConfig(memoryConfig);
    }

    private void normalizeNestedMemoryConfig(RuntimeConfig.MemoryConfig memoryConfig) {
        if (memoryConfig.getDisclosure() == null) {
            memoryConfig.setDisclosure(RuntimeConfig.MemoryDisclosureConfig.builder().build());
        }
        if (memoryConfig.getReranking() == null) {
            memoryConfig.setReranking(RuntimeConfig.MemoryRerankingConfig.builder().build());
        }
        if (memoryConfig.getDiagnostics() == null) {
            memoryConfig.setDiagnostics(RuntimeConfig.MemoryDiagnosticsConfig.builder().build());
        }
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = memoryConfig.getDisclosure();
        if (disclosureConfig.getMode() == null || disclosureConfig.getMode().isBlank()) {
            disclosureConfig.setMode(DEFAULT_MEMORY_DISCLOSURE_MODE);
        }
        if (disclosureConfig.getPromptStyle() == null || disclosureConfig.getPromptStyle().isBlank()) {
            disclosureConfig.setPromptStyle(DEFAULT_MEMORY_PROMPT_STYLE);
        }
        if (disclosureConfig.getToolExpansionEnabled() == null) {
            disclosureConfig.setToolExpansionEnabled(DEFAULT_MEMORY_TOOL_EXPANSION_ENABLED);
        }
        if (disclosureConfig.getDisclosureHintsEnabled() == null) {
            disclosureConfig.setDisclosureHintsEnabled(DEFAULT_MEMORY_DISCLOSURE_HINTS_ENABLED);
        }
        if (disclosureConfig.getDetailMinScore() == null) {
            disclosureConfig.setDetailMinScore(DEFAULT_MEMORY_DISCLOSURE_DETAIL_MIN_SCORE);
        }
        RuntimeConfig.MemoryRerankingConfig rerankingConfig = memoryConfig.getReranking();
        if (rerankingConfig.getEnabled() == null) {
            rerankingConfig.setEnabled(DEFAULT_MEMORY_RERANKING_ENABLED);
        }
        if (rerankingConfig.getProfile() == null || rerankingConfig.getProfile().isBlank()) {
            rerankingConfig.setProfile(DEFAULT_MEMORY_RERANKING_PROFILE);
        }
        RuntimeConfig.MemoryDiagnosticsConfig diagnosticsConfig = memoryConfig.getDiagnostics();
        if (diagnosticsConfig.getVerbosity() == null || diagnosticsConfig.getVerbosity().isBlank()) {
            diagnosticsConfig.setVerbosity(DEFAULT_MEMORY_DIAGNOSTICS_VERBOSITY);
        }
    }
}

final class ResilienceConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getResilience() == null) {
            cfg.setResilience(RuntimeConfig.ResilienceConfig.builder().build());
        }
        normalizeResilienceConfig(cfg.getResilience());
        normalizeFollowThroughConfig(cfg.getResilience());
    }

    private void normalizeResilienceConfig(RuntimeConfig.ResilienceConfig resilienceConfig) {
        RuntimeConfig.ResilienceConfig defaults = RuntimeConfig.ResilienceConfig.builder().build();
        if (resilienceConfig.getEnabled() == null) {
            resilienceConfig.setEnabled(defaults.getEnabled());
        }
        Integer hotRetryMaxAttempts = resilienceConfig.getHotRetryMaxAttempts();
        if (hotRetryMaxAttempts == null || hotRetryMaxAttempts < 0) {
            resilienceConfig.setHotRetryMaxAttempts(defaults.getHotRetryMaxAttempts());
        }
        Long hotRetryBaseDelayMs = resilienceConfig.getHotRetryBaseDelayMs();
        if (hotRetryBaseDelayMs == null || hotRetryBaseDelayMs < 0L) {
            resilienceConfig.setHotRetryBaseDelayMs(defaults.getHotRetryBaseDelayMs());
        }
        Long hotRetryCapMs = resilienceConfig.getHotRetryCapMs();
        if (hotRetryCapMs == null || hotRetryCapMs < 0L) {
            resilienceConfig.setHotRetryCapMs(defaults.getHotRetryCapMs());
        }
        Integer circuitBreakerFailureThreshold = resilienceConfig.getCircuitBreakerFailureThreshold();
        if (circuitBreakerFailureThreshold == null || circuitBreakerFailureThreshold < 1) {
            resilienceConfig.setCircuitBreakerFailureThreshold(defaults.getCircuitBreakerFailureThreshold());
        }
        Long circuitBreakerWindowSeconds = resilienceConfig.getCircuitBreakerWindowSeconds();
        if (circuitBreakerWindowSeconds == null || circuitBreakerWindowSeconds < 1L) {
            resilienceConfig.setCircuitBreakerWindowSeconds(defaults.getCircuitBreakerWindowSeconds());
        }
        Long circuitBreakerOpenDurationSeconds = resilienceConfig.getCircuitBreakerOpenDurationSeconds();
        if (circuitBreakerOpenDurationSeconds == null || circuitBreakerOpenDurationSeconds < 1L) {
            resilienceConfig.setCircuitBreakerOpenDurationSeconds(defaults.getCircuitBreakerOpenDurationSeconds());
        }
        if (resilienceConfig.getDegradationCompactContext() == null) {
            resilienceConfig.setDegradationCompactContext(defaults.getDegradationCompactContext());
        }
        Integer degradationCompactMinMessages = resilienceConfig.getDegradationCompactMinMessages();
        if (degradationCompactMinMessages == null || degradationCompactMinMessages < 0) {
            resilienceConfig.setDegradationCompactMinMessages(defaults.getDegradationCompactMinMessages());
        }
        if (resilienceConfig.getDegradationDowngradeModel() == null) {
            resilienceConfig.setDegradationDowngradeModel(defaults.getDegradationDowngradeModel());
        }
        resilienceConfig.setDegradationFallbackModelTier(
                normalizeResilienceFallbackTier(resilienceConfig.getDegradationFallbackModelTier()));
        if (resilienceConfig.getDegradationStripTools() == null) {
            resilienceConfig.setDegradationStripTools(defaults.getDegradationStripTools());
        }
        if (resilienceConfig.getColdRetryEnabled() == null) {
            resilienceConfig.setColdRetryEnabled(defaults.getColdRetryEnabled());
        }
        Integer coldRetryMaxAttempts = resilienceConfig.getColdRetryMaxAttempts();
        if (coldRetryMaxAttempts == null || coldRetryMaxAttempts < 1) {
            resilienceConfig.setColdRetryMaxAttempts(defaults.getColdRetryMaxAttempts());
        }
        Integer l2ProviderFallbackMaxAttempts = resilienceConfig.getL2ProviderFallbackMaxAttempts();
        if (l2ProviderFallbackMaxAttempts == null || l2ProviderFallbackMaxAttempts < 1) {
            resilienceConfig.setL2ProviderFallbackMaxAttempts(5);
        }
    }

    private void normalizeFollowThroughConfig(RuntimeConfig.ResilienceConfig resilienceConfig) {
        RuntimeConfig.FollowThroughConfig defaults = RuntimeConfig.FollowThroughConfig.builder().build();
        if (resilienceConfig.getFollowThrough() == null) {
            resilienceConfig.setFollowThrough(RuntimeConfig.FollowThroughConfig.builder().build());
        }
        RuntimeConfig.FollowThroughConfig cfg = resilienceConfig.getFollowThrough();
        if (cfg.getEnabled() == null) {
            cfg.setEnabled(defaults.getEnabled());
        }
        String tier = ModelTierCatalog.normalizeTierId(cfg.getModelTier());
        if (tier == null || !ModelTierCatalog.isExplicitSelectableTier(tier)) {
            tier = defaults.getModelTier();
        }
        cfg.setModelTier(tier);
        Integer timeoutSeconds = cfg.getTimeoutSeconds();
        if (timeoutSeconds == null || timeoutSeconds < 1) {
            cfg.setTimeoutSeconds(defaults.getTimeoutSeconds());
        }
        Integer maxChainDepth = cfg.getMaxChainDepth();
        if (maxChainDepth == null || maxChainDepth < 0) {
            cfg.setMaxChainDepth(defaults.getMaxChainDepth());
        }
    }

    private String normalizeResilienceFallbackTier(String value) {
        String normalizedTierId = ModelTierCatalog.normalizeTierId(value);
        return ModelTierCatalog.isExplicitSelectableTier(normalizedTierId) ? normalizedTierId : "balanced";
    }
}

final class HiveConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getHive() == null) {
            cfg.setHive(new RuntimeConfig.HiveConfig());
        }
        if (cfg.getHive().getEnabled() == null) {
            cfg.getHive().setEnabled(DEFAULT_HIVE_ENABLED);
        }
        if (cfg.getHive().getAutoConnect() == null) {
            cfg.getHive().setAutoConnect(DEFAULT_HIVE_AUTO_CONNECT);
        }
        if (cfg.getHive().getManagedByProperties() == null) {
            cfg.getHive().setManagedByProperties(DEFAULT_HIVE_MANAGED_BY_PROPERTIES);
        }
        normalizeHiveSdlcConfig(cfg.getHive());
    }

    private void normalizeHiveSdlcConfig(RuntimeConfig.HiveConfig hiveConfig) {
        if (hiveConfig.getSdlc() == null) {
            hiveConfig.setSdlc(new RuntimeConfig.HiveSdlcConfig());
        }
        RuntimeConfig.HiveSdlcConfig sdlcConfig = hiveConfig.getSdlc();
        if (sdlcConfig.getCurrentContextEnabled() == null) {
            sdlcConfig.setCurrentContextEnabled(DEFAULT_HIVE_SDLC_FUNCTION_ENABLED);
        }
        if (sdlcConfig.getCardReadEnabled() == null) {
            sdlcConfig.setCardReadEnabled(DEFAULT_HIVE_SDLC_FUNCTION_ENABLED);
        }
        if (sdlcConfig.getCardSearchEnabled() == null) {
            sdlcConfig.setCardSearchEnabled(DEFAULT_HIVE_SDLC_FUNCTION_ENABLED);
        }
        if (sdlcConfig.getThreadMessageEnabled() == null) {
            sdlcConfig.setThreadMessageEnabled(DEFAULT_HIVE_SDLC_FUNCTION_ENABLED);
        }
        if (sdlcConfig.getReviewRequestEnabled() == null) {
            sdlcConfig.setReviewRequestEnabled(DEFAULT_HIVE_SDLC_FUNCTION_ENABLED);
        }
        if (sdlcConfig.getFollowupCardCreateEnabled() == null) {
            sdlcConfig.setFollowupCardCreateEnabled(DEFAULT_HIVE_SDLC_FUNCTION_ENABLED);
        }
        if (sdlcConfig.getLifecycleSignalEnabled() == null) {
            sdlcConfig.setLifecycleSignalEnabled(DEFAULT_HIVE_SDLC_FUNCTION_ENABLED);
        }
    }
}

final class SelfEvolvingConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        if (cfg.getSelfEvolving() == null) {
            cfg.setSelfEvolving(new RuntimeConfig.SelfEvolvingConfig());
        }
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = cfg.getSelfEvolving();
        if (selfEvolvingConfig.getManagedByProperties() == null) {
            selfEvolvingConfig.setManagedByProperties(false);
        }
        if (selfEvolvingConfig.getOverriddenPaths() == null) {
            selfEvolvingConfig.setOverriddenPaths(new ArrayList<>());
        } else {
            selfEvolvingConfig.setOverriddenPaths(new ArrayList<>(selfEvolvingConfig.getOverriddenPaths()));
        }
        if (selfEvolvingConfig.getEnabled() == null) {
            selfEvolvingConfig.setEnabled(DEFAULT_SELF_EVOLVING_ENABLED);
        }
        selfEvolvingConfig.setTracePayloadOverride(DEFAULT_SELF_EVOLVING_TRACE_PAYLOAD_OVERRIDE);
        normalizeTactics(selfEvolvingConfig);
        normalizeCapture(selfEvolvingConfig);
        normalizeJudge(selfEvolvingConfig);
        normalizeEvolution(selfEvolvingConfig);
        normalizePromotion(selfEvolvingConfig);
        normalizeBenchmark(selfEvolvingConfig);
        normalizeHive(selfEvolvingConfig);
    }

    private void normalizeTactics(RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig) {
        if (selfEvolvingConfig.getTactics() == null) {
            selfEvolvingConfig.setTactics(new RuntimeConfig.SelfEvolvingTacticsConfig());
        }
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = selfEvolvingConfig.getTactics();
        if (tacticsConfig.getEnabled() == null) {
            tacticsConfig.setEnabled(DEFAULT_SELF_EVOLVING_TACTICS_ENABLED);
        }
        if (tacticsConfig.getSearch() == null) {
            tacticsConfig.setSearch(new RuntimeConfig.SelfEvolvingTacticSearchConfig());
        }
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = tacticsConfig.getSearch();
        searchConfig.setMode(RuntimeConfigSupport.normalizeNonBlankString(searchConfig.getMode(),
                DEFAULT_SELF_EVOLVING_TACTIC_SEARCH_MODE));
        if (searchConfig.getBm25() == null) {
            searchConfig.setBm25(new RuntimeConfig.SelfEvolvingTacticBm25Config());
        }
        if (searchConfig.getBm25().getEnabled() == null) {
            searchConfig.getBm25().setEnabled(DEFAULT_SELF_EVOLVING_TACTIC_BM25_ENABLED);
        }
        normalizeEmbeddings(searchConfig);
        normalizeTacticToggles(searchConfig);
    }

    private void normalizeEmbeddings(RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig) {
        if (searchConfig.getEmbeddings() == null) {
            searchConfig.setEmbeddings(new RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig());
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig = searchConfig.getEmbeddings();
        if (embeddingsConfig.getEnabled() == null) {
            embeddingsConfig.setEnabled(DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_ENABLED);
        }
        embeddingsConfig.setEnabled("hybrid".equalsIgnoreCase(searchConfig.getMode()));
        embeddingsConfig.setProvider(
                normalizeSelfEvolvingEmbeddingProvider(embeddingsConfig.getProvider(), searchConfig.getMode()));
        embeddingsConfig.setBaseUrl(
                normalizeSelfEvolvingEmbeddingBaseUrl(embeddingsConfig.getBaseUrl(), embeddingsConfig.getProvider()));
        if (DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_PROVIDER.equals(embeddingsConfig.getProvider())) {
            embeddingsConfig.setModel(RuntimeConfigSupport.normalizeNonBlankString(embeddingsConfig.getModel(),
                    DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_MODEL));
        } else {
            embeddingsConfig.setModel(RuntimeConfigSupport.normalizeNonBlankString(embeddingsConfig.getModel(), null));
        }
        embeddingsConfig.setAutoFallbackToBm25(DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_AUTO_FALLBACK_TO_BM25);
        if (embeddingsConfig.getLocal() == null) {
            embeddingsConfig.setLocal(new RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig());
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig = embeddingsConfig.getLocal();
        if (localConfig.getAutoInstall() == null) {
            localConfig.setAutoInstall(DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_AUTO_INSTALL);
        }
        if (localConfig.getPullOnStart() == null) {
            localConfig.setPullOnStart(DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_PULL_ON_START);
        }
        if (localConfig.getRequireHealthyRuntime() == null) {
            localConfig.setRequireHealthyRuntime(DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_REQUIRE_HEALTHY_RUNTIME);
        }
        if (localConfig.getFailOpen() == null) {
            localConfig.setFailOpen(DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_FAIL_OPEN);
        }
        localConfig.setStartupTimeoutMs(DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_STARTUP_TIMEOUT_MS);
        if (localConfig.getInitialRestartBackoffMs() == null || localConfig.getInitialRestartBackoffMs() <= 0) {
            localConfig.setInitialRestartBackoffMs(DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_INITIAL_RESTART_BACKOFF_MS);
        }
        localConfig.setMinimumRuntimeVersion(RuntimeConfigSupport.normalizeNonBlankString(
                localConfig.getMinimumRuntimeVersion(), DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_MINIMUM_RUNTIME_VERSION));
    }

    private void normalizeTacticToggles(RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig) {
        if (searchConfig.getPersonalization() == null) {
            searchConfig.setPersonalization(new RuntimeConfig.SelfEvolvingToggleConfig());
        }
        if (searchConfig.getPersonalization().getEnabled() == null) {
            searchConfig.getPersonalization().setEnabled(DEFAULT_SELF_EVOLVING_TACTIC_PERSONALIZATION_ENABLED);
        }
        if (searchConfig.getNegativeMemory() == null) {
            searchConfig.setNegativeMemory(new RuntimeConfig.SelfEvolvingToggleConfig());
        }
        if (searchConfig.getNegativeMemory().getEnabled() == null) {
            searchConfig.getNegativeMemory().setEnabled(DEFAULT_SELF_EVOLVING_TACTIC_NEGATIVE_MEMORY_ENABLED);
        }
    }

    private void normalizeCapture(RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig) {
        if (selfEvolvingConfig.getCapture() == null) {
            selfEvolvingConfig.setCapture(new RuntimeConfig.SelfEvolvingCaptureConfig());
        }
        RuntimeConfig.SelfEvolvingCaptureConfig captureConfig = selfEvolvingConfig.getCapture();
        captureConfig.setLlm(normalizeCaptureMode(captureConfig.getLlm(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL));
        captureConfig.setTool(normalizeCaptureMode(captureConfig.getTool(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL));
        captureConfig
                .setContext(normalizeCaptureMode(captureConfig.getContext(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL));
        captureConfig.setSkill(normalizeCaptureMode(captureConfig.getSkill(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL));
        captureConfig.setTier(normalizeCaptureMode(captureConfig.getTier(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL));
        captureConfig
                .setInfra(normalizeCaptureMode(captureConfig.getInfra(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_META_ONLY));
    }

    private void normalizeJudge(RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig) {
        if (selfEvolvingConfig.getJudge() == null) {
            selfEvolvingConfig.setJudge(new RuntimeConfig.SelfEvolvingJudgeConfig());
        }
        RuntimeConfig.SelfEvolvingJudgeConfig judgeConfig = selfEvolvingConfig.getJudge();
        if (judgeConfig.getEnabled() == null) {
            judgeConfig.setEnabled(DEFAULT_SELF_EVOLVING_JUDGE_ENABLED);
        }
        judgeConfig.setPrimaryTier(
                normalizeSelfEvolvingJudgeTier(judgeConfig.getPrimaryTier(), DEFAULT_SELF_EVOLVING_JUDGE_PRIMARY_TIER));
        judgeConfig.setTiebreakerTier(normalizeSelfEvolvingJudgeTier(judgeConfig.getTiebreakerTier(),
                DEFAULT_SELF_EVOLVING_JUDGE_TIEBREAKER_TIER));
        judgeConfig.setEvolutionTier(normalizeSelfEvolvingJudgeTier(judgeConfig.getEvolutionTier(),
                DEFAULT_SELF_EVOLVING_JUDGE_EVOLUTION_TIER));
        if (judgeConfig.getRequireEvidenceAnchors() == null) {
            judgeConfig.setRequireEvidenceAnchors(DEFAULT_SELF_EVOLVING_REQUIRE_EVIDENCE_ANCHORS);
        }
        Double uncertaintyThreshold = judgeConfig.getUncertaintyThreshold();
        if (uncertaintyThreshold == null || uncertaintyThreshold < 0.0d || uncertaintyThreshold > 1.0d) {
            judgeConfig.setUncertaintyThreshold(DEFAULT_SELF_EVOLVING_UNCERTAINTY_THRESHOLD);
        }
    }

    private void normalizeEvolution(RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig) {
        if (selfEvolvingConfig.getEvolution() == null) {
            selfEvolvingConfig.setEvolution(new RuntimeConfig.SelfEvolvingEvolutionConfig());
        }
        RuntimeConfig.SelfEvolvingEvolutionConfig evolutionConfig = selfEvolvingConfig.getEvolution();
        if (evolutionConfig.getEnabled() == null) {
            evolutionConfig.setEnabled(DEFAULT_SELF_EVOLVING_EVOLUTION_ENABLED);
        }
        evolutionConfig.setModes(normalizeStringList(evolutionConfig.getModes(), DEFAULT_SELF_EVOLVING_MODES));
        evolutionConfig.setArtifactTypes(
                normalizeStringList(evolutionConfig.getArtifactTypes(), DEFAULT_SELF_EVOLVING_ARTIFACT_TYPES));
    }

    private void normalizePromotion(RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig) {
        if (selfEvolvingConfig.getPromotion() == null) {
            selfEvolvingConfig.setPromotion(new RuntimeConfig.SelfEvolvingPromotionConfig());
        }
        RuntimeConfig.SelfEvolvingPromotionConfig promotionConfig = selfEvolvingConfig.getPromotion();
        promotionConfig.setMode(RuntimeConfigSupport.normalizeNonBlankString(promotionConfig.getMode(),
                DEFAULT_SELF_EVOLVING_PROMOTION_MODE));
        if (promotionConfig.getAllowAutoAccept() == null) {
            promotionConfig.setAllowAutoAccept(DEFAULT_SELF_EVOLVING_ALLOW_AUTO_ACCEPT);
        }
        if (promotionConfig.getShadowRequired() == null) {
            promotionConfig.setShadowRequired(DEFAULT_SELF_EVOLVING_SHADOW_REQUIRED);
        }
        if (promotionConfig.getCanaryRequired() == null) {
            promotionConfig.setCanaryRequired(DEFAULT_SELF_EVOLVING_CANARY_REQUIRED);
        }
        if (promotionConfig.getHiveApprovalPreferred() == null) {
            promotionConfig.setHiveApprovalPreferred(DEFAULT_SELF_EVOLVING_HIVE_APPROVAL_PREFERRED);
        }
    }

    private void normalizeBenchmark(RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig) {
        if (selfEvolvingConfig.getBenchmark() == null) {
            selfEvolvingConfig.setBenchmark(new RuntimeConfig.SelfEvolvingBenchmarkConfig());
        }
        RuntimeConfig.SelfEvolvingBenchmarkConfig benchmarkConfig = selfEvolvingConfig.getBenchmark();
        if (benchmarkConfig.getEnabled() == null) {
            benchmarkConfig.setEnabled(DEFAULT_SELF_EVOLVING_BENCHMARK_ENABLED);
        }
        if (benchmarkConfig.getHarvestProductionRuns() == null) {
            benchmarkConfig.setHarvestProductionRuns(DEFAULT_SELF_EVOLVING_HARVEST_PRODUCTION_RUNS);
        }
        if (benchmarkConfig.getAutoCreateRegressionCases() == null) {
            benchmarkConfig.setAutoCreateRegressionCases(DEFAULT_SELF_EVOLVING_AUTO_CREATE_REGRESSION_CASES);
        }
    }

    private void normalizeHive(RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig) {
        if (selfEvolvingConfig.getHive() == null) {
            selfEvolvingConfig.setHive(new RuntimeConfig.SelfEvolvingHiveConfig());
        }
        RuntimeConfig.SelfEvolvingHiveConfig hiveConfig = selfEvolvingConfig.getHive();
        if (hiveConfig.getPublishInspectionProjection() == null) {
            hiveConfig.setPublishInspectionProjection(DEFAULT_SELF_EVOLVING_PUBLISH_INSPECTION_PROJECTION);
        }
        if (hiveConfig.getReadonlyInspection() == null) {
            hiveConfig.setReadonlyInspection(DEFAULT_SELF_EVOLVING_READONLY_INSPECTION);
        }
    }

    private List<String> normalizeStringList(List<String> values, List<String> defaultValues) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>(defaultValues);
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String normalizedValue = RuntimeConfigSupport.normalizeNonBlankString(value, null);
            if (normalizedValue != null && !normalized.contains(normalizedValue)) {
                normalized.add(normalizedValue);
            }
        }
        if (normalized.isEmpty()) {
            return new ArrayList<>(defaultValues);
        }
        return normalized;
    }

    private String normalizeCaptureMode(String value, String defaultValue) {
        String normalizedValue = RuntimeConfigSupport.normalizeNonBlankString(value, defaultValue);
        if (normalizedValue == null) {
            return defaultValue;
        }
        if (DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL.equals(normalizedValue)
                || DEFAULT_SELF_EVOLVING_CAPTURE_MODE_META_ONLY.equals(normalizedValue)) {
            return normalizedValue;
        }
        return defaultValue;
    }

    private String normalizeSelfEvolvingEmbeddingProvider(String provider, String mode) {
        String normalizedProvider = RuntimeConfigSupport.normalizeNonBlankString(provider, null);
        if (normalizedProvider != null) {
            return normalizedProvider.toLowerCase(Locale.ROOT);
        }
        return "hybrid".equalsIgnoreCase(mode) ? DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_PROVIDER : null;
    }

    private String normalizeSelfEvolvingEmbeddingBaseUrl(String baseUrl, String provider) {
        String normalizedBaseUrl = RuntimeConfigSupport.normalizeNonBlankString(baseUrl, null);
        if (normalizedBaseUrl != null) {
            return normalizedBaseUrl;
        }
        if (DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_PROVIDER.equalsIgnoreCase(provider)) {
            return DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_BASE_URL;
        }
        return null;
    }

    private String normalizeSelfEvolvingJudgeTier(String value, String defaultValue) {
        String normalizedValue = RuntimeConfigSupport.normalizeNonBlankString(value, defaultValue);
        if (normalizedValue == null) {
            return defaultValue;
        }
        String normalizedTierId = ModelTierCatalog.normalizeTierId(normalizedValue);
        if ("standard".equals(normalizedTierId)) {
            normalizedTierId = "smart";
        } else if ("premium".equals(normalizedTierId)) {
            normalizedTierId = "deep";
        }
        return normalizedTierId != null && SUPPORTED_SELF_EVOLVING_JUDGE_TIERS.contains(normalizedTierId)
                ? normalizedTierId
                : defaultValue;
    }
}

final class SecretConfigService implements RuntimeConfigSectionService {
    @Override
    public void normalize(RuntimeConfig cfg) {
        cfg.getTelegram().setToken(normalizeSecret(cfg.getTelegram().getToken()));
        cfg.getVoice().setApiKey(normalizeSecret(cfg.getVoice().getApiKey()));
        cfg.getVoice().setWhisperSttApiKey(normalizeSecret(cfg.getVoice().getWhisperSttApiKey()));
        if (cfg.getLlm() != null && cfg.getLlm().getProviders() != null) {
            for (RuntimeConfig.LlmProviderConfig providerConfig : cfg.getLlm().getProviders().values()) {
                if (providerConfig != null) {
                    providerConfig.setApiKey(normalizeSecret(providerConfig.getApiKey()));
                }
            }
        }
        normalizeSelfEvolvingEmbeddingsApiKey(cfg);
    }

    private void normalizeSelfEvolvingEmbeddingsApiKey(RuntimeConfig cfg) {
        if (cfg.getSelfEvolving() == null || cfg.getSelfEvolving().getTactics() == null
                || cfg.getSelfEvolving().getTactics().getSearch() == null
                || cfg.getSelfEvolving().getTactics().getSearch().getEmbeddings() == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddings = cfg.getSelfEvolving().getTactics().getSearch()
                .getEmbeddings();
        embeddings.setApiKey(normalizeSecret(embeddings.getApiKey()));
    }

    private Secret normalizeSecret(Secret secret) {
        if (secret == null) {
            return null;
        }
        if (secret.getEncrypted() == null) {
            secret.setEncrypted(false);
        }
        if (secret.getPresent() == null) {
            secret.setPresent(Secret.hasValue(secret));
        } else if (Secret.hasValue(secret)) {
            secret.setPresent(true);
        }
        return secret;
    }
}

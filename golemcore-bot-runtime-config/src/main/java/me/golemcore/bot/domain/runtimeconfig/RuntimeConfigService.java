package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.*;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigSupport.normalizeCompactionTriggerMode;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigSupport.normalizeOptionalModelTier;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigSupport.normalizeVoiceProvider;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;
import me.golemcore.bot.port.outbound.ManagedPolicyRuntimeConfigPort;
import me.golemcore.bot.port.outbound.SelfEvolvingBootstrapOverridePort;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.port.outbound.RuntimeConfigAdminPort;
import me.golemcore.bot.port.outbound.SessionRetentionRuntimeConfigPort;
import me.golemcore.bot.port.outbound.RuntimeConfigPersistencePort;
import org.springframework.stereotype.Service;

/**
 * Service for managing application-level runtime configuration.
 * <p>
 * Configuration is stored in separate files per section in the preferences directory:
 * <ul>
 * <li>telegram.json - Telegram adapter configuration
 * <li>model-router.json - Model routing configuration
 * <li>llm.json - LLM provider configurations
 * <li>tools.json - Core tool configurations
 * <li>voice.json - ElevenLabs voice configuration
 * <li>... and more (see {@link RuntimeConfig.ConfigSection})
 * </ul>
 *
 * @see RuntimeConfig.ConfigSection
 */
@Service
@Slf4j
public class RuntimeConfigService
        implements RuntimeConfigQueryPort, RuntimeConfigAdminPort, SelfEvolvingRuntimeConfigPort,
        ManagedPolicyRuntimeConfigPort, SessionRetentionRuntimeConfigPort, ModelRoutingConfigView, AutoModeConfigView,
        UpdateRuntimeConfigView, TracingConfigView, ShellRuntimeConfigView, TurnRuntimeConfigView,
        ToolLoopRuntimeConfigView, MemoryRuntimeConfigView, DelayedActionsRuntimeConfigView, RuntimeConfigMutationPort {

    private final RuntimeConfigPersistencePort runtimeConfigPersistencePort;
    private final SelfEvolvingBootstrapOverridePort selfEvolvingBootstrapOverrideService;
    private final RuntimeConfigSnapshotProvider snapshotProvider;
    private final RuntimeConfigMutationService mutationService;
    private final RuntimeConfigRedactor redactor;
    private final RuntimeConfigNormalizer normalizer;

    public RuntimeConfigService(RuntimeConfigPersistencePort runtimeConfigPersistencePort,
            SelfEvolvingBootstrapOverridePort selfEvolvingBootstrapOverrideService,
            RuntimeConfigSnapshotProvider snapshotProvider, RuntimeConfigMutationService mutationService,
            RuntimeConfigRedactor redactor, RuntimeConfigNormalizer normalizer) {
        this.runtimeConfigPersistencePort = Objects.requireNonNull(runtimeConfigPersistencePort,
                "runtimeConfigPersistencePort must not be null");
        this.selfEvolvingBootstrapOverrideService = Objects.requireNonNull(selfEvolvingBootstrapOverrideService,
                "selfEvolvingBootstrapOverrideService must not be null");
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider must not be null");
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
    }

    // ==================== Section Validation ====================

    /**
     * Check if the given section ID is a valid configuration section.
     *
     * @param sectionId
     *            the section ID to validate (e.g., "telegram", "model-router")
     *
     * @return true if the section ID is in the whitelist
     */
    public static boolean isValidConfigSection(String sectionId) {
        return RuntimeConfig.ConfigSection.isValidSection(sectionId);
    }

    // ==================== Main Config Access ====================

    /**
     * Get current RuntimeConfig (lazy-loaded, cached).
     */
    public RuntimeConfig getRuntimeConfig() {
        return snapshotProvider.getOrLoad(() -> buildEffectiveRuntimeConfig(loadOrCreate()));
    }

    public RuntimeConfig getRuntimeConfigForApi() {
        RuntimeConfig source = getRuntimeConfig();
        try {
            RuntimeConfig copy = copyRuntimeConfig(source);
            redactor.redactSecrets(copy);
            return copy;
        } catch (RuntimeException e) {
            log.warn("[RuntimeConfig] Failed to build redacted runtime config: {}", e.getMessage());
            RuntimeConfig fallback = RuntimeConfig.builder().build();
            redactor.redactSecrets(fallback);
            return fallback;
        }
    }

    public RuntimeConfig snapshotRuntimeConfig() {
        return copyRuntimeConfig(getRuntimeConfig());
    }

    /**
     * Update and persist RuntimeConfig.
     */
    public void updateRuntimeConfig(RuntimeConfig newConfig) {
        normalizer.normalize(newConfig);
        RuntimeConfig storedPersistedConfig = RuntimeConfig.builder()
                .selfEvolving(runtimeConfigPersistencePort.loadSection(RuntimeConfig.ConfigSection.SELF_EVOLVING,
                        RuntimeConfig.SelfEvolvingConfig.class, RuntimeConfig.SelfEvolvingConfig::new, false))
                .build();
        normalizer.normalize(storedPersistedConfig);
        RuntimeConfig persistedConfig = copyRuntimeConfig(newConfig);
        selfEvolvingBootstrapOverrideService.restorePersistedValues(persistedConfig, storedPersistedConfig);
        clearSelfEvolvingRuntimeMetadata(persistedConfig);
        normalizer.normalize(persistedConfig);
        RuntimeConfig effectiveConfig = buildEffectiveRuntimeConfig(persistedConfig);
        mutationService.persist(persistedConfig, effectiveConfig);
    }

    public RuntimeConfig reloadRuntimeConfig() {
        return snapshotProvider.reload(() -> buildEffectiveRuntimeConfig(loadOrCreate()));
    }

    public void replaceManagedPolicySections(RuntimeConfig.LlmConfig llmConfig,
            RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        RuntimeConfig snapshot = snapshotRuntimeConfig();
        RuntimeConfig llmSnapshot = copyRuntimeConfig(RuntimeConfig.builder()
                .llm(llmConfig != null ? llmConfig : RuntimeConfig.LlmConfig.builder().build()).build());
        RuntimeConfig modelRouterSnapshot = copyRuntimeConfig(RuntimeConfig.builder().modelRouter(
                modelRouterConfig != null ? modelRouterConfig : RuntimeConfig.ModelRouterConfig.builder().build())
                .build());
        snapshot.setLlm(llmSnapshot.getLlm());
        snapshot.setModelRouter(modelRouterSnapshot.getModelRouter());
        updateRuntimeConfig(snapshot);
    }

    public void restoreRuntimeConfigSnapshot(RuntimeConfig snapshot) {
        updateRuntimeConfig(copyRuntimeConfig(snapshot));
    }

    public RuntimeConfig.ResilienceConfig getResilienceConfig() {
        RuntimeConfig.ResilienceConfig resilienceConfig = getRuntimeConfig().getResilience();
        return resilienceConfig != null ? resilienceConfig : RuntimeConfig.ResilienceConfig.builder().build();
    }

    public boolean isResilienceEnabled() {
        Boolean enabled = getResilienceConfig().getEnabled();
        return enabled != null && enabled;
    }

    public RuntimeConfig.FollowThroughConfig getFollowThroughConfig() {
        RuntimeConfig.FollowThroughConfig cfg = getResilienceConfig().getFollowThrough();
        return cfg != null ? cfg : RuntimeConfig.FollowThroughConfig.builder().build();
    }

    public boolean isFollowThroughEnabled() {
        if (!isResilienceEnabled()) {
            return false;
        }
        Boolean enabled = getFollowThroughConfig().getEnabled();
        return enabled == null || enabled;
    }

    public RuntimeConfig.AutoProceedConfig getAutoProceedConfig() {
        RuntimeConfig.AutoProceedConfig cfg = getResilienceConfig().getAutoProceed();
        return cfg != null ? cfg : RuntimeConfig.AutoProceedConfig.builder().build();
    }

    public boolean isAutoProceedEnabled() {
        if (!isResilienceEnabled()) {
            return false;
        }
        Boolean enabled = getAutoProceedConfig().getEnabled();
        return enabled != null && enabled;
    }

    public RuntimeConfig.SelfEvolvingConfig getSelfEvolvingConfig() {
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = getRuntimeConfig().getSelfEvolving();
        return selfEvolvingConfig != null ? selfEvolvingConfig : RuntimeConfig.SelfEvolvingConfig.builder().build();
    }

    public boolean isSelfEvolvingEnabled() {
        Boolean enabled = getSelfEvolvingConfig().getEnabled();
        return enabled != null ? enabled : DEFAULT_SELF_EVOLVING_ENABLED;
    }

    public boolean isSelfEvolvingTracePayloadOverrideEnabled() {
        return isSelfEvolvingEnabled();
    }

    public String getSelfEvolvingJudgePrimaryTier() {
        String primaryTier = getSelfEvolvingConfig().getJudge().getPrimaryTier();
        return primaryTier != null ? primaryTier : DEFAULT_SELF_EVOLVING_JUDGE_PRIMARY_TIER;
    }

    public String getSelfEvolvingJudgeTiebreakerTier() {
        String tiebreakerTier = getSelfEvolvingConfig().getJudge().getTiebreakerTier();
        return tiebreakerTier != null ? tiebreakerTier : DEFAULT_SELF_EVOLVING_JUDGE_TIEBREAKER_TIER;
    }

    public String getSelfEvolvingJudgeEvolutionTier() {
        String evolutionTier = getSelfEvolvingConfig().getJudge().getEvolutionTier();
        return evolutionTier != null ? evolutionTier : DEFAULT_SELF_EVOLVING_JUDGE_EVOLUTION_TIER;
    }

    public String getSelfEvolvingPromotionMode() {
        String promotionMode = getSelfEvolvingConfig().getPromotion().getMode();
        return promotionMode != null ? promotionMode : DEFAULT_SELF_EVOLVING_PROMOTION_MODE;
    }

    public boolean isSelfEvolvingPromotionShadowRequired() {
        Boolean shadowRequired = getSelfEvolvingConfig().getPromotion().getShadowRequired();
        return shadowRequired != null ? shadowRequired : Boolean.FALSE;
    }

    public boolean isSelfEvolvingPromotionCanaryRequired() {
        Boolean canaryRequired = getSelfEvolvingConfig().getPromotion().getCanaryRequired();
        return canaryRequired != null ? canaryRequired : Boolean.FALSE;
    }

    // ==================== Tactic Query Expansion ====================

    public boolean isTacticQueryExpansionEnabled() {
        RuntimeConfig.SelfEvolvingTacticQueryExpansionConfig queryExpansion = getSelfEvolvingConfig().getTactics()
                .getSearch().getQueryExpansion();
        if (queryExpansion == null) {
            return DEFAULT_SELF_EVOLVING_TACTIC_QUERY_EXPANSION_ENABLED;
        }
        Boolean enabled = queryExpansion.getEnabled();
        return enabled != null ? enabled : DEFAULT_SELF_EVOLVING_TACTIC_QUERY_EXPANSION_ENABLED;
    }

    public String getTacticQueryExpansionTier() {
        RuntimeConfig.SelfEvolvingTacticQueryExpansionConfig queryExpansion = getSelfEvolvingConfig().getTactics()
                .getSearch().getQueryExpansion();
        if (queryExpansion == null) {
            return DEFAULT_SELF_EVOLVING_TACTIC_QUERY_EXPANSION_TIER;
        }
        String tier = queryExpansion.getTier();
        return tier != null ? tier : DEFAULT_SELF_EVOLVING_TACTIC_QUERY_EXPANSION_TIER;
    }

    public int getTacticAdvisoryCount() {
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = getSelfEvolvingConfig().getTactics().getSearch();
        Integer count = searchConfig.getAdvisoryCount();
        return count != null && count >= 1 ? Math.min(count, 5) : 1;
    }

    // ==================== Telegram ====================

    public boolean isTelegramEnabled() {
        Boolean val = getRuntimeConfig().getTelegram().getEnabled();
        return val != null && val;
    }

    public String getTelegramToken() {
        return Secret.valueOrEmpty(getRuntimeConfig().getTelegram().getToken());
    }

    public List<String> getTelegramAllowedUsers() {
        List<String> runtimeUsers = getRuntimeConfig().getTelegram().getAllowedUsers();
        return runtimeUsers != null ? runtimeUsers : List.of();
    }

    // ==================== Model Router ====================

    public RuntimeConfig.LlmProviderConfig getLlmProviderConfig(String providerName) {
        RuntimeConfig.LlmProviderConfig runtime = getRuntimeLlmProviderConfig(providerName);
        return runtime != null ? runtime : RuntimeConfig.LlmProviderConfig.builder().build();
    }

    public boolean hasLlmProviderApiKey(String providerName) {
        RuntimeConfig.LlmProviderConfig provider = getLlmProviderConfig(providerName);
        return Secret.hasValue(provider.getApiKey());
    }

    public List<String> getConfiguredLlmProviders() {
        return new ArrayList<>(getRuntimeConfig().getLlm().getProviders().keySet());
    }

    /**
     * Get list of configured LLM providers that have a valid API key.
     */
    public List<String> getConfiguredLlmProvidersWithApiKey() {
        RuntimeConfig.LlmConfig llm = getRuntimeConfig().getLlm();
        if (llm == null || llm.getProviders() == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, RuntimeConfig.LlmProviderConfig> entry : llm.getProviders().entrySet()) {
            if (entry.getValue() != null && Secret.hasValue(entry.getValue().getApiKey())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Add a new LLM provider configuration.
     */
    public void addLlmProvider(String name, RuntimeConfig.LlmProviderConfig config) {
        RuntimeConfig cfg = getRuntimeConfig();
        cfg.getLlm().getProviders().put(name.toLowerCase(Locale.ROOT), config);
        updateRuntimeConfig(cfg);
        log.info("[RuntimeConfig] Added LLM provider: {}", name);
    }

    /**
     * Update an existing LLM provider configuration. If apiKey is not provided (null or empty), preserves the existing
     * apiKey.
     */
    public void updateLlmProvider(String name, RuntimeConfig.LlmProviderConfig newConfig) {
        RuntimeConfig cfg = getRuntimeConfig();
        String normalizedName = name.toLowerCase(Locale.ROOT);
        RuntimeConfig.LlmProviderConfig existing = cfg.getLlm().getProviders().get(normalizedName);

        if (existing != null && !Secret.hasValue(newConfig.getApiKey())) {
            // Preserve existing API key if new one is not provided
            newConfig.setApiKey(existing.getApiKey());
        }

        cfg.getLlm().getProviders().put(normalizedName, newConfig);
        updateRuntimeConfig(cfg);
        log.info("[RuntimeConfig] Updated LLM provider: {}", name);
    }

    /**
     * Remove an LLM provider configuration.
     */
    public boolean removeLlmProvider(String name) {
        RuntimeConfig cfg = getRuntimeConfig();
        RuntimeConfig.LlmProviderConfig removed = cfg.getLlm().getProviders().remove(name.toLowerCase(Locale.ROOT));
        if (removed != null) {
            updateRuntimeConfig(cfg);
            log.info("[RuntimeConfig] Removed LLM provider: {}", name);
            return true;
        }
        return false;
    }

    // ==================== MCP Catalog CRUD ====================

    /**
     * Get the MCP catalog entries.
     */
    public List<RuntimeConfig.McpCatalogEntry> getMcpCatalog() {
        RuntimeConfig.McpConfig mcp = getRuntimeConfig().getMcp();
        if (mcp == null || mcp.getCatalog() == null) {
            return List.of();
        }
        return mcp.getCatalog();
    }

    /**
     * Add a new MCP catalog entry.
     */
    public void addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry entry) {
        RuntimeConfig cfg = getRuntimeConfig();
        RuntimeConfig.McpConfig mcp = ensureMcpConfig(cfg);
        List<RuntimeConfig.McpCatalogEntry> catalog = ensureMcpCatalog(mcp);
        catalog.add(entry);
        updateRuntimeConfig(cfg);
        log.info("[RuntimeConfig] Added MCP catalog entry: {}", entry.getName());
    }

    /**
     * Update an existing MCP catalog entry by name.
     */
    public boolean updateMcpCatalogEntry(String name, RuntimeConfig.McpCatalogEntry newEntry) {
        RuntimeConfig cfg = getRuntimeConfig();
        RuntimeConfig.McpConfig mcp = ensureMcpConfig(cfg);
        List<RuntimeConfig.McpCatalogEntry> catalog = ensureMcpCatalog(mcp);
        for (int i = 0; i < catalog.size(); i++) {
            if (name.equals(catalog.get(i).getName())) {
                newEntry.setName(name);
                catalog.set(i, newEntry);
                updateRuntimeConfig(cfg);
                log.info("[RuntimeConfig] Updated MCP catalog entry: {}", name);
                return true;
            }
        }
        return false;
    }

    /**
     * Remove an MCP catalog entry by name.
     */
    public boolean removeMcpCatalogEntry(String name) {
        RuntimeConfig cfg = getRuntimeConfig();
        RuntimeConfig.McpConfig mcp = ensureMcpConfig(cfg);
        List<RuntimeConfig.McpCatalogEntry> catalog = ensureMcpCatalog(mcp);
        boolean removed = catalog.removeIf(entry -> name.equals(entry.getName()));
        if (removed) {
            updateRuntimeConfig(cfg);
            log.info("[RuntimeConfig] Removed MCP catalog entry: {}", name);
        }
        return removed;
    }

    private RuntimeConfig.McpConfig ensureMcpConfig(RuntimeConfig cfg) {
        RuntimeConfig.McpConfig mcp = cfg.getMcp();
        if (mcp == null) {
            mcp = new RuntimeConfig.McpConfig();
            cfg.setMcp(mcp);
        }
        return mcp;
    }

    private List<RuntimeConfig.McpCatalogEntry> ensureMcpCatalog(RuntimeConfig.McpConfig mcp) {
        List<RuntimeConfig.McpCatalogEntry> catalog = mcp.getCatalog();
        if (catalog == null) {
            catalog = new ArrayList<>();
            mcp.setCatalog(catalog);
        }
        return catalog;
    }

    private RuntimeConfig.LlmProviderConfig getRuntimeLlmProviderConfig(String providerName) {
        RuntimeConfig.LlmConfig llm = getRuntimeConfig().getLlm();
        if (llm == null || providerName == null) {
            return null;
        }
        return llm.getProviders().get(providerName);
    }

    // ==================== Voice ====================

    public boolean isVoiceEnabled() {
        Boolean val = getRuntimeConfig().getVoice().getEnabled();
        return val != null && val;
    }

    public boolean isUsageEnabled() {
        Boolean val = getRuntimeConfig().getUsage().getEnabled();
        return val != null ? val : true;
    }

    public boolean isTelemetryEnabled() {
        RuntimeConfig.TelemetryConfig telemetryConfig = getRuntimeConfig().getTelemetry();
        if (telemetryConfig == null) {
            return true;
        }
        Boolean val = telemetryConfig.getEnabled();
        return val != null ? val : true;
    }

    // ==================== MCP ====================

    public boolean isMcpEnabled() {
        Boolean val = getRuntimeConfig().getMcp().getEnabled();
        return val != null ? val : DEFAULT_MCP_ENABLED;
    }

    public int getMcpDefaultStartupTimeout() {
        Integer val = getRuntimeConfig().getMcp().getDefaultStartupTimeout();
        return val != null ? val : DEFAULT_MCP_STARTUP_TIMEOUT;
    }

    public int getMcpDefaultIdleTimeout() {
        Integer val = getRuntimeConfig().getMcp().getDefaultIdleTimeout();
        return val != null ? val : DEFAULT_MCP_IDLE_TIMEOUT;
    }

    // ==================== Plan ====================

    public boolean isPlanEnabled() {
        return true;
    }

    public boolean isPlanStopOnFailure() {
        return true;
    }

    public String getPlanModelTier() {
        RuntimeConfig.PlanConfig planConfig = getRuntimeConfig().getPlan();
        return planConfig != null ? normalizeOptionalModelTier(planConfig.getModelTier()) : null;
    }

    public String getVoiceApiKey() {
        return Secret.valueOrEmpty(getRuntimeConfig().getVoice().getApiKey());
    }

    public String getVoiceId() {
        String val = getRuntimeConfig().getVoice().getVoiceId();
        return val != null ? val : DEFAULT_VOICE_ID;
    }

    public String getTtsModelId() {
        String val = getRuntimeConfig().getVoice().getTtsModelId();
        return val != null ? val : DEFAULT_TTS_MODEL;
    }

    public String getSttModelId() {
        String val = getRuntimeConfig().getVoice().getSttModelId();
        return val != null ? val : DEFAULT_STT_MODEL;
    }

    public float getVoiceSpeed() {
        Float val = getRuntimeConfig().getVoice().getSpeed();
        return val != null ? val : DEFAULT_VOICE_SPEED;
    }

    public boolean isTelegramRespondWithVoiceEnabled() {
        Boolean val = getRuntimeConfig().getVoice().getTelegramRespondWithVoice();
        return val != null && val;
    }

    public boolean isTelegramTranscribeIncomingEnabled() {
        Boolean val = getRuntimeConfig().getVoice().getTelegramTranscribeIncoming();
        return val != null && val;
    }

    public String getSttProvider() {
        return normalizeVoiceProvider(getRuntimeConfig().getVoice().getSttProvider());
    }

    public String getTtsProvider() {
        return normalizeVoiceProvider(getRuntimeConfig().getVoice().getTtsProvider());
    }

    public String getWhisperSttUrl() {
        String val = getRuntimeConfig().getVoice().getWhisperSttUrl();
        return val != null ? val : "";
    }

    public String getWhisperSttApiKey() {
        return Secret.valueOrEmpty(getRuntimeConfig().getVoice().getWhisperSttApiKey());
    }

    public boolean isWhisperSttConfigured() {
        return DEFAULT_WHISPER_STT_PROVIDER.equals(getSttProvider()) && !getWhisperSttUrl().isBlank();
    }

    // ==================== Rate Limit ====================

    public boolean isRateLimitEnabled() {
        Boolean val = getRuntimeConfig().getRateLimit().getEnabled();
        return val != null ? val : false;
    }

    public int getUserRequestsPerMinute() {
        Integer val = getRuntimeConfig().getRateLimit().getUserRequestsPerMinute();
        return val != null ? val : DEFAULT_RATE_USER_PER_MINUTE;
    }

    public int getUserRequestsPerHour() {
        Integer val = getRuntimeConfig().getRateLimit().getUserRequestsPerHour();
        return val != null ? val : DEFAULT_RATE_USER_PER_HOUR;
    }

    public int getUserRequestsPerDay() {
        Integer val = getRuntimeConfig().getRateLimit().getUserRequestsPerDay();
        return val != null ? val : DEFAULT_RATE_USER_PER_DAY;
    }

    public int getChannelMessagesPerSecond() {
        Integer val = getRuntimeConfig().getRateLimit().getChannelMessagesPerSecond();
        return val != null ? val : DEFAULT_RATE_CHANNEL_PER_SECOND;
    }

    public int getLlmRequestsPerMinute() {
        Integer val = getRuntimeConfig().getRateLimit().getLlmRequestsPerMinute();
        return val != null ? val : DEFAULT_RATE_LLM_PER_MINUTE;
    }

    // ==================== Security ====================

    public boolean isSanitizeInputEnabled() {
        Boolean val = getRuntimeConfig().getSecurity().getSanitizeInput();
        return val != null ? val : true;
    }

    public boolean isPromptInjectionDetectionEnabled() {
        Boolean val = getRuntimeConfig().getSecurity().getDetectPromptInjection();
        return val != null ? val : true;
    }

    public boolean isCommandInjectionDetectionEnabled() {
        Boolean val = getRuntimeConfig().getSecurity().getDetectCommandInjection();
        return val != null ? val : true;
    }

    public int getMaxInputLength() {
        Integer val = getRuntimeConfig().getSecurity().getMaxInputLength();
        return val != null ? val : DEFAULT_MAX_INPUT_LENGTH;
    }

    public boolean isAllowlistEnabled() {
        Boolean val = getRuntimeConfig().getSecurity().getAllowlistEnabled();
        return val != null ? val : DEFAULT_ALLOWLIST_ENABLED;
    }

    public boolean isToolConfirmationEnabled() {
        return ShellRuntimeConfigView.super.isToolConfirmationEnabled();
    }

    public int getToolConfirmationTimeoutSeconds() {
        return ShellRuntimeConfigView.super.getToolConfirmationTimeoutSeconds();
    }

    public boolean isTracingEnabled() {
        return TracingConfigView.super.isTracingEnabled();
    }

    public boolean isPayloadSnapshotsEnabled() {
        return TracingConfigView.super.isPayloadSnapshotsEnabled();
    }

    public int getSessionTraceBudgetMb() {
        return TracingConfigView.super.getSessionTraceBudgetMb();
    }

    public int getTraceMaxSnapshotSizeKb() {
        return TracingConfigView.super.getTraceMaxSnapshotSizeKb();
    }

    public int getTraceMaxSnapshotsPerSpan() {
        return TracingConfigView.super.getTraceMaxSnapshotsPerSpan();
    }

    public int getTraceMaxTracesPerSession() {
        return TracingConfigView.super.getTraceMaxTracesPerSession();
    }

    public boolean isTraceInboundPayloadCaptureEnabled() {
        return TracingConfigView.super.isTraceInboundPayloadCaptureEnabled();
    }

    public boolean isTraceOutboundPayloadCaptureEnabled() {
        return TracingConfigView.super.isTraceOutboundPayloadCaptureEnabled();
    }

    public boolean isTraceToolPayloadCaptureEnabled() {
        return TracingConfigView.super.isTraceToolPayloadCaptureEnabled();
    }

    public boolean isTraceLlmPayloadCaptureEnabled() {
        return TracingConfigView.super.isTraceLlmPayloadCaptureEnabled();
    }

    // ==================== Compaction ====================

    public boolean isCompactionEnabled() {
        Boolean val = getRuntimeConfig().getCompaction().getEnabled();
        return val != null ? val : true;
    }

    public int getCompactionMaxContextTokens() {
        Integer val = getRuntimeConfig().getCompaction().getMaxContextTokens();
        return val != null ? val : DEFAULT_AUTO_COMPACT_MAX_TOKENS;
    }

    public int getCompactionKeepLastMessages() {
        Integer val = getRuntimeConfig().getCompaction().getKeepLastMessages();
        return val != null ? val : DEFAULT_AUTO_COMPACT_KEEP_LAST;
    }

    public String getCompactionTriggerMode() {
        String val = getRuntimeConfig().getCompaction().getTriggerMode();
        return normalizeCompactionTriggerMode(val);
    }

    public double getCompactionModelThresholdRatio() {
        Double val = getRuntimeConfig().getCompaction().getModelThresholdRatio();
        if (val == null || val <= 0.0d || val > 1.0d) {
            return DEFAULT_COMPACTION_MODEL_THRESHOLD_RATIO;
        }
        return val;
    }

    public boolean isCompactionPreserveTurnBoundariesEnabled() {
        Boolean val = getRuntimeConfig().getCompaction().getPreserveTurnBoundaries();
        return val != null ? val : DEFAULT_COMPACTION_PRESERVE_TURN_BOUNDARIES;
    }

    public boolean isCompactionDetailsEnabled() {
        Boolean val = getRuntimeConfig().getCompaction().getDetailsEnabled();
        return val != null ? val : DEFAULT_COMPACTION_DETAILS_ENABLED;
    }

    public int getCompactionDetailsMaxItemsPerCategory() {
        Integer val = getRuntimeConfig().getCompaction().getDetailsMaxItemsPerCategory();
        return val != null ? val : DEFAULT_COMPACTION_DETAILS_MAX_ITEMS;
    }

    public int getCompactionSummaryTimeoutMs() {
        Integer val = getRuntimeConfig().getCompaction().getSummaryTimeoutMs();
        return val != null ? val : DEFAULT_COMPACTION_SUMMARY_TIMEOUT_MS;
    }

    public boolean isSessionRetentionEnabled() {
        return sessionRetentionBoolean(RuntimeConfig.SessionRetentionConfig::getEnabled,
                DEFAULT_SESSION_RETENTION_ENABLED);
    }

    public Duration getSessionRetentionMaxAge() {
        return sessionRetentionDuration(RuntimeConfig.SessionRetentionConfig::getMaxAge,
                DEFAULT_SESSION_RETENTION_MAX_AGE, "sessionRetention.maxAge");
    }

    public Duration getSessionRetentionCleanupInterval() {
        return sessionRetentionDuration(RuntimeConfig.SessionRetentionConfig::getCleanupInterval,
                DEFAULT_SESSION_RETENTION_CLEANUP_INTERVAL, "sessionRetention.cleanupInterval");
    }

    public boolean isSessionRetentionProtectActiveSessions() {
        return sessionRetentionBoolean(RuntimeConfig.SessionRetentionConfig::getProtectActiveSessions,
                DEFAULT_SESSION_RETENTION_PROTECT_ACTIVE);
    }

    public boolean isSessionRetentionProtectSessionsWithPlans() {
        return sessionRetentionBoolean(RuntimeConfig.SessionRetentionConfig::getProtectSessionsWithPlans,
                DEFAULT_SESSION_RETENTION_PROTECT_PLANS);
    }

    public boolean isSessionRetentionProtectSessionsWithDelayedActions() {
        return sessionRetentionBoolean(RuntimeConfig.SessionRetentionConfig::getProtectSessionsWithDelayedActions,
                DEFAULT_SESSION_RETENTION_PROTECT_DELAYED_ACTIONS);
    }

    private boolean sessionRetentionBoolean(
            java.util.function.Function<RuntimeConfig.SessionRetentionConfig, Boolean> getter, boolean defaultValue) {
        RuntimeConfig.SessionRetentionConfig cfg = getRuntimeConfig().getSessionRetention();
        if (cfg == null) {
            return defaultValue;
        }
        Boolean val = getter.apply(cfg);
        return val != null ? val : defaultValue;
    }

    private Duration sessionRetentionDuration(
            java.util.function.Function<RuntimeConfig.SessionRetentionConfig, String> getter, Duration defaultValue,
            String fieldName) {
        RuntimeConfig.SessionRetentionConfig cfg = getRuntimeConfig().getSessionRetention();
        if (cfg == null) {
            return defaultValue;
        }
        String raw = getter.apply(cfg);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            Duration parsed = Duration.parse(raw);
            if (parsed.isNegative() || parsed.isZero()) {
                log.warn("Ignoring non-positive duration for {}: {} (falling back to {})", fieldName, raw,
                        defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse duration for {}: {} (falling back to {})", fieldName, raw, defaultValue);
            return defaultValue;
        }
    }

    // ==================== Skills ====================

    public boolean isSkillsEnabled() {
        RuntimeConfig.SkillsConfig skillsConfig = getRuntimeConfig().getSkills();
        if (skillsConfig == null) {
            return true;
        }
        Boolean val = skillsConfig.getEnabled();
        return val != null ? val : true;
    }

    public boolean isSkillsProgressiveLoadingEnabled() {
        RuntimeConfig.SkillsConfig skillsConfig = getRuntimeConfig().getSkills();
        if (skillsConfig == null) {
            return true;
        }
        Boolean val = skillsConfig.getProgressiveLoading();
        return val != null ? val : true;
    }

    // ==================== Invite Codes ====================

    /**
     * Generate a new single-use invite code.
     */
    public RuntimeConfig.InviteCode generateInviteCode() {
        RuntimeConfig cfg = getRuntimeConfig();
        RuntimeConfig.InviteCode inviteCode = RuntimeConfigInviteCodeSupport.generateInviteCode(cfg);
        runtimeConfigPersistencePort.persist(cfg);
        return inviteCode;
    }

    /**
     * Revoke (remove) an invite code.
     */
    public boolean revokeInviteCode(String code) {
        RuntimeConfig cfg = getRuntimeConfig();
        boolean removed = RuntimeConfigInviteCodeSupport.revokeInviteCode(cfg, code);
        if (removed) {
            runtimeConfigPersistencePort.persist(cfg);
        }
        return removed;
    }

    /**
     * Redeem a single-use invite code, adding the user to allowed users.
     */
    public boolean redeemInviteCode(String code, String userId) {
        RuntimeConfig cfg = getRuntimeConfig();
        boolean redeemed = RuntimeConfigInviteCodeSupport.redeemInviteCode(cfg, code, userId);
        if (redeemed) {
            runtimeConfigPersistencePort.persist(cfg);
        }
        return redeemed;
    }

    /**
     * Remove a Telegram user from allowed users list.
     */
    public boolean removeTelegramAllowedUser(String userId) {
        RuntimeConfig cfg = getRuntimeConfig();
        boolean removed = RuntimeConfigInviteCodeSupport.removeTelegramAllowedUser(cfg, userId);
        if (removed) {
            runtimeConfigPersistencePort.persist(cfg);
        }
        return removed;
    }

    // ==================== Persistence ====================

    private RuntimeConfig loadOrCreate() {
        RuntimeConfig config = runtimeConfigPersistencePort.loadOrCreate();
        log.info("[RuntimeConfig] Loaded runtime config from {} section files",
                RuntimeConfig.ConfigSection.values().length);
        return config;
    }

    private RuntimeConfig buildEffectiveRuntimeConfig(RuntimeConfig baseConfig) {
        RuntimeConfig effectiveConfig = copyRuntimeConfig(baseConfig);
        normalizer.normalize(effectiveConfig);
        clearSelfEvolvingRuntimeMetadata(effectiveConfig);
        selfEvolvingBootstrapOverrideService.apply(effectiveConfig);
        applySelfEvolvingRuntimeMetadata(effectiveConfig);
        normalizer.normalize(effectiveConfig);
        return effectiveConfig;
    }

    public RuntimeConfig copyRuntimeConfig(RuntimeConfig source) {
        return runtimeConfigPersistencePort.copy(source);
    }

    private void clearSelfEvolvingRuntimeMetadata(RuntimeConfig cfg) {
        if (cfg == null || cfg.getSelfEvolving() == null) {
            return;
        }
        cfg.getSelfEvolving().setManagedByProperties(false);
        cfg.getSelfEvolving().setOverriddenPaths(new ArrayList<>());
    }

    private void applySelfEvolvingRuntimeMetadata(RuntimeConfig cfg) {
        if (cfg == null || cfg.getSelfEvolving() == null) {
            return;
        }
        List<String> overriddenPaths = selfEvolvingBootstrapOverrideService.getOverriddenPaths();
        cfg.getSelfEvolving().setManagedByProperties(selfEvolvingBootstrapOverrideService.hasManagedOverrides());
        cfg.getSelfEvolving().setOverriddenPaths(new ArrayList<>(overriddenPaths));
    }

}

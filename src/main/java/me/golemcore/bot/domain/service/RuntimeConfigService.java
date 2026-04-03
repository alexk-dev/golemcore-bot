package me.golemcore.bot.domain.service;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Service for managing application-level runtime configuration.
 *
 * <p>
 * Configuration is stored in separate files per section in the preferences
 * directory:
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
public class RuntimeConfigService {

    private static final String PREFERENCES_DIR = "preferences";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String INVITE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_CODE_LENGTH = 20;
    private static final String REASONING_NONE = "none";
    private static final String DEFAULT_BALANCED_MODEL = "openai/gpt-5.1";
    private static final String DEFAULT_BALANCED_REASONING = REASONING_NONE;
    private static final String DEFAULT_ROUTING_MODEL = "openai/gpt-5.2-codex";
    private static final String DEFAULT_ROUTING_REASONING = REASONING_NONE;
    private static final String DEFAULT_SMART_MODEL = "openai/gpt-5.1";
    private static final String DEFAULT_SMART_REASONING = REASONING_NONE;
    private static final String DEFAULT_CODING_MODEL = "openai/gpt-5.2";
    private static final String DEFAULT_CODING_REASONING = REASONING_NONE;
    private static final String DEFAULT_DEEP_MODEL = "openai/gpt-5.2";
    private static final String DEFAULT_DEEP_REASONING = REASONING_NONE;
    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final String DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM";
    private static final String DEFAULT_TTS_MODEL = "eleven_multilingual_v2";
    private static final String DEFAULT_STT_MODEL = "scribe_v1";
    private static final float DEFAULT_VOICE_SPEED = 1.0f;
    private static final int DEFAULT_RATE_USER_PER_MINUTE = 20;
    private static final int DEFAULT_RATE_USER_PER_HOUR = 100;
    private static final int DEFAULT_RATE_USER_PER_DAY = 500;
    private static final int DEFAULT_RATE_CHANNEL_PER_SECOND = 30;
    private static final int DEFAULT_RATE_LLM_PER_MINUTE = 60;
    private static final int DEFAULT_MAX_INPUT_LENGTH = 10000;
    private static final boolean DEFAULT_ALLOWLIST_ENABLED = true;
    private static final boolean DEFAULT_TOOL_CONFIRMATION_ENABLED = false;
    private static final int DEFAULT_TOOL_CONFIRMATION_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_AUTO_TICK_INTERVAL_SECONDS = 30;
    private static final int DEFAULT_AUTO_TIMEOUT_MINUTES = 10;
    private static final int DEFAULT_AUTO_MAX_GOALS = 3;
    private static final String DEFAULT_AUTO_MODEL_TIER = "default";
    private static final boolean DEFAULT_AUTO_REFLECTION_ENABLED = true;
    private static final int DEFAULT_AUTO_REFLECTION_FAILURE_THRESHOLD = 2;
    private static final boolean DEFAULT_UPDATE_AUTO_ENABLED = true;
    private static final int DEFAULT_UPDATE_CHECK_INTERVAL_MINUTES = 60;
    private static final boolean DEFAULT_UPDATE_MAINTENANCE_WINDOW_ENABLED = false;
    private static final String DEFAULT_UPDATE_MAINTENANCE_WINDOW_START_UTC = "00:00";
    private static final String DEFAULT_UPDATE_MAINTENANCE_WINDOW_END_UTC = "00:00";
    private static final boolean DEFAULT_TRACING_ENABLED = true;
    private static final boolean DEFAULT_TRACING_PAYLOAD_SNAPSHOTS_ENABLED = false;
    private static final int DEFAULT_TRACING_SESSION_TRACE_BUDGET_MB = 128;
    private static final int DEFAULT_TRACING_MAX_SNAPSHOT_SIZE_KB = 256;
    private static final int DEFAULT_TRACING_MAX_SNAPSHOTS_PER_SPAN = 10;
    private static final int DEFAULT_TRACING_MAX_TRACES_PER_SESSION = 100;
    private static final boolean DEFAULT_TRACING_CAPTURE_INBOUND_PAYLOADS = false;
    private static final boolean DEFAULT_TRACING_CAPTURE_OUTBOUND_PAYLOADS = false;
    private static final boolean DEFAULT_TRACING_CAPTURE_TOOL_PAYLOADS = false;
    private static final boolean DEFAULT_TRACING_CAPTURE_LLM_PAYLOADS = false;
    private static final int DEFAULT_AUTO_COMPACT_MAX_TOKENS = 50000;
    private static final int DEFAULT_AUTO_COMPACT_KEEP_LAST = 20;
    private static final String DEFAULT_COMPACTION_TRIGGER_MODE = "model_ratio";
    private static final String COMPACTION_TRIGGER_MODE_TOKEN_THRESHOLD = "token_threshold";
    private static final double DEFAULT_COMPACTION_MODEL_THRESHOLD_RATIO = 0.95d;
    private static final boolean DEFAULT_COMPACTION_PRESERVE_TURN_BOUNDARIES = true;
    private static final boolean DEFAULT_COMPACTION_DETAILS_ENABLED = true;
    private static final int DEFAULT_COMPACTION_DETAILS_MAX_ITEMS = 50;
    private static final int DEFAULT_COMPACTION_SUMMARY_TIMEOUT_MS = 15000;
    private static final int DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS = 1800;
    private static final int DEFAULT_MEMORY_MAX_PROMPT_BUDGET_TOKENS = 3500;
    private static final int DEFAULT_MEMORY_VERSION = 2;
    private static final int DEFAULT_MEMORY_WORKING_TOP_K = 6;
    private static final int DEFAULT_MEMORY_EPISODIC_TOP_K = 8;
    private static final int DEFAULT_MEMORY_SEMANTIC_TOP_K = 6;
    private static final int DEFAULT_MEMORY_PROCEDURAL_TOP_K = 4;
    private static final boolean DEFAULT_MEMORY_PROMOTION_ENABLED = true;
    private static final double DEFAULT_MEMORY_PROMOTION_MIN_CONFIDENCE = 0.75;
    private static final boolean DEFAULT_MEMORY_DECAY_ENABLED = true;
    private static final int DEFAULT_MEMORY_DECAY_DAYS = 30;
    private static final int DEFAULT_MEMORY_RETRIEVAL_LOOKBACK_DAYS = 21;
    private static final boolean DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED = true;
    private static final String DEFAULT_MEMORY_DISCLOSURE_MODE = "summary";
    private static final String DEFAULT_MEMORY_PROMPT_STYLE = "balanced";
    private static final boolean DEFAULT_MEMORY_TOOL_EXPANSION_ENABLED = true;
    private static final boolean DEFAULT_MEMORY_DISCLOSURE_HINTS_ENABLED = true;
    private static final double DEFAULT_MEMORY_DISCLOSURE_DETAIL_MIN_SCORE = 0.80;
    private static final boolean DEFAULT_MEMORY_RERANKING_ENABLED = true;
    private static final String DEFAULT_MEMORY_RERANKING_PROFILE = "balanced";
    private static final String DEFAULT_MEMORY_DIAGNOSTICS_VERBOSITY = "basic";
    private static final int DEFAULT_TURN_MAX_LLM_CALLS = 200;
    private static final int DEFAULT_TURN_MAX_TOOL_EXECUTIONS = 500;
    private static final Duration DEFAULT_TURN_DEADLINE = Duration.ofHours(1);
    private static final String DEFAULT_STT_PROVIDER = "golemcore/elevenlabs";
    private static final String DEFAULT_TTS_PROVIDER = "golemcore/elevenlabs";
    private static final String DEFAULT_WHISPER_STT_PROVIDER = "golemcore/whisper";
    private static final String LEGACY_ELEVENLABS_PROVIDER = "elevenlabs";
    private static final String LEGACY_WHISPER_PROVIDER = "whisper";
    private static final boolean DEFAULT_TURN_AUTO_RETRY_ENABLED = true;
    private static final int DEFAULT_TURN_AUTO_RETRY_MAX_ATTEMPTS = 2;
    private static final long DEFAULT_TURN_AUTO_RETRY_BASE_DELAY_MS = 600L;
    private static final boolean DEFAULT_TURN_QUEUE_STEERING_ENABLED = true;
    private static final String DEFAULT_TURN_QUEUE_STEERING_MODE = "one-at-a-time";
    private static final String DEFAULT_TURN_QUEUE_FOLLOW_UP_MODE = "one-at-a-time";
    private static final boolean DEFAULT_TURN_PROGRESS_UPDATES_ENABLED = true;
    private static final boolean DEFAULT_TURN_PROGRESS_INTENT_ENABLED = true;
    private static final int DEFAULT_TURN_PROGRESS_BATCH_SIZE = 8;
    private static final int DEFAULT_TURN_PROGRESS_MAX_SILENCE_SECONDS = 10;
    private static final int DEFAULT_TURN_PROGRESS_SUMMARY_TIMEOUT_MS = 8000;
    private static final boolean DEFAULT_MCP_ENABLED = true;
    private static final int DEFAULT_MCP_STARTUP_TIMEOUT = 30;
    private static final int DEFAULT_MCP_IDLE_TIMEOUT = 5;
    private static final boolean DEFAULT_PLAN_ENABLED = false;
    private static final int DEFAULT_PLAN_MAX_PLANS = 5;
    private static final int DEFAULT_PLAN_MAX_STEPS_PER_PLAN = 50;
    private static final boolean DEFAULT_PLAN_STOP_ON_FAILURE = true;
    private static final boolean DEFAULT_DELAYED_ACTIONS_ENABLED = true;
    private static final int DEFAULT_DELAYED_ACTIONS_TICK_SECONDS = 1;
    private static final int DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION = 50;
    private static final Duration DEFAULT_DELAYED_ACTIONS_MAX_DELAY = Duration.ofDays(30);
    private static final int DEFAULT_DELAYED_ACTIONS_MAX_ATTEMPTS = 4;
    private static final Duration DEFAULT_DELAYED_ACTIONS_LEASE_DURATION = Duration.ofMinutes(2);
    private static final Duration DEFAULT_DELAYED_ACTIONS_RETENTION = Duration.ofDays(7);
    private static final boolean DEFAULT_DELAYED_ACTIONS_ALLOW_RUN_LATER = true;
    private static final String DEFAULT_MODEL_REGISTRY_BRANCH = "main";

    private static final boolean DEFAULT_HIVE_ENABLED = false;
    private static final boolean DEFAULT_HIVE_AUTO_CONNECT = false;
    private static final boolean DEFAULT_HIVE_MANAGED_BY_PROPERTIES = false;
    private static final boolean DEFAULT_SELF_EVOLVING_ENABLED = false;
    private static final boolean DEFAULT_SELF_EVOLVING_TRACE_PAYLOAD_OVERRIDE = true;
    private static final boolean DEFAULT_SELF_EVOLVING_TACTICS_ENABLED = false;
    private static final String DEFAULT_SELF_EVOLVING_TACTIC_SEARCH_MODE = "hybrid";
    private static final boolean DEFAULT_SELF_EVOLVING_TACTIC_BM25_ENABLED = true;
    private static final boolean DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_ENABLED = false;
    private static final boolean DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_AUTO_FALLBACK_TO_BM25 = true;
    private static final boolean DEFAULT_SELF_EVOLVING_TACTIC_RERANK_CROSS_ENCODER = true;
    private static final String DEFAULT_SELF_EVOLVING_TACTIC_RERANK_TIER = "deep";
    private static final boolean DEFAULT_SELF_EVOLVING_TACTIC_PERSONALIZATION_ENABLED = true;
    private static final boolean DEFAULT_SELF_EVOLVING_TACTIC_NEGATIVE_MEMORY_ENABLED = true;
    private static final boolean DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_AUTO_INSTALL = false;
    private static final boolean DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_PULL_ON_START = false;
    private static final boolean DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_REQUIRE_HEALTHY_RUNTIME = true;
    private static final boolean DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_FAIL_OPEN = true;
    private static final int DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_STARTUP_TIMEOUT_MS = 5000;
    private static final int DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_INITIAL_RESTART_BACKOFF_MS = 1000;
    private static final String DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_MINIMUM_RUNTIME_VERSION = "0.19.0";
    private static final String DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL = "full";
    private static final String DEFAULT_SELF_EVOLVING_CAPTURE_MODE_META_ONLY = "meta_only";
    private static final boolean DEFAULT_SELF_EVOLVING_JUDGE_ENABLED = true;
    private static final String DEFAULT_SELF_EVOLVING_JUDGE_PRIMARY_TIER = "smart";
    private static final String DEFAULT_SELF_EVOLVING_JUDGE_TIEBREAKER_TIER = "deep";
    private static final String DEFAULT_SELF_EVOLVING_JUDGE_EVOLUTION_TIER = "deep";
    private static final Set<String> SUPPORTED_SELF_EVOLVING_JUDGE_TIERS = Set.of(
            "balanced",
            "smart",
            "deep",
            "coding",
            "special1",
            "special2",
            "special3",
            "special4",
            "special5");
    private static final boolean DEFAULT_SELF_EVOLVING_REQUIRE_EVIDENCE_ANCHORS = true;
    private static final double DEFAULT_SELF_EVOLVING_UNCERTAINTY_THRESHOLD = 0.22d;
    private static final boolean DEFAULT_SELF_EVOLVING_EVOLUTION_ENABLED = true;
    private static final List<String> DEFAULT_SELF_EVOLVING_MODES = List.of("fix", "derive", "tune");
    private static final List<String> DEFAULT_SELF_EVOLVING_ARTIFACT_TYPES = List.of(
            "skill",
            "prompt",
            "routing_policy",
            "tool_policy",
            "memory_policy");
    private static final String DEFAULT_SELF_EVOLVING_PROMOTION_MODE = "approval_gate";
    private static final boolean DEFAULT_SELF_EVOLVING_ALLOW_AUTO_ACCEPT = true;
    private static final boolean DEFAULT_SELF_EVOLVING_SHADOW_REQUIRED = true;
    private static final boolean DEFAULT_SELF_EVOLVING_CANARY_REQUIRED = true;
    private static final boolean DEFAULT_SELF_EVOLVING_HIVE_APPROVAL_PREFERRED = true;
    private static final boolean DEFAULT_SELF_EVOLVING_BENCHMARK_ENABLED = true;
    private static final boolean DEFAULT_SELF_EVOLVING_HARVEST_PRODUCTION_RUNS = true;
    private static final boolean DEFAULT_SELF_EVOLVING_AUTO_CREATE_REGRESSION_CASES = true;
    private static final boolean DEFAULT_SELF_EVOLVING_PUBLISH_INSPECTION_PROJECTION = true;
    private static final boolean DEFAULT_SELF_EVOLVING_READONLY_INSPECTION = true;
    private final StoragePort storagePort;
    private final SelfEvolvingBootstrapOverrideService selfEvolvingBootstrapOverrideService;
    private final ObjectMapper objectMapper;

    private final AtomicReference<RuntimeConfig> configRef = new AtomicReference<>();

    public RuntimeConfigService(StoragePort storagePort,
            SelfEvolvingBootstrapOverrideService selfEvolvingBootstrapOverrideService) {
        this.storagePort = storagePort;
        this.selfEvolvingBootstrapOverrideService = selfEvolvingBootstrapOverrideService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ==================== Section Validation ====================

    /**
     * Check if the given section ID is a valid configuration section.
     *
     * @param sectionId
     *            the section ID to validate (e.g., "telegram", "model-router")
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
        RuntimeConfig current = configRef.get();
        if (current == null) {
            synchronized (this) {
                current = configRef.get();
                if (current == null) {
                    current = buildEffectiveRuntimeConfig(loadOrCreate());
                    configRef.set(current);
                }
            }
        }
        return current;
    }

    public RuntimeConfig getRuntimeConfigForApi() {
        RuntimeConfig source = getRuntimeConfig();
        try {
            String json = objectMapper.writeValueAsString(source);
            RuntimeConfig copy = objectMapper.readValue(json, RuntimeConfig.class);
            redactSecrets(copy);
            return copy;
        } catch (IOException e) {
            log.warn("[RuntimeConfig] Failed to build redacted runtime config: {}", e.getMessage());
            RuntimeConfig fallback = RuntimeConfig.builder().build();
            redactSecrets(fallback);
            return fallback;
        }
    }

    /**
     * Update and persist RuntimeConfig.
     */
    public void updateRuntimeConfig(RuntimeConfig newConfig) {
        normalizeRuntimeConfig(newConfig);
        RuntimeConfig storedPersistedConfig = RuntimeConfig.builder()
                .selfEvolving(loadSection(RuntimeConfig.ConfigSection.SELF_EVOLVING,
                        RuntimeConfig.SelfEvolvingConfig.class,
                        RuntimeConfig.SelfEvolvingConfig::new,
                        false))
                .build();
        normalizeRuntimeConfig(storedPersistedConfig);
        RuntimeConfig persistedConfig = copyRuntimeConfig(newConfig);
        selfEvolvingBootstrapOverrideService.restorePersistedValues(persistedConfig, storedPersistedConfig);
        clearSelfEvolvingRuntimeMetadata(persistedConfig);
        normalizeRuntimeConfig(persistedConfig);
        RuntimeConfig effectiveConfig = buildEffectiveRuntimeConfig(persistedConfig);
        RuntimeConfig oldConfig = this.configRef.get();
        this.configRef.set(effectiveConfig);
        try {
            persist(persistedConfig);
        } catch (Exception e) {
            // Rollback in-memory change on persist failure
            this.configRef.set(oldConfig);
            throw e;
        }
    }

    public RuntimeConfig reloadRuntimeConfig() {
        RuntimeConfig reloaded = buildEffectiveRuntimeConfig(loadOrCreate());
        configRef.set(reloaded);
        return reloaded;
    }

    public RuntimeConfig.HiveConfig getHiveConfig() {
        RuntimeConfig.HiveConfig hiveConfig = getRuntimeConfig().getHive();
        return hiveConfig != null ? hiveConfig : RuntimeConfig.HiveConfig.builder().build();
    }

    public boolean isHiveManagedByProperties() {
        Boolean managedByProperties = getHiveConfig().getManagedByProperties();
        return managedByProperties != null && managedByProperties;
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
     * Update an existing LLM provider configuration. If apiKey is not provided
     * (null or empty), preserves the existing apiKey.
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

    public String getBalancedModel() {
        String val = getRuntimeConfig().getModelRouter().getBalancedModel();
        return val != null ? val : DEFAULT_BALANCED_MODEL;
    }

    public String getRoutingModel() {
        String val = getRuntimeConfig().getModelRouter().getRoutingModel();
        return val != null ? val : DEFAULT_ROUTING_MODEL;
    }

    public String getRoutingModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getRoutingModelReasoning();
        return val != null ? val : DEFAULT_ROUTING_REASONING;
    }

    public String getBalancedModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getBalancedModelReasoning();
        return val != null ? val : DEFAULT_BALANCED_REASONING;
    }

    public String getSmartModel() {
        String val = getRuntimeConfig().getModelRouter().getSmartModel();
        return val != null ? val : DEFAULT_SMART_MODEL;
    }

    public String getSmartModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getSmartModelReasoning();
        return val != null ? val : DEFAULT_SMART_REASONING;
    }

    public String getCodingModel() {
        String val = getRuntimeConfig().getModelRouter().getCodingModel();
        return val != null ? val : DEFAULT_CODING_MODEL;
    }

    public String getCodingModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getCodingModelReasoning();
        return val != null ? val : DEFAULT_CODING_REASONING;
    }

    public String getDeepModel() {
        String val = getRuntimeConfig().getModelRouter().getDeepModel();
        return val != null ? val : DEFAULT_DEEP_MODEL;
    }

    public String getDeepModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getDeepModelReasoning();
        return val != null ? val : DEFAULT_DEEP_REASONING;
    }

    public double getTemperature() {
        Double val = getRuntimeConfig().getModelRouter().getTemperature();
        return val != null ? val : DEFAULT_TEMPERATURE;
    }

    public boolean isDynamicTierEnabled() {
        Boolean val = getRuntimeConfig().getModelRouter().getDynamicTierEnabled();
        return val != null ? val : true;
    }

    public RuntimeConfig.TierBinding getModelTierBinding(String tier) {
        RuntimeConfig.ModelRouterConfig modelRouter = getRuntimeConfig().getModelRouter();
        if (ModelTierCatalog.ROUTING_TIER.equals(tier)) {
            return modelRouter.getRouting();
        }
        return modelRouter.getTierBinding(tier);
    }

    public boolean isFilesystemEnabled() {
        Boolean val = getRuntimeConfig().getTools().getFilesystemEnabled();
        return val != null ? val : true;
    }

    public boolean isShellEnabled() {
        Boolean val = getRuntimeConfig().getTools().getShellEnabled();
        return val != null ? val : true;
    }

    public Map<String, String> getShellEnvironmentVariables() {
        List<RuntimeConfig.ShellEnvironmentVariable> configured = getRuntimeConfig()
                .getTools()
                .getShellEnvironmentVariables();
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (RuntimeConfig.ShellEnvironmentVariable variable : configured) {
            if (variable == null || variable.getName() == null || variable.getName().isBlank()) {
                continue;
            }
            String name = variable.getName().trim();
            String value = variable.getValue() != null ? variable.getValue() : "";
            result.put(name, value);
        }
        return result;
    }

    public boolean isSkillManagementEnabled() {
        Boolean val = getRuntimeConfig().getTools().getSkillManagementEnabled();
        return val != null ? val : true;
    }

    public boolean isSkillTransitionEnabled() {
        Boolean val = getRuntimeConfig().getTools().getSkillTransitionEnabled();
        return val != null ? val : true;
    }

    public boolean isTierToolEnabled() {
        Boolean val = getRuntimeConfig().getTools().getTierEnabled();
        return val != null ? val : true;
    }

    public boolean isGoalManagementEnabled() {
        Boolean val = getRuntimeConfig().getTools().getGoalManagementEnabled();
        return val != null ? val : true;
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
        RuntimeConfig.PlanConfig planConfig = getRuntimeConfig().getPlan();
        if (planConfig == null) {
            return DEFAULT_PLAN_ENABLED;
        }
        Boolean val = planConfig.getEnabled();
        return val != null ? val : DEFAULT_PLAN_ENABLED;
    }

    public int getPlanMaxPlans() {
        RuntimeConfig.PlanConfig planConfig = getRuntimeConfig().getPlan();
        if (planConfig == null) {
            return DEFAULT_PLAN_MAX_PLANS;
        }
        Integer val = planConfig.getMaxPlans();
        return val != null ? val : DEFAULT_PLAN_MAX_PLANS;
    }

    public int getPlanMaxStepsPerPlan() {
        RuntimeConfig.PlanConfig planConfig = getRuntimeConfig().getPlan();
        if (planConfig == null) {
            return DEFAULT_PLAN_MAX_STEPS_PER_PLAN;
        }
        Integer val = planConfig.getMaxStepsPerPlan();
        return val != null ? val : DEFAULT_PLAN_MAX_STEPS_PER_PLAN;
    }

    public boolean isPlanStopOnFailure() {
        RuntimeConfig.PlanConfig planConfig = getRuntimeConfig().getPlan();
        if (planConfig == null) {
            return DEFAULT_PLAN_STOP_ON_FAILURE;
        }
        Boolean val = planConfig.getStopOnFailure();
        return val != null ? val : DEFAULT_PLAN_STOP_ON_FAILURE;
    }

    // ==================== Delayed Actions ====================

    public boolean isDelayedActionsEnabled() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null) {
            return DEFAULT_DELAYED_ACTIONS_ENABLED;
        }
        Boolean val = delayedConfig.getEnabled();
        return val != null ? val : DEFAULT_DELAYED_ACTIONS_ENABLED;
    }

    public int getDelayedActionsTickSeconds() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null) {
            return DEFAULT_DELAYED_ACTIONS_TICK_SECONDS;
        }
        Integer val = delayedConfig.getTickSeconds();
        return val != null && val > 0 ? val : DEFAULT_DELAYED_ACTIONS_TICK_SECONDS;
    }

    public int getDelayedActionsMaxPendingPerSession() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null) {
            return DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION;
        }
        Integer val = delayedConfig.getMaxPendingPerSession();
        return val != null && val > 0 ? val : DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION;
    }

    public Duration getDelayedActionsMaxDelay() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null || delayedConfig.getMaxDelay() == null || delayedConfig.getMaxDelay().isBlank()) {
            return DEFAULT_DELAYED_ACTIONS_MAX_DELAY;
        }
        try {
            return Duration.parse(delayedConfig.getMaxDelay());
        } catch (DateTimeParseException e) {
            return DEFAULT_DELAYED_ACTIONS_MAX_DELAY;
        }
    }

    public int getDelayedActionsDefaultMaxAttempts() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null) {
            return DEFAULT_DELAYED_ACTIONS_MAX_ATTEMPTS;
        }
        Integer val = delayedConfig.getDefaultMaxAttempts();
        return val != null && val > 0 ? val : DEFAULT_DELAYED_ACTIONS_MAX_ATTEMPTS;
    }

    public Duration getDelayedActionsLeaseDuration() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null || delayedConfig.getLeaseDuration() == null
                || delayedConfig.getLeaseDuration().isBlank()) {
            return DEFAULT_DELAYED_ACTIONS_LEASE_DURATION;
        }
        try {
            return Duration.parse(delayedConfig.getLeaseDuration());
        } catch (DateTimeParseException e) {
            return DEFAULT_DELAYED_ACTIONS_LEASE_DURATION;
        }
    }

    public Duration getDelayedActionsRetentionAfterCompletion() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null || delayedConfig.getRetentionAfterCompletion() == null
                || delayedConfig.getRetentionAfterCompletion().isBlank()) {
            return DEFAULT_DELAYED_ACTIONS_RETENTION;
        }
        try {
            return Duration.parse(delayedConfig.getRetentionAfterCompletion());
        } catch (DateTimeParseException e) {
            return DEFAULT_DELAYED_ACTIONS_RETENTION;
        }
    }

    public boolean isDelayedActionsRunLaterEnabled() {
        RuntimeConfig.DelayedActionsConfig delayedConfig = getRuntimeConfig().getDelayedActions();
        if (delayedConfig == null) {
            return DEFAULT_DELAYED_ACTIONS_ALLOW_RUN_LATER;
        }
        Boolean val = delayedConfig.getAllowRunLater();
        return val != null ? val : DEFAULT_DELAYED_ACTIONS_ALLOW_RUN_LATER;
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

    // ==================== Auto Mode ====================

    public boolean isAutoModeEnabled() {
        Boolean val = getRuntimeConfig().getAutoMode().getEnabled();
        return val != null ? val : true;
    }

    public int getAutoTickIntervalSeconds() {
        Integer val = getRuntimeConfig().getAutoMode().getTickIntervalSeconds();
        return val != null ? val : DEFAULT_AUTO_TICK_INTERVAL_SECONDS;
    }

    public int getAutoTaskTimeLimitMinutes() {
        Integer val = getRuntimeConfig().getAutoMode().getTaskTimeLimitMinutes();
        return val != null ? val : DEFAULT_AUTO_TIMEOUT_MINUTES;
    }

    public boolean isAutoStartEnabled() {
        Boolean val = getRuntimeConfig().getAutoMode().getAutoStart();
        return val != null ? val : true;
    }

    public int getAutoMaxGoals() {
        Integer val = getRuntimeConfig().getAutoMode().getMaxGoals();
        return val != null ? val : DEFAULT_AUTO_MAX_GOALS;
    }

    public String getAutoModelTier() {
        String val = getRuntimeConfig().getAutoMode().getModelTier();
        return val != null ? val : DEFAULT_AUTO_MODEL_TIER;
    }

    public boolean isAutoReflectionEnabled() {
        Boolean val = getRuntimeConfig().getAutoMode().getReflectionEnabled();
        return val != null ? val : DEFAULT_AUTO_REFLECTION_ENABLED;
    }

    public int getAutoReflectionFailureThreshold() {
        Integer val = getRuntimeConfig().getAutoMode().getReflectionFailureThreshold();
        return val != null ? val : DEFAULT_AUTO_REFLECTION_FAILURE_THRESHOLD;
    }

    public String getAutoReflectionModelTier() {
        return getRuntimeConfig().getAutoMode().getReflectionModelTier();
    }

    public boolean isAutoReflectionTierPriority() {
        Boolean val = getRuntimeConfig().getAutoMode().getReflectionTierPriority();
        return val != null && val;
    }

    public boolean isAutoNotifyMilestonesEnabled() {
        Boolean val = getRuntimeConfig().getAutoMode().getNotifyMilestones();
        return val != null ? val : true;
    }

    // ==================== Update ====================

    public boolean isAutoUpdateEnabled() {
        RuntimeConfig.UpdateConfig updateConfig = getRuntimeConfig().getUpdate();
        if (updateConfig == null) {
            return DEFAULT_UPDATE_AUTO_ENABLED;
        }
        Boolean val = updateConfig.getAutoEnabled();
        return val != null ? val : DEFAULT_UPDATE_AUTO_ENABLED;
    }

    public int getUpdateCheckIntervalMinutes() {
        RuntimeConfig.UpdateConfig updateConfig = getRuntimeConfig().getUpdate();
        if (updateConfig == null) {
            return DEFAULT_UPDATE_CHECK_INTERVAL_MINUTES;
        }
        Integer val = updateConfig.getCheckIntervalMinutes();
        return val != null ? val : DEFAULT_UPDATE_CHECK_INTERVAL_MINUTES;
    }

    public boolean isUpdateMaintenanceWindowEnabled() {
        RuntimeConfig.UpdateConfig updateConfig = getRuntimeConfig().getUpdate();
        if (updateConfig == null) {
            return DEFAULT_UPDATE_MAINTENANCE_WINDOW_ENABLED;
        }
        Boolean val = updateConfig.getMaintenanceWindowEnabled();
        return val != null ? val : DEFAULT_UPDATE_MAINTENANCE_WINDOW_ENABLED;
    }

    public String getUpdateMaintenanceWindowStartUtc() {
        RuntimeConfig.UpdateConfig updateConfig = getRuntimeConfig().getUpdate();
        if (updateConfig == null) {
            return DEFAULT_UPDATE_MAINTENANCE_WINDOW_START_UTC;
        }
        return normalizeUtcTimeValue(updateConfig.getMaintenanceWindowStartUtc(),
                DEFAULT_UPDATE_MAINTENANCE_WINDOW_START_UTC);
    }

    public String getUpdateMaintenanceWindowEndUtc() {
        RuntimeConfig.UpdateConfig updateConfig = getRuntimeConfig().getUpdate();
        if (updateConfig == null) {
            return DEFAULT_UPDATE_MAINTENANCE_WINDOW_END_UTC;
        }
        return normalizeUtcTimeValue(updateConfig.getMaintenanceWindowEndUtc(),
                DEFAULT_UPDATE_MAINTENANCE_WINDOW_END_UTC);
    }

    public boolean isTracingEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_ENABLED;
        }
        Boolean value = tracingConfig.getEnabled();
        return value != null ? value : DEFAULT_TRACING_ENABLED;
    }

    public boolean isPayloadSnapshotsEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_PAYLOAD_SNAPSHOTS_ENABLED;
        }
        Boolean value = tracingConfig.getPayloadSnapshotsEnabled();
        return value != null ? value : DEFAULT_TRACING_PAYLOAD_SNAPSHOTS_ENABLED;
    }

    public int getSessionTraceBudgetMb() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null || tracingConfig.getSessionTraceBudgetMb() == null) {
            return DEFAULT_TRACING_SESSION_TRACE_BUDGET_MB;
        }
        return tracingConfig.getSessionTraceBudgetMb();
    }

    public int getTraceMaxSnapshotSizeKb() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null || tracingConfig.getMaxSnapshotSizeKb() == null) {
            return DEFAULT_TRACING_MAX_SNAPSHOT_SIZE_KB;
        }
        return tracingConfig.getMaxSnapshotSizeKb();
    }

    public int getTraceMaxSnapshotsPerSpan() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null || tracingConfig.getMaxSnapshotsPerSpan() == null) {
            return DEFAULT_TRACING_MAX_SNAPSHOTS_PER_SPAN;
        }
        return tracingConfig.getMaxSnapshotsPerSpan();
    }

    public int getTraceMaxTracesPerSession() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null || tracingConfig.getMaxTracesPerSession() == null) {
            return DEFAULT_TRACING_MAX_TRACES_PER_SESSION;
        }
        return tracingConfig.getMaxTracesPerSession();
    }

    public boolean isTraceInboundPayloadCaptureEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_CAPTURE_INBOUND_PAYLOADS;
        }
        Boolean value = tracingConfig.getCaptureInboundPayloads();
        return value != null ? value : DEFAULT_TRACING_CAPTURE_INBOUND_PAYLOADS;
    }

    public boolean isTraceOutboundPayloadCaptureEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_CAPTURE_OUTBOUND_PAYLOADS;
        }
        Boolean value = tracingConfig.getCaptureOutboundPayloads();
        return value != null ? value : DEFAULT_TRACING_CAPTURE_OUTBOUND_PAYLOADS;
    }

    public boolean isTraceToolPayloadCaptureEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_CAPTURE_TOOL_PAYLOADS;
        }
        Boolean value = tracingConfig.getCaptureToolPayloads();
        return value != null ? value : DEFAULT_TRACING_CAPTURE_TOOL_PAYLOADS;
    }

    public boolean isTraceLlmPayloadCaptureEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_CAPTURE_LLM_PAYLOADS;
        }
        Boolean value = tracingConfig.getCaptureLlmPayloads();
        return value != null ? value : DEFAULT_TRACING_CAPTURE_LLM_PAYLOADS;
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
        Boolean val = getRuntimeConfig().getSecurity().getToolConfirmationEnabled();
        return val != null ? val : DEFAULT_TOOL_CONFIRMATION_ENABLED;
    }

    public int getToolConfirmationTimeoutSeconds() {
        Integer val = getRuntimeConfig().getSecurity().getToolConfirmationTimeoutSeconds();
        return val != null ? val : DEFAULT_TOOL_CONFIRMATION_TIMEOUT_SECONDS;
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

    // ==================== Turn Budget ====================

    public int getTurnMaxLlmCalls() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_MAX_LLM_CALLS;
        }
        Integer val = turnConfig.getMaxLlmCalls();
        return val != null ? val : DEFAULT_TURN_MAX_LLM_CALLS;
    }

    public int getTurnMaxToolExecutions() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_MAX_TOOL_EXECUTIONS;
        }
        Integer val = turnConfig.getMaxToolExecutions();
        return val != null ? val : DEFAULT_TURN_MAX_TOOL_EXECUTIONS;
    }

    public Duration getTurnDeadline() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null || turnConfig.getDeadline() == null || turnConfig.getDeadline().isBlank()) {
            return DEFAULT_TURN_DEADLINE;
        }
        try {
            return Duration.parse(turnConfig.getDeadline());
        } catch (DateTimeParseException e) {
            return DEFAULT_TURN_DEADLINE;
        }
    }

    public boolean isTurnAutoRetryEnabled() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_AUTO_RETRY_ENABLED;
        }
        Boolean val = turnConfig.getAutoRetryEnabled();
        return val != null ? val : DEFAULT_TURN_AUTO_RETRY_ENABLED;
    }

    public int getTurnAutoRetryMaxAttempts() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_AUTO_RETRY_MAX_ATTEMPTS;
        }
        Integer val = turnConfig.getAutoRetryMaxAttempts();
        return val != null ? val : DEFAULT_TURN_AUTO_RETRY_MAX_ATTEMPTS;
    }

    public long getTurnAutoRetryBaseDelayMs() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_AUTO_RETRY_BASE_DELAY_MS;
        }
        Long val = turnConfig.getAutoRetryBaseDelayMs();
        return val != null ? val : DEFAULT_TURN_AUTO_RETRY_BASE_DELAY_MS;
    }

    public boolean isTurnQueueSteeringEnabled() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_QUEUE_STEERING_ENABLED;
        }
        Boolean val = turnConfig.getQueueSteeringEnabled();
        return val != null ? val : DEFAULT_TURN_QUEUE_STEERING_ENABLED;
    }

    public String getTurnQueueSteeringMode() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null || turnConfig.getQueueSteeringMode() == null
                || turnConfig.getQueueSteeringMode().isBlank()) {
            return DEFAULT_TURN_QUEUE_STEERING_MODE;
        }
        return normalizeQueueMode(turnConfig.getQueueSteeringMode());
    }

    public String getTurnQueueFollowUpMode() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null || turnConfig.getQueueFollowUpMode() == null
                || turnConfig.getQueueFollowUpMode().isBlank()) {
            return DEFAULT_TURN_QUEUE_FOLLOW_UP_MODE;
        }
        return normalizeQueueMode(turnConfig.getQueueFollowUpMode());
    }

    public boolean isTurnProgressUpdatesEnabled() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_PROGRESS_UPDATES_ENABLED;
        }
        Boolean val = turnConfig.getProgressUpdatesEnabled();
        return val != null ? val : DEFAULT_TURN_PROGRESS_UPDATES_ENABLED;
    }

    public boolean isTurnProgressIntentEnabled() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_PROGRESS_INTENT_ENABLED;
        }
        Boolean val = turnConfig.getProgressIntentEnabled();
        return val != null ? val : DEFAULT_TURN_PROGRESS_INTENT_ENABLED;
    }

    public int getTurnProgressBatchSize() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_PROGRESS_BATCH_SIZE;
        }
        Integer val = turnConfig.getProgressBatchSize();
        return val != null ? val : DEFAULT_TURN_PROGRESS_BATCH_SIZE;
    }

    public Duration getTurnProgressMaxSilence() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return Duration.ofSeconds(DEFAULT_TURN_PROGRESS_MAX_SILENCE_SECONDS);
        }
        Integer seconds = turnConfig.getProgressMaxSilenceSeconds();
        int safeSeconds = seconds != null ? seconds : DEFAULT_TURN_PROGRESS_MAX_SILENCE_SECONDS;
        return Duration.ofSeconds(safeSeconds);
    }

    public int getTurnProgressSummaryTimeoutMs() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_PROGRESS_SUMMARY_TIMEOUT_MS;
        }
        Integer val = turnConfig.getProgressSummaryTimeoutMs();
        return val != null ? val : DEFAULT_TURN_PROGRESS_SUMMARY_TIMEOUT_MS;
    }

    private String normalizeQueueMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return DEFAULT_TURN_QUEUE_STEERING_MODE;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(normalized)) {
            return "all";
        }
        if ("one-at-a-time".equals(normalized) || "one_at_a_time".equals(normalized)
                || "one-at-time".equals(normalized) || "single".equals(normalized)) {
            return "one-at-a-time";
        }
        return DEFAULT_TURN_QUEUE_STEERING_MODE;
    }

    // ==================== Memory ====================

    public boolean isMemoryEnabled() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return true;
        }
        Boolean val = memoryConfig.getEnabled();
        return val != null ? val : true;
    }

    public int getMemorySoftPromptBudgetTokens() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS;
        }
        Integer val = memoryConfig.getSoftPromptBudgetTokens();
        return val != null ? val : DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS;
    }

    public int getMemoryVersion() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_VERSION;
        }
        Integer val = memoryConfig.getVersion();
        return val != null ? val : DEFAULT_MEMORY_VERSION;
    }

    public int getMemoryMaxPromptBudgetTokens() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_MAX_PROMPT_BUDGET_TOKENS;
        }
        Integer val = memoryConfig.getMaxPromptBudgetTokens();
        return val != null ? val : DEFAULT_MEMORY_MAX_PROMPT_BUDGET_TOKENS;
    }

    public int getMemoryWorkingTopK() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_WORKING_TOP_K;
        }
        Integer val = memoryConfig.getWorkingTopK();
        return val != null ? val : DEFAULT_MEMORY_WORKING_TOP_K;
    }

    public int getMemoryEpisodicTopK() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_EPISODIC_TOP_K;
        }
        Integer val = memoryConfig.getEpisodicTopK();
        return val != null ? val : DEFAULT_MEMORY_EPISODIC_TOP_K;
    }

    public int getMemorySemanticTopK() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_SEMANTIC_TOP_K;
        }
        Integer val = memoryConfig.getSemanticTopK();
        return val != null ? val : DEFAULT_MEMORY_SEMANTIC_TOP_K;
    }

    public int getMemoryProceduralTopK() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_PROCEDURAL_TOP_K;
        }
        Integer val = memoryConfig.getProceduralTopK();
        return val != null ? val : DEFAULT_MEMORY_PROCEDURAL_TOP_K;
    }

    public boolean isMemoryPromotionEnabled() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_PROMOTION_ENABLED;
        }
        Boolean val = memoryConfig.getPromotionEnabled();
        return val != null ? val : DEFAULT_MEMORY_PROMOTION_ENABLED;
    }

    public double getMemoryPromotionMinConfidence() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_PROMOTION_MIN_CONFIDENCE;
        }
        Double val = memoryConfig.getPromotionMinConfidence();
        return val != null ? val : DEFAULT_MEMORY_PROMOTION_MIN_CONFIDENCE;
    }

    public boolean isMemoryDecayEnabled() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_DECAY_ENABLED;
        }
        Boolean val = memoryConfig.getDecayEnabled();
        return val != null ? val : DEFAULT_MEMORY_DECAY_ENABLED;
    }

    public int getMemoryDecayDays() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_DECAY_DAYS;
        }
        Integer val = memoryConfig.getDecayDays();
        return val != null ? val : DEFAULT_MEMORY_DECAY_DAYS;
    }

    public int getMemoryRetrievalLookbackDays() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_RETRIEVAL_LOOKBACK_DAYS;
        }
        Integer val = memoryConfig.getRetrievalLookbackDays();
        return val != null ? val : DEFAULT_MEMORY_RETRIEVAL_LOOKBACK_DAYS;
    }

    public boolean isMemoryCodeAwareExtractionEnabled() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED;
        }
        Boolean val = memoryConfig.getCodeAwareExtractionEnabled();
        return val != null ? val : DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED;
    }

    public String getMemoryDisclosureMode() {
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = getMemoryDisclosureConfig();
        String val = disclosureConfig.getMode();
        return val != null ? val : DEFAULT_MEMORY_DISCLOSURE_MODE;
    }

    public String getMemoryPromptStyle() {
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = getMemoryDisclosureConfig();
        String val = disclosureConfig.getPromptStyle();
        return val != null ? val : DEFAULT_MEMORY_PROMPT_STYLE;
    }

    public boolean isMemoryToolExpansionEnabled() {
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = getMemoryDisclosureConfig();
        Boolean val = disclosureConfig.getToolExpansionEnabled();
        return val != null ? val : DEFAULT_MEMORY_TOOL_EXPANSION_ENABLED;
    }

    public boolean isMemoryDisclosureHintsEnabled() {
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = getMemoryDisclosureConfig();
        Boolean val = disclosureConfig.getDisclosureHintsEnabled();
        return val != null ? val : DEFAULT_MEMORY_DISCLOSURE_HINTS_ENABLED;
    }

    public double getMemoryDetailMinScore() {
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = getMemoryDisclosureConfig();
        Double val = disclosureConfig.getDetailMinScore();
        return val != null ? val : DEFAULT_MEMORY_DISCLOSURE_DETAIL_MIN_SCORE;
    }

    public boolean isMemoryRerankingEnabled() {
        RuntimeConfig.MemoryRerankingConfig rerankingConfig = getMemoryRerankingConfig();
        Boolean val = rerankingConfig.getEnabled();
        return val != null ? val : DEFAULT_MEMORY_RERANKING_ENABLED;
    }

    public String getMemoryRerankingProfile() {
        RuntimeConfig.MemoryRerankingConfig rerankingConfig = getMemoryRerankingConfig();
        String val = rerankingConfig.getProfile();
        return val != null ? val : DEFAULT_MEMORY_RERANKING_PROFILE;
    }

    public String getMemoryDiagnosticsVerbosity() {
        RuntimeConfig.MemoryDiagnosticsConfig diagnosticsConfig = getMemoryDiagnosticsConfig();
        String val = diagnosticsConfig.getVerbosity();
        return val != null ? val : DEFAULT_MEMORY_DIAGNOSTICS_VERBOSITY;
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
        StringBuilder code = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            code.append(INVITE_CHARS.charAt(SECURE_RANDOM.nextInt(INVITE_CHARS.length())));
        }

        RuntimeConfig.InviteCode inviteCode = RuntimeConfig.InviteCode.builder()
                .code(code.toString())
                .used(false)
                .createdAt(Instant.now())
                .build();

        RuntimeConfig cfg = getRuntimeConfig();
        List<RuntimeConfig.InviteCode> inviteCodes = ensureMutableInviteCodes(cfg.getTelegram());
        inviteCodes.add(inviteCode);
        persist(cfg);

        log.info("[RuntimeConfig] Generated invite code: {}", inviteCode.getCode());
        return inviteCode;
    }

    /**
     * Revoke (remove) an invite code.
     */
    public boolean revokeInviteCode(String code) {
        RuntimeConfig cfg = getRuntimeConfig();
        List<RuntimeConfig.InviteCode> codes = ensureMutableInviteCodes(cfg.getTelegram());
        boolean removed = codes.removeIf(ic -> ic.getCode().equals(code));
        if (removed) {
            persist(cfg);
            log.info("[RuntimeConfig] Revoked invite code: {}", code);
        }
        return removed;
    }

    /**
     * Redeem a single-use invite code, adding the user to allowed users.
     */
    public boolean redeemInviteCode(String code, String userId) {
        RuntimeConfig cfg = getRuntimeConfig();
        List<RuntimeConfig.InviteCode> codes = ensureMutableInviteCodes(cfg.getTelegram());

        List<String> allowed = ensureMutableAllowedUsers(cfg.getTelegram());
        if (!allowed.isEmpty() && !allowed.contains(userId)) {
            log.warn("[RuntimeConfig] Invite redemption denied for user {}: invited user already registered", userId);
            return false;
        }

        for (RuntimeConfig.InviteCode ic : codes) {
            if (ic.getCode().equals(code)) {
                if (ic.isUsed()) {
                    return false;
                }
                ic.setUsed(true);
                if (!allowed.contains(userId)) {
                    allowed.add(userId);
                }
                persist(cfg);
                log.info("[RuntimeConfig] Redeemed invite code {} for user {}", code, userId);
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a Telegram user from allowed users list.
     */
    public boolean removeTelegramAllowedUser(String userId) {
        RuntimeConfig cfg = getRuntimeConfig();
        RuntimeConfig.TelegramConfig telegramConfig = cfg.getTelegram();
        List<String> allowedUsers = ensureMutableAllowedUsers(telegramConfig);
        if (allowedUsers.isEmpty()) {
            return false;
        }
        boolean removed = allowedUsers.removeIf(existingUserId -> existingUserId.equals(userId));
        if (removed) {
            int revokedCodes = revokeActiveInviteCodes(telegramConfig);
            persist(cfg);
            log.info("[RuntimeConfig] Removed telegram allowed user: {} (revoked {} active invite codes)",
                    userId, revokedCodes);
        }
        return removed;
    }

    private List<String> ensureMutableAllowedUsers(RuntimeConfig.TelegramConfig telegramConfig) {
        List<String> allowedUsers = telegramConfig.getAllowedUsers();
        if (allowedUsers == null) {
            List<String> mutableAllowedUsers = new ArrayList<>();
            telegramConfig.setAllowedUsers(mutableAllowedUsers);
            return mutableAllowedUsers;
        }
        if (!(allowedUsers instanceof ArrayList<?>)) {
            List<String> mutableAllowedUsers = new ArrayList<>(allowedUsers);
            telegramConfig.setAllowedUsers(mutableAllowedUsers);
            return mutableAllowedUsers;
        }
        return allowedUsers;
    }

    private List<RuntimeConfig.InviteCode> ensureMutableInviteCodes(RuntimeConfig.TelegramConfig telegramConfig) {
        List<RuntimeConfig.InviteCode> inviteCodes = telegramConfig.getInviteCodes();
        if (inviteCodes == null) {
            List<RuntimeConfig.InviteCode> mutableInviteCodes = new ArrayList<>();
            telegramConfig.setInviteCodes(mutableInviteCodes);
            return mutableInviteCodes;
        }
        if (!(inviteCodes instanceof ArrayList<?>)) {
            List<RuntimeConfig.InviteCode> mutableInviteCodes = new ArrayList<>(inviteCodes);
            telegramConfig.setInviteCodes(mutableInviteCodes);
            return mutableInviteCodes;
        }
        return inviteCodes;
    }

    private int revokeActiveInviteCodes(RuntimeConfig.TelegramConfig telegramConfig) {
        List<RuntimeConfig.InviteCode> inviteCodes = ensureMutableInviteCodes(telegramConfig);
        if (inviteCodes.isEmpty()) {
            return 0;
        }

        List<RuntimeConfig.InviteCode> retainedInviteCodes = new ArrayList<>(inviteCodes.size());
        int revokedCount = 0;
        for (RuntimeConfig.InviteCode inviteCode : inviteCodes) {
            if (inviteCode != null && !inviteCode.isUsed()) {
                revokedCount++;
                continue;
            }
            retainedInviteCodes.add(inviteCode);
        }

        if (revokedCount > 0) {
            telegramConfig.setInviteCodes(retainedInviteCodes);
        }
        return revokedCount;
    }

    // ==================== Persistence ====================

    /**
     * Persist all sections of the RuntimeConfig to separate files.
     */
    private void persist(RuntimeConfig cfg) {
        persistSection(RuntimeConfig.ConfigSection.TELEGRAM, cfg.getTelegram(),
                RuntimeConfig.TelegramConfig::new);
        persistSection(RuntimeConfig.ConfigSection.MODEL_ROUTER, cfg.getModelRouter(),
                RuntimeConfig.ModelRouterConfig::new);
        persistSection(RuntimeConfig.ConfigSection.LLM, cfg.getLlm(),
                RuntimeConfig.LlmConfig::new);
        persistSection(RuntimeConfig.ConfigSection.TOOLS, cfg.getTools(),
                RuntimeConfig.ToolsConfig::new);
        persistSection(RuntimeConfig.ConfigSection.VOICE, cfg.getVoice(),
                RuntimeConfig.VoiceConfig::new);
        persistSection(RuntimeConfig.ConfigSection.AUTO_MODE, cfg.getAutoMode(),
                RuntimeConfig.AutoModeConfig::new);
        persistSection(RuntimeConfig.ConfigSection.UPDATE, cfg.getUpdate(),
                RuntimeConfig.UpdateConfig::new);
        persistSection(RuntimeConfig.ConfigSection.TRACING, cfg.getTracing(),
                RuntimeConfig.TracingConfig::new);
        persistSection(RuntimeConfig.ConfigSection.RATE_LIMIT, cfg.getRateLimit(),
                RuntimeConfig.RateLimitConfig::new);
        persistSection(RuntimeConfig.ConfigSection.SECURITY, cfg.getSecurity(),
                RuntimeConfig.SecurityConfig::new);
        persistSection(RuntimeConfig.ConfigSection.COMPACTION, cfg.getCompaction(),
                RuntimeConfig.CompactionConfig::new);
        persistSection(RuntimeConfig.ConfigSection.TURN, cfg.getTurn(),
                RuntimeConfig.TurnConfig::new);
        persistSection(RuntimeConfig.ConfigSection.MEMORY, cfg.getMemory(),
                RuntimeConfig.MemoryConfig::new);
        persistSection(RuntimeConfig.ConfigSection.SKILLS, cfg.getSkills(),
                RuntimeConfig.SkillsConfig::new);
        persistSection(RuntimeConfig.ConfigSection.MODEL_REGISTRY, cfg.getModelRegistry(),
                RuntimeConfig.ModelRegistryConfig::new);
        persistSection(RuntimeConfig.ConfigSection.USAGE, cfg.getUsage(),
                RuntimeConfig.UsageConfig::new);
        persistSection(RuntimeConfig.ConfigSection.MCP, cfg.getMcp(),
                RuntimeConfig.McpConfig::new);
        persistSection(RuntimeConfig.ConfigSection.PLAN, cfg.getPlan(),
                RuntimeConfig.PlanConfig::new);
        persistSection(RuntimeConfig.ConfigSection.DELAYED_ACTIONS, cfg.getDelayedActions(),
                RuntimeConfig.DelayedActionsConfig::new);

        persistSection(RuntimeConfig.ConfigSection.HIVE, cfg.getHive(),
                RuntimeConfig.HiveConfig::new);
        persistSection(RuntimeConfig.ConfigSection.SELF_EVOLVING, cfg.getSelfEvolving(),
                RuntimeConfig.SelfEvolvingConfig::new);

        log.debug("[RuntimeConfig] Persisted all config sections");
    }

    /**
     * Persist a single configuration section to its file.
     */
    @SuppressWarnings("unchecked")
    private <T> void persistSection(RuntimeConfig.ConfigSection section, T config,
            Supplier<T> defaultSupplier) {
        T toSave = config != null ? config : defaultSupplier.get();
        String fileName = section.getFileName();

        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toSave);
            storagePort.putTextAtomic(PREFERENCES_DIR, fileName, json, true).join();

            // Read-back validation
            String persisted = storagePort.getText(PREFERENCES_DIR, fileName).join();
            if (persisted == null || persisted.isBlank()) {
                throw new IllegalStateException("Persisted " + section.getFileId() + " is empty after write");
            }
            Object validated = objectMapper.readValue(persisted, section.getConfigClass());
            if (validated == null) {
                throw new IllegalStateException("Persisted " + section.getFileId() + " failed validation");
            }

            log.trace("[RuntimeConfig] Persisted section: {}", section.getFileId());
        } catch (Exception e) {
            log.error("[RuntimeConfig] Failed to persist section {}: {}", section.getFileId(), e.getMessage());
            throw new IllegalStateException("Failed to persist config section: " + section.getFileId(), e);
        }
    }

    /**
     * Load all configuration sections and assemble into RuntimeConfig.
     */
    private RuntimeConfig loadOrCreate() {
        RuntimeConfig.TelegramConfig telegram = loadSection(RuntimeConfig.ConfigSection.TELEGRAM,
                RuntimeConfig.TelegramConfig.class, RuntimeConfig.TelegramConfig::new);
        RuntimeConfig.ModelRouterConfig modelRouter = loadSection(RuntimeConfig.ConfigSection.MODEL_ROUTER,
                RuntimeConfig.ModelRouterConfig.class, RuntimeConfig.ModelRouterConfig::new);
        RuntimeConfig.LlmConfig llm = loadSection(RuntimeConfig.ConfigSection.LLM,
                RuntimeConfig.LlmConfig.class, RuntimeConfig.LlmConfig::new);
        RuntimeConfig.ToolsConfig tools = loadSection(RuntimeConfig.ConfigSection.TOOLS,
                RuntimeConfig.ToolsConfig.class, RuntimeConfig.ToolsConfig::new);
        RuntimeConfig.VoiceConfig voice = loadSection(RuntimeConfig.ConfigSection.VOICE,
                RuntimeConfig.VoiceConfig.class, RuntimeConfig.VoiceConfig::new);
        RuntimeConfig.AutoModeConfig autoMode = loadSection(RuntimeConfig.ConfigSection.AUTO_MODE,
                RuntimeConfig.AutoModeConfig.class, RuntimeConfig.AutoModeConfig::new);
        RuntimeConfig.UpdateConfig update = loadSection(RuntimeConfig.ConfigSection.UPDATE,
                RuntimeConfig.UpdateConfig.class, RuntimeConfig.UpdateConfig::new);
        RuntimeConfig.TracingConfig tracing = loadSection(RuntimeConfig.ConfigSection.TRACING,
                RuntimeConfig.TracingConfig.class, RuntimeConfig.TracingConfig::new);
        RuntimeConfig.RateLimitConfig rateLimit = loadSection(RuntimeConfig.ConfigSection.RATE_LIMIT,
                RuntimeConfig.RateLimitConfig.class, RuntimeConfig.RateLimitConfig::new);
        RuntimeConfig.SecurityConfig security = loadSection(RuntimeConfig.ConfigSection.SECURITY,
                RuntimeConfig.SecurityConfig.class, RuntimeConfig.SecurityConfig::new);
        RuntimeConfig.CompactionConfig compaction = loadSection(RuntimeConfig.ConfigSection.COMPACTION,
                RuntimeConfig.CompactionConfig.class, RuntimeConfig.CompactionConfig::new);
        RuntimeConfig.TurnConfig turn = loadSection(RuntimeConfig.ConfigSection.TURN,
                RuntimeConfig.TurnConfig.class, RuntimeConfig.TurnConfig::new);
        RuntimeConfig.MemoryConfig memory = loadSection(RuntimeConfig.ConfigSection.MEMORY,
                RuntimeConfig.MemoryConfig.class, RuntimeConfig.MemoryConfig::new);
        RuntimeConfig.SkillsConfig skills = loadSection(RuntimeConfig.ConfigSection.SKILLS,
                RuntimeConfig.SkillsConfig.class, RuntimeConfig.SkillsConfig::new);
        RuntimeConfig.ModelRegistryConfig modelRegistry = loadSection(RuntimeConfig.ConfigSection.MODEL_REGISTRY,
                RuntimeConfig.ModelRegistryConfig.class, RuntimeConfig.ModelRegistryConfig::new);
        RuntimeConfig.UsageConfig usage = loadSection(RuntimeConfig.ConfigSection.USAGE,
                RuntimeConfig.UsageConfig.class, RuntimeConfig.UsageConfig::new);
        RuntimeConfig.McpConfig mcp = loadSection(RuntimeConfig.ConfigSection.MCP,
                RuntimeConfig.McpConfig.class, RuntimeConfig.McpConfig::new);
        RuntimeConfig.PlanConfig plan = loadSection(RuntimeConfig.ConfigSection.PLAN,
                RuntimeConfig.PlanConfig.class, RuntimeConfig.PlanConfig::new);
        RuntimeConfig.DelayedActionsConfig delayedActions = loadSection(RuntimeConfig.ConfigSection.DELAYED_ACTIONS,
                RuntimeConfig.DelayedActionsConfig.class, RuntimeConfig.DelayedActionsConfig::new);

        RuntimeConfig.HiveConfig hive = loadSection(RuntimeConfig.ConfigSection.HIVE,
                RuntimeConfig.HiveConfig.class, RuntimeConfig.HiveConfig::new);
        RuntimeConfig.SelfEvolvingConfig selfEvolving = loadSection(RuntimeConfig.ConfigSection.SELF_EVOLVING,
                RuntimeConfig.SelfEvolvingConfig.class, RuntimeConfig.SelfEvolvingConfig::new);

        RuntimeConfig config = RuntimeConfig.builder()
                .telegram(telegram)
                .modelRouter(modelRouter)
                .llm(llm)
                .tools(tools)
                .voice(voice)
                .autoMode(autoMode)
                .update(update)
                .tracing(tracing)
                .rateLimit(rateLimit)
                .security(security)
                .compaction(compaction)
                .turn(turn)
                .memory(memory)
                .skills(skills)
                .modelRegistry(modelRegistry)
                .usage(usage)
                .mcp(mcp)
                .plan(plan)
                .delayedActions(delayedActions)
                .hive(hive)
                .selfEvolving(selfEvolving)
                .build();

        log.info("[RuntimeConfig] Loaded runtime config from {} section files",
                RuntimeConfig.ConfigSection.values().length);
        return config;
    }

    private RuntimeConfig buildEffectiveRuntimeConfig(RuntimeConfig baseConfig) {
        RuntimeConfig effectiveConfig = copyRuntimeConfig(baseConfig);
        normalizeRuntimeConfig(effectiveConfig);
        clearSelfEvolvingRuntimeMetadata(effectiveConfig);
        selfEvolvingBootstrapOverrideService.apply(effectiveConfig);
        applySelfEvolvingRuntimeMetadata(effectiveConfig);
        normalizeRuntimeConfig(effectiveConfig);
        return effectiveConfig;
    }

    private RuntimeConfig copyRuntimeConfig(RuntimeConfig source) {
        if (source == null) {
            return RuntimeConfig.builder().build();
        }
        try {
            String json = objectMapper.writeValueAsString(source);
            return objectMapper.readValue(json, RuntimeConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy runtime config", e);
        }
    }

    /**
     * Load a single configuration section from its file.
     */
    private <T> T loadSection(RuntimeConfig.ConfigSection section, Class<T> configClass,
            Supplier<T> defaultSupplier) {
        return loadSection(section, configClass, defaultSupplier, true);
    }

    private <T> T loadSection(RuntimeConfig.ConfigSection section, Class<T> configClass,
            Supplier<T> defaultSupplier, boolean persistDefault) {
        String fileName = section.getFileName();

        try {
            String json = storagePort.getText(PREFERENCES_DIR, fileName).join();
            if (json != null && !json.isBlank()) {
                T loaded = objectMapper.readValue(json, configClass);
                log.trace("[RuntimeConfig] Loaded section: {}", section.getFileId());
                return loaded;
            }
        } catch (IOException | RuntimeException e) { // NOSONAR - intentional fallback to default
            log.debug("[RuntimeConfig] No saved {} config, using default: {}", section.getFileId(), e.getMessage());
        }

        T defaultConfig = defaultSupplier.get();
        if (!persistDefault) {
            return defaultConfig;
        }

        // Create default and persist it
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(defaultConfig);
            storagePort.putTextAtomic(PREFERENCES_DIR, fileName, json, true).join();
            log.debug("[RuntimeConfig] Created default section: {}", section.getFileId());
        } catch (Exception e) {
            log.warn("[RuntimeConfig] Failed to persist default {}: {}", section.getFileId(), e.getMessage());
        }
        return defaultConfig;
    }

    private void normalizeRuntimeConfig(RuntimeConfig cfg) {
        if (cfg == null) {
            return;
        }
        if (cfg.getTelegram() != null) {
            cfg.getTelegram().setAuthMode("invite_only");
            ensureMutableAllowedUsers(cfg.getTelegram());
            ensureMutableInviteCodes(cfg.getTelegram());
        }
        if (cfg.getTools() == null) {
            cfg.setTools(RuntimeConfig.ToolsConfig.builder().build());
        }
        if (cfg.getModelRouter() == null) {
            cfg.setModelRouter(new RuntimeConfig.ModelRouterConfig());
        }
        normalizeModelRouterConfig(cfg.getModelRouter());
        if (cfg.getAutoMode() == null) {
            cfg.setAutoMode(new RuntimeConfig.AutoModeConfig());
        }
        if (cfg.getAutoMode().getReflectionEnabled() == null) {
            cfg.getAutoMode().setReflectionEnabled(DEFAULT_AUTO_REFLECTION_ENABLED);
        }
        if (cfg.getAutoMode().getReflectionFailureThreshold() == null) {
            cfg.getAutoMode().setReflectionFailureThreshold(DEFAULT_AUTO_REFLECTION_FAILURE_THRESHOLD);
        }
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
        cfg.getUpdate().setMaintenanceWindowStartUtc(normalizeUtcTimeValue(
                cfg.getUpdate().getMaintenanceWindowStartUtc(),
                DEFAULT_UPDATE_MAINTENANCE_WINDOW_START_UTC));
        cfg.getUpdate().setMaintenanceWindowEndUtc(normalizeUtcTimeValue(
                cfg.getUpdate().getMaintenanceWindowEndUtc(),
                DEFAULT_UPDATE_MAINTENANCE_WINDOW_END_UTC));
        if (cfg.getTracing() == null) {
            cfg.setTracing(new RuntimeConfig.TracingConfig());
        }
        if (cfg.getTracing().getEnabled() == null) {
            cfg.getTracing().setEnabled(DEFAULT_TRACING_ENABLED);
        }
        if (cfg.getTracing().getPayloadSnapshotsEnabled() == null) {
            cfg.getTracing().setPayloadSnapshotsEnabled(DEFAULT_TRACING_PAYLOAD_SNAPSHOTS_ENABLED);
        }
        Integer sessionTraceBudgetMb = cfg.getTracing().getSessionTraceBudgetMb();
        if (sessionTraceBudgetMb == null || sessionTraceBudgetMb < 1) {
            cfg.getTracing().setSessionTraceBudgetMb(DEFAULT_TRACING_SESSION_TRACE_BUDGET_MB);
        }
        Integer maxSnapshotSizeKb = cfg.getTracing().getMaxSnapshotSizeKb();
        if (maxSnapshotSizeKb == null || maxSnapshotSizeKb < 1) {
            cfg.getTracing().setMaxSnapshotSizeKb(DEFAULT_TRACING_MAX_SNAPSHOT_SIZE_KB);
        }
        Integer maxSnapshotsPerSpan = cfg.getTracing().getMaxSnapshotsPerSpan();
        if (maxSnapshotsPerSpan == null || maxSnapshotsPerSpan < 1) {
            cfg.getTracing().setMaxSnapshotsPerSpan(DEFAULT_TRACING_MAX_SNAPSHOTS_PER_SPAN);
        }
        Integer maxTracesPerSession = cfg.getTracing().getMaxTracesPerSession();
        if (maxTracesPerSession == null || maxTracesPerSession < 1) {
            cfg.getTracing().setMaxTracesPerSession(DEFAULT_TRACING_MAX_TRACES_PER_SESSION);
        }
        if (cfg.getTracing().getCaptureInboundPayloads() == null) {
            cfg.getTracing().setCaptureInboundPayloads(DEFAULT_TRACING_CAPTURE_INBOUND_PAYLOADS);
        }
        if (cfg.getTracing().getCaptureOutboundPayloads() == null) {
            cfg.getTracing().setCaptureOutboundPayloads(DEFAULT_TRACING_CAPTURE_OUTBOUND_PAYLOADS);
        }
        if (cfg.getTracing().getCaptureToolPayloads() == null) {
            cfg.getTracing().setCaptureToolPayloads(DEFAULT_TRACING_CAPTURE_TOOL_PAYLOADS);
        }
        if (cfg.getTracing().getCaptureLlmPayloads() == null) {
            cfg.getTracing().setCaptureLlmPayloads(DEFAULT_TRACING_CAPTURE_LLM_PAYLOADS);
        }
        cfg.getTools().setShellEnvironmentVariables(
                normalizeShellEnvironmentVariables(cfg.getTools().getShellEnvironmentVariables()));
        if (cfg.getLlm() == null) {
            cfg.setLlm(RuntimeConfig.LlmConfig.builder().build());
        }
        if (cfg.getLlm().getProviders() == null) {
            cfg.getLlm().setProviders(new LinkedHashMap<>());
        }
        if (cfg.getMemory() == null) {
            cfg.setMemory(new RuntimeConfig.MemoryConfig());
        }
        if (cfg.getCompaction() == null) {
            cfg.setCompaction(new RuntimeConfig.CompactionConfig());
        }
        cfg.getCompaction().setTriggerMode(normalizeCompactionTriggerMode(cfg.getCompaction().getTriggerMode()));
        Double modelThresholdRatio = cfg.getCompaction().getModelThresholdRatio();
        if (modelThresholdRatio == null || modelThresholdRatio <= 0.0d || modelThresholdRatio > 1.0d) {
            cfg.getCompaction().setModelThresholdRatio(DEFAULT_COMPACTION_MODEL_THRESHOLD_RATIO);
        }
        if (cfg.getCompaction().getPreserveTurnBoundaries() == null) {
            cfg.getCompaction().setPreserveTurnBoundaries(DEFAULT_COMPACTION_PRESERVE_TURN_BOUNDARIES);
        }
        if (cfg.getCompaction().getDetailsEnabled() == null) {
            cfg.getCompaction().setDetailsEnabled(DEFAULT_COMPACTION_DETAILS_ENABLED);
        }
        if (cfg.getCompaction().getDetailsMaxItemsPerCategory() == null) {
            cfg.getCompaction().setDetailsMaxItemsPerCategory(DEFAULT_COMPACTION_DETAILS_MAX_ITEMS);
        }
        if (cfg.getCompaction().getSummaryTimeoutMs() == null) {
            cfg.getCompaction().setSummaryTimeoutMs(DEFAULT_COMPACTION_SUMMARY_TIMEOUT_MS);
        }
        if (cfg.getTurn() == null) {
            cfg.setTurn(new RuntimeConfig.TurnConfig());
        }
        if (cfg.getModelRegistry() == null) {
            cfg.setModelRegistry(new RuntimeConfig.ModelRegistryConfig());
        }
        if (cfg.getModelRegistry().getBranch() == null || cfg.getModelRegistry().getBranch().isBlank()) {
            cfg.getModelRegistry().setBranch(DEFAULT_MODEL_REGISTRY_BRANCH);
        } else {
            cfg.getModelRegistry().setBranch(cfg.getModelRegistry().getBranch().trim());
        }
        if (cfg.getPlan() == null) {
            cfg.setPlan(new RuntimeConfig.PlanConfig());
        }
        if (cfg.getDelayedActions() == null) {
            cfg.setDelayedActions(new RuntimeConfig.DelayedActionsConfig());
        }
        if (cfg.getPlan().getEnabled() == null) {
            cfg.getPlan().setEnabled(DEFAULT_PLAN_ENABLED);
        }
        if (cfg.getPlan().getMaxPlans() == null || cfg.getPlan().getMaxPlans() < 1) {
            cfg.getPlan().setMaxPlans(DEFAULT_PLAN_MAX_PLANS);
        }
        if (cfg.getPlan().getMaxStepsPerPlan() == null || cfg.getPlan().getMaxStepsPerPlan() < 1) {
            cfg.getPlan().setMaxStepsPerPlan(DEFAULT_PLAN_MAX_STEPS_PER_PLAN);
        }
        if (cfg.getPlan().getStopOnFailure() == null) {
            cfg.getPlan().setStopOnFailure(DEFAULT_PLAN_STOP_ON_FAILURE);
        }
        if (cfg.getDelayedActions().getEnabled() == null) {
            cfg.getDelayedActions().setEnabled(DEFAULT_DELAYED_ACTIONS_ENABLED);
        }
        Integer delayedTickSeconds = cfg.getDelayedActions().getTickSeconds();
        if (delayedTickSeconds == null || delayedTickSeconds < 1) {
            cfg.getDelayedActions().setTickSeconds(DEFAULT_DELAYED_ACTIONS_TICK_SECONDS);
        }
        Integer delayedMaxPending = cfg.getDelayedActions().getMaxPendingPerSession();
        if (delayedMaxPending == null || delayedMaxPending < 1) {
            cfg.getDelayedActions().setMaxPendingPerSession(DEFAULT_DELAYED_ACTIONS_MAX_PENDING_PER_SESSION);
        }
        if (cfg.getDelayedActions().getMaxDelay() == null || cfg.getDelayedActions().getMaxDelay().isBlank()) {
            cfg.getDelayedActions().setMaxDelay(DEFAULT_DELAYED_ACTIONS_MAX_DELAY.toString());
        } else if (!isValidDuration(cfg.getDelayedActions().getMaxDelay())) {
            cfg.getDelayedActions().setMaxDelay(DEFAULT_DELAYED_ACTIONS_MAX_DELAY.toString());
        }
        Integer delayedMaxAttempts = cfg.getDelayedActions().getDefaultMaxAttempts();
        if (delayedMaxAttempts == null || delayedMaxAttempts < 1) {
            cfg.getDelayedActions().setDefaultMaxAttempts(DEFAULT_DELAYED_ACTIONS_MAX_ATTEMPTS);
        }
        if (cfg.getDelayedActions().getLeaseDuration() == null
                || cfg.getDelayedActions().getLeaseDuration().isBlank()) {
            cfg.getDelayedActions().setLeaseDuration(DEFAULT_DELAYED_ACTIONS_LEASE_DURATION.toString());
        } else if (!isValidDuration(cfg.getDelayedActions().getLeaseDuration())) {
            cfg.getDelayedActions().setLeaseDuration(DEFAULT_DELAYED_ACTIONS_LEASE_DURATION.toString());
        }
        if (cfg.getDelayedActions().getRetentionAfterCompletion() == null
                || cfg.getDelayedActions().getRetentionAfterCompletion().isBlank()) {
            cfg.getDelayedActions().setRetentionAfterCompletion(DEFAULT_DELAYED_ACTIONS_RETENTION.toString());
        } else if (!isValidDuration(cfg.getDelayedActions().getRetentionAfterCompletion())) {
            cfg.getDelayedActions()
                    .setRetentionAfterCompletion(DEFAULT_DELAYED_ACTIONS_RETENTION.toString());
        }
        if (cfg.getDelayedActions().getAllowRunLater() == null) {
            cfg.getDelayedActions().setAllowRunLater(DEFAULT_DELAYED_ACTIONS_ALLOW_RUN_LATER);
        }
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
        normalizeSelfEvolvingConfig(cfg);
        if (!Integer.valueOf(DEFAULT_MEMORY_VERSION).equals(cfg.getMemory().getVersion())) {
            cfg.getMemory().setVersion(DEFAULT_MEMORY_VERSION);
        }
        if (cfg.getTurn().getAutoRetryEnabled() == null) {
            cfg.getTurn().setAutoRetryEnabled(DEFAULT_TURN_AUTO_RETRY_ENABLED);
        }
        if (cfg.getTurn().getAutoRetryMaxAttempts() == null) {
            cfg.getTurn().setAutoRetryMaxAttempts(DEFAULT_TURN_AUTO_RETRY_MAX_ATTEMPTS);
        }
        if (cfg.getTurn().getAutoRetryBaseDelayMs() == null) {
            cfg.getTurn().setAutoRetryBaseDelayMs(DEFAULT_TURN_AUTO_RETRY_BASE_DELAY_MS);
        }
        if (cfg.getTurn().getQueueSteeringEnabled() == null) {
            cfg.getTurn().setQueueSteeringEnabled(DEFAULT_TURN_QUEUE_STEERING_ENABLED);
        }
        if (cfg.getTurn().getQueueSteeringMode() == null || cfg.getTurn().getQueueSteeringMode().isBlank()) {
            cfg.getTurn().setQueueSteeringMode(DEFAULT_TURN_QUEUE_STEERING_MODE);
        } else {
            cfg.getTurn().setQueueSteeringMode(normalizeQueueMode(cfg.getTurn().getQueueSteeringMode()));
        }
        if (cfg.getTurn().getQueueFollowUpMode() == null || cfg.getTurn().getQueueFollowUpMode().isBlank()) {
            cfg.getTurn().setQueueFollowUpMode(DEFAULT_TURN_QUEUE_FOLLOW_UP_MODE);
        } else {
            cfg.getTurn().setQueueFollowUpMode(normalizeQueueMode(cfg.getTurn().getQueueFollowUpMode()));
        }
        if (cfg.getTurn().getProgressUpdatesEnabled() == null) {
            cfg.getTurn().setProgressUpdatesEnabled(DEFAULT_TURN_PROGRESS_UPDATES_ENABLED);
        }
        if (cfg.getTurn().getProgressIntentEnabled() == null) {
            cfg.getTurn().setProgressIntentEnabled(DEFAULT_TURN_PROGRESS_INTENT_ENABLED);
        }
        Integer progressBatchSize = cfg.getTurn().getProgressBatchSize();
        if (progressBatchSize == null || progressBatchSize < 1) {
            cfg.getTurn().setProgressBatchSize(DEFAULT_TURN_PROGRESS_BATCH_SIZE);
        }
        Integer progressMaxSilenceSeconds = cfg.getTurn().getProgressMaxSilenceSeconds();
        if (progressMaxSilenceSeconds == null || progressMaxSilenceSeconds < 1) {
            cfg.getTurn().setProgressMaxSilenceSeconds(DEFAULT_TURN_PROGRESS_MAX_SILENCE_SECONDS);
        }
        Integer progressSummaryTimeoutMs = cfg.getTurn().getProgressSummaryTimeoutMs();
        if (progressSummaryTimeoutMs == null || progressSummaryTimeoutMs < 1000) {
            cfg.getTurn().setProgressSummaryTimeoutMs(DEFAULT_TURN_PROGRESS_SUMMARY_TIMEOUT_MS);
        }
        normalizeMemoryConfig(cfg.getMemory());
        normalizeSecretFlags(cfg);
    }

    private void normalizeSelfEvolvingConfig(RuntimeConfig cfg) {
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
        if (selfEvolvingConfig.getTactics() == null) {
            selfEvolvingConfig.setTactics(new RuntimeConfig.SelfEvolvingTacticsConfig());
        }
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = selfEvolvingConfig.getTactics();
        if (tacticsConfig.getEnabled() == null) {
            tacticsConfig.setEnabled(DEFAULT_SELF_EVOLVING_TACTICS_ENABLED);
        }
        tacticsConfig.setEnabled(Boolean.TRUE.equals(selfEvolvingConfig.getEnabled()));
        if (tacticsConfig.getSearch() == null) {
            tacticsConfig.setSearch(new RuntimeConfig.SelfEvolvingTacticSearchConfig());
        }
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = tacticsConfig.getSearch();
        searchConfig.setMode(normalizeNonBlankString(
                searchConfig.getMode(),
                DEFAULT_SELF_EVOLVING_TACTIC_SEARCH_MODE));
        if (searchConfig.getBm25() == null) {
            searchConfig.setBm25(new RuntimeConfig.SelfEvolvingTacticBm25Config());
        }
        if (searchConfig.getBm25().getEnabled() == null) {
            searchConfig.getBm25().setEnabled(DEFAULT_SELF_EVOLVING_TACTIC_BM25_ENABLED);
        }
        if (searchConfig.getEmbeddings() == null) {
            searchConfig.setEmbeddings(new RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig());
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig = searchConfig.getEmbeddings();
        if (embeddingsConfig.getEnabled() == null) {
            embeddingsConfig.setEnabled(DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDINGS_ENABLED);
        }
        embeddingsConfig.setEnabled("hybrid".equalsIgnoreCase(searchConfig.getMode()));
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
        localConfig.setMinimumRuntimeVersion(normalizeNonBlankString(
                localConfig.getMinimumRuntimeVersion(),
                DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_MINIMUM_RUNTIME_VERSION));
        if (searchConfig.getRerank() == null) {
            searchConfig.setRerank(new RuntimeConfig.SelfEvolvingTacticRerankConfig());
        }
        RuntimeConfig.SelfEvolvingTacticRerankConfig rerankConfig = searchConfig.getRerank();
        if (rerankConfig.getCrossEncoder() == null) {
            rerankConfig.setCrossEncoder(DEFAULT_SELF_EVOLVING_TACTIC_RERANK_CROSS_ENCODER);
        }
        rerankConfig.setTier(normalizeNonBlankString(
                rerankConfig.getTier(),
                DEFAULT_SELF_EVOLVING_TACTIC_RERANK_TIER));
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
        if (selfEvolvingConfig.getCapture() == null) {
            selfEvolvingConfig.setCapture(new RuntimeConfig.SelfEvolvingCaptureConfig());
        }
        RuntimeConfig.SelfEvolvingCaptureConfig captureConfig = selfEvolvingConfig.getCapture();
        captureConfig.setLlm(normalizeCaptureMode(captureConfig.getLlm(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL));
        captureConfig.setTool(normalizeCaptureMode(captureConfig.getTool(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL));
        captureConfig.setContext(
                normalizeCaptureMode(captureConfig.getContext(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL));
        captureConfig.setSkill(normalizeCaptureMode(captureConfig.getSkill(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL));
        captureConfig.setTier(normalizeCaptureMode(captureConfig.getTier(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL));
        captureConfig.setInfra(
                normalizeCaptureMode(captureConfig.getInfra(), DEFAULT_SELF_EVOLVING_CAPTURE_MODE_META_ONLY));

        if (selfEvolvingConfig.getJudge() == null) {
            selfEvolvingConfig.setJudge(new RuntimeConfig.SelfEvolvingJudgeConfig());
        }
        RuntimeConfig.SelfEvolvingJudgeConfig judgeConfig = selfEvolvingConfig.getJudge();
        if (judgeConfig.getEnabled() == null) {
            judgeConfig.setEnabled(DEFAULT_SELF_EVOLVING_JUDGE_ENABLED);
        }
        judgeConfig.setPrimaryTier(normalizeSelfEvolvingJudgeTier(
                judgeConfig.getPrimaryTier(),
                DEFAULT_SELF_EVOLVING_JUDGE_PRIMARY_TIER));
        judgeConfig.setTiebreakerTier(normalizeSelfEvolvingJudgeTier(
                judgeConfig.getTiebreakerTier(),
                DEFAULT_SELF_EVOLVING_JUDGE_TIEBREAKER_TIER));
        judgeConfig.setEvolutionTier(normalizeSelfEvolvingJudgeTier(
                judgeConfig.getEvolutionTier(),
                DEFAULT_SELF_EVOLVING_JUDGE_EVOLUTION_TIER));
        if (judgeConfig.getRequireEvidenceAnchors() == null) {
            judgeConfig.setRequireEvidenceAnchors(DEFAULT_SELF_EVOLVING_REQUIRE_EVIDENCE_ANCHORS);
        }
        Double uncertaintyThreshold = judgeConfig.getUncertaintyThreshold();
        if (uncertaintyThreshold == null || uncertaintyThreshold < 0.0d || uncertaintyThreshold > 1.0d) {
            judgeConfig.setUncertaintyThreshold(DEFAULT_SELF_EVOLVING_UNCERTAINTY_THRESHOLD);
        }

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

        if (selfEvolvingConfig.getPromotion() == null) {
            selfEvolvingConfig.setPromotion(new RuntimeConfig.SelfEvolvingPromotionConfig());
        }
        RuntimeConfig.SelfEvolvingPromotionConfig promotionConfig = selfEvolvingConfig.getPromotion();
        promotionConfig.setMode(normalizeNonBlankString(
                promotionConfig.getMode(),
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

    private List<String> normalizeStringList(List<String> values, List<String> defaultValues) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>(defaultValues);
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String normalizedValue = normalizeNonBlankString(value, null);
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
        String normalizedValue = normalizeNonBlankString(value, defaultValue);
        if (normalizedValue == null) {
            return defaultValue;
        }
        if (DEFAULT_SELF_EVOLVING_CAPTURE_MODE_FULL.equals(normalizedValue)
                || DEFAULT_SELF_EVOLVING_CAPTURE_MODE_META_ONLY.equals(normalizedValue)) {
            return normalizedValue;
        }
        return defaultValue;
    }

    private String normalizeSelfEvolvingJudgeTier(String value, String defaultValue) {
        String normalizedValue = normalizeNonBlankString(value, defaultValue);
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

    private String normalizeNonBlankString(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private RuntimeConfig.MemoryDisclosureConfig getMemoryDisclosureConfig() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null || memoryConfig.getDisclosure() == null) {
            return RuntimeConfig.MemoryDisclosureConfig.builder().build();
        }
        return memoryConfig.getDisclosure();
    }

    private RuntimeConfig.MemoryDiagnosticsConfig getMemoryDiagnosticsConfig() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null || memoryConfig.getDiagnostics() == null) {
            return RuntimeConfig.MemoryDiagnosticsConfig.builder().build();
        }
        return memoryConfig.getDiagnostics();
    }

    private RuntimeConfig.MemoryRerankingConfig getMemoryRerankingConfig() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null || memoryConfig.getReranking() == null) {
            return RuntimeConfig.MemoryRerankingConfig.builder().build();
        }
        return memoryConfig.getReranking();
    }

    private void normalizeMemoryConfig(RuntimeConfig.MemoryConfig memoryConfig) {
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

    private void normalizeModelRouterConfig(RuntimeConfig.ModelRouterConfig modelRouter) {
        if (modelRouter == null) {
            return;
        }
        RuntimeConfig.TierBinding routingBinding = normalizeBinding(
                modelRouter.getRouting(),
                DEFAULT_ROUTING_MODEL,
                DEFAULT_ROUTING_REASONING);
        modelRouter.setRouting(routingBinding);

        Map<String, RuntimeConfig.TierBinding> normalizedTiers = new LinkedHashMap<>();
        for (String tier : ModelTierCatalog.orderedExplicitTiers()) {
            normalizedTiers.put(tier, normalizeBinding(
                    modelRouter.getTierBinding(tier),
                    defaultModelForTier(tier),
                    defaultReasoningForTier(tier)));
        }
        modelRouter.setTiers(normalizedTiers);

        if (modelRouter.getDynamicTierEnabled() == null) {
            modelRouter.setDynamicTierEnabled(true);
        }
    }

    private boolean isValidDuration(String value) {
        try {
            Duration parsed = Duration.parse(value);
            return parsed != null;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private RuntimeConfig.TierBinding normalizeBinding(RuntimeConfig.TierBinding binding, String defaultModel,
            String defaultReasoning) {
        RuntimeConfig.TierBinding normalized = binding != null
                ? binding
                : RuntimeConfig.TierBinding.builder().build();
        if (normalized.getModel() != null) {
            String trimmedModel = normalized.getModel().trim();
            normalized.setModel(trimmedModel.isEmpty() ? null : trimmedModel);
        }
        if (normalized.getReasoning() != null) {
            String trimmedReasoning = normalized.getReasoning().trim();
            normalized.setReasoning(trimmedReasoning.isEmpty() ? null : trimmedReasoning);
        }
        if (normalized.getModel() == null && defaultModel != null) {
            normalized.setModel(defaultModel);
        }
        if (normalized.getReasoning() == null) {
            normalized.setReasoning(defaultReasoning);
        }
        return normalized;
    }

    private String defaultModelForTier(String tier) {
        return switch (tier) {
        case "balanced" -> DEFAULT_BALANCED_MODEL;
        case "smart" -> DEFAULT_SMART_MODEL;
        case "deep" -> DEFAULT_DEEP_MODEL;
        case "coding" -> DEFAULT_CODING_MODEL;
        default -> null;
        };
    }

    private String defaultReasoningForTier(String tier) {
        return switch (tier) {
        case "balanced" -> DEFAULT_BALANCED_REASONING;
        case "smart" -> DEFAULT_SMART_REASONING;
        case "deep" -> DEFAULT_DEEP_REASONING;
        case "coding" -> DEFAULT_CODING_REASONING;
        default -> REASONING_NONE;
        };
    }

    private String normalizeVoiceProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (LEGACY_ELEVENLABS_PROVIDER.equals(normalized)) {
            return DEFAULT_STT_PROVIDER;
        }
        if (LEGACY_WHISPER_PROVIDER.equals(normalized)) {
            return DEFAULT_WHISPER_STT_PROVIDER;
        }
        return normalized;
    }

    private String normalizeCompactionTriggerMode(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_COMPACTION_TRIGGER_MODE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
        case DEFAULT_COMPACTION_TRIGGER_MODE, COMPACTION_TRIGGER_MODE_TOKEN_THRESHOLD -> normalized;
        default -> DEFAULT_COMPACTION_TRIGGER_MODE;
        };
    }

    private String normalizeUtcTimeValue(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            java.time.LocalTime parsed = java.time.LocalTime.parse(value.trim());
            return parsed.withSecond(0).withNano(0).toString();
        } catch (DateTimeParseException e) {
            return defaultValue;
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
            deduplicated.put(normalizedName, RuntimeConfig.ShellEnvironmentVariable.builder()
                    .name(normalizedName)
                    .value(normalizedValue)
                    .build());
        }
        return new ArrayList<>(deduplicated.values());
    }

    private void normalizeSecretFlags(RuntimeConfig cfg) {
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

    private void redactSecrets(RuntimeConfig cfg) {
        cfg.getTelegram().setToken(Secret.redacted(cfg.getTelegram().getToken()));
        cfg.getVoice().setApiKey(Secret.redacted(cfg.getVoice().getApiKey()));
        cfg.getVoice().setWhisperSttApiKey(Secret.redacted(cfg.getVoice().getWhisperSttApiKey()));

        if (cfg.getLlm() != null && cfg.getLlm().getProviders() != null) {
            for (RuntimeConfig.LlmProviderConfig providerConfig : cfg.getLlm().getProviders().values()) {
                if (providerConfig != null) {
                    providerConfig.setApiKey(Secret.redacted(providerConfig.getApiKey()));
                }
            }
        }
    }
}

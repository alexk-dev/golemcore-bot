package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <li>tools.json - Tool configurations (browser, filesystem, etc.)
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
    private static final int DEFAULT_AUTO_COMPACT_MAX_TOKENS = 50000;
    private static final int DEFAULT_AUTO_COMPACT_KEEP_LAST = 20;
    private static final int DEFAULT_MEMORY_RECENT_DAYS = 7;
    private static final int DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS = 1800;
    private static final int DEFAULT_MEMORY_MAX_PROMPT_BUDGET_TOKENS = 3500;
    private static final int DEFAULT_MEMORY_WORKING_TOP_K = 6;
    private static final int DEFAULT_MEMORY_EPISODIC_TOP_K = 8;
    private static final int DEFAULT_MEMORY_SEMANTIC_TOP_K = 6;
    private static final int DEFAULT_MEMORY_PROCEDURAL_TOP_K = 4;
    private static final boolean DEFAULT_MEMORY_PROMOTION_ENABLED = true;
    private static final double DEFAULT_MEMORY_PROMOTION_MIN_CONFIDENCE = 0.75;
    private static final boolean DEFAULT_MEMORY_DECAY_ENABLED = true;
    private static final int DEFAULT_MEMORY_DECAY_DAYS = 30;
    private static final boolean DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED = true;
    private static final boolean DEFAULT_MEMORY_LEGACY_DAILY_NOTES_ENABLED = true;
    private static final int DEFAULT_TURN_MAX_LLM_CALLS = 200;
    private static final int DEFAULT_TURN_MAX_TOOL_EXECUTIONS = 500;
    private static final java.time.Duration DEFAULT_TURN_DEADLINE = java.time.Duration.ofHours(1);
    private static final int DEFAULT_IMAP_PORT = 993;
    private static final int DEFAULT_IMAP_CONNECT_TIMEOUT = 10000;
    private static final int DEFAULT_IMAP_READ_TIMEOUT = 30000;
    private static final int DEFAULT_IMAP_MAX_BODY_LENGTH = 50000;
    private static final int DEFAULT_IMAP_DEFAULT_MESSAGE_LIMIT = 20;
    private static final String DEFAULT_IMAP_SECURITY = "ssl";
    private static final int DEFAULT_SMTP_PORT = 587;
    private static final int DEFAULT_SMTP_CONNECT_TIMEOUT = 10000;
    private static final int DEFAULT_SMTP_READ_TIMEOUT = 30000;
    private static final String DEFAULT_SMTP_SECURITY = "starttls";
    private static final String DEFAULT_BROWSER_API_PROVIDER = "brave";
    private static final boolean DEFAULT_BROWSER_ENABLED = true;
    private static final String DEFAULT_BROWSER_TYPE = "playwright";
    private static final boolean DEFAULT_BROWSER_HEADLESS = true;
    private static final int DEFAULT_BROWSER_TIMEOUT_MS = 30000;
    private static final String DEFAULT_BROWSER_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final String DEFAULT_RAG_URL = "http://localhost:9621";
    private static final String DEFAULT_RAG_QUERY_MODE = "hybrid";
    private static final int DEFAULT_RAG_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_RAG_INDEX_MIN_LENGTH = 50;
    private static final boolean DEFAULT_MCP_ENABLED = true;
    private static final int DEFAULT_MCP_STARTUP_TIMEOUT = 30;
    private static final int DEFAULT_MCP_IDLE_TIMEOUT = 5;
    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    private final AtomicReference<RuntimeConfig> configRef = new AtomicReference<>();

    public RuntimeConfigService(StoragePort storagePort) {
        this.storagePort = storagePort;
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
                    current = loadOrCreate();
                    normalizeRuntimeConfig(current);
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
        RuntimeConfig oldConfig = this.configRef.get();
        this.configRef.set(newConfig);
        try {
            persist(newConfig);
        } catch (Exception e) {
            // Rollback in-memory change on persist failure
            this.configRef.set(oldConfig);
            throw e;
        }
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

    // ==================== Brave Search ====================

    public String getBrowserApiProvider() {
        String val = getRuntimeConfig().getTools().getBrowserApiProvider();
        return val != null && !val.isBlank() ? val : DEFAULT_BROWSER_API_PROVIDER;
    }

    public boolean isBrowserEnabled() {
        Boolean val = getRuntimeConfig().getTools().getBrowserEnabled();
        return val != null ? val : DEFAULT_BROWSER_ENABLED;
    }

    public String getBrowserType() {
        String val = getRuntimeConfig().getTools().getBrowserType();
        return val != null && !val.isBlank() ? val : DEFAULT_BROWSER_TYPE;
    }

    public int getBrowserTimeoutMs() {
        Integer val = getRuntimeConfig().getTools().getBrowserTimeout();
        return val != null ? val : DEFAULT_BROWSER_TIMEOUT_MS;
    }

    public String getBrowserUserAgent() {
        String val = getRuntimeConfig().getTools().getBrowserUserAgent();
        return val != null && !val.isBlank() ? val : DEFAULT_BROWSER_USER_AGENT;
    }

    public boolean isBrowserHeadless() {
        Boolean val = getRuntimeConfig().getTools().getBrowserHeadless();
        return val != null ? val : DEFAULT_BROWSER_HEADLESS;
    }

    public boolean isBraveSearchEnabled() {
        Boolean val = getRuntimeConfig().getTools().getBraveSearchEnabled();
        return val != null && val && DEFAULT_BROWSER_API_PROVIDER.equals(getBrowserApiProvider());
    }

    public String getBraveSearchApiKey() {
        return Secret.valueOrEmpty(getRuntimeConfig().getTools().getBraveSearchApiKey());
    }

    public boolean isFilesystemEnabled() {
        Boolean val = getRuntimeConfig().getTools().getFilesystemEnabled();
        return val != null ? val : true;
    }

    public boolean isShellEnabled() {
        Boolean val = getRuntimeConfig().getTools().getShellEnabled();
        return val != null ? val : true;
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

    public boolean isRagEnabled() {
        Boolean val = getRuntimeConfig().getRag().getEnabled();
        return val != null && val;
    }

    public String getRagUrl() {
        String val = getRuntimeConfig().getRag().getUrl();
        return val != null && !val.isBlank() ? val : DEFAULT_RAG_URL;
    }

    public String getRagApiKey() {
        return Secret.valueOrEmpty(getRuntimeConfig().getRag().getApiKey());
    }

    public String getRagQueryMode() {
        String val = getRuntimeConfig().getRag().getQueryMode();
        return val != null && !val.isBlank() ? val : DEFAULT_RAG_QUERY_MODE;
    }

    public int getRagTimeoutSeconds() {
        Integer val = getRuntimeConfig().getRag().getTimeoutSeconds();
        return val != null ? val : DEFAULT_RAG_TIMEOUT_SECONDS;
    }

    public int getRagIndexMinLength() {
        Integer val = getRuntimeConfig().getRag().getIndexMinLength();
        return val != null ? val : DEFAULT_RAG_INDEX_MIN_LENGTH;
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

    // ==================== IMAP ====================

    public BotProperties.ImapToolProperties getResolvedImapConfig() {
        RuntimeConfig.ImapConfig rc = getRuntimeConfig().getTools().getImap();
        BotProperties.ImapToolProperties resolved = new BotProperties.ImapToolProperties();
        resolved.setEnabled(rc.getEnabled() != null && rc.getEnabled());
        resolved.setHost(rc.getHost() != null ? rc.getHost() : "");
        resolved.setPort(rc.getPort() != null ? rc.getPort() : DEFAULT_IMAP_PORT);
        resolved.setUsername(rc.getUsername() != null ? rc.getUsername() : "");
        resolved.setPassword(Secret.valueOrEmpty(rc.getPassword()));
        resolved.setSecurity(rc.getSecurity() != null ? rc.getSecurity() : DEFAULT_IMAP_SECURITY);
        resolved.setSslTrust(rc.getSslTrust() != null ? rc.getSslTrust() : "");
        resolved.setConnectTimeout(
                rc.getConnectTimeout() != null ? rc.getConnectTimeout() : DEFAULT_IMAP_CONNECT_TIMEOUT);
        resolved.setReadTimeout(rc.getReadTimeout() != null ? rc.getReadTimeout() : DEFAULT_IMAP_READ_TIMEOUT);
        resolved.setMaxBodyLength(rc.getMaxBodyLength() != null ? rc.getMaxBodyLength() : DEFAULT_IMAP_MAX_BODY_LENGTH);
        resolved.setDefaultMessageLimit(
                rc.getDefaultMessageLimit() != null ? rc.getDefaultMessageLimit() : DEFAULT_IMAP_DEFAULT_MESSAGE_LIMIT);
        return resolved;
    }

    // ==================== SMTP ====================

    public BotProperties.SmtpToolProperties getResolvedSmtpConfig() {
        RuntimeConfig.SmtpConfig rc = getRuntimeConfig().getTools().getSmtp();
        BotProperties.SmtpToolProperties resolved = new BotProperties.SmtpToolProperties();
        resolved.setEnabled(rc.getEnabled() != null && rc.getEnabled());
        resolved.setHost(rc.getHost() != null ? rc.getHost() : "");
        resolved.setPort(rc.getPort() != null ? rc.getPort() : DEFAULT_SMTP_PORT);
        resolved.setUsername(rc.getUsername() != null ? rc.getUsername() : "");
        resolved.setPassword(Secret.valueOrEmpty(rc.getPassword()));
        resolved.setSecurity(rc.getSecurity() != null ? rc.getSecurity() : DEFAULT_SMTP_SECURITY);
        resolved.setSslTrust(rc.getSslTrust() != null ? rc.getSslTrust() : "");
        resolved.setConnectTimeout(
                rc.getConnectTimeout() != null ? rc.getConnectTimeout() : DEFAULT_SMTP_CONNECT_TIMEOUT);
        resolved.setReadTimeout(rc.getReadTimeout() != null ? rc.getReadTimeout() : DEFAULT_SMTP_READ_TIMEOUT);
        return resolved;
    }

    // ==================== Auto Mode ====================

    public boolean isAutoModeEnabled() {
        Boolean val = getRuntimeConfig().getAutoMode().getEnabled();
        return val != null && val;
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

    public boolean isAutoNotifyMilestonesEnabled() {
        Boolean val = getRuntimeConfig().getAutoMode().getNotifyMilestones();
        return val != null ? val : true;
    }

    // ==================== Rate Limit ====================

    public boolean isRateLimitEnabled() {
        Boolean val = getRuntimeConfig().getRateLimit().getEnabled();
        return val != null ? val : true;
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

    public java.time.Duration getTurnDeadline() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null || turnConfig.getDeadline() == null || turnConfig.getDeadline().isBlank()) {
            return DEFAULT_TURN_DEADLINE;
        }
        try {
            return java.time.Duration.parse(turnConfig.getDeadline());
        } catch (java.time.format.DateTimeParseException e) {
            return DEFAULT_TURN_DEADLINE;
        }
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

    public int getMemoryRecentDays() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_RECENT_DAYS;
        }
        Integer val = memoryConfig.getRecentDays();
        return val != null ? val : DEFAULT_MEMORY_RECENT_DAYS;
    }

    public int getMemorySoftPromptBudgetTokens() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS;
        }
        Integer val = memoryConfig.getSoftPromptBudgetTokens();
        return val != null ? val : DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS;
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

    public boolean isMemoryCodeAwareExtractionEnabled() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED;
        }
        Boolean val = memoryConfig.getCodeAwareExtractionEnabled();
        return val != null ? val : DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED;
    }

    public boolean isMemoryLegacyDailyNotesEnabled() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_LEGACY_DAILY_NOTES_ENABLED;
        }
        Boolean val = memoryConfig.getLegacyDailyNotesEnabled();
        return val != null ? val : DEFAULT_MEMORY_LEGACY_DAILY_NOTES_ENABLED;
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

    /**
     * Update the Telegram auth mode and persist.
     */
    public void setTelegramAuthMode(String mode) {
        RuntimeConfig cfg = getRuntimeConfig();
        cfg.getTelegram().setAuthMode(mode);
        persist(cfg);
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
        if (cfg.getTelegram().getInviteCodes() == null) {
            cfg.getTelegram().setInviteCodes(new ArrayList<>());
        }
        cfg.getTelegram().getInviteCodes().add(inviteCode);
        persist(cfg);

        log.info("[RuntimeConfig] Generated invite code: {}", inviteCode.getCode());
        return inviteCode;
    }

    /**
     * Revoke (remove) an invite code.
     */
    public boolean revokeInviteCode(String code) {
        RuntimeConfig cfg = getRuntimeConfig();
        List<RuntimeConfig.InviteCode> codes = cfg.getTelegram().getInviteCodes();
        if (codes == null) {
            return false;
        }
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
        List<RuntimeConfig.InviteCode> codes = cfg.getTelegram().getInviteCodes();
        if (codes == null) {
            return false;
        }

        for (RuntimeConfig.InviteCode ic : codes) {
            if (ic.getCode().equals(code)) {
                if (ic.isUsed()) {
                    return false;
                }
                ic.setUsed(true);

                List<String> allowed = cfg.getTelegram().getAllowedUsers();
                if (allowed == null) {
                    allowed = new ArrayList<>();
                    cfg.getTelegram().setAllowedUsers(allowed);
                }
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
        persistSection(RuntimeConfig.ConfigSection.USAGE, cfg.getUsage(),
                RuntimeConfig.UsageConfig::new);
        persistSection(RuntimeConfig.ConfigSection.RAG, cfg.getRag(),
                RuntimeConfig.RagConfig::new);
        persistSection(RuntimeConfig.ConfigSection.MCP, cfg.getMcp(),
                RuntimeConfig.McpConfig::new);

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
        RuntimeConfig.UsageConfig usage = loadSection(RuntimeConfig.ConfigSection.USAGE,
                RuntimeConfig.UsageConfig.class, RuntimeConfig.UsageConfig::new);
        RuntimeConfig.RagConfig rag = loadSection(RuntimeConfig.ConfigSection.RAG,
                RuntimeConfig.RagConfig.class, RuntimeConfig.RagConfig::new);
        RuntimeConfig.McpConfig mcp = loadSection(RuntimeConfig.ConfigSection.MCP,
                RuntimeConfig.McpConfig.class, RuntimeConfig.McpConfig::new);

        RuntimeConfig config = RuntimeConfig.builder()
                .telegram(telegram)
                .modelRouter(modelRouter)
                .llm(llm)
                .tools(tools)
                .voice(voice)
                .autoMode(autoMode)
                .rateLimit(rateLimit)
                .security(security)
                .compaction(compaction)
                .turn(turn)
                .memory(memory)
                .skills(skills)
                .usage(usage)
                .rag(rag)
                .mcp(mcp)
                .build();

        log.info("[RuntimeConfig] Loaded runtime config from {} section files",
                RuntimeConfig.ConfigSection.values().length);
        return config;
    }

    /**
     * Load a single configuration section from its file.
     */
    private <T> T loadSection(RuntimeConfig.ConfigSection section, Class<T> configClass,
            Supplier<T> defaultSupplier) {
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

        // Create default and persist it
        T defaultConfig = defaultSupplier.get();
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
        if (cfg.getLlm() == null) {
            cfg.setLlm(RuntimeConfig.LlmConfig.builder().build());
        }
        if (cfg.getLlm().getProviders() == null) {
            cfg.getLlm().setProviders(new java.util.LinkedHashMap<>());
        }
        normalizeSecretFlags(cfg);
    }

    private void normalizeSecretFlags(RuntimeConfig cfg) {
        cfg.getTelegram().setToken(normalizeSecret(cfg.getTelegram().getToken()));
        cfg.getTools().setBraveSearchApiKey(normalizeSecret(cfg.getTools().getBraveSearchApiKey()));
        cfg.getVoice().setApiKey(normalizeSecret(cfg.getVoice().getApiKey()));
        cfg.getRag().setApiKey(normalizeSecret(cfg.getRag().getApiKey()));

        RuntimeConfig.ImapConfig imapConfig = cfg.getTools().getImap();
        if (imapConfig != null) {
            imapConfig.setPassword(normalizeSecret(imapConfig.getPassword()));
        }

        RuntimeConfig.SmtpConfig smtpConfig = cfg.getTools().getSmtp();
        if (smtpConfig != null) {
            smtpConfig.setPassword(normalizeSecret(smtpConfig.getPassword()));
        }

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
        cfg.getTools().setBraveSearchApiKey(Secret.redacted(cfg.getTools().getBraveSearchApiKey()));
        cfg.getVoice().setApiKey(Secret.redacted(cfg.getVoice().getApiKey()));
        cfg.getRag().setApiKey(Secret.redacted(cfg.getRag().getApiKey()));

        RuntimeConfig.ImapConfig imapConfig = cfg.getTools().getImap();
        if (imapConfig != null) {
            imapConfig.setPassword(Secret.redacted(imapConfig.getPassword()));
        }

        RuntimeConfig.SmtpConfig smtpConfig = cfg.getTools().getSmtp();
        if (smtpConfig != null) {
            smtpConfig.setPassword(Secret.redacted(smtpConfig.getPassword()));
        }

        if (cfg.getLlm() != null && cfg.getLlm().getProviders() != null) {
            for (RuntimeConfig.LlmProviderConfig providerConfig : cfg.getLlm().getProviders().values()) {
                if (providerConfig != null) {
                    providerConfig.setApiKey(Secret.redacted(providerConfig.getApiKey()));
                }
            }
        }
    }
}

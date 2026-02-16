package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing application-level runtime configuration. Loads from
 * {@code preferences/runtime-config.json} via StoragePort and provides typed
 * getters with BotProperties fallback.
 */
@Service
@Slf4j
public class RuntimeConfigService {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String CONFIG_FILE = "runtime-config.json";
    private static final String INVITE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_CODE_LENGTH = 20;
    private static final String DEFAULT_BALANCED_MODEL = "openai/gpt-5.1";
    private static final String DEFAULT_BALANCED_REASONING = "medium";
    private static final String DEFAULT_SMART_MODEL = "openai/gpt-5.1";
    private static final String DEFAULT_SMART_REASONING = "high";
    private static final String DEFAULT_CODING_MODEL = "openai/gpt-5.2";
    private static final String DEFAULT_CODING_REASONING = "medium";
    private static final String DEFAULT_DEEP_MODEL = "openai/gpt-5.2";
    private static final String DEFAULT_DEEP_REASONING = "xhigh";
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
    private static final int DEFAULT_AUTO_TICK_INTERVAL_SECONDS = 30;
    private static final int DEFAULT_AUTO_TIMEOUT_MINUTES = 10;
    private static final int DEFAULT_AUTO_MAX_GOALS = 3;
    private static final String DEFAULT_AUTO_MODEL_TIER = "default";
    private static final int DEFAULT_AUTO_COMPACT_MAX_TOKENS = 50000;
    private static final int DEFAULT_AUTO_COMPACT_KEEP_LAST = 20;
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

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    private volatile RuntimeConfig config;

    public RuntimeConfigService(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Get current RuntimeConfig (lazy-loaded, cached).
     */
    public RuntimeConfig getRuntimeConfig() {
        if (config == null) {
            synchronized (this) {
                if (config == null) {
                    config = loadOrCreate();
                }
            }
        }
        return config;
    }

    /**
     * Update and persist RuntimeConfig.
     */
    public void updateRuntimeConfig(RuntimeConfig newConfig) {
        this.config = newConfig;
        persist(newConfig);
    }

    // ==================== Telegram ====================

    public boolean isTelegramEnabled() {
        Boolean val = getRuntimeConfig().getTelegram().getEnabled();
        return val != null && val;
    }

    public String getTelegramToken() {
        String val = getRuntimeConfig().getTelegram().getToken();
        return val != null ? val : "";
    }

    public List<String> getTelegramAllowedUsers() {
        List<String> runtimeUsers = getRuntimeConfig().getTelegram().getAllowedUsers();
        return runtimeUsers != null ? runtimeUsers : List.of();
    }

    // ==================== Model Router ====================

    public String getBalancedModel() {
        String val = getRuntimeConfig().getModelRouter().getBalancedModel();
        return val != null ? val : DEFAULT_BALANCED_MODEL;
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

    public boolean isBraveSearchEnabled() {
        Boolean val = getRuntimeConfig().getTools().getBraveSearchEnabled();
        return val != null && val;
    }

    public String getBraveSearchApiKey() {
        String val = getRuntimeConfig().getTools().getBraveSearchApiKey();
        return val != null ? val : "";
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

    public String getVoiceApiKey() {
        String val = getRuntimeConfig().getVoice().getApiKey();
        return val != null ? val : "";
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

    // ==================== IMAP ====================

    public BotProperties.ImapToolProperties getResolvedImapConfig() {
        RuntimeConfig.ImapConfig rc = getRuntimeConfig().getTools().getImap();
        BotProperties.ImapToolProperties resolved = new BotProperties.ImapToolProperties();
        resolved.setEnabled(rc.getEnabled() != null && rc.getEnabled());
        resolved.setHost(rc.getHost() != null ? rc.getHost() : "");
        resolved.setPort(rc.getPort() != null ? rc.getPort() : DEFAULT_IMAP_PORT);
        resolved.setUsername(rc.getUsername() != null ? rc.getUsername() : "");
        resolved.setPassword(rc.getPassword() != null ? rc.getPassword() : "");
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
        resolved.setPassword(rc.getPassword() != null ? rc.getPassword() : "");
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

    // ==================== Invite Codes ====================

    /**
     * Generate a new single-use invite code.
     */
    public RuntimeConfig.InviteCode generateInviteCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            code.append(INVITE_CHARS.charAt(random.nextInt(INVITE_CHARS.length())));
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

    private void persist(RuntimeConfig cfg) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cfg);
            storagePort.putText(PREFERENCES_DIR, CONFIG_FILE, json).join();
            log.debug("[RuntimeConfig] Persisted runtime config");
        } catch (Exception e) {
            log.error("[RuntimeConfig] Failed to persist runtime config", e);
        }
    }

    private RuntimeConfig loadOrCreate() {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, CONFIG_FILE).join();
            if (json != null && !json.isBlank()) {
                RuntimeConfig loaded = objectMapper.readValue(json, RuntimeConfig.class);
                log.info("[RuntimeConfig] Loaded runtime config from storage");
                return loaded;
            }
        } catch (IOException | RuntimeException e) { // NOSONAR
            log.debug("[RuntimeConfig] No saved runtime config, creating default: {}", e.getMessage());
        }

        RuntimeConfig defaultConfig = RuntimeConfig.builder().build();
        persist(defaultConfig);
        log.info("[RuntimeConfig] Created default runtime config");
        return defaultConfig;
    }
}

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
 * Service for managing application-level runtime configuration.
 *
 * <p>
 * Runtime config is persisted to {@code preferences/runtime-config.json} and is
 * the single source of truth (no fallback to application.properties).
 *
 * <p>
 * Concurrency model: copy-on-write snapshots. Public getters return deep copies
 * to avoid exposing shared mutable graph.
 */
@Service
@Slf4j
public class RuntimeConfigService {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String CONFIG_FILE = "runtime-config.json";
    private static final String INVITE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_CODE_LENGTH = 20;

    // Local defaults for resolved tool configs (no app.properties fallback).
    private static final boolean DEFAULT_IMAP_ENABLED = false;
    private static final String DEFAULT_IMAP_HOST = "";
    private static final int DEFAULT_IMAP_PORT = 993;
    private static final String DEFAULT_IMAP_USERNAME = "";
    private static final String DEFAULT_IMAP_PASSWORD = "";
    private static final String DEFAULT_IMAP_SECURITY = "ssl";
    private static final String DEFAULT_IMAP_SSL_TRUST = "";
    private static final int DEFAULT_IMAP_CONNECT_TIMEOUT = 10000;
    private static final int DEFAULT_IMAP_READ_TIMEOUT = 30000;
    private static final int DEFAULT_IMAP_MAX_BODY_LENGTH = 50000;
    private static final int DEFAULT_IMAP_MESSAGE_LIMIT = 20;

    private static final boolean DEFAULT_SMTP_ENABLED = false;
    private static final String DEFAULT_SMTP_HOST = "";
    private static final int DEFAULT_SMTP_PORT = 587;
    private static final String DEFAULT_SMTP_USERNAME = "";
    private static final String DEFAULT_SMTP_PASSWORD = "";
    private static final String DEFAULT_SMTP_SECURITY = "starttls";
    private static final String DEFAULT_SMTP_SSL_TRUST = "";
    private static final int DEFAULT_SMTP_CONNECT_TIMEOUT = 10000;
    private static final int DEFAULT_SMTP_READ_TIMEOUT = 30000;

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    private volatile RuntimeConfig config;

    public RuntimeConfigService(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Get current RuntimeConfig snapshot.
     */
    public RuntimeConfig getRuntimeConfig() {
        RuntimeConfig snapshot = ensureLoaded();
        return deepCopy(snapshot);
    }

    /**
     * Update and persist RuntimeConfig atomically.
     */
    public synchronized void updateRuntimeConfig(RuntimeConfig newConfig) {
        RuntimeConfig normalized = normalizeConfig(newConfig);
        this.config = normalized;
        persist(normalized);
    }

    // ==================== Telegram ====================

    public boolean isTelegramEnabled() {
        Boolean val = ensureLoaded().getTelegram().getEnabled();
        return val != null && val;
    }

    public String getTelegramToken() {
        String val = ensureLoaded().getTelegram().getToken();
        return val != null ? val : "";
    }

    public List<String> getTelegramAllowedUserIds() {
        List<String> users = ensureLoaded().getTelegram().getAllowedUserIds();
        return users != null ? List.copyOf(users) : List.of();
    }

    // ==================== Model Router ====================

    public String getBalancedModel() {
        String val = ensureLoaded().getModelRouter().getBalancedModel();
        return val != null ? val : "";
    }

    public String getBalancedModelReasoning() {
        String val = ensureLoaded().getModelRouter().getBalancedModelReasoning();
        return val != null ? val : "";
    }

    public String getSmartModel() {
        String val = ensureLoaded().getModelRouter().getSmartModel();
        return val != null ? val : "";
    }

    public String getSmartModelReasoning() {
        String val = ensureLoaded().getModelRouter().getSmartModelReasoning();
        return val != null ? val : "";
    }

    public String getCodingModel() {
        String val = ensureLoaded().getModelRouter().getCodingModel();
        return val != null ? val : "";
    }

    public String getCodingModelReasoning() {
        String val = ensureLoaded().getModelRouter().getCodingModelReasoning();
        return val != null ? val : "";
    }

    public String getDeepModel() {
        String val = ensureLoaded().getModelRouter().getDeepModel();
        return val != null ? val : "";
    }

    public String getDeepModelReasoning() {
        String val = ensureLoaded().getModelRouter().getDeepModelReasoning();
        return val != null ? val : "";
    }

    public double getTemperature() {
        Double val = ensureLoaded().getModelRouter().getTemperature();
        return val != null ? val : 0.0d;
    }

    public boolean isDynamicTierEnabled() {
        Boolean val = ensureLoaded().getModelRouter().getDynamicTierEnabled();
        return val != null && val;
    }

    // ==================== Brave Search ====================

    public boolean isBraveSearchEnabled() {
        Boolean val = ensureLoaded().getTools().getBraveSearchEnabled();
        return val != null && val;
    }

    public String getBraveSearchApiKey() {
        String val = ensureLoaded().getTools().getBraveSearchApiKey();
        return val != null ? val : "";
    }

    // ==================== Voice ====================

    public boolean isVoiceEnabled() {
        Boolean val = ensureLoaded().getVoice().getEnabled();
        return val != null && val;
    }

    public String getVoiceApiKey() {
        String val = ensureLoaded().getVoice().getApiKey();
        return val != null ? val : "";
    }

    public String getVoiceId() {
        String val = ensureLoaded().getVoice().getVoiceId();
        return val != null ? val : "";
    }

    public String getTtsModelId() {
        String val = ensureLoaded().getVoice().getTtsModelId();
        return val != null ? val : "";
    }

    public String getSttModelId() {
        String val = ensureLoaded().getVoice().getSttModelId();
        return val != null ? val : "";
    }

    public float getVoiceSpeed() {
        Float val = ensureLoaded().getVoice().getSpeed();
        return val != null ? val : 1.0f;
    }

    // ==================== IMAP ====================

    public BotProperties.ImapToolProperties getResolvedImapConfig() {
        RuntimeConfig.ImapConfig rc = ensureLoaded().getTools().getImap();
        BotProperties.ImapToolProperties resolved = new BotProperties.ImapToolProperties();
        resolved.setEnabled(rc.getEnabled() != null ? rc.getEnabled() : DEFAULT_IMAP_ENABLED);
        resolved.setHost(rc.getHost() != null ? rc.getHost() : DEFAULT_IMAP_HOST);
        resolved.setPort(rc.getPort() != null ? rc.getPort() : DEFAULT_IMAP_PORT);
        resolved.setUsername(rc.getUsername() != null ? rc.getUsername() : DEFAULT_IMAP_USERNAME);
        resolved.setPassword(rc.getPassword() != null ? rc.getPassword() : DEFAULT_IMAP_PASSWORD);
        resolved.setSecurity(rc.getSecurity() != null ? rc.getSecurity() : DEFAULT_IMAP_SECURITY);
        resolved.setSslTrust(rc.getSslTrust() != null ? rc.getSslTrust() : DEFAULT_IMAP_SSL_TRUST);
        resolved.setConnectTimeout(
                rc.getConnectTimeout() != null ? rc.getConnectTimeout() : DEFAULT_IMAP_CONNECT_TIMEOUT);
        resolved.setReadTimeout(rc.getReadTimeout() != null ? rc.getReadTimeout() : DEFAULT_IMAP_READ_TIMEOUT);
        resolved.setMaxBodyLength(rc.getMaxBodyLength() != null ? rc.getMaxBodyLength() : DEFAULT_IMAP_MAX_BODY_LENGTH);
        resolved.setDefaultMessageLimit(rc.getDefaultMessageLimit() != null ? rc.getDefaultMessageLimit()
                : DEFAULT_IMAP_MESSAGE_LIMIT);
        return resolved;
    }

    // ==================== SMTP ====================

    public BotProperties.SmtpToolProperties getResolvedSmtpConfig() {
        RuntimeConfig.SmtpConfig rc = ensureLoaded().getTools().getSmtp();
        BotProperties.SmtpToolProperties resolved = new BotProperties.SmtpToolProperties();
        resolved.setEnabled(rc.getEnabled() != null ? rc.getEnabled() : DEFAULT_SMTP_ENABLED);
        resolved.setHost(rc.getHost() != null ? rc.getHost() : DEFAULT_SMTP_HOST);
        resolved.setPort(rc.getPort() != null ? rc.getPort() : DEFAULT_SMTP_PORT);
        resolved.setUsername(rc.getUsername() != null ? rc.getUsername() : DEFAULT_SMTP_USERNAME);
        resolved.setPassword(rc.getPassword() != null ? rc.getPassword() : DEFAULT_SMTP_PASSWORD);
        resolved.setSecurity(rc.getSecurity() != null ? rc.getSecurity() : DEFAULT_SMTP_SECURITY);
        resolved.setSslTrust(rc.getSslTrust() != null ? rc.getSslTrust() : DEFAULT_SMTP_SSL_TRUST);
        resolved.setConnectTimeout(
                rc.getConnectTimeout() != null ? rc.getConnectTimeout() : DEFAULT_SMTP_CONNECT_TIMEOUT);
        resolved.setReadTimeout(rc.getReadTimeout() != null ? rc.getReadTimeout() : DEFAULT_SMTP_READ_TIMEOUT);
        return resolved;
    }

    // ==================== Invite Codes ====================

    /**
     * Generate a new single-use invite code.
     */
    public synchronized RuntimeConfig.InviteCode generateInviteCode() {
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

        RuntimeConfig cfg = deepCopy(ensureLoaded());
        cfg.getTelegram().getInviteCodes().add(inviteCode);

        this.config = cfg;
        persist(cfg);

        log.info("[RuntimeConfig] Generated invite code: {}", inviteCode.getCode());
        return inviteCode;
    }

    /**
     * Revoke (remove) an invite code.
     */
    public synchronized boolean revokeInviteCode(String code) {
        RuntimeConfig cfg = deepCopy(ensureLoaded());
        List<RuntimeConfig.InviteCode> codes = cfg.getTelegram().getInviteCodes();
        boolean removed = codes.removeIf(ic -> ic.getCode().equals(code));
        if (removed) {
            this.config = cfg;
            persist(cfg);
            log.info("[RuntimeConfig] Revoked invite code: {}", code);
        }
        return removed;
    }

    /**
     * Redeem a single-use invite code, adding the user to allowed users.
     */
    public synchronized boolean redeemInviteCode(String code, String userId) {
        RuntimeConfig cfg = deepCopy(ensureLoaded());
        List<RuntimeConfig.InviteCode> codes = cfg.getTelegram().getInviteCodes();

        for (RuntimeConfig.InviteCode ic : codes) {
            if (ic.getCode().equals(code)) {
                if (ic.isUsed()) {
                    return false;
                }
                ic.setUsed(true);

                List<String> allowed = cfg.getTelegram().getAllowedUserIds();
                if (!allowed.contains(userId)) {
                    allowed.add(userId);
                }

                this.config = cfg;
                persist(cfg);
                log.info("[RuntimeConfig] Redeemed invite code {} for user {}", code, userId);
                return true;
            }
        }
        return false;
    }

    // ==================== Persistence ====================

    private RuntimeConfig ensureLoaded() {
        RuntimeConfig current = this.config;
        if (current == null) {
            synchronized (this) {
                if (this.config == null) {
                    this.config = loadOrCreate();
                }
                current = this.config;
            }
        }
        return current;
    }

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
                RuntimeConfig normalized = normalizeConfig(loaded);
                log.info("[RuntimeConfig] Loaded runtime config from storage");
                return normalized;
            }
        } catch (IOException | RuntimeException e) { // NOSONAR
            log.debug("[RuntimeConfig] No saved runtime config, creating default: {}", e.getMessage());
        }

        RuntimeConfig defaultConfig = normalizeConfig(RuntimeConfig.builder().build());
        persist(defaultConfig);
        log.info("[RuntimeConfig] Created default runtime config");
        return defaultConfig;
    }

    private RuntimeConfig normalizeConfig(RuntimeConfig cfg) {
        RuntimeConfig normalized = cfg != null ? cfg : RuntimeConfig.builder().build();

        if (normalized.getTelegram() == null) {
            normalized.setTelegram(new RuntimeConfig.TelegramConfig());
        }
        if (normalized.getTelegram().getAllowedUserIds() == null) {
            normalized.getTelegram().setAllowedUserIds(new ArrayList<>());
        }
        if (normalized.getTelegram().getInviteCodes() == null) {
            normalized.getTelegram().setInviteCodes(new ArrayList<>());
        }

        if (normalized.getModelRouter() == null) {
            normalized.setModelRouter(new RuntimeConfig.ModelRouterConfig());
        }

        if (normalized.getTools() == null) {
            normalized.setTools(new RuntimeConfig.ToolsConfig());
        }
        if (normalized.getTools().getImap() == null) {
            normalized.getTools().setImap(new RuntimeConfig.ImapConfig());
        }
        if (normalized.getTools().getSmtp() == null) {
            normalized.getTools().setSmtp(new RuntimeConfig.SmtpConfig());
        }

        if (normalized.getVoice() == null) {
            normalized.setVoice(new RuntimeConfig.VoiceConfig());
        }
        if (normalized.getAutoMode() == null) {
            normalized.setAutoMode(new RuntimeConfig.AutoModeConfig());
        }
        if (normalized.getRateLimit() == null) {
            normalized.setRateLimit(new RuntimeConfig.RateLimitConfig());
        }
        if (normalized.getSecurity() == null) {
            normalized.setSecurity(new RuntimeConfig.SecurityConfig());
        }
        if (normalized.getCompaction() == null) {
            normalized.setCompaction(new RuntimeConfig.CompactionConfig());
        }

        return normalized;
    }

    private RuntimeConfig deepCopy(RuntimeConfig source) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(source);
            RuntimeConfig copy = objectMapper.readValue(bytes, RuntimeConfig.class);
            return normalizeConfig(copy);
        } catch (IOException e) {
            log.warn("[RuntimeConfig] Failed to deep copy runtime config, falling back to normalize in-place");
            return normalizeConfig(source);
        }
    }
}

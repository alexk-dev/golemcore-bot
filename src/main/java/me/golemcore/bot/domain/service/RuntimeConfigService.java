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

    private final StoragePort storagePort;
    private final BotProperties botProperties;
    private final ObjectMapper objectMapper;

    private volatile RuntimeConfig config;

    public RuntimeConfigService(StoragePort storagePort, BotProperties botProperties) {
        this.storagePort = storagePort;
        this.botProperties = botProperties;
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
        if (val != null && !val.isBlank()) {
            return val;
        }
        BotProperties.ChannelProperties tg = botProperties.getChannels().get("telegram");
        return tg != null ? tg.getToken() : "";
    }

    public List<String> getTelegramAllowedUsers() {
        List<String> runtimeUsers = getRuntimeConfig().getTelegram().getAllowedUsers();
        if (runtimeUsers != null && !runtimeUsers.isEmpty()) {
            return runtimeUsers;
        }
        BotProperties.ChannelProperties tg = botProperties.getChannels().get("telegram");
        return tg != null ? tg.getAllowFrom() : List.of();
    }

    // ==================== Model Router ====================

    public String getBalancedModel() {
        String val = getRuntimeConfig().getModelRouter().getBalancedModel();
        return val != null ? val : botProperties.getRouter().getBalancedModel();
    }

    public String getBalancedModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getBalancedModelReasoning();
        return val != null ? val : botProperties.getRouter().getBalancedModelReasoning();
    }

    public String getSmartModel() {
        String val = getRuntimeConfig().getModelRouter().getSmartModel();
        return val != null ? val : botProperties.getRouter().getSmartModel();
    }

    public String getSmartModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getSmartModelReasoning();
        return val != null ? val : botProperties.getRouter().getSmartModelReasoning();
    }

    public String getCodingModel() {
        String val = getRuntimeConfig().getModelRouter().getCodingModel();
        return val != null ? val : botProperties.getRouter().getCodingModel();
    }

    public String getCodingModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getCodingModelReasoning();
        return val != null ? val : botProperties.getRouter().getCodingModelReasoning();
    }

    public String getDeepModel() {
        String val = getRuntimeConfig().getModelRouter().getDeepModel();
        return val != null ? val : botProperties.getRouter().getDeepModel();
    }

    public String getDeepModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getDeepModelReasoning();
        return val != null ? val : botProperties.getRouter().getDeepModelReasoning();
    }

    public double getTemperature() {
        Double val = getRuntimeConfig().getModelRouter().getTemperature();
        return val != null ? val : botProperties.getRouter().getTemperature();
    }

    public boolean isDynamicTierEnabled() {
        Boolean val = getRuntimeConfig().getModelRouter().getDynamicTierEnabled();
        return val != null ? val : botProperties.getRouter().isDynamicTierEnabled();
    }

    // ==================== Brave Search ====================

    public boolean isBraveSearchEnabled() {
        Boolean val = getRuntimeConfig().getTools().getBraveSearchEnabled();
        return val != null ? val : botProperties.getTools().getBraveSearch().isEnabled();
    }

    public String getBraveSearchApiKey() {
        String val = getRuntimeConfig().getTools().getBraveSearchApiKey();
        if (val != null && !val.isBlank()) {
            return val;
        }
        return botProperties.getTools().getBraveSearch().getApiKey();
    }

    // ==================== Voice ====================

    public boolean isVoiceEnabled() {
        Boolean val = getRuntimeConfig().getVoice().getEnabled();
        return val != null ? val : botProperties.getVoice().isEnabled();
    }

    public String getVoiceApiKey() {
        String val = getRuntimeConfig().getVoice().getApiKey();
        if (val != null && !val.isBlank()) {
            return val;
        }
        return botProperties.getVoice().getApiKey();
    }

    public String getVoiceId() {
        String val = getRuntimeConfig().getVoice().getVoiceId();
        return val != null ? val : botProperties.getVoice().getVoiceId();
    }

    public String getTtsModelId() {
        String val = getRuntimeConfig().getVoice().getTtsModelId();
        return val != null ? val : botProperties.getVoice().getTtsModelId();
    }

    public String getSttModelId() {
        String val = getRuntimeConfig().getVoice().getSttModelId();
        return val != null ? val : botProperties.getVoice().getSttModelId();
    }

    public float getVoiceSpeed() {
        Float val = getRuntimeConfig().getVoice().getSpeed();
        return val != null ? val : botProperties.getVoice().getSpeed();
    }

    // ==================== IMAP ====================

    public BotProperties.ImapToolProperties getResolvedImapConfig() {
        RuntimeConfig.ImapConfig rc = getRuntimeConfig().getTools().getImap();
        BotProperties.ImapToolProperties base = botProperties.getTools().getImap();
        BotProperties.ImapToolProperties resolved = new BotProperties.ImapToolProperties();
        resolved.setEnabled(rc.getEnabled() != null ? rc.getEnabled() : base.isEnabled());
        resolved.setHost(rc.getHost() != null ? rc.getHost() : base.getHost());
        resolved.setPort(rc.getPort() != null ? rc.getPort() : base.getPort());
        resolved.setUsername(rc.getUsername() != null ? rc.getUsername() : base.getUsername());
        resolved.setPassword(rc.getPassword() != null ? rc.getPassword() : base.getPassword());
        resolved.setSecurity(rc.getSecurity() != null ? rc.getSecurity() : base.getSecurity());
        resolved.setSslTrust(rc.getSslTrust() != null ? rc.getSslTrust() : base.getSslTrust());
        resolved.setConnectTimeout(rc.getConnectTimeout() != null ? rc.getConnectTimeout() : base.getConnectTimeout());
        resolved.setReadTimeout(rc.getReadTimeout() != null ? rc.getReadTimeout() : base.getReadTimeout());
        resolved.setMaxBodyLength(rc.getMaxBodyLength() != null ? rc.getMaxBodyLength() : base.getMaxBodyLength());
        resolved.setDefaultMessageLimit(
                rc.getDefaultMessageLimit() != null ? rc.getDefaultMessageLimit() : base.getDefaultMessageLimit());
        return resolved;
    }

    // ==================== SMTP ====================

    public BotProperties.SmtpToolProperties getResolvedSmtpConfig() {
        RuntimeConfig.SmtpConfig rc = getRuntimeConfig().getTools().getSmtp();
        BotProperties.SmtpToolProperties base = botProperties.getTools().getSmtp();
        BotProperties.SmtpToolProperties resolved = new BotProperties.SmtpToolProperties();
        resolved.setEnabled(rc.getEnabled() != null ? rc.getEnabled() : base.isEnabled());
        resolved.setHost(rc.getHost() != null ? rc.getHost() : base.getHost());
        resolved.setPort(rc.getPort() != null ? rc.getPort() : base.getPort());
        resolved.setUsername(rc.getUsername() != null ? rc.getUsername() : base.getUsername());
        resolved.setPassword(rc.getPassword() != null ? rc.getPassword() : base.getPassword());
        resolved.setSecurity(rc.getSecurity() != null ? rc.getSecurity() : base.getSecurity());
        resolved.setSslTrust(rc.getSslTrust() != null ? rc.getSslTrust() : base.getSslTrust());
        resolved.setConnectTimeout(rc.getConnectTimeout() != null ? rc.getConnectTimeout() : base.getConnectTimeout());
        resolved.setReadTimeout(rc.getReadTimeout() != null ? rc.getReadTimeout() : base.getReadTimeout());
        return resolved;
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

package me.golemcore.bot.domain.extensions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One-way migration from legacy core-owned tool/RAG settings into plugin-owned
 * configuration blobs.
 */
@Service
@Slf4j
public class LegacyPluginConfigurationMigrationService {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String TOOLS_FILE = "tools.json";
    private static final String RAG_FILE = "rag.json";

    private static final String BROWSER_PLUGIN_ID = "golemcore/browser";
    private static final String BRAVE_SEARCH_PLUGIN_ID = "golemcore/brave-search";
    private static final String MAIL_PLUGIN_ID = "golemcore/mail";
    private static final String LIGHTRAG_PLUGIN_ID = "golemcore/lightrag";
    private static final int DEFAULT_BRAVE_SEARCH_COUNT = 5;

    private final StoragePort storagePort;
    private final PluginConfigurationService pluginConfigurationService;
    private final ObjectMapper objectMapper;

    public LegacyPluginConfigurationMigrationService(StoragePort storagePort,
            PluginConfigurationService pluginConfigurationService,
            ObjectMapper objectMapper) {
        this.storagePort = storagePort;
        this.pluginConfigurationService = pluginConfigurationService;
        this.objectMapper = objectMapper;
    }

    public void migrateIfNeeded() {
        LegacyToolsConfig tools = loadLegacySection(TOOLS_FILE, LegacyToolsConfig.class);
        LegacyRagConfig rag = loadLegacySection(RAG_FILE, LegacyRagConfig.class);

        migrateBrowser(tools);
        migrateBraveSearch(tools);
        migrateMail(tools);
        migrateLightRag(rag);
    }

    private void migrateBrowser(LegacyToolsConfig tools) {
        if (pluginConfigurationService.hasPluginConfig(BROWSER_PLUGIN_ID) || tools == null
                || !hasBrowserLegacyValues(tools)) {
            return;
        }
        Map<String, Object> pluginConfig = new LinkedHashMap<>();
        pluginConfig.put("enabled", tools.getBrowserEnabled());
        pluginConfig.put("headless", tools.getBrowserHeadless());
        pluginConfig.put("timeoutMs", tools.getBrowserTimeout());
        pluginConfig.put("userAgent", tools.getBrowserUserAgent());
        pluginConfigurationService.savePluginConfig(BROWSER_PLUGIN_ID, pluginConfig);
    }

    private void migrateBraveSearch(LegacyToolsConfig tools) {
        if (pluginConfigurationService.hasPluginConfig(BRAVE_SEARCH_PLUGIN_ID) || tools == null
                || !hasBraveLegacyValues(tools)) {
            return;
        }
        Map<String, Object> pluginConfig = new LinkedHashMap<>();
        pluginConfig.put("enabled", tools.getBraveSearchEnabled());
        pluginConfig.put("apiKey", valueOf(tools.getBraveSearchApiKey()));
        pluginConfig.put("defaultCount", DEFAULT_BRAVE_SEARCH_COUNT);
        pluginConfigurationService.savePluginConfig(BRAVE_SEARCH_PLUGIN_ID, pluginConfig);
    }

    private void migrateMail(LegacyToolsConfig tools) {
        if (pluginConfigurationService.hasPluginConfig(MAIL_PLUGIN_ID) || tools == null
                || !hasMailLegacyValues(tools)) {
            return;
        }
        Map<String, Object> pluginConfig = new LinkedHashMap<>();
        pluginConfig.put("imap", migrateImapConfig(tools.getImap()));
        pluginConfig.put("smtp", migrateSmtpConfig(tools.getSmtp()));
        pluginConfigurationService.savePluginConfig(MAIL_PLUGIN_ID, pluginConfig);
    }

    private Map<String, Object> migrateImapConfig(LegacyImapConfig config) {
        Map<String, Object> migrated = new LinkedHashMap<>();
        if (config == null) {
            return migrated;
        }
        migrated.put("enabled", config.getEnabled());
        migrated.put("host", config.getHost());
        migrated.put("port", config.getPort());
        migrated.put("username", config.getUsername());
        migrated.put("password", valueOf(config.getPassword()));
        migrated.put("security", config.getSecurity());
        migrated.put("sslTrust", config.getSslTrust());
        migrated.put("connectTimeout", config.getConnectTimeout());
        migrated.put("readTimeout", config.getReadTimeout());
        migrated.put("maxBodyLength", config.getMaxBodyLength());
        migrated.put("defaultMessageLimit", config.getDefaultMessageLimit());
        return migrated;
    }

    private Map<String, Object> migrateSmtpConfig(LegacySmtpConfig config) {
        Map<String, Object> migrated = new LinkedHashMap<>();
        if (config == null) {
            return migrated;
        }
        migrated.put("enabled", config.getEnabled());
        migrated.put("host", config.getHost());
        migrated.put("port", config.getPort());
        migrated.put("username", config.getUsername());
        migrated.put("password", valueOf(config.getPassword()));
        migrated.put("security", config.getSecurity());
        migrated.put("sslTrust", config.getSslTrust());
        migrated.put("connectTimeout", config.getConnectTimeout());
        migrated.put("readTimeout", config.getReadTimeout());
        return migrated;
    }

    private void migrateLightRag(LegacyRagConfig rag) {
        if (pluginConfigurationService.hasPluginConfig(LIGHTRAG_PLUGIN_ID) || rag == null || !hasRagLegacyValues(rag)) {
            return;
        }
        Map<String, Object> pluginConfig = new LinkedHashMap<>();
        pluginConfig.put("enabled", rag.getEnabled());
        pluginConfig.put("url", rag.getUrl());
        pluginConfig.put("apiKey", valueOf(rag.getApiKey()));
        pluginConfig.put("queryMode", rag.getQueryMode());
        pluginConfig.put("timeoutSeconds", rag.getTimeoutSeconds());
        pluginConfig.put("indexMinLength", rag.getIndexMinLength());
        pluginConfigurationService.savePluginConfig(LIGHTRAG_PLUGIN_ID, pluginConfig);
    }

    private boolean hasBrowserLegacyValues(LegacyToolsConfig tools) {
        return tools.getBrowserEnabled() != null
                || tools.getBrowserHeadless() != null
                || tools.getBrowserTimeout() != null
                || hasText(tools.getBrowserUserAgent());
    }

    private boolean hasBraveLegacyValues(LegacyToolsConfig tools) {
        return tools.getBraveSearchEnabled() != null || Secret.hasValue(tools.getBraveSearchApiKey());
    }

    private boolean hasMailLegacyValues(LegacyToolsConfig tools) {
        return hasImapLegacyValues(tools.getImap()) || hasSmtpLegacyValues(tools.getSmtp());
    }

    private boolean hasImapLegacyValues(LegacyImapConfig config) {
        return config != null
                && (config.getEnabled() != null
                        || hasText(config.getHost())
                        || config.getPort() != null
                        || hasText(config.getUsername())
                        || Secret.hasValue(config.getPassword())
                        || hasText(config.getSecurity())
                        || hasText(config.getSslTrust())
                        || config.getConnectTimeout() != null
                        || config.getReadTimeout() != null
                        || config.getMaxBodyLength() != null
                        || config.getDefaultMessageLimit() != null);
    }

    private boolean hasSmtpLegacyValues(LegacySmtpConfig config) {
        return config != null
                && (config.getEnabled() != null
                        || hasText(config.getHost())
                        || config.getPort() != null
                        || hasText(config.getUsername())
                        || Secret.hasValue(config.getPassword())
                        || hasText(config.getSecurity())
                        || hasText(config.getSslTrust())
                        || config.getConnectTimeout() != null
                        || config.getReadTimeout() != null);
    }

    private boolean hasRagLegacyValues(LegacyRagConfig rag) {
        return rag.getEnabled() != null
                || hasText(rag.getUrl())
                || Secret.hasValue(rag.getApiKey())
                || hasText(rag.getQueryMode())
                || rag.getTimeoutSeconds() != null
                || rag.getIndexMinLength() != null;
    }

    private <T> T loadLegacySection(String fileName, Class<T> type) {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, fileName).join();
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, type);
        } catch (Exception ex) { // NOSONAR - migration must never break startup
            log.debug("[PluginMigration] Skipping unreadable legacy section {}: {}", fileName, ex.getMessage());
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String valueOf(Secret secret) {
        return secret != null ? secret.getValue() : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    private static class LegacyToolsConfig {
        private Boolean browserEnabled;
        private Integer browserTimeout;
        private String browserUserAgent;
        private Boolean browserHeadless;
        private Boolean braveSearchEnabled;
        private Secret braveSearchApiKey;
        private LegacyImapConfig imap;
        private LegacySmtpConfig smtp;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    private static class LegacyImapConfig {
        private Boolean enabled;
        private String host;
        private Integer port;
        private String username;
        private Secret password;
        private String security;
        private String sslTrust;
        private Integer connectTimeout;
        private Integer readTimeout;
        private Integer maxBodyLength;
        private Integer defaultMessageLimit;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    private static class LegacySmtpConfig {
        private Boolean enabled;
        private String host;
        private Integer port;
        private String username;
        private Secret password;
        private String security;
        private String sslTrust;
        private Integer connectTimeout;
        private Integer readTimeout;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    private static class LegacyRagConfig {
        private Boolean enabled;
        private String url;
        private Secret apiKey;
        private String queryMode;
        private Integer timeoutSeconds;
        private Integer indexMinLength;
    }
}

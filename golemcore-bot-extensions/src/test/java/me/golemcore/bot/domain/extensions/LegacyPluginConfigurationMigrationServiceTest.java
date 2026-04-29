package me.golemcore.bot.domain.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyPluginConfigurationMigrationServiceTest {

    private StoragePort storagePort;
    private PluginConfigurationService pluginConfigurationService;
    private ObjectMapper objectMapper;
    private LegacyPluginConfigurationMigrationService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        pluginConfigurationService = mock(PluginConfigurationService.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new LegacyPluginConfigurationMigrationService(storagePort, pluginConfigurationService, objectMapper);

        when(storagePort.getText(anyString(), eq("tools.json"))).thenReturn(CompletableFuture.completedFuture("""
                {
                  "browserEnabled": true,
                  "browserHeadless": false,
                  "browserTimeout": 45000,
                  "browserUserAgent": "TestAgent/1.0",
                  "braveSearchEnabled": true,
                  "braveSearchApiKey": { "value": "brave-key", "encrypted": false, "present": true },
                  "imap": {
                    "enabled": true,
                    "host": "imap.example.com",
                    "port": 993,
                    "username": "imap-user",
                    "password": { "value": "imap-secret", "encrypted": false, "present": true },
                    "security": "ssl",
                    "sslTrust": "*",
                    "connectTimeout": 1000,
                    "readTimeout": 2000,
                    "maxBodyLength": 4000,
                    "defaultMessageLimit": 25
                  },
                  "smtp": {
                    "enabled": true,
                    "host": "smtp.example.com",
                    "port": 587,
                    "username": "smtp-user",
                    "password": { "value": "smtp-secret", "encrypted": false, "present": true },
                    "security": "starttls",
                    "sslTrust": "mail.example.com",
                    "connectTimeout": 3000,
                    "readTimeout": 4000
                  }
                }
                """));
        when(storagePort.getText(anyString(), eq("rag.json"))).thenReturn(CompletableFuture.completedFuture("""
                {
                  "enabled": true,
                  "url": "http://rag.local",
                  "apiKey": { "value": "rag-key", "encrypted": false, "present": true },
                  "queryMode": "hybrid",
                  "timeoutSeconds": 12,
                  "indexMinLength": 75
                }
                """));
    }

    @Test
    void shouldMigrateLegacyRuntimeConfigIntoPluginOwnedBlobs() {
        when(pluginConfigurationService.hasPluginConfig("golemcore/browser")).thenReturn(false);
        when(pluginConfigurationService.hasPluginConfig("golemcore/brave-search")).thenReturn(false);
        when(pluginConfigurationService.hasPluginConfig("golemcore/mail")).thenReturn(false);
        when(pluginConfigurationService.hasPluginConfig("golemcore/lightrag")).thenReturn(false);

        service.migrateIfNeeded();

        verify(pluginConfigurationService).savePluginConfig(eq("golemcore/browser"), eq(Map.of(
                "enabled", true,
                "headless", false,
                "timeoutMs", 45_000,
                "userAgent", "TestAgent/1.0")));

        verify(pluginConfigurationService).savePluginConfig(eq("golemcore/brave-search"), eq(Map.of(
                "enabled", true,
                "apiKey", "brave-key",
                "defaultCount", 5)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> mailConfigCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pluginConfigurationService).savePluginConfig(eq("golemcore/mail"), mailConfigCaptor.capture());
        Map<String, Object> mailConfig = mailConfigCaptor.getValue();
        assertNotNull(mailConfig);
        @SuppressWarnings("unchecked")
        Map<String, Object> imap = (Map<String, Object>) mailConfig.get("imap");
        @SuppressWarnings("unchecked")
        Map<String, Object> smtp = (Map<String, Object>) mailConfig.get("smtp");
        assertEquals("imap.example.com", imap.get("host"));
        assertEquals("imap-secret", imap.get("password"));
        assertEquals(25, imap.get("defaultMessageLimit"));
        assertEquals("smtp.example.com", smtp.get("host"));
        assertEquals("smtp-secret", smtp.get("password"));
        assertEquals("starttls", smtp.get("security"));

        verify(pluginConfigurationService).savePluginConfig(eq("golemcore/lightrag"), eq(Map.of(
                "enabled", true,
                "url", "http://rag.local",
                "apiKey", "rag-key",
                "queryMode", "hybrid",
                "timeoutSeconds", 12,
                "indexMinLength", 75)));
    }
}

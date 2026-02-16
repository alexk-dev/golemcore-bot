package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.TooManyMethods") // Comprehensive test coverage for critical config service
class RuntimeConfigServiceTest {

    private StoragePort storagePort;
    private RuntimeConfigService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new RuntimeConfigService(storagePort);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    // ==================== Lazy Loading ====================

    @Test
    void shouldCreateDefaultConfigWhenStorageEmpty() {
        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config);
        assertNotNull(config.getTelegram());
        assertNotNull(config.getModelRouter());
        assertNotNull(config.getLlm());
        assertNotNull(config.getTools());
    }

    @Test
    void shouldLoadConfigFromStorage() throws Exception {
        RuntimeConfig stored = RuntimeConfig.builder().build();
        stored.getModelRouter().setBalancedModel("custom/model");
        String json = objectMapper.writeValueAsString(stored);
        when(storagePort.getText("preferences", "runtime-config.json"))
                .thenReturn(CompletableFuture.completedFuture(json));

        RuntimeConfig config = service.getRuntimeConfig();

        assertEquals("custom/model", config.getModelRouter().getBalancedModel());
    }

    @Test
    void shouldCacheConfigAfterFirstLoad() {
        RuntimeConfig first = service.getRuntimeConfig();
        RuntimeConfig second = service.getRuntimeConfig();

        assertEquals(first, second);
        verify(storagePort, times(1)).getText(anyString(), anyString());
    }

    @Test
    void shouldFallbackToDefaultOnCorruptedJson() {
        when(storagePort.getText("preferences", "runtime-config.json"))
                .thenReturn(CompletableFuture.completedFuture("{invalid json!!!}"));

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config);
    }

    @Test
    void shouldFallbackToDefaultOnStorageException() {
        when(storagePort.getText("preferences", "runtime-config.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk error")));

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config);
    }

    // ==================== Default Values ====================

    @Test
    void shouldReturnDefaultModelWhenNotConfigured() {
        assertEquals("openai/gpt-5.1", service.getBalancedModel());
        assertEquals("openai/gpt-5.1", service.getSmartModel());
        assertEquals("openai/gpt-5.2", service.getCodingModel());
        assertEquals("openai/gpt-5.2", service.getDeepModel());
        assertEquals("openai/gpt-5.2-codex", service.getRoutingModel());
    }

    @Test
    void shouldReturnDefaultReasoningWhenNotConfigured() {
        assertEquals("none", service.getBalancedModelReasoning());
        assertEquals("none", service.getSmartModelReasoning());
        assertEquals("none", service.getCodingModelReasoning());
        assertEquals("none", service.getDeepModelReasoning());
        assertEquals("none", service.getRoutingModelReasoning());
    }

    @Test
    void shouldReturnDefaultTemperature() {
        assertEquals(0.7, service.getTemperature(), 0.001);
    }

    @Test
    void shouldReturnDefaultRateLimits() {
        assertEquals(20, service.getUserRequestsPerMinute());
        assertEquals(100, service.getUserRequestsPerHour());
        assertEquals(500, service.getUserRequestsPerDay());
        assertEquals(30, service.getChannelMessagesPerSecond());
        assertEquals(60, service.getLlmRequestsPerMinute());
    }

    @Test
    void shouldReturnDefaultSecuritySettings() {
        assertTrue(service.isSanitizeInputEnabled());
        assertTrue(service.isPromptInjectionDetectionEnabled());
        assertTrue(service.isCommandInjectionDetectionEnabled());
        assertEquals(10000, service.getMaxInputLength());
        assertTrue(service.isAllowlistEnabled());
        assertFalse(service.isToolConfirmationEnabled());
        assertEquals(60, service.getToolConfirmationTimeoutSeconds());
    }

    @Test
    void shouldReturnDefaultAutoModeSettings() {
        assertFalse(service.isAutoModeEnabled());
        assertEquals(30, service.getAutoTickIntervalSeconds());
        assertEquals(10, service.getAutoTaskTimeLimitMinutes());
        assertEquals(3, service.getAutoMaxGoals());
        assertEquals("default", service.getAutoModelTier());
    }

    @Test
    void shouldReturnDefaultCompactionSettings() {
        assertTrue(service.isCompactionEnabled());
        assertEquals(50000, service.getCompactionMaxContextTokens());
        assertEquals(20, service.getCompactionKeepLastMessages());
    }

    @Test
    void shouldReturnDefaultVoiceSettings() {
        assertFalse(service.isVoiceEnabled());
        assertEquals("21m00Tcm4TlvDq8ikWAM", service.getVoiceId());
        assertEquals("eleven_multilingual_v2", service.getTtsModelId());
        assertEquals("scribe_v1", service.getSttModelId());
        assertEquals(1.0f, service.getVoiceSpeed(), 0.01);
    }

    @Test
    void shouldReturnDefaultMcpSettings() {
        assertTrue(service.isMcpEnabled());
        assertEquals(30, service.getMcpDefaultStartupTimeout());
        assertEquals(5, service.getMcpDefaultIdleTimeout());
    }

    @Test
    void shouldReturnDefaultTurnBudget() {
        assertEquals(200, service.getTurnMaxLlmCalls());
        assertEquals(500, service.getTurnMaxToolExecutions());
        assertEquals(java.time.Duration.ofHours(1), service.getTurnDeadline());
    }

    @Test
    void shouldReturnDefaultTurnDeadlineOnInvalidFormat() throws Exception {
        RuntimeConfig config = RuntimeConfig.builder().build();
        config.getTurn().setDeadline("not-a-duration");
        String json = objectMapper.writeValueAsString(config);
        when(storagePort.getText("preferences", "runtime-config.json"))
                .thenReturn(CompletableFuture.completedFuture(json));

        assertEquals(java.time.Duration.ofHours(1), service.getTurnDeadline());
    }

    @Test
    void shouldReturnDefaultMemorySettings() {
        assertTrue(service.isMemoryEnabled());
        assertEquals(7, service.getMemoryRecentDays());
    }

    @Test
    void shouldReturnDefaultSkillsSettings() {
        assertTrue(service.isSkillsEnabled());
        assertTrue(service.isSkillsProgressiveLoadingEnabled());
    }

    @Test
    void shouldReturnDefaultToolEnablement() {
        assertTrue(service.isFilesystemEnabled());
        assertTrue(service.isShellEnabled());
        assertTrue(service.isSkillManagementEnabled());
        assertTrue(service.isSkillTransitionEnabled());
        assertTrue(service.isTierToolEnabled());
        assertTrue(service.isGoalManagementEnabled());
        assertTrue(service.isBrowserEnabled());
        assertTrue(service.isDynamicTierEnabled());
    }

    @Test
    void shouldReturnDefaultRagSettings() {
        assertFalse(service.isRagEnabled());
        assertEquals("http://localhost:9621", service.getRagUrl());
        assertEquals("hybrid", service.getRagQueryMode());
        assertEquals(10, service.getRagTimeoutSeconds());
        assertEquals(50, service.getRagIndexMinLength());
    }

    @Test
    void shouldReturnDefaultBrowserSettings() {
        assertEquals("brave", service.getBrowserApiProvider());
        assertEquals("playwright", service.getBrowserType());
        assertEquals(30000, service.getBrowserTimeoutMs());
        assertTrue(service.isBrowserHeadless());
        assertNotNull(service.getBrowserUserAgent());
    }

    // ==================== Telegram ====================

    @Test
    void shouldReturnFalseWhenTelegramDisabled() {
        assertFalse(service.isTelegramEnabled());
    }

    @Test
    void shouldReturnEmptyTokenWhenNotConfigured() {
        assertEquals("", service.getTelegramToken());
    }

    @Test
    void shouldReturnEmptyAllowedUsersWhenNull() {
        assertNotNull(service.getTelegramAllowedUsers());
        assertTrue(service.getTelegramAllowedUsers().isEmpty());
    }

    // ==================== LLM Providers ====================

    @Test
    void shouldReturnEmptyProviderConfigWhenNotConfigured() {
        RuntimeConfig.LlmProviderConfig config = service.getLlmProviderConfig("unknown");

        assertNotNull(config);
        assertFalse(service.hasLlmProviderApiKey("unknown"));
    }

    @Test
    void shouldDetectConfiguredLlmProviderApiKey() throws Exception {
        RuntimeConfig config = RuntimeConfig.builder().build();
        Map<String, RuntimeConfig.LlmProviderConfig> providers = new LinkedHashMap<>();
        providers.put("openai", RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("sk-test-key"))
                .build());
        config.getLlm().setProviders(providers);
        String json = objectMapper.writeValueAsString(config);
        when(storagePort.getText("preferences", "runtime-config.json"))
                .thenReturn(CompletableFuture.completedFuture(json));

        assertTrue(service.hasLlmProviderApiKey("openai"));
        assertFalse(service.hasLlmProviderApiKey("anthropic"));
        assertEquals(List.of("openai"), service.getConfiguredLlmProviders());
    }

    // ==================== Secret Redaction ====================

    @Test
    void shouldRedactSecretsInApiResponse() throws Exception {
        RuntimeConfig config = RuntimeConfig.builder().build();
        config.getTelegram().setToken(Secret.of("bot-token-secret"));
        config.getVoice().setApiKey(Secret.of("voice-key-secret"));
        config.getRag().setApiKey(Secret.of("rag-key-secret"));
        config.getTools().setBraveSearchApiKey(Secret.of("brave-key-secret"));

        Map<String, RuntimeConfig.LlmProviderConfig> providers = new LinkedHashMap<>();
        providers.put("openai", RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("openai-secret"))
                .build());
        config.getLlm().setProviders(providers);

        String json = objectMapper.writeValueAsString(config);
        when(storagePort.getText("preferences", "runtime-config.json"))
                .thenReturn(CompletableFuture.completedFuture(json));

        RuntimeConfig redacted = service.getRuntimeConfigForApi();

        assertNull(redacted.getTelegram().getToken().getValue());
        assertTrue(redacted.getTelegram().getToken().getPresent());
        assertNull(redacted.getVoice().getApiKey().getValue());
        assertTrue(redacted.getVoice().getApiKey().getPresent());
        assertNull(redacted.getRag().getApiKey().getValue());
        assertNull(redacted.getTools().getBraveSearchApiKey().getValue());
        assertNull(redacted.getLlm().getProviders().get("openai").getApiKey().getValue());
        assertTrue(redacted.getLlm().getProviders().get("openai").getApiKey().getPresent());
    }

    @Test
    void shouldNotLeakSecretsInOriginalAfterRedaction() throws Exception {
        RuntimeConfig config = RuntimeConfig.builder().build();
        config.getTelegram().setToken(Secret.of("my-secret-token"));
        String json = objectMapper.writeValueAsString(config);
        when(storagePort.getText("preferences", "runtime-config.json"))
                .thenReturn(CompletableFuture.completedFuture(json));

        service.getRuntimeConfigForApi();
        String token = service.getTelegramToken();

        assertEquals("my-secret-token", token);
    }

    // ==================== Persistence ====================

    @Test
    void shouldPersistOnUpdate() {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.getModelRouter().setBalancedModel("custom/model");

        service.updateRuntimeConfig(newConfig);

        verify(storagePort).putText(anyString(), anyString(), anyString());

        RuntimeConfig updated = service.getRuntimeConfig();
        assertEquals("custom/model", updated.getModelRouter().getBalancedModel());
    }

    @Test
    void shouldNotThrowOnPersistFailure() {
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk full")));

        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        service.updateRuntimeConfig(newConfig);

        assertNotNull(service.getRuntimeConfig());
    }

    // ==================== Normalization ====================

    @Test
    void shouldNormalizeLlmConfigWhenNull() throws Exception {
        String json = "{}";
        when(storagePort.getText("preferences", "runtime-config.json"))
                .thenReturn(CompletableFuture.completedFuture(json));

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getLlm());
        assertNotNull(config.getLlm().getProviders());
    }

    @Test
    void shouldNormalizeSecretPresenceFlags() throws Exception {
        RuntimeConfig config = RuntimeConfig.builder().build();
        Secret secretWithValue = Secret.builder().value("test-key").present(null).encrypted(null).build();
        config.getTelegram().setToken(secretWithValue);
        String json = objectMapper.writeValueAsString(config);
        when(storagePort.getText("preferences", "runtime-config.json"))
                .thenReturn(CompletableFuture.completedFuture(json));

        RuntimeConfig loaded = service.getRuntimeConfig();

        assertTrue(loaded.getTelegram().getToken().getPresent());
        assertFalse(loaded.getTelegram().getToken().getEncrypted());
    }

    // ==================== Invite Codes ====================

    @Test
    void shouldGenerateInviteCode() {
        RuntimeConfig.InviteCode code = service.generateInviteCode();

        assertNotNull(code);
        assertNotNull(code.getCode());
        assertEquals(20, code.getCode().length());
        assertFalse(code.isUsed());
        assertNotNull(code.getCreatedAt());
        verify(storagePort, times(2)).putText(anyString(), anyString(), anyString());
    }

    @Test
    void shouldGenerateUniqueInviteCodes() {
        RuntimeConfig.InviteCode code1 = service.generateInviteCode();
        RuntimeConfig.InviteCode code2 = service.generateInviteCode();

        assertFalse(code1.getCode().equals(code2.getCode()));
    }

    @Test
    void shouldRedeemInviteCode() {
        RuntimeConfig.InviteCode generated = service.generateInviteCode();

        boolean redeemed = service.redeemInviteCode(generated.getCode(), "user123");

        assertTrue(redeemed);
        assertTrue(service.getTelegramAllowedUsers().contains("user123"));
    }

    @Test
    void shouldNotRedeemUsedInviteCode() {
        RuntimeConfig.InviteCode generated = service.generateInviteCode();
        service.redeemInviteCode(generated.getCode(), "user1");

        boolean secondRedeem = service.redeemInviteCode(generated.getCode(), "user2");

        assertFalse(secondRedeem);
        assertFalse(service.getTelegramAllowedUsers().contains("user2"));
    }

    @Test
    void shouldNotRedeemNonexistentCode() {
        boolean result = service.redeemInviteCode("NONEXISTENT", "user1");

        assertFalse(result);
    }

    @Test
    void shouldNotDuplicateUserOnRedeem() {
        RuntimeConfig.InviteCode code1 = service.generateInviteCode();
        RuntimeConfig.InviteCode code2 = service.generateInviteCode();
        service.redeemInviteCode(code1.getCode(), "user1");
        service.redeemInviteCode(code2.getCode(), "user1");

        long count = service.getTelegramAllowedUsers().stream()
                .filter("user1"::equals)
                .count();
        assertEquals(1, count);
    }

    @Test
    void shouldRevokeInviteCode() {
        RuntimeConfig.InviteCode generated = service.generateInviteCode();

        boolean revoked = service.revokeInviteCode(generated.getCode());

        assertTrue(revoked);
    }

    @Test
    void shouldReturnFalseWhenRevokingNonexistentCode() {
        boolean revoked = service.revokeInviteCode("NONEXISTENT");

        assertFalse(revoked);
    }

    @Test
    void shouldNotRedeemAfterRevocation() {
        RuntimeConfig.InviteCode generated = service.generateInviteCode();
        service.revokeInviteCode(generated.getCode());

        boolean redeemed = service.redeemInviteCode(generated.getCode(), "user1");

        assertFalse(redeemed);
    }

    // ==================== IMAP / SMTP Resolved Config ====================

    @Test
    void shouldResolveImapConfigWithDefaults() {
        BotProperties.ImapToolProperties resolved = service.getResolvedImapConfig();

        assertFalse(resolved.isEnabled());
        assertEquals("", resolved.getHost());
        assertEquals(993, resolved.getPort());
        assertEquals("", resolved.getUsername());
        assertEquals("", resolved.getPassword());
        assertEquals("ssl", resolved.getSecurity());
        assertEquals(10000, resolved.getConnectTimeout());
        assertEquals(30000, resolved.getReadTimeout());
        assertEquals(50000, resolved.getMaxBodyLength());
        assertEquals(20, resolved.getDefaultMessageLimit());
    }

    @Test
    void shouldResolveSmtpConfigWithDefaults() {
        BotProperties.SmtpToolProperties resolved = service.getResolvedSmtpConfig();

        assertFalse(resolved.isEnabled());
        assertEquals("", resolved.getHost());
        assertEquals(587, resolved.getPort());
        assertEquals("", resolved.getUsername());
        assertEquals("", resolved.getPassword());
        assertEquals("starttls", resolved.getSecurity());
        assertEquals(10000, resolved.getConnectTimeout());
        assertEquals(30000, resolved.getReadTimeout());
    }

    @Test
    void shouldResolveImapConfigWithCustomValues() throws Exception {
        RuntimeConfig config = RuntimeConfig.builder().build();
        RuntimeConfig.ImapConfig imap = config.getTools().getImap();
        imap.setEnabled(true);
        imap.setHost("imap.example.com");
        imap.setPort(143);
        imap.setUsername("user@example.com");
        imap.setPassword(Secret.of("pass"));
        imap.setSecurity("starttls");
        imap.setMaxBodyLength(10000);

        String json = objectMapper.writeValueAsString(config);
        when(storagePort.getText("preferences", "runtime-config.json"))
                .thenReturn(CompletableFuture.completedFuture(json));

        BotProperties.ImapToolProperties resolved = service.getResolvedImapConfig();

        assertTrue(resolved.isEnabled());
        assertEquals("imap.example.com", resolved.getHost());
        assertEquals(143, resolved.getPort());
        assertEquals("user@example.com", resolved.getUsername());
        assertEquals("pass", resolved.getPassword());
        assertEquals("starttls", resolved.getSecurity());
        assertEquals(10000, resolved.getMaxBodyLength());
    }

    // ==================== Telegram Auth Mode ====================

    @Test
    void shouldSetTelegramAuthModeAndPersist() {
        service.setTelegramAuthMode("user");

        assertEquals("user", service.getRuntimeConfig().getTelegram().getAuthMode());
        verify(storagePort, times(2)).putText(anyString(), anyString(), anyString());
    }

    // ==================== Voice Telegram Options ====================

    @Test
    void shouldReturnFalseForVoiceTelegramOptions() {
        assertFalse(service.isTelegramRespondWithVoiceEnabled());
        assertFalse(service.isTelegramTranscribeIncomingEnabled());
    }

    // ==================== Brave Search ====================

    @Test
    void shouldDetectBraveSearchDisabledByDefault() {
        assertFalse(service.isBraveSearchEnabled());
        assertEquals("", service.getBraveSearchApiKey());
    }

    // ==================== Usage ====================

    @Test
    void shouldReturnUsageEnabledByDefault() {
        assertTrue(service.isUsageEnabled());
    }
}

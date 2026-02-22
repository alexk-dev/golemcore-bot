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
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.TooManyMethods") // Comprehensive test coverage for critical config service
class RuntimeConfigServiceTest {

    private StoragePort storagePort;
    private RuntimeConfigService service;
    private ObjectMapper objectMapper;
    /** Per-file storage for modular config sections */
    private Map<String, String> persistedSections;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        persistedSections = new ConcurrentHashMap<>();

        // Mock putTextAtomic to capture written content per section file
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedSections.put(fileName, content);
                    return CompletableFuture.completedFuture(null);
                });

        // Mock getText to return persisted content per section file
        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    return CompletableFuture.completedFuture(persistedSections.get(fileName));
                });

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
        RuntimeConfig.ModelRouterConfig modelRouter = RuntimeConfig.ModelRouterConfig.builder()
                .balancedModel("custom/model")
                .build();
        String json = objectMapper.writeValueAsString(modelRouter);
        persistedSections.put("model-router.json", json);

        RuntimeConfig config = service.getRuntimeConfig();

        assertEquals("custom/model", config.getModelRouter().getBalancedModel());
    }

    @Test
    void shouldCacheConfigAfterFirstLoad() {
        RuntimeConfig first = service.getRuntimeConfig();
        RuntimeConfig second = service.getRuntimeConfig();

        assertEquals(first, second);
        // First call loads all 15 sections, second call returns cached
        // Verify getText was called for section loading (15 sections on first load)
        verify(storagePort, atLeast(15)).getText(anyString(), anyString());
    }

    @Test
    void shouldFallbackToDefaultOnCorruptedJson() {
        // Put corrupted JSON for a section file
        persistedSections.put("telegram.json", "{invalid json!!!}");

        RuntimeConfig config = service.getRuntimeConfig();

        // Should still load with defaults for corrupted section
        assertNotNull(config);
        assertNotNull(config.getTelegram());
    }

    @Test
    void shouldFallbackToDefaultOnStorageException() {
        // Make getText fail for one section
        when(storagePort.getText("preferences", "telegram.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk error")));

        RuntimeConfig config = service.getRuntimeConfig();

        // Should still load with defaults for failed section
        assertNotNull(config);
        assertNotNull(config.getTelegram());
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
        RuntimeConfig.TurnConfig turn = RuntimeConfig.TurnConfig.builder()
                .deadline("not-a-duration")
                .build();
        String json = objectMapper.writeValueAsString(turn);
        persistedSections.put("turn.json", json);

        assertEquals(java.time.Duration.ofHours(1), service.getTurnDeadline());
    }

    @Test
    void shouldReturnDefaultMemorySettings() {
        assertTrue(service.isMemoryEnabled());
        assertEquals(1800, service.getMemorySoftPromptBudgetTokens());
        assertEquals(3500, service.getMemoryMaxPromptBudgetTokens());
        assertEquals(6, service.getMemoryWorkingTopK());
        assertEquals(8, service.getMemoryEpisodicTopK());
        assertEquals(6, service.getMemorySemanticTopK());
        assertEquals(4, service.getMemoryProceduralTopK());
        assertEquals(21, service.getMemoryRetrievalLookbackDays());
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
        Map<String, RuntimeConfig.LlmProviderConfig> providers = new LinkedHashMap<>();
        providers.put("openai", RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("sk-test-key"))
                .build());
        RuntimeConfig.LlmConfig llm = RuntimeConfig.LlmConfig.builder()
                .providers(providers)
                .build();
        String json = objectMapper.writeValueAsString(llm);
        persistedSections.put("llm.json", json);

        assertTrue(service.hasLlmProviderApiKey("openai"));
        assertFalse(service.hasLlmProviderApiKey("anthropic"));
        assertEquals(List.of("openai"), service.getConfiguredLlmProviders());
    }

    // ==================== Secret Redaction ====================

    @Test
    void shouldRedactSecretsInApiResponse() throws Exception {
        // Setup individual section files with secrets
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .token(Secret.of("bot-token-secret"))
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

        RuntimeConfig.VoiceConfig voice = RuntimeConfig.VoiceConfig.builder()
                .apiKey(Secret.of("voice-key-secret"))
                .build();
        persistedSections.put("voice.json", objectMapper.writeValueAsString(voice));

        RuntimeConfig.RagConfig rag = RuntimeConfig.RagConfig.builder()
                .apiKey(Secret.of("rag-key-secret"))
                .build();
        persistedSections.put("rag.json", objectMapper.writeValueAsString(rag));

        RuntimeConfig.ToolsConfig tools = RuntimeConfig.ToolsConfig.builder()
                .braveSearchApiKey(Secret.of("brave-key-secret"))
                .build();
        persistedSections.put("tools.json", objectMapper.writeValueAsString(tools));

        Map<String, RuntimeConfig.LlmProviderConfig> providers = new LinkedHashMap<>();
        providers.put("openai", RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("openai-secret"))
                .build());
        RuntimeConfig.LlmConfig llm = RuntimeConfig.LlmConfig.builder()
                .providers(providers)
                .build();
        persistedSections.put("llm.json", objectMapper.writeValueAsString(llm));

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
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .token(Secret.of("my-secret-token"))
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

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

        // Verify all 15 sections were persisted
        verify(storagePort, times(15)).putTextAtomic(anyString(), anyString(), anyString(), anyBoolean());

        RuntimeConfig updated = service.getRuntimeConfig();
        assertEquals("custom/model", updated.getModelRouter().getBalancedModel());
    }

    @Test
    void shouldThrowAndRollbackOnPersistFailure() throws Exception {
        // Setup initial config section
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .enabled(false)
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

        // Load initial config
        service.getRuntimeConfig();

        // Setup persist failure for next update
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk full")));

        // Try to update with different value - should throw and rollback
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.getTelegram().setEnabled(true);

        assertThrows(IllegalStateException.class, () -> service.updateRuntimeConfig(newConfig));

        // Verify rollback - should still have original config value
        assertFalse(service.getRuntimeConfig().getTelegram().getEnabled());
    }

    // ==================== Normalization ====================

    @Test
    void shouldNormalizeLlmConfigWhenNull() throws Exception {
        // Put empty JSON for llm section
        persistedSections.put("llm.json", "{}");

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getLlm());
        assertNotNull(config.getLlm().getProviders());
    }

    @Test
    void shouldNormalizeSecretPresenceFlags() throws Exception {
        Secret secretWithValue = Secret.builder().value("test-key").present(null).encrypted(null).build();
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .token(secretWithValue)
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

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
        // Verify that persist was called (15 sections + 15 initial defaults on load)
        verify(storagePort, atLeast(15)).putTextAtomic(anyString(), anyString(), anyString(), anyBoolean());
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
        RuntimeConfig.ImapConfig imap = RuntimeConfig.ImapConfig.builder()
                .enabled(true)
                .host("imap.example.com")
                .port(143)
                .username("user@example.com")
                .password(Secret.of("pass"))
                .security("starttls")
                .maxBodyLength(10000)
                .build();
        RuntimeConfig.ToolsConfig tools = RuntimeConfig.ToolsConfig.builder()
                .imap(imap)
                .build();
        persistedSections.put("tools.json", objectMapper.writeValueAsString(tools));

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
        // Verify that persist was called (15 sections + 15 initial defaults on load)
        verify(storagePort, atLeast(15)).putTextAtomic(anyString(), anyString(), anyString(), anyBoolean());
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

    // ==================== Section Validation ====================

    @Test
    void shouldValidateKnownConfigSections() {
        assertTrue(RuntimeConfigService.isValidConfigSection("telegram"));
        assertTrue(RuntimeConfigService.isValidConfigSection("model-router"));
        assertTrue(RuntimeConfigService.isValidConfigSection("llm"));
        assertTrue(RuntimeConfigService.isValidConfigSection("tools"));
        assertTrue(RuntimeConfigService.isValidConfigSection("voice"));
        assertTrue(RuntimeConfigService.isValidConfigSection("auto-mode"));
        assertTrue(RuntimeConfigService.isValidConfigSection("rate-limit"));
        assertTrue(RuntimeConfigService.isValidConfigSection("security"));
        assertTrue(RuntimeConfigService.isValidConfigSection("compaction"));
        assertTrue(RuntimeConfigService.isValidConfigSection("turn"));
        assertTrue(RuntimeConfigService.isValidConfigSection("memory"));
        assertTrue(RuntimeConfigService.isValidConfigSection("skills"));
        assertTrue(RuntimeConfigService.isValidConfigSection("usage"));
        assertTrue(RuntimeConfigService.isValidConfigSection("rag"));
        assertTrue(RuntimeConfigService.isValidConfigSection("mcp"));
    }

    @Test
    void shouldRejectUnknownConfigSections() {
        assertFalse(RuntimeConfigService.isValidConfigSection("unknown"));
        assertFalse(RuntimeConfigService.isValidConfigSection("brave"));
        assertFalse(RuntimeConfigService.isValidConfigSection("runtime-config"));
        assertFalse(RuntimeConfigService.isValidConfigSection(""));
        assertFalse(RuntimeConfigService.isValidConfigSection(null));
    }

    @Test
    void shouldValidateSectionCaseInsensitively() {
        assertTrue(RuntimeConfigService.isValidConfigSection("TELEGRAM"));
        assertTrue(RuntimeConfigService.isValidConfigSection("Model-Router"));
        assertTrue(RuntimeConfigService.isValidConfigSection("AUTO-MODE"));
    }

    // ==================== ConfigSection Enum ====================

    @Test
    void shouldReturnCorrectFileNameForSection() {
        assertEquals("telegram.json", RuntimeConfig.ConfigSection.TELEGRAM.getFileName());
        assertEquals("model-router.json", RuntimeConfig.ConfigSection.MODEL_ROUTER.getFileName());
        assertEquals("auto-mode.json", RuntimeConfig.ConfigSection.AUTO_MODE.getFileName());
    }

    @Test
    void shouldFindSectionByFileId() {
        assertTrue(RuntimeConfig.ConfigSection.fromFileId("telegram").isPresent());
        assertEquals(RuntimeConfig.ConfigSection.TELEGRAM, RuntimeConfig.ConfigSection.fromFileId("telegram").get());
        assertTrue(RuntimeConfig.ConfigSection.fromFileId("model-router").isPresent());
        assertEquals(RuntimeConfig.ConfigSection.MODEL_ROUTER,
                RuntimeConfig.ConfigSection.fromFileId("model-router").get());
    }

    @Test
    void shouldReturnEmptyForUnknownFileId() {
        assertFalse(RuntimeConfig.ConfigSection.fromFileId("unknown").isPresent());
        assertFalse(RuntimeConfig.ConfigSection.fromFileId("").isPresent());
        assertFalse(RuntimeConfig.ConfigSection.fromFileId(null).isPresent());
    }

    // ==================== Modular Storage ====================

    @Test
    void shouldLoadAllSectionsIndependently() throws Exception {
        // Setup different values in different section files
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .enabled(true)
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

        RuntimeConfig.ModelRouterConfig modelRouter = RuntimeConfig.ModelRouterConfig.builder()
                .balancedModel("custom/balanced")
                .build();
        persistedSections.put("model-router.json", objectMapper.writeValueAsString(modelRouter));

        RuntimeConfig.VoiceConfig voice = RuntimeConfig.VoiceConfig.builder()
                .enabled(true)
                .voiceId("custom-voice")
                .build();
        persistedSections.put("voice.json", objectMapper.writeValueAsString(voice));

        RuntimeConfig config = service.getRuntimeConfig();

        // Verify each section loaded correctly
        assertTrue(config.getTelegram().getEnabled());
        assertEquals("custom/balanced", config.getModelRouter().getBalancedModel());
        assertTrue(config.getVoice().getEnabled());
        assertEquals("custom-voice", config.getVoice().getVoiceId());
    }

    @Test
    void shouldPersistToIndividualSectionFiles() throws Exception {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.getTelegram().setEnabled(true);
        newConfig.getModelRouter().setBalancedModel("updated/model");

        service.updateRuntimeConfig(newConfig);

        // Verify sections were persisted to individual files
        assertTrue(persistedSections.containsKey("telegram.json"));
        assertTrue(persistedSections.containsKey("model-router.json"));
        assertTrue(persistedSections.containsKey("llm.json"));
        assertTrue(persistedSections.containsKey("tools.json"));
    }
}

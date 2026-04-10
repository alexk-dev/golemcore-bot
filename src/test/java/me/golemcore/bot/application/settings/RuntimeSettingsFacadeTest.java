package me.golemcore.bot.application.settings;

import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.MemoryPreset;
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;
import me.golemcore.bot.domain.service.HiveManagedPolicyService;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.VoiceProviderCatalogPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSettingsFacadeTest {

    private RuntimeConfigService runtimeConfigService;
    private UserPreferencesService preferencesService;
    private MemoryPresetService memoryPresetService;
    private HiveManagedPolicyService hiveManagedPolicyService;
    private RuntimeSettingsFacade facade;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        preferencesService = mock(UserPreferencesService.class);
        memoryPresetService = mock(MemoryPresetService.class);
        hiveManagedPolicyService = mock(HiveManagedPolicyService.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        VoiceProviderCatalogPort voiceProviderCatalogPort = mock(VoiceProviderCatalogPort.class);
        RuntimeSettingsValidator validator = new RuntimeSettingsValidator(
                modelSelectionService,
                voiceProviderCatalogPort);
        RuntimeSettingsMergeService mergeService = new RuntimeSettingsMergeService();
        facade = new RuntimeSettingsFacade(
                runtimeConfigService,
                preferencesService,
                memoryPresetService,
                hiveManagedPolicyService,
                validator,
                mergeService);
    }

    @Test
    void shouldRejectManagedHiveMutationBeforePersistingRuntimeConfig() {
        RuntimeConfig current = RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .enabled(false)
                        .build())
                .build();
        RuntimeConfig incoming = RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .enabled(true)
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.isHiveManagedByProperties()).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> facade.updateRuntimeConfig(incoming));

        verify(runtimeConfigService, never()).updateRuntimeConfig(any());
    }

    @Test
    void shouldDelegateGetRuntimeConfigForApi() {
        RuntimeConfig config = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(config);

        assertEquals(config, facade.getRuntimeConfigForApi());
    }

    @Test
    void shouldInitializeMissingLlmConfigWhenAddingProvider() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        RuntimeConfig apiView = RuntimeConfig.builder().build();
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(apiView);

        RuntimeConfig response = facade.addLlmProvider("openai", providerConfig);

        assertEquals(apiView, response);
        assertEquals(Map.of(), current.getLlm().getProviders());
        verify(runtimeConfigService).addLlmProvider("openai", providerConfig);
    }

    @Test
    void shouldRejectUpdatingUnknownProvider() {
        RuntimeConfig current = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new java.util.LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.updateLlmProvider("openai", RuntimeConfig.LlmProviderConfig.builder().build()));

        assertEquals("Provider 'openai' does not exist", error.getMessage());
    }

    @Test
    void shouldUpdateModelRouterConfigAndReturnApiView() {
        RuntimeConfig current = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new java.util.LinkedHashMap<>(Map.of("openai",
                                RuntimeConfig.LlmProviderConfig.builder().build())))
                        .build())
                .build();
        RuntimeConfig apiView = RuntimeConfig.builder().build();
        RuntimeConfig.ModelRouterConfig update = RuntimeConfig.ModelRouterConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(apiView);

        RuntimeConfig response = facade.updateModelRouterConfig(update);

        assertEquals(apiView, response);
        assertEquals(update, current.getModelRouter());
        verify(runtimeConfigService).updateRuntimeConfig(current);
    }

    @Test
    void shouldRejectAddingDuplicateProvider() {
        RuntimeConfig current = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new java.util.LinkedHashMap<>(Map.of("openai",
                                RuntimeConfig.LlmProviderConfig.builder().build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.addLlmProvider("openai", RuntimeConfig.LlmProviderConfig.builder().build()));

        assertTrue(error.getMessage().contains("already exists"));
    }

    @Test
    void shouldUpdateExistingProviderAndReturnApiView() {
        RuntimeConfig current = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new java.util.LinkedHashMap<>(Map.of("openai",
                                RuntimeConfig.LlmProviderConfig.builder().build())))
                        .build())
                .build();
        RuntimeConfig apiView = RuntimeConfig.builder().build();
        RuntimeConfig.LlmProviderConfig update = RuntimeConfig.LlmProviderConfig.builder()
                .requestTimeoutSeconds(30)
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(apiView);

        RuntimeConfig response = facade.updateLlmProvider("openai", update);

        assertEquals(apiView, response);
        verify(runtimeConfigService).updateLlmProvider("openai", update);
    }

    @Test
    void shouldRejectHiveManagedLlmMutation() {
        RuntimeConfig current = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new java.util.LinkedHashMap<>()).build())
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .build();
        RuntimeConfig incoming = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(Map.of("openai", RuntimeConfig.LlmProviderConfig.builder().build()))
                        .build())
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(hiveManagedPolicyService.getBindingState()).thenReturn(java.util.Optional.of(HivePolicyBindingState
                .builder()
                .policyGroupId("pg-1")
                .build()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> facade.updateRuntimeConfig(incoming));

        assertEquals("LLM settings are managed by Hive policy group \"pg-1\" and are read-only",
                error.getMessage());
        verify(runtimeConfigService, never()).updateRuntimeConfig(any());
    }

    @Test
    void shouldThrowWhenRemovingUnknownProvider() {
        RuntimeConfig current = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.removeLlmProvider("openai")).thenReturn(false);

        assertThrows(java.util.NoSuchElementException.class, () -> facade.removeLlmProvider("openai"));
    }

    @Test
    void shouldDefaultToEmptyShellEnvironmentVariableList() {
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(RuntimeConfig.builder().build());

        assertEquals(List.of(), facade.getShellEnvironmentVariables());
    }

    @Test
    void shouldUpdateToolsConfigAndReturnApiView() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        RuntimeConfig apiView = RuntimeConfig.builder().build();
        RuntimeConfig.ToolsConfig update = RuntimeConfig.ToolsConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(apiView);

        RuntimeConfig response = facade.updateToolsConfig(update);

        assertEquals(apiView, response);
        assertEquals(update, current.getTools());
        verify(runtimeConfigService).updateRuntimeConfig(current);
    }

    @Test
    void shouldPreserveExistingMcpCatalogWhenIncomingCatalogMissing() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("filesystem")
                .command("npx")
                .build();
        RuntimeConfig current = RuntimeConfig.builder()
                .mcp(RuntimeConfig.McpConfig.builder().catalog(List.of(entry)).build())
                .build();
        RuntimeConfig.McpConfig incoming = RuntimeConfig.McpConfig.builder().enabled(true).build();
        RuntimeConfig apiView = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(apiView);

        RuntimeConfig response = facade.updateMcpConfig(incoming);

        assertEquals(apiView, response);
        assertEquals(List.of(entry), incoming.getCatalog());
        verify(runtimeConfigService).updateRuntimeConfig(current);
    }

    @Test
    void shouldRejectNullMcpConfig() {
        assertThrows(IllegalArgumentException.class, () -> facade.updateMcpConfig(null));
    }

    @Test
    void shouldMergeWebhookSecretsBeforeSavingPreferences() {
        UserPreferences prefs = new UserPreferences();
        prefs.setWebhooks(UserPreferences.WebhookConfig.builder()
                .token(Secret.of("bearer-secret"))
                .mappings(List.of(UserPreferences.HookMapping.builder()
                        .name("build")
                        .hmacSecret(Secret.of("hmac-secret"))
                        .build()))
                .build());
        UserPreferences.WebhookConfig incoming = UserPreferences.WebhookConfig.builder()
                .token(Secret.builder().build())
                .mappings(List.of(UserPreferences.HookMapping.builder()
                        .name("build")
                        .hmacSecret(Secret.builder().build())
                        .build()))
                .build();
        when(preferencesService.getPreferences()).thenReturn(prefs);

        facade.updateWebhooksConfig(incoming);

        assertEquals("bearer-secret", incoming.getToken().getValue());
        assertEquals("hmac-secret", incoming.getMappings().getFirst().getHmacSecret().getValue());
        verify(preferencesService).savePreferences(prefs);
    }

    @Test
    void shouldCreateDefaultVoiceConfigWhenCurrentVoiceMissing() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        RuntimeConfig apiView = RuntimeConfig.builder().build();
        RuntimeConfig.VoiceConfig update = RuntimeConfig.VoiceConfig.builder()
                .enabled(false)
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(apiView);

        RuntimeConfig response = facade.updateVoiceConfig(update);

        assertEquals(apiView, response);
        assertNull(update.getApiKey());
        assertNull(update.getWhisperSttApiKey());
        verify(runtimeConfigService).updateRuntimeConfig(current);
    }

    @Test
    void shouldApplyAdvancedConfigSections() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        RuntimeConfig.RateLimitConfig rateLimit = RuntimeConfig.RateLimitConfig.builder().build();
        RuntimeConfig.SecurityConfig security = RuntimeConfig.SecurityConfig.builder().build();
        RuntimeConfig.CompactionConfig compaction = RuntimeConfig.CompactionConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig response = facade.updateAdvancedConfig(rateLimit, security, compaction);

        assertEquals(current, response);
        assertEquals(rateLimit, current.getRateLimit());
        assertEquals(security, current.getSecurity());
        assertEquals(compaction, current.getCompaction());
        verify(runtimeConfigService).updateRuntimeConfig(current);
    }

    @Test
    void shouldReturnMemoryPresetsAndUpdatePassiveRuntimeSections() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        RuntimeConfig apiView = RuntimeConfig.builder().build();
        RuntimeConfig.SkillsConfig skills = RuntimeConfig.SkillsConfig.builder().build();
        RuntimeConfig.UsageConfig usage = RuntimeConfig.UsageConfig.builder().build();
        RuntimeConfig.TelemetryConfig telemetry = RuntimeConfig.TelemetryConfig.builder().build();
        List<MemoryPreset> presets = List.of(MemoryPreset.builder().id("default").label("Default").build());
        when(memoryPresetService.getPresets()).thenReturn(presets);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(apiView);

        assertEquals(presets, facade.getMemoryPresets());
        assertEquals(apiView, facade.updateSkillsConfig(skills));
        assertEquals(apiView, facade.updateUsageConfig(usage));
        assertEquals(apiView, facade.updateTelemetryConfig(telemetry));
        assertEquals(skills, current.getSkills());
        assertEquals(usage, current.getUsage());
        assertEquals(telemetry, current.getTelemetry());
    }
}

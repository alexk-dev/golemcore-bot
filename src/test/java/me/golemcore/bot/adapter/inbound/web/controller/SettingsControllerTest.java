package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.application.settings.RuntimeSettingsFacade;
import me.golemcore.bot.adapter.inbound.web.mapper.RuntimeSettingsWebMapper;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.ShellEnvironmentVariableDto;
import me.golemcore.bot.application.settings.RuntimeSettingsMergeService;
import me.golemcore.bot.application.settings.RuntimeSettingsValidator;
import me.golemcore.bot.adapter.outbound.voice.PluginVoiceProviderCatalogAdapter;
import me.golemcore.bot.domain.model.MemoryPreset;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.plugin.runtime.SttProviderRegistry;
import me.golemcore.bot.plugin.runtime.TtsProviderRegistry;
import me.golemcore.plugin.api.extension.spi.SttProvider;
import me.golemcore.plugin.api.extension.spi.TtsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SettingsControllerTest {

    private UserPreferencesService preferencesService;
    private ModelSelectionService modelSelectionService;
    private RuntimeConfigService runtimeConfigService;
    private MemoryPresetService memoryPresetService;
    private SttProviderRegistry sttProviderRegistry;
    private TtsProviderRegistry ttsProviderRegistry;
    private RuntimeSettingsWebMapper runtimeSettingsWebMapper;
    private SettingsController controller;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        memoryPresetService = mock(MemoryPresetService.class);
        sttProviderRegistry = new SttProviderRegistry();
        ttsProviderRegistry = new TtsProviderRegistry();
        runtimeSettingsWebMapper = new RuntimeSettingsWebMapper();
        registerSttProvider("golemcore/elevenlabs", "elevenlabs");
        registerSttProvider("golemcore/whisper", "whisper");
        registerTtsProvider("golemcore/elevenlabs", "elevenlabs");
        registerTtsProvider("golemcore/whisper", "whisper");
        controller = createController(sttProviderRegistry, ttsProviderRegistry);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(RuntimeConfig.builder().build());
        when(runtimeConfigService.isHiveManagedByProperties()).thenReturn(false);
        when(modelSelectionService.validateModel(anyString(), anyList()))
                .thenReturn(new ModelSelectionService.ValidationResult(true, null));
        when(modelSelectionService.resolveProviderForModel(anyString())).thenAnswer(invocation -> {
            String model = invocation.getArgument(0);
            if (model == null) {
                return null;
            }
            int delimiterIndex = model.indexOf('/');
            return delimiterIndex > 0 ? model.substring(0, delimiterIndex) : null;
        });
    }

    private SettingsController createController(SttProviderRegistry sttRegistry, TtsProviderRegistry ttsRegistry) {
        RuntimeSettingsFacade runtimeSettingsFacade = new RuntimeSettingsFacade(
                runtimeConfigService,
                preferencesService,
                memoryPresetService,
                new RuntimeSettingsValidator(
                        modelSelectionService,
                        new PluginVoiceProviderCatalogAdapter(sttRegistry, ttsRegistry)),
                new RuntimeSettingsMergeService());
        return new SettingsController(preferencesService, modelSelectionService, runtimeSettingsFacade, runtimeSettingsWebMapper);
    }

    @Test
    void shouldGetSettings() {
        UserPreferences prefs = new UserPreferences();
        prefs.setLanguage("en");
        prefs.setTimezone("UTC");
        prefs.setNotificationsEnabled(true);
        prefs.setModelTier("balanced");
        when(preferencesService.getPreferences()).thenReturn(prefs);

        StepVerifier.create(controller.getSettings())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SettingsResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("en", body.getLanguage());
                    assertEquals("UTC", body.getTimezone());
                    assertEquals("balanced", body.getModelTier());
                })
                .verifyComplete();
    }

    @Test
    void shouldGetSettingsWithTierOverrides() {
        UserPreferences prefs = new UserPreferences();
        prefs.setLanguage("en");
        Map<String, UserPreferences.TierOverride> overrides = new LinkedHashMap<>();
        overrides.put("special1", new UserPreferences.TierOverride("gpt-4o", "medium"));
        prefs.setTierOverrides(overrides);
        when(preferencesService.getPreferences()).thenReturn(prefs);

        StepVerifier.create(controller.getSettings())
                .assertNext(response -> {
                    SettingsResponse body = response.getBody();
                    assertNotNull(body);
                    assertNotNull(body.getTierOverrides());
                    assertEquals("gpt-4o", body.getTierOverrides().get("special1").getModel());
                })
                .verifyComplete();
    }

    @Test
    void shouldGetSettingsWithWebhookTokensExposed() {
        UserPreferences prefs = new UserPreferences();
        prefs.setWebhooks(UserPreferences.WebhookConfig.builder()
                .enabled(true)
                .token(Secret.of("bearer-secret"))
                .mappings(List.of(UserPreferences.HookMapping.builder()
                        .name("github-push")
                        .action("agent")
                        .authMode("hmac")
                        .hmacSecret(Secret.of("hmac-secret"))
                        .messageTemplate("Push to {repository.name}")
                        .build()))
                .build());
        when(preferencesService.getPreferences()).thenReturn(prefs);

        StepVerifier.create(controller.getSettings())
                .assertNext(response -> {
                    SettingsResponse body = response.getBody();
                    assertNotNull(body);
                    assertNotNull(body.getWebhooks());
                    assertTrue(body.getWebhooks().isEnabled());
                    assertNotNull(body.getWebhooks().getToken());
                    assertEquals("bearer-secret", body.getWebhooks().getToken().getValue());
                    assertEquals(1, body.getWebhooks().getMappings().size());
                    assertNotNull(body.getWebhooks().getMappings().get(0).getHmacSecret());
                    assertEquals("hmac-secret", body.getWebhooks().getMappings().get(0).getHmacSecret().getValue());
                })
                .verifyComplete();
    }

    @Test
    void shouldGetMemoryPresets() {
        MemoryPreset preset = MemoryPreset.builder()
                .id("coding_balanced")
                .label("Coding Balanced")
                .comment("Balanced default for most developers.")
                .memory(RuntimeConfig.MemoryConfig.builder()
                        .softPromptBudgetTokens(1800)
                        .maxPromptBudgetTokens(3500)
                        .workingTopK(6)
                        .episodicTopK(8)
                        .semanticTopK(6)
                        .proceduralTopK(6)
                        .promotionEnabled(true)
                        .promotionMinConfidence(0.8)
                        .decayEnabled(true)
                        .decayDays(30)
                        .retrievalLookbackDays(21)
                        .codeAwareExtractionEnabled(true)
                        .build())
                .build();
        when(memoryPresetService.getPresets()).thenReturn(List.of(preset));

        StepVerifier.create(controller.getMemoryPresets())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<MemoryPreset> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("coding_balanced", body.get(0).getId());
                })
                .verifyComplete();
    }

    @Test
    void shouldUpdatePreferences() {
        UserPreferences prefs = new UserPreferences();
        when(preferencesService.getPreferences()).thenReturn(prefs);

        PreferencesUpdateRequest request = new PreferencesUpdateRequest();
        request.setLanguage("fr");
        request.setTimezone("Europe/Paris");

        StepVerifier.create(controller.updatePreferences(request))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(preferencesService).savePreferences(prefs);
        assertEquals("fr", prefs.getLanguage());
        assertEquals("Europe/Paris", prefs.getTimezone());
    }

    @Test
    void shouldUpdatePartialPreferences() {
        UserPreferences prefs = new UserPreferences();
        prefs.setLanguage("en");
        prefs.setModelTier("balanced");
        when(preferencesService.getPreferences()).thenReturn(prefs);

        PreferencesUpdateRequest request = new PreferencesUpdateRequest();
        request.setNotificationsEnabled(true);
        request.setModelTier("special3");
        request.setTierForce(true);

        StepVerifier.create(controller.updatePreferences(request))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals("en", prefs.getLanguage()); // unchanged
        assertEquals("special3", prefs.getModelTier());
        assertTrue(prefs.isTierForce());
        assertTrue(prefs.isNotificationsEnabled());
    }

    @Test
    void shouldNormalizeAutoModeDefaultTierToNull() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .autoMode(RuntimeConfig.AutoModeConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.AutoModeConfig update = RuntimeConfig.AutoModeConfig.builder()
                .enabled(true)
                .modelTier("default")
                .reflectionModelTier("special2")
                .reflectionTierPriority(true)
                .build();

        StepVerifier.create(controller.updateAutoConfig(update))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        assertNull(captor.getValue().getAutoMode().getModelTier());
        assertEquals("special2", captor.getValue().getAutoMode().getReflectionModelTier());
        assertTrue(Boolean.TRUE.equals(captor.getValue().getAutoMode().getReflectionTierPriority()));
    }

    @Test
    void shouldRejectAutoModeConfigWithUnknownReflectionTier() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .autoMode(RuntimeConfig.AutoModeConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.AutoModeConfig update = RuntimeConfig.AutoModeConfig.builder()
                .enabled(true)
                .reflectionModelTier("turbo")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateAutoConfig(update));

        assertTrue(error.getMessage().contains("autoMode.reflectionModelTier"));
    }

    @Test
    void shouldUpdateTracingConfigWhenValid() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tracing(RuntimeConfig.TracingConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.TracingConfig tracingConfig = RuntimeConfig.TracingConfig.builder()
                .enabled(true)
                .payloadSnapshotsEnabled(true)
                .sessionTraceBudgetMb(256)
                .maxSnapshotSizeKb(512)
                .maxSnapshotsPerSpan(5)
                .maxTracesPerSession(30)
                .captureInboundPayloads(true)
                .captureOutboundPayloads(false)
                .captureToolPayloads(true)
                .captureLlmPayloads(true)
                .build();

        StepVerifier.create(controller.updateTracingConfig(tracingConfig))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig.TracingConfig saved = captor.getValue().getTracing();
        assertTrue(saved.getEnabled());
        assertTrue(saved.getPayloadSnapshotsEnabled());
        assertEquals(256, saved.getSessionTraceBudgetMb());
        assertEquals(512, saved.getMaxSnapshotSizeKb());
        assertEquals(5, saved.getMaxSnapshotsPerSpan());
        assertEquals(30, saved.getMaxTracesPerSession());
        assertTrue(saved.getCaptureInboundPayloads());
        assertFalse(saved.getCaptureOutboundPayloads());
        assertTrue(saved.getCaptureToolPayloads());
        assertTrue(saved.getCaptureLlmPayloads());
    }

    @Test
    void shouldRejectTracingBudgetBelowOneMegabyte() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tracing(RuntimeConfig.TracingConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.TracingConfig tracingConfig = RuntimeConfig.TracingConfig.builder()
                .enabled(true)
                .sessionTraceBudgetMb(0)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateTracingConfig(tracingConfig));
        assertEquals("tracing.sessionTraceBudgetMb must be between 1 and 1024", error.getMessage());
    }

    @Test
    void shouldRejectRuntimeConfigWithMultipleTelegramAllowedUsers() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .authMode("invite_only")
                        .allowedUsers(List.of("1001", "1002"))
                        .build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertTrue(error.getMessage().contains("supports only one invited user"));
    }

    @Test
    void shouldUpdateHiveConfigWhenWritable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder().build();
        RuntimeConfig responseConfig = RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .enabled(true)
                        .serverUrl("https://hive.example.com")
                        .displayName("Builder")
                        .hostLabel("lab-a")
                        .autoConnect(true)
                        .managedByProperties(false)
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(responseConfig);

        RuntimeConfig.HiveConfig update = RuntimeConfig.HiveConfig.builder()
                .enabled(true)
                .serverUrl("https://hive.example.com")
                .displayName("Builder")
                .hostLabel("lab-a")
                .autoConnect(true)
                .managedByProperties(false)
                .build();

        StepVerifier.create(controller.updateHiveConfig(update))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    RuntimeConfig body = response.getBody();
                    assertNotNull(body);
                    assertEquals("https://hive.example.com", body.getHive().getServerUrl());
                })
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        assertEquals("https://hive.example.com", captor.getValue().getHive().getServerUrl());
        assertTrue(captor.getValue().getHive().getAutoConnect());
    }

    @Test
    void shouldMergeAndNormalizeModelRegistryConfigWhenUpdatingRuntimeConfig() {
        RuntimeConfig current = RuntimeConfig.builder()
                .modelRegistry(RuntimeConfig.ModelRegistryConfig.builder()
                        .repositoryUrl("https://github.com/alexk-dev/golemcore-models")
                        .branch("main")
                        .build())
                .build();
        RuntimeConfig responseConfig = RuntimeConfig.builder()
                .modelRegistry(RuntimeConfig.ModelRegistryConfig.builder()
                        .repositoryUrl("https://github.com/alexk-dev/golemcore-models")
                        .branch("main")
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(responseConfig);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .modelRegistry(RuntimeConfig.ModelRegistryConfig.builder()
                        .repositoryUrl("https://github.com/alexk-dev/golemcore-models")
                        .branch("  ")
                        .build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        assertEquals("https://github.com/alexk-dev/golemcore-models",
                captor.getValue().getModelRegistry().getRepositoryUrl());
        assertEquals("main", captor.getValue().getModelRegistry().getBranch());
    }

    @Test
    void shouldPreserveCurrentModelRegistryConfigWhenIncomingSectionIsEmpty() {
        RuntimeConfig.ModelRegistryConfig currentModelRegistry = RuntimeConfig.ModelRegistryConfig.builder()
                .repositoryUrl("https://github.com/alexk-dev/golemcore-models")
                .branch("main")
                .build();
        RuntimeConfig current = RuntimeConfig.builder()
                .modelRegistry(currentModelRegistry)
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .modelRegistry(new RuntimeConfig.ModelRegistryConfig())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        assertEquals(currentModelRegistry, captor.getValue().getModelRegistry());
    }

    @Test
    void shouldMergeSelfEvolvingConfigWhenUpdatingRuntimeConfig() {
        RuntimeConfig current = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(false)
                        .tracePayloadOverride(true)
                        .build())
                .build();
        RuntimeConfig responseConfig = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(true)
                        .tracePayloadOverride(true)
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(responseConfig);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(true)
                        .tracePayloadOverride(true)
                        .build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        assertTrue(Boolean.TRUE.equals(captor.getValue().getSelfEvolving().getEnabled()));
    }

    @Test
    void shouldAllowDisablingSelfEvolvingWhenUpdatingRuntimeConfig() {
        RuntimeConfig current = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(true)
                        .tracePayloadOverride(true)
                        .build())
                .build();
        RuntimeConfig responseConfig = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(false)
                        .tracePayloadOverride(true)
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(responseConfig);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(false)
                        .tracePayloadOverride(true)
                        .build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        assertFalse(Boolean.TRUE.equals(captor.getValue().getSelfEvolving().getEnabled()));
    }

    @Test
    void shouldRejectRuntimeConfigWhenModelRegistryRepositoryUrlIsInvalid() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .modelRegistry(RuntimeConfig.ModelRegistryConfig.builder()
                        .repositoryUrl("not-a-url")
                        .branch("main")
                        .build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertEquals("modelRegistry.repositoryUrl must be a valid http(s) URL", error.getMessage());
    }

    @Test
    void shouldRejectHiveUpdatesWhenManagedByProperties() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .enabled(true)
                        .serverUrl("https://managed.example.com")
                        .managedByProperties(true)
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.isHiveManagedByProperties()).thenReturn(true);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .enabled(false)
                        .serverUrl("https://other.example.com")
                        .managedByProperties(false)
                        .build())
                .build();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.updateRuntimeConfig(incoming).block());
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("Hive settings are managed"));
    }

    @Test
    void shouldUpdateTierOverrides() {
        UserPreferences prefs = new UserPreferences();
        when(preferencesService.getPreferences()).thenReturn(prefs);

        Map<String, SettingsResponse.TierOverrideDto> overrides = Map.of(
                "special2", SettingsResponse.TierOverrideDto.builder()
                        .model("gpt-4o")
                        .reasoning("medium")
                        .build());

        StepVerifier.create(controller.updateTierOverrides(overrides))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(preferencesService).savePreferences(prefs);
        assertNotNull(prefs.getTierOverrides());
        assertEquals("gpt-4o", prefs.getTierOverrides().get("special2").getModel());
    }

    @Test
    void shouldRejectWebhookConfigWithUnknownModelTier() {
        UserPreferences prefs = new UserPreferences();
        when(preferencesService.getPreferences()).thenReturn(prefs);

        UserPreferences.WebhookConfig webhookConfig = UserPreferences.WebhookConfig.builder()
                .enabled(true)
                .mappings(List.of(UserPreferences.HookMapping.builder()
                        .name("deploy")
                        .action("agent")
                        .model("turbo")
                        .build()))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateWebhooksConfig(webhookConfig));
        assertEquals("webhooks.mapping.model must be a known tier id", error.getMessage());
    }

    @Test
    void shouldRejectLlmProviderRemovalWhenUsedByModelRouter() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .balancedModel("openai/gpt-5.1")
                        .build())
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x")).build(),
                                "anthropic", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("y")).build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "anthropic", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("y")).build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateLlmConfig(update));
        assertTrue(error.getMessage().contains("Cannot remove provider 'openai'"));
    }

    @Test
    void shouldRejectLlmProviderRemovalWhenUsedBySpecialTier() {
        RuntimeConfig.ModelRouterConfig modelRouter = RuntimeConfig.ModelRouterConfig.builder().build();
        modelRouter.setTierBinding("special2", RuntimeConfig.TierBinding.builder()
                .model("openai/special2-model")
                .reasoning("none")
                .build());
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(modelRouter)
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x")).build(),
                                "anthropic", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("y")).build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "anthropic", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("y")).build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateLlmConfig(update));
        assertTrue(error.getMessage().contains("Cannot remove provider 'openai'"));
    }

    @Test
    void shouldRejectLlmProviderRemovalWhenAliasOnlyModelIsUsedByRouter() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .balancedModel("gpt-5.1")
                        .build())
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x")).build(),
                                "anthropic", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("y")).build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(modelSelectionService.resolveProviderForModel("gpt-5.1")).thenReturn("openai");

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "anthropic", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("y")).build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateLlmConfig(update));
        assertTrue(error.getMessage().contains("Cannot remove provider 'openai'"));
    }

    @Test
    void shouldRejectModelRouterUpdateWhenTierModelIsUnknown() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x")).build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ModelRouterConfig modelRouter = RuntimeConfig.ModelRouterConfig.builder().build();
        modelRouter.setTierBinding("special1", RuntimeConfig.TierBinding.builder()
                .model("custom/ghost-model")
                .reasoning("none")
                .build());
        when(modelSelectionService.validateModel("custom/ghost-model", List.of("openai")))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "model.not.found"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateModelRouterConfig(modelRouter));
        assertTrue(error.getMessage().contains("special1"));
        assertTrue(error.getMessage().contains("custom/ghost-model"));
    }

    @Test
    void shouldRejectModelRouterUpdateWhenTierProviderIsNotConfigured() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x")).build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ModelRouterConfig modelRouter = RuntimeConfig.ModelRouterConfig.builder().build();
        modelRouter.setTierBinding("special2", RuntimeConfig.TierBinding.builder()
                .model("anthropic/claude-sonnet-4")
                .reasoning("none")
                .build());
        when(modelSelectionService.validateModel("anthropic/claude-sonnet-4", List.of("openai")))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "provider.not.configured"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateModelRouterConfig(modelRouter));
        assertTrue(error.getMessage().contains("special2"));
        assertTrue(error.getMessage().contains("provider"));
    }

    @Test
    void shouldRejectInvalidLlmProviderName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "Bad Name", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x")).build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateLlmConfig(update));
        assertTrue(error.getMessage().contains("llm.providers keys must be lowercase"));
    }

    @Test
    void shouldRejectInvalidApiType() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("x"))
                .apiType("invalid")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.addLlmProvider("test", providerConfig));
        assertTrue(error.getMessage().contains("apiType must be one of"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "openai", "anthropic", "gemini" })
    void shouldAcceptValidApiTypes(String apiType) {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("x"))
                .apiType(apiType)
                .build();

        assertDoesNotThrow(() -> controller.addLlmProvider("test", providerConfig));
    }

    @ParameterizedTest
    @ValueSource(strings = { "OPENAI", "Anthropic", "GEMINI" })
    void shouldAcceptCaseInsensitiveApiTypes(String apiType) {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("x"))
                .apiType(apiType)
                .build();

        assertDoesNotThrow(() -> controller.addLlmProvider("test", providerConfig));
    }

    @Test
    void shouldAcceptNullApiType() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("x"))
                .apiType(null)
                .build();

        assertDoesNotThrow(() -> controller.addLlmProvider("test", providerConfig));
    }

    @Test
    void shouldAcceptBlankApiType() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("x"))
                .apiType("   ")
                .build();

        assertDoesNotThrow(() -> controller.addLlmProvider("test", providerConfig));
    }

    @Test
    void shouldRejectInvalidApiTypeInLlmConfigUpdate() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "openai", RuntimeConfig.LlmProviderConfig.builder()
                                .apiKey(Secret.of("x"))
                                .apiType("invalid")
                                .build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateLlmConfig(update));
        assertTrue(error.getMessage().contains("apiType must be one of"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "OPENAI", "Anthropic", "GEMINI", "   " })
    void shouldAcceptCaseInsensitiveAndBlankApiTypeInLlmConfigUpdate(String apiType) {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "openai", RuntimeConfig.LlmProviderConfig.builder()
                                .apiKey(Secret.of("x"))
                                .apiType(apiType)
                                .build())))
                .build();

        assertDoesNotThrow(() -> controller.updateLlmConfig(update));
    }

    @Test
    void shouldRejectInvalidLlmProviderTimeout() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "openai",
                        RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x"))
                                .requestTimeoutSeconds(0).build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateLlmConfig(update));
        assertTrue(error.getMessage().contains("requestTimeoutSeconds must be between 1 and 3600"));
    }

    @Test
    void shouldCreateShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable variable = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("API_TOKEN")
                .value("abc123")
                .build();

        StepVerifier.create(controller.createShellEnvironmentVariable(variable))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals(1, runtimeConfig.getTools().getShellEnvironmentVariables().size());
        assertEquals("API_TOKEN", runtimeConfig.getTools().getShellEnvironmentVariables().get(0).getName());
        assertEquals("abc123", runtimeConfig.getTools().getShellEnvironmentVariables().get(0).getValue());
        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
    }

    @Test
    void shouldRejectDuplicateShellEnvironmentVariableName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("API_TOKEN")
                                        .value("v1")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable duplicate = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("API_TOKEN")
                .value("v2")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(duplicate));
        assertTrue(error.getMessage().contains("duplicate name"));
    }

    @Test
    void shouldUpdateShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("API_TOKEN")
                                        .value("old")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable update = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("API_TOKEN")
                .value("new-value")
                .build();

        StepVerifier.create(controller.updateShellEnvironmentVariable("API_TOKEN", update))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals("new-value", runtimeConfig.getTools().getShellEnvironmentVariables().get(0).getValue());
        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
    }

    @Test
    void shouldRenameShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("OLD_NAME")
                                        .value("value")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable update = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("NEW_NAME")
                .value("value")
                .build();

        StepVerifier.create(controller.updateShellEnvironmentVariable("OLD_NAME", update))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals("NEW_NAME", runtimeConfig.getTools().getShellEnvironmentVariables().get(0).getName());
    }

    @Test
    void shouldDeleteShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("DELETE_ME")
                                        .value("value")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        StepVerifier.create(controller.deleteShellEnvironmentVariable("DELETE_ME"))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertTrue(runtimeConfig.getTools().getShellEnvironmentVariables().isEmpty());
        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
    }

    @Test
    void shouldReturnNotFoundWhenDeletingUnknownShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.deleteShellEnvironmentVariable("MISSING_VAR"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void shouldRejectInvalidShellEnvironmentVariableName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable invalid = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("1INVALID")
                .value("value")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(invalid));
        assertTrue(error.getMessage().contains("[A-Za-z_][A-Za-z0-9_]*"));
    }

    @Test
    void shouldRejectReservedShellEnvironmentVariableName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable invalid = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("HOME")
                .value("/tmp")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(invalid));
        assertTrue(error.getMessage().contains("reserved variable"));
    }

    @Test
    void shouldRejectDuplicateShellEnvironmentVariableWhenUpdatingToolsConfig() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ToolsConfig toolsConfig = RuntimeConfig.ToolsConfig.builder()
                .shellEnvironmentVariables(new ArrayList<>(List.of(
                        RuntimeConfig.ShellEnvironmentVariable.builder().name("API_TOKEN").value("v1").build(),
                        RuntimeConfig.ShellEnvironmentVariable.builder().name("API_TOKEN").value("v2").build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateToolsConfig(toolsConfig));
        assertTrue(error.getMessage().contains("duplicate name"));
    }

    @Test
    void shouldRejectDuplicateShellEnvironmentVariableWhenUpdatingRuntimeConfig() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder().name("API_TOKEN").value("v1").build(),
                                RuntimeConfig.ShellEnvironmentVariable.builder().name("API_TOKEN").value("v2")
                                        .build())))
                        .build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertTrue(error.getMessage().contains("duplicate name"));
    }

    @Test
    void shouldAllowRuntimeConfigUpdateWhenToolsIsNull() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder().build();
        incoming.setTools(null);

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldInitializeTelegramSectionWhenBaselineRuntimeConfigIsNull() {
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(null);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();
        assertNotNull(saved.getTelegram());
        assertEquals("invite_only", saved.getTelegram().getAuthMode());
        assertNotNull(saved.getLlm());
        assertNotNull(saved.getModelRouter());
    }

    @Test
    void shouldPreserveExistingToolsSectionWhenIncomingToolsSectionIsEmpty() {
        RuntimeConfig current = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .filesystemEnabled(true)
                        .shellEnabled(true)
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder().build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();
        assertEquals(Boolean.TRUE, saved.getTools().getFilesystemEnabled());
        assertEquals(Boolean.TRUE, saved.getTools().getShellEnabled());
    }

    @Test
    void shouldPreserveUnspecifiedRuntimeSectionsDuringPartialRuntimeUpdate() {
        RuntimeConfig current = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x")).build())))
                        .build())
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .balancedModel("openai/gpt-5.1")
                        .build())
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .filesystemEnabled(true)
                        .build())
                .turn(RuntimeConfig.TurnConfig.builder()
                        .maxLlmCalls(9)
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .turn(RuntimeConfig.TurnConfig.builder()
                        .maxLlmCalls(12)
                        .build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();
        assertEquals(12, saved.getTurn().getMaxLlmCalls());
        assertEquals(Boolean.TRUE, saved.getTools().getFilesystemEnabled());
        assertEquals("openai/gpt-5.1", saved.getModelRouter().getBalancedModel());
        assertTrue(saved.getLlm().getProviders().containsKey("openai"));
    }

    @Test
    void shouldRejectInvalidTurnProgressBatchSize() {
        RuntimeConfig.TurnConfig turnConfig = RuntimeConfig.TurnConfig.builder()
                .progressBatchSize(0)
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder().build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateTurnConfig(turnConfig));

        assertEquals("turn.progressBatchSize must be between 1 and 50", error.getMessage());
    }

    @Test
    void shouldUpdateTurnConfigWithProgressSettings() {
        RuntimeConfig current = RuntimeConfig.builder().turn(new RuntimeConfig.TurnConfig()).build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig.TurnConfig turnConfig = RuntimeConfig.TurnConfig.builder()
                .maxLlmCalls(10)
                .maxToolExecutions(20)
                .deadline("PT30M")
                .progressUpdatesEnabled(true)
                .progressIntentEnabled(true)
                .progressBatchSize(7)
                .progressMaxSilenceSeconds(12)
                .progressSummaryTimeoutMs(9000)
                .build();

        StepVerifier.create(controller.updateTurnConfig(turnConfig))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();
        assertEquals(7, saved.getTurn().getProgressBatchSize());
        assertEquals(12, saved.getTurn().getProgressMaxSilenceSeconds());
        assertEquals(9000, saved.getTurn().getProgressSummaryTimeoutMs());
    }

    @Test
    void shouldGetShellEnvironmentVariablesFromRuntimeConfigForApi() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("API_TOKEN")
                                        .value("abc123")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        StepVerifier.create(controller.getShellEnvironmentVariables())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<ShellEnvironmentVariableDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("API_TOKEN", body.get(0).getName());
                    assertEquals("abc123", body.get(0).getValue());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyShellEnvironmentVariablesWhenToolsMissingInApiConfig() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder().build();
        runtimeConfig.setTools(null);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        StepVerifier.create(controller.getShellEnvironmentVariables())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<ShellEnvironmentVariableDto> body = response.getBody();
                    assertNotNull(body);
                    assertTrue(body.isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void shouldNormalizeShellEnvironmentVariableNameAndNullValueOnCreate() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable variable = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("  API_TOKEN  ")
                .value(null)
                .build();

        StepVerifier.create(controller.createShellEnvironmentVariable(variable))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.ShellEnvironmentVariable stored = runtimeConfig.getTools().getShellEnvironmentVariables().get(0);
        assertEquals("API_TOKEN", stored.getName());
        assertEquals("", stored.getValue());
    }

    @Test
    void shouldRejectNullShellEnvironmentVariablePayload() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(null));
        assertTrue(error.getMessage().contains("item is required"));
    }

    @Test
    void shouldRejectTooLongShellEnvironmentVariableName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        String tooLongName = "A".repeat(129);
        RuntimeConfig.ShellEnvironmentVariable variable = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name(tooLongName)
                .value("value")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(variable));
        assertTrue(error.getMessage().contains("at most 128"));
    }

    @Test
    void shouldRejectTooLongShellEnvironmentVariableValue() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        String tooLongValue = "a".repeat(8193);
        RuntimeConfig.ShellEnvironmentVariable variable = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("API_TOKEN")
                .value(tooLongValue)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(variable));
        assertTrue(error.getMessage().contains("at most 8192"));
    }

    @Test
    void shouldUsePathVariableNameWhenUpdatingShellEnvironmentVariableWithBlankBodyName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("API_TOKEN")
                                        .value("old-value")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable update = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("   ")
                .value("new-value")
                .build();

        StepVerifier.create(controller.updateShellEnvironmentVariable("API_TOKEN", update))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.ShellEnvironmentVariable stored = runtimeConfig.getTools().getShellEnvironmentVariables().get(0);
        assertEquals("API_TOKEN", stored.getName());
        assertEquals("new-value", stored.getValue());
    }

    @Test
    void shouldRejectShellEnvironmentVariableRenameCollisionOnUpdate() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder().name("FIRST").value("a").build(),
                                RuntimeConfig.ShellEnvironmentVariable.builder().name("SECOND").value("b").build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable update = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("SECOND")
                .value("updated")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateShellEnvironmentVariable("FIRST", update));
        assertTrue(error.getMessage().contains("duplicate name"));
    }

    @Test
    void shouldReturnNotFoundWhenUpdatingMissingShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable update = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("MISSING")
                .value("value")
                .build();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.updateShellEnvironmentVariable("MISSING", update));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void shouldUpdateMemoryConfigWhenValid() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .enabled(true)
                .softPromptBudgetTokens(1800)
                .maxPromptBudgetTokens(3500)
                .workingTopK(6)
                .episodicTopK(8)
                .semanticTopK(6)
                .proceduralTopK(4)
                .promotionEnabled(true)
                .promotionMinConfidence(0.8)
                .decayEnabled(true)
                .decayDays(30)
                .retrievalLookbackDays(21)
                .codeAwareExtractionEnabled(true)
                .build();

        StepVerifier.create(controller.updateMemoryConfig(memoryConfig))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
        assertEquals(3500, runtimeConfig.getMemory().getMaxPromptBudgetTokens());
    }

    @Test
    void shouldUpdateMemoryConfigWhenDisclosureSettingsValid() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .enabled(true)
                .softPromptBudgetTokens(1800)
                .maxPromptBudgetTokens(3500)
                .disclosure(RuntimeConfig.MemoryDisclosureConfig.builder()
                        .mode("summary")
                        .promptStyle("balanced")
                        .toolExpansionEnabled(true)
                        .disclosureHintsEnabled(true)
                        .detailMinScore(0.88)
                        .build())
                .reranking(RuntimeConfig.MemoryRerankingConfig.builder()
                        .enabled(true)
                        .profile("aggressive")
                        .build())
                .diagnostics(RuntimeConfig.MemoryDiagnosticsConfig.builder()
                        .verbosity("basic")
                        .build())
                .build();

        StepVerifier.create(controller.updateMemoryConfig(memoryConfig))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
        assertEquals("summary", runtimeConfig.getMemory().getDisclosure().getMode());
        assertEquals("balanced", runtimeConfig.getMemory().getDisclosure().getPromptStyle());
        assertEquals("aggressive", runtimeConfig.getMemory().getReranking().getProfile());
        assertEquals("basic", runtimeConfig.getMemory().getDiagnostics().getVerbosity());
    }

    @Test
    void shouldRejectMemoryConfigWhenMaxBudgetIsLessThanSoftBudget() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .softPromptBudgetTokens(2000)
                .maxPromptBudgetTokens(1000)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateMemoryConfig(memoryConfig));
        assertTrue(error.getMessage().contains("memory.maxPromptBudgetTokens"));
    }

    @Test
    void shouldRejectMemoryConfigWhenTopKOutOfRange() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .semanticTopK(99)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateMemoryConfig(memoryConfig));
        assertTrue(error.getMessage().contains("memory.semanticTopK"));
    }

    @Test
    void shouldRejectMemoryConfigWhenPromotionConfidenceOutOfRange() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .promotionMinConfidence(1.5)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateMemoryConfig(memoryConfig));
        assertTrue(error.getMessage().contains("memory.promotionMinConfidence"));
    }

    @Test
    void shouldRejectMemoryConfigWhenRetrievalLookbackOutOfRange() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .retrievalLookbackDays(999)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateMemoryConfig(memoryConfig));
        assertTrue(error.getMessage().contains("memory.retrievalLookbackDays"));
    }

    @Test
    void shouldRejectMemoryConfigWhenDisclosureDetailMinScoreOutOfRange() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .disclosure(RuntimeConfig.MemoryDisclosureConfig.builder()
                        .mode("summary")
                        .promptStyle("balanced")
                        .detailMinScore(1.5)
                        .build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateMemoryConfig(memoryConfig));
        assertTrue(error.getMessage().contains("memory.disclosure.detailMinScore"));
    }

    @Test
    void shouldRejectMemoryConfigWhenRerankingProfileInvalid() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .reranking(RuntimeConfig.MemoryRerankingConfig.builder()
                        .enabled(true)
                        .profile("turbo")
                        .build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateMemoryConfig(memoryConfig));
        assertTrue(error.getMessage().contains("memory.reranking.profile"));
    }

    @Test
    void shouldRejectRuntimeConfigUpdateWhenMemoryConfigIsInvalid() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .memory(RuntimeConfig.MemoryConfig.builder()
                        .softPromptBudgetTokens(1000)
                        .maxPromptBudgetTokens(500)
                        .build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertTrue(error.getMessage().contains("memory.maxPromptBudgetTokens"));
    }

    @Test
    void shouldAllowWhisperAsBothSttAndTtsWithoutLegacyUrl() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("whisper")
                .ttsProvider("whisper")
                .build();

        StepVerifier.create(controller.updateVoiceConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.VoiceConfig saved = runtimeConfig.getVoice();
        assertEquals("golemcore/whisper", saved.getSttProvider());
        assertEquals("golemcore/whisper", saved.getTtsProvider());
        assertNull(saved.getWhisperSttUrl());
    }

    @Test
    void shouldRejectUnsupportedTtsProvider() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("elevenlabs")
                .ttsProvider("google")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateVoiceConfig(incoming));
        assertTrue(error.getMessage().contains("voice.ttsProvider must resolve to a loaded TTS provider"));
    }

    @Test
    void shouldRejectUnloadedVoiceProvidersEvenWhenCanonicalIdsAreUsed() {
        SettingsController unloadedController = createController(new SttProviderRegistry(), new TtsProviderRegistry());
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("golemcore/elevenlabs")
                .ttsProvider("golemcore/elevenlabs")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> unloadedController.updateVoiceConfig(incoming));
        assertTrue(error.getMessage().contains("voice.sttProvider must resolve to a loaded STT provider"));
    }

    @Test
    void shouldPreserveWhisperApiKeyWhenNotProvidedOnUpdate() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .apiKey(Secret.of("eleven"))
                        .sttProvider("whisper")
                        .whisperSttUrl("http://localhost:5092")
                        .whisperSttApiKey(Secret.of("whisper-secret"))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("whisper")
                .whisperSttUrl("http://localhost:5092")
                .build();

        StepVerifier.create(controller.updateVoiceConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.VoiceConfig saved = runtimeConfig.getVoice();
        assertEquals("whisper-secret", saved.getWhisperSttApiKey().getValue());
        assertEquals("golemcore/whisper", saved.getSttProvider());
        assertEquals("golemcore/elevenlabs", saved.getTtsProvider());
        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
    }

    @Test
    void shouldNormalizeVoiceProviderValuesOnUpdate() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .apiKey(Secret.of("eleven"))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("  WHISPER  ")
                .ttsProvider("  WHISPER  ")
                .build();

        StepVerifier.create(controller.updateVoiceConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.VoiceConfig saved = runtimeConfig.getVoice();
        assertEquals("golemcore/whisper", saved.getSttProvider());
        assertEquals("golemcore/whisper", saved.getTtsProvider());
    }

    @Test
    void shouldRejectUnsupportedSttProvider() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("google")
                .ttsProvider("elevenlabs")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateVoiceConfig(incoming));
        assertTrue(error.getMessage().contains("voice.sttProvider must resolve to a loaded STT provider"));
    }

    @Test
    void shouldAllowRuntimeConfigUpdateWhenLegacyWhisperUrlIsInvalid() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .whisperSttUrl("ftp://localhost:5092")
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("whisper")
                        .ttsProvider("whisper")
                        .build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();
        assertEquals("golemcore/whisper", saved.getVoice().getSttProvider());
        assertEquals("golemcore/whisper", saved.getVoice().getTtsProvider());
    }

    @Test
    void shouldNormalizeBlankWhisperUrlToNullWhenSttIsElevenLabs() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("elevenlabs")
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("elevenlabs")
                .ttsProvider("elevenlabs")
                .whisperSttUrl("   ")
                .build();

        StepVerifier.create(controller.updateVoiceConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.VoiceConfig saved = runtimeConfig.getVoice();
        assertNull(saved.getWhisperSttUrl());
        assertEquals("golemcore/elevenlabs", saved.getSttProvider());
        assertEquals("golemcore/elevenlabs", saved.getTtsProvider());
    }

    @Test
    void shouldAllowDisabledVoiceConfigWhenNoVoiceProvidersAreLoaded() {
        SettingsController unloadedController = createController(new SttProviderRegistry(), new TtsProviderRegistry());
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .enabled(false)
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .enabled(false)
                .build();

        StepVerifier.create(unloadedController.updateVoiceConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.VoiceConfig saved = runtimeConfig.getVoice();
        assertNull(saved.getSttProvider());
        assertNull(saved.getTtsProvider());
    }

    @Test
    void shouldPreserveWhisperApiKeyDuringRuntimeUpdateWhenNotProvided() {
        RuntimeConfig current = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .balancedModel("openai/gpt-5.1")
                        .build())
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder()
                                        .apiKey(Secret.of("openai-secret"))
                                        .build())))
                        .build())
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("whisper")
                        .ttsProvider("elevenlabs")
                        .whisperSttUrl("http://localhost:5092")
                        .whisperSttApiKey(Secret.of("whisper-secret"))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("whisper")
                        .whisperSttUrl("http://localhost:5092")
                        .build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();
        assertEquals("whisper-secret", saved.getVoice().getWhisperSttApiKey().getValue());
        assertEquals("openai/gpt-5.1", saved.getModelRouter().getBalancedModel());
        assertEquals("openai-secret", saved.getLlm().getProviders().get("openai").getApiKey().getValue());
    }

    @Test
    void shouldAllowWhisperVoiceConfigInRuntimeUpdate() {
        RuntimeConfig current = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("whisper")
                        .ttsProvider("whisper")
                        .build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();
        assertEquals("golemcore/whisper", saved.getVoice().getSttProvider());
        assertEquals("golemcore/whisper", saved.getVoice().getTtsProvider());
    }

    @Test
    void shouldRejectInvalidCompactionTriggerMode() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .compaction(RuntimeConfig.CompactionConfig.builder()
                        .triggerMode("magic")
                        .build())
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertTrue(exception.getMessage().contains("compaction.triggerMode must be one of"));
        assertTrue(exception.getMessage().contains("model_ratio"));
        assertTrue(exception.getMessage().contains("token_threshold"));
    }

    @Test
    void shouldRejectInvalidCompactionModelThresholdRatio() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .compaction(RuntimeConfig.CompactionConfig.builder()
                        .modelThresholdRatio(1.2d)
                        .build())
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertEquals("compaction.modelThresholdRatio must be between 0 and 1", exception.getMessage());
    }

    @Test
    void shouldRejectZeroCompactionModelThresholdRatio() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .compaction(RuntimeConfig.CompactionConfig.builder()
                        .modelThresholdRatio(0.0d)
                        .build())
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertEquals("compaction.modelThresholdRatio must be between 0 and 1", exception.getMessage());
    }

    @Test
    void shouldNormalizeBlankCompactionModeAndMissingRatioToDefaults() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .compaction(RuntimeConfig.CompactionConfig.builder()
                        .triggerMode("   ")
                        .modelThresholdRatio(null)
                        .build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();
        assertEquals("model_ratio", saved.getCompaction().getTriggerMode());
        assertEquals(0.95d, saved.getCompaction().getModelThresholdRatio(), 0.0001d);
    }

    @Test
    void shouldNormalizeCompactionTriggerModeCaseAndWhitespace() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .compaction(RuntimeConfig.CompactionConfig.builder()
                        .triggerMode(" Token_Threshold ")
                        .modelThresholdRatio(0.8d)
                        .build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();
        assertEquals("token_threshold", saved.getCompaction().getTriggerMode());
        assertEquals(0.8d, saved.getCompaction().getModelThresholdRatio(), 0.0001d);
    }

    @Test
    void shouldUpdatePlanConfigWhenValid() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .plan(RuntimeConfig.PlanConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.PlanConfig planConfig = RuntimeConfig.PlanConfig.builder()
                .enabled(true)
                .maxPlans(8)
                .maxStepsPerPlan(120)
                .stopOnFailure(false)
                .build();

        StepVerifier.create(controller.updatePlanConfig(planConfig))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
        assertEquals(8, runtimeConfig.getPlan().getMaxPlans());
        assertEquals(120, runtimeConfig.getPlan().getMaxStepsPerPlan());
        assertEquals(Boolean.FALSE, runtimeConfig.getPlan().getStopOnFailure());
    }

    @Test
    void shouldUpdateTelemetryConfigWhenValid() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .telemetry(RuntimeConfig.TelemetryConfig.builder().enabled(true).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.TelemetryConfig telemetryConfig = RuntimeConfig.TelemetryConfig.builder()
                .enabled(false)
                .build();

        StepVerifier.create(controller.updateTelemetryConfig(telemetryConfig))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
        assertEquals(Boolean.FALSE, runtimeConfig.getTelemetry().getEnabled());
    }

    @Test
    void shouldRejectPlanConfigWhenMaxPlansOutOfRange() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .plan(RuntimeConfig.PlanConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.PlanConfig planConfig = RuntimeConfig.PlanConfig.builder()
                .enabled(true)
                .maxPlans(0)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updatePlanConfig(planConfig));
        assertTrue(error.getMessage().contains("plan.maxPlans"));
    }

    @Test
    void shouldPreserveExistingPlanSectionDuringPartialRuntimeUpdate() {
        RuntimeConfig current = RuntimeConfig.builder()
                .plan(RuntimeConfig.PlanConfig.builder()
                        .enabled(true)
                        .maxPlans(9)
                        .maxStepsPerPlan(70)
                        .stopOnFailure(false)
                        .build())
                .turn(RuntimeConfig.TurnConfig.builder().maxLlmCalls(9).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .turn(RuntimeConfig.TurnConfig.builder().maxLlmCalls(12).build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();
        assertEquals(Boolean.TRUE, saved.getPlan().getEnabled());
        assertEquals(9, saved.getPlan().getMaxPlans());
        assertEquals(70, saved.getPlan().getMaxStepsPerPlan());
        assertEquals(Boolean.FALSE, saved.getPlan().getStopOnFailure());
    }

    // ==================== MCP Catalog CRUD ====================

    @Test
    void shouldGetMcpCatalog() {
        List<RuntimeConfig.McpCatalogEntry> catalog = List.of(
                RuntimeConfig.McpCatalogEntry.builder().name("github").command("npx github").build());
        when(runtimeConfigService.getMcpCatalog()).thenReturn(catalog);

        StepVerifier.create(controller.getMcpCatalog())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(1, response.getBody().size());
                    assertEquals("github", response.getBody().get(0).getName());
                })
                .verifyComplete();
    }

    @Test
    void shouldAddMcpCatalogEntry() {
        when(runtimeConfigService.getMcpCatalog()).thenReturn(new ArrayList<>());

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx github-mcp")
                .build();

        StepVerifier.create(controller.addMcpCatalogEntry(entry))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(runtimeConfigService).addMcpCatalogEntry(entry);
    }

    @Test
    void shouldNormalizeNameOnAdd() {
        when(runtimeConfigService.getMcpCatalog()).thenReturn(new ArrayList<>());

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("  GitHub  ")
                .command("npx github-mcp")
                .build();

        StepVerifier.create(controller.addMcpCatalogEntry(entry))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals("github", entry.getName());
    }

    @Test
    void shouldRejectDuplicateCatalogEntryName() {
        List<RuntimeConfig.McpCatalogEntry> existing = List.of(
                RuntimeConfig.McpCatalogEntry.builder().name("github").command("npx github").build());
        when(runtimeConfigService.getMcpCatalog()).thenReturn(existing);

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx another-github")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.addMcpCatalogEntry(entry));
        assertTrue(error.getMessage().contains("already exists"));
    }

    @Test
    void shouldRejectCatalogEntryWithNullName() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .command("npx test")
                .build();

        assertThrows(IllegalArgumentException.class, () -> controller.addMcpCatalogEntry(entry));
    }

    @Test
    void shouldRejectCatalogEntryWithBlankName() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("   ")
                .command("npx test")
                .build();

        assertThrows(IllegalArgumentException.class, () -> controller.addMcpCatalogEntry(entry));
    }

    @ParameterizedTest
    @ValueSource(strings = { "-invalid", "_bad", "has spaces", "special!chars" })
    void shouldRejectCatalogEntryWithInvalidNamePattern(String name) {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name(name)
                .command("npx test")
                .build();

        assertThrows(IllegalArgumentException.class, () -> controller.addMcpCatalogEntry(entry));
    }

    @ParameterizedTest
    @ValueSource(strings = { "github", "slack-mcp", "my-server-2", "0test" })
    void shouldAcceptCatalogEntryWithValidNamePattern(String name) {
        when(runtimeConfigService.getMcpCatalog()).thenReturn(new ArrayList<>());

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name(name)
                .command("npx test")
                .build();

        assertDoesNotThrow(() -> controller.addMcpCatalogEntry(entry));
    }

    @Test
    void shouldRejectCatalogEntryWithNullCommand() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .build();

        assertThrows(IllegalArgumentException.class, () -> controller.addMcpCatalogEntry(entry));
    }

    @Test
    void shouldRejectCatalogEntryWithBlankCommand() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("   ")
                .build();

        assertThrows(IllegalArgumentException.class, () -> controller.addMcpCatalogEntry(entry));
    }

    @Test
    void shouldRejectCatalogEntryWithStartupTimeoutOutOfRange() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx test")
                .startupTimeoutSeconds(0)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.addMcpCatalogEntry(entry));
        assertTrue(error.getMessage().contains("startupTimeoutSeconds"));
    }

    @Test
    void shouldRejectCatalogEntryWithStartupTimeoutAboveMax() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx test")
                .startupTimeoutSeconds(301)
                .build();

        assertThrows(IllegalArgumentException.class, () -> controller.addMcpCatalogEntry(entry));
    }

    @Test
    void shouldRejectCatalogEntryWithIdleTimeoutOutOfRange() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx test")
                .idleTimeoutMinutes(0)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.addMcpCatalogEntry(entry));
        assertTrue(error.getMessage().contains("idleTimeoutMinutes"));
    }

    @Test
    void shouldRejectCatalogEntryWithIdleTimeoutAboveMax() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx test")
                .idleTimeoutMinutes(121)
                .build();

        assertThrows(IllegalArgumentException.class, () -> controller.addMcpCatalogEntry(entry));
    }

    @Test
    void shouldAcceptCatalogEntryWithNullTimeouts() {
        when(runtimeConfigService.getMcpCatalog()).thenReturn(new ArrayList<>());

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx test")
                .startupTimeoutSeconds(null)
                .idleTimeoutMinutes(null)
                .build();

        assertDoesNotThrow(() -> controller.addMcpCatalogEntry(entry));
    }

    @Test
    void shouldUpdateMcpCatalogEntry() {
        when(runtimeConfigService.updateMcpCatalogEntry(anyString(),
                org.mockito.ArgumentMatchers.any(RuntimeConfig.McpCatalogEntry.class))).thenReturn(true);

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx updated-github")
                .build();

        StepVerifier.create(controller.updateMcpCatalogEntry("github", entry))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldReturnNotFoundWhenUpdatingNonexistentCatalogEntry() {
        when(runtimeConfigService.updateMcpCatalogEntry(anyString(),
                org.mockito.ArgumentMatchers.any(RuntimeConfig.McpCatalogEntry.class))).thenReturn(false);

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("nonexistent")
                .command("npx test")
                .build();

        assertThrows(ResponseStatusException.class,
                () -> controller.updateMcpCatalogEntry("nonexistent", entry));
    }

    @Test
    void shouldNormalizeNameOnUpdate() {
        when(runtimeConfigService.updateMcpCatalogEntry(anyString(),
                org.mockito.ArgumentMatchers.any(RuntimeConfig.McpCatalogEntry.class))).thenReturn(true);

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx updated")
                .build();

        StepVerifier.create(controller.updateMcpCatalogEntry("  GitHub  ", entry))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(runtimeConfigService).updateMcpCatalogEntry(org.mockito.ArgumentMatchers.eq("github"),
                org.mockito.ArgumentMatchers.any(RuntimeConfig.McpCatalogEntry.class));
    }

    @Test
    void shouldDeleteMcpCatalogEntry() {
        when(runtimeConfigService.removeMcpCatalogEntry("github")).thenReturn(true);

        StepVerifier.create(controller.removeMcpCatalogEntry("github"))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldReturnNotFoundWhenDeletingNonexistentCatalogEntry() {
        when(runtimeConfigService.removeMcpCatalogEntry("nonexistent")).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> controller.removeMcpCatalogEntry("nonexistent"));
    }

    @Test
    void shouldNormalizeNameOnDelete() {
        when(runtimeConfigService.removeMcpCatalogEntry("github")).thenReturn(true);

        StepVerifier.create(controller.removeMcpCatalogEntry("  GitHub  "))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(runtimeConfigService).removeMcpCatalogEntry("github");
    }

    @Test
    void shouldPreserveCatalogWhenUpdatingGlobalMcpSettings() {
        List<RuntimeConfig.McpCatalogEntry> existingCatalog = new ArrayList<>();
        existingCatalog.add(RuntimeConfig.McpCatalogEntry.builder()
                .name("github").command("npx github").build());

        RuntimeConfig.McpConfig existingMcp = new RuntimeConfig.McpConfig();
        existingMcp.setEnabled(true);
        existingMcp.setCatalog(existingCatalog);

        RuntimeConfig current = RuntimeConfig.builder().mcp(existingMcp).build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig.McpConfig incomingMcp = new RuntimeConfig.McpConfig();
        incomingMcp.setEnabled(false);
        incomingMcp.setCatalog(null);

        StepVerifier.create(controller.updateMcpConfig(incomingMcp))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertNotNull(incomingMcp.getCatalog());
        assertEquals(1, incomingMcp.getCatalog().size());
        assertEquals("github", incomingMcp.getCatalog().get(0).getName());
    }

    @Test
    void shouldGetRuntimeConfigFromFacade() {
        RuntimeConfig apiView = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(apiView);

        StepVerifier.create(controller.getRuntimeConfig())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(apiView, response.getBody());
                })
                .verifyComplete();
    }

    @Test
    void shouldDelegateRuntimeEndpointUpdates() {
        RuntimeConfig current = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of("openai",
                                RuntimeConfig.LlmProviderConfig.builder().build())))
                        .build())
                .build();
        RuntimeConfig apiView = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(apiView);
        when(runtimeConfigService.removeLlmProvider("openai")).thenReturn(true);
        when(preferencesService.getPreferences()).thenReturn(new UserPreferences());

        StepVerifier.create(controller.updateModelRouterConfig(RuntimeConfig.ModelRouterConfig.builder().build()))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
        StepVerifier.create(controller.updateLlmProvider("openai", RuntimeConfig.LlmProviderConfig.builder()
                .requestTimeoutSeconds(30)
                .build()))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
        StepVerifier.create(controller.removeLlmProvider("openai"))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
        StepVerifier.create(controller.updateToolsConfig(RuntimeConfig.ToolsConfig.builder().build()))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
        StepVerifier.create(controller.updateSkillsConfig(RuntimeConfig.SkillsConfig.builder().build()))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
        StepVerifier.create(controller.updateUsageConfig(RuntimeConfig.UsageConfig.builder().build()))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
        StepVerifier.create(controller.updateTelemetryConfig(RuntimeConfig.TelemetryConfig.builder().build()))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
        StepVerifier.create(controller.updateWebhooksConfig(UserPreferences.WebhookConfig.builder().build()))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDelegateAdvancedConfigUpdate() throws Exception {
        RuntimeConfig current = RuntimeConfig.builder().build();
        RuntimeConfig apiView = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(apiView);
        Class<?> requestType = Class.forName(SettingsController.class.getName() + "$AdvancedConfigRequest");
        var constructor = requestType.getDeclaredConstructor(
                RuntimeConfig.RateLimitConfig.class,
                RuntimeConfig.SecurityConfig.class,
                RuntimeConfig.CompactionConfig.class);
        constructor.setAccessible(true);
        Object request = constructor.newInstance(
                RuntimeConfig.RateLimitConfig.builder().build(),
                RuntimeConfig.SecurityConfig.builder().build(),
                RuntimeConfig.CompactionConfig.builder().build());
        Method method = SettingsController.class.getMethod("updateAdvancedConfig", requestType);

        Mono<ResponseEntity<RuntimeConfig>> response = (Mono<ResponseEntity<RuntimeConfig>>) method.invoke(controller,
                request);

        StepVerifier.create(response)
                .assertNext(result -> assertEquals(HttpStatus.OK, result.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldRejectNullCatalogEntry() {
        assertThrows(IllegalArgumentException.class, () -> controller.addMcpCatalogEntry(null));
    }

    private void registerSttProvider(String providerId, String alias) {
        SttProvider provider = mock(SttProvider.class);
        when(provider.getProviderId()).thenReturn(providerId);
        when(provider.getAliases()).thenReturn(Set.of(alias));
        when(provider.isAvailable()).thenReturn(true);
        sttProviderRegistry.replaceProviders(providerId, List.of(provider));
    }

    private void registerTtsProvider(String providerId, String alias) {
        TtsProvider provider = mock(TtsProvider.class);
        when(provider.getProviderId()).thenReturn(providerId);
        when(provider.getAliases()).thenReturn(Set.of(alias));
        when(provider.isAvailable()).thenReturn(true);
        ttsProviderRegistry.replaceProviders(providerId, List.of(provider));
    }
}

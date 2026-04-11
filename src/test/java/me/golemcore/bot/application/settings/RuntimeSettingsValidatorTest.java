package me.golemcore.bot.application.settings;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.adapter.outbound.voice.PluginVoiceProviderCatalogAdapter;
import me.golemcore.bot.port.outbound.VoiceProviderCatalogPort;
import me.golemcore.bot.plugin.runtime.SttProviderRegistry;
import me.golemcore.bot.plugin.runtime.TtsProviderRegistry;
import me.golemcore.plugin.api.extension.spi.SttProvider;
import me.golemcore.plugin.api.extension.spi.TtsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeSettingsValidatorTest {

    private ModelSelectionService modelSelectionService;
    private VoiceProviderCatalogPort voiceProviderCatalogPort;
    private RuntimeSettingsValidator validator;

    @BeforeEach
    void setUp() {
        modelSelectionService = mock(ModelSelectionService.class);
        voiceProviderCatalogPort = new PluginVoiceProviderCatalogAdapter(new SttProviderRegistry(),
                new TtsProviderRegistry());
        validator = new RuntimeSettingsValidator(
                modelSelectionService,
                voiceProviderCatalogPort);
    }

    @Test
    void shouldRejectNullRuntimeConfigDuringFullUpdateValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateRuntimeConfigUpdate(RuntimeConfig.builder().build(), null, false));
    }

    @Test
    void shouldNormalizeNullTelegramAllowedUsersDuringFullValidation() {
        RuntimeConfig config = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder().build())
                .llm(RuntimeConfig.LlmConfig.builder().providers(new java.util.LinkedHashMap<>()).build())
                .hive(RuntimeConfig.HiveConfig.builder().enabled(false).build())
                .build();

        validator.validateRuntimeConfigUpdate(RuntimeConfig.builder().build(), config, false);

        assertEquals(List.of(), config.getTelegram().getAllowedUsers());
    }

    @Test
    void shouldCreateMissingTelegramConfigDuringFullValidation() {
        RuntimeConfig config = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new java.util.LinkedHashMap<>()).build())
                .hive(RuntimeConfig.HiveConfig.builder().enabled(false).build())
                .build();

        validator.validateRuntimeConfigUpdate(RuntimeConfig.builder().build(), config, false);

        assertEquals(List.of(), config.getTelegram().getAllowedUsers());
    }

    @Test
    void shouldRejectInvalidProviderConfigFields() {
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .requestTimeoutSeconds(0)
                .build();

        assertThrows(IllegalArgumentException.class, () -> validator.validateProviderConfig("openai", providerConfig));

        providerConfig.setRequestTimeoutSeconds(30);
        providerConfig.setBaseUrl("ftp://invalid");
        assertThrows(IllegalArgumentException.class, () -> validator.validateProviderConfig("openai", providerConfig));

        providerConfig.setBaseUrl("https://api.example.com");
        providerConfig.setApiType("unsupported");
        assertThrows(IllegalArgumentException.class, () -> validator.validateProviderConfig("openai", providerConfig));
    }

    @Test
    void shouldRejectNullAndMalformedLlmConfigInputs() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateLlmConfig(null,
                RuntimeConfig.ModelRouterConfig.builder().build()));
        assertThrows(IllegalArgumentException.class, () -> validator.validateProviderConfig("openai", null));
        assertThrows(IllegalArgumentException.class, () -> validator.normalizeProviderName("Bad Name"));

        RuntimeConfig.LlmConfig blankProviderKey = RuntimeConfig.LlmConfig.builder()
                .providers(new java.util.LinkedHashMap<>(Map.of("", RuntimeConfig.LlmProviderConfig.builder().build())))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateLlmConfig(blankProviderKey, RuntimeConfig.ModelRouterConfig.builder().build()));

        RuntimeConfig.LlmConfig spacedProviderKey = RuntimeConfig.LlmConfig.builder()
                .providers(new java.util.LinkedHashMap<>(Map.of(" openai ", RuntimeConfig.LlmProviderConfig.builder()
                        .build())))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateLlmConfig(spacedProviderKey,
                        RuntimeConfig.ModelRouterConfig.builder().build()));

        RuntimeConfig.LlmConfig invalidPatternProviderKey = RuntimeConfig.LlmConfig.builder()
                .providers(new java.util.LinkedHashMap<>(Map.of("open.ai",
                        RuntimeConfig.LlmProviderConfig.builder().build())))
                .build();
        assertThrows(IllegalArgumentException.class, () -> validator.validateLlmConfig(
                invalidPatternProviderKey, RuntimeConfig.ModelRouterConfig.builder().build()));
    }

    @Test
    void shouldInitializeNullProviderMapAndAllowNullModelRouter() {
        RuntimeConfig.LlmConfig llmConfig = RuntimeConfig.LlmConfig.builder().providers(null).build();

        assertDoesNotThrow(() -> validator.validateModelRouterConfig(null, llmConfig));
        validator.validateLlmConfig(llmConfig, RuntimeConfig.ModelRouterConfig.builder().build());

        assertEquals(Map.of(), llmConfig.getProviders());
    }

    @Test
    void shouldRejectInvalidTurnDeadline() {
        RuntimeConfig.TurnConfig turnConfig = RuntimeConfig.TurnConfig.builder()
                .deadline("tomorrow")
                .build();

        assertThrows(IllegalArgumentException.class, () -> validator.validateTurnConfig(turnConfig));
    }

    @Test
    void shouldRejectInvalidTurnNumericConstraints() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateTurnConfig(null));
        assertThrows(IllegalArgumentException.class, () -> validator.validateTurnConfig(
                RuntimeConfig.TurnConfig.builder().maxLlmCalls(0).build()));
        assertThrows(IllegalArgumentException.class, () -> validator.validateTurnConfig(
                RuntimeConfig.TurnConfig.builder().maxToolExecutions(0).build()));
        assertThrows(IllegalArgumentException.class, () -> validator.validateTurnConfig(
                RuntimeConfig.TurnConfig.builder().deadline("PT0S").build()));
    }

    @Test
    void shouldRejectMemoryConfigWhenMaxBudgetLowerThanSoftBudget() {
        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .softPromptBudgetTokens(500)
                .maxPromptBudgetTokens(400)
                .build();

        assertThrows(IllegalArgumentException.class, () -> validator.validateMemoryConfig(memoryConfig));
    }

    @Test
    void shouldCreateDefaultMemoryNestedSections() {
        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder().build();

        validator.validateMemoryConfig(memoryConfig);

        assertNotNull(memoryConfig.getDisclosure());
        assertNotNull(memoryConfig.getReranking());
        assertNotNull(memoryConfig.getDiagnostics());
        assertThrows(IllegalArgumentException.class, () -> validator.validateMemoryConfig(null));
    }

    @Test
    void shouldNormalizeLegacyVoiceProvidersAndBlankWhisperUrl() {
        SttProvider sttProvider = mock(SttProvider.class);
        when(sttProvider.getProviderId()).thenReturn("golemcore/elevenlabs");
        TtsProvider ttsProvider = mock(TtsProvider.class);
        when(ttsProvider.getProviderId()).thenReturn("golemcore/whisper");
        SttProviderRegistry sttProviderRegistry = new SttProviderRegistry();
        sttProviderRegistry.replaceProviders("golemcore/elevenlabs", List.of(sttProvider));
        TtsProviderRegistry ttsProviderRegistry = new TtsProviderRegistry();
        ttsProviderRegistry.replaceProviders("golemcore/whisper", List.of(ttsProvider));
        RuntimeSettingsValidator loadedValidator = new RuntimeSettingsValidator(
                modelSelectionService,
                new PluginVoiceProviderCatalogAdapter(sttProviderRegistry, ttsProviderRegistry));
        RuntimeConfig.VoiceConfig voiceConfig = RuntimeConfig.VoiceConfig.builder()
                .enabled(false)
                .sttProvider("elevenlabs")
                .ttsProvider("whisper")
                .whisperSttUrl("   ")
                .build();

        loadedValidator.validateVoiceConfig(voiceConfig);

        assertEquals("golemcore/elevenlabs", voiceConfig.getSttProvider());
        assertEquals("golemcore/whisper", voiceConfig.getTtsProvider());
        assertNull(voiceConfig.getWhisperSttUrl());
    }

    @Test
    void shouldRejectEnabledVoiceConfigWhenNoProvidersAreLoaded() {
        RuntimeSettingsValidator unloadedValidator = new RuntimeSettingsValidator(
                modelSelectionService,
                new PluginVoiceProviderCatalogAdapter(new SttProviderRegistry(), new TtsProviderRegistry()));
        RuntimeConfig.VoiceConfig voiceConfig = RuntimeConfig.VoiceConfig.builder()
                .enabled(true)
                .build();

        assertThrows(IllegalArgumentException.class, () -> unloadedValidator.validateVoiceConfig(voiceConfig));
    }

    @Test
    void shouldValidateShellEnvironmentVariableConstraints() {
        RuntimeConfig.ShellEnvironmentVariable reserved = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("HOME")
                .value("x")
                .build();
        RuntimeConfig.ShellEnvironmentVariable invalid = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("1BAD")
                .value("x")
                .build();
        RuntimeConfig.ToolsConfig duplicateTools = RuntimeConfig.ToolsConfig.builder()
                .shellEnvironmentVariables(List.of(
                        RuntimeConfig.ShellEnvironmentVariable.builder().name("FOO").value("1").build(),
                        RuntimeConfig.ShellEnvironmentVariable.builder().name("FOO").value("2").build()))
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeAndValidateShellEnvironmentVariable(reserved));
        assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeAndValidateShellEnvironmentVariable(invalid));
        assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeAndValidateShellEnvironmentVariables(duplicateTools));
    }

    @Test
    void shouldNormalizeModelRegistryDefaults() {
        RuntimeConfig.ModelRegistryConfig modelRegistryConfig = RuntimeConfig.ModelRegistryConfig.builder()
                .repositoryUrl("  ")
                .branch(" ")
                .build();

        validator.validateAndNormalizeModelRegistryConfig(modelRegistryConfig);

        assertNull(modelRegistryConfig.getRepositoryUrl());
        assertEquals("main", modelRegistryConfig.getBranch());
    }

    @Test
    void shouldRejectManagedHiveMutation() {
        RuntimeConfig current = RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder().enabled(false).build())
                .build();
        RuntimeConfig.HiveConfig incoming = RuntimeConfig.HiveConfig.builder()
                .enabled(true)
                .build();

        assertThrows(IllegalStateException.class, () -> validator.rejectManagedHiveMutation(current, incoming, true));
    }

    @Test
    void shouldRejectProviderRemovalWhenModelRouterStillUsesIt() {
        RuntimeConfig.ModelRouterConfig modelRouterConfig = RuntimeConfig.ModelRouterConfig.builder()
                .routing(RuntimeConfig.TierBinding.builder().model("openai/gpt-5").build())
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> validator.validateProviderRemoval(modelRouterConfig, "openai"));
    }

    @Test
    void shouldNormalizeWebhookTierSelection() {
        UserPreferences.WebhookConfig webhookConfig = UserPreferences.WebhookConfig.builder()
                .mappings(List.of(UserPreferences.HookMapping.builder()
                        .name("build")
                        .model("default")
                        .build()))
                .build();

        validator.validateWebhookConfig(webhookConfig);

        assertNull(webhookConfig.getMappings().getFirst().getModel());
    }

    @Test
    void shouldRejectInvalidMcpCatalogEntry() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("Bad Name")
                .command("")
                .build();

        assertThrows(IllegalArgumentException.class, () -> validator.validateMcpCatalogEntry(entry));
    }

    @Test
    void shouldRejectModelRouterProviderWhenProviderNotConfigured() {
        when(modelSelectionService.validateModel("openai/gpt-5", List.of()))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "provider.not.configured"));
        RuntimeConfig.ModelRouterConfig modelRouterConfig = RuntimeConfig.ModelRouterConfig.builder()
                .routing(RuntimeConfig.TierBinding.builder().model("openai/gpt-5").build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateModelRouterConfig(modelRouterConfig,
                        RuntimeConfig.LlmConfig.builder().build()));

        assertTrue(error.getMessage().contains("provider is not configured"));
    }

    @Test
    void shouldDefaultMissingProvidersAndCompactionTriggerMode() {
        RuntimeConfig.LlmConfig llmConfig = RuntimeConfig.LlmConfig.builder().build();
        RuntimeConfig.CompactionConfig compactionConfig = RuntimeConfig.CompactionConfig.builder().build();

        validator.validateLlmConfig(llmConfig, RuntimeConfig.ModelRouterConfig.builder().build());
        validator.validateCompactionConfig(compactionConfig);

        assertEquals(Map.of(), llmConfig.getProviders());
        assertEquals("model_ratio", compactionConfig.getTriggerMode());
    }

    @Test
    void shouldAcceptGonkaProviderWhenSourceUrlConfigured() {
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(me.golemcore.bot.domain.model.Secret
                        .of("0000000000000000000000000000000000000000000000000000000000000001"))
                .apiType("gonka")
                .sourceUrl("https://node3.gonka.ai")
                .build();

        assertDoesNotThrow(() -> validator.validateProviderConfig("gonka", config));
    }

    @Test
    void shouldRejectGonkaProviderWithoutSourceUrlOrEndpoints() {
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder()
                .apiType("gonka")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateProviderConfig("gonka", config));

        assertTrue(error.getMessage().contains("sourceUrl or endpoints is required"));
    }

}

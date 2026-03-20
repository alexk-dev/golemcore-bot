package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ModelSelectionServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private ModelConfigService modelConfigService;
    private UserPreferencesService preferencesService;
    private ModelSelectionService service;

    private UserPreferences userPreferences;
    private Map<String, ModelConfigService.ModelSettings> modelRegistry;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelConfigService = mock(ModelConfigService.class);
        preferencesService = mock(UserPreferencesService.class);

        when(runtimeConfigService.getBalancedModel()).thenReturn("openai/gpt-5.1");
        when(runtimeConfigService.getBalancedModelReasoning()).thenReturn("medium");
        when(runtimeConfigService.getSmartModel()).thenReturn("openai/gpt-5.1");
        when(runtimeConfigService.getSmartModelReasoning()).thenReturn("high");
        when(runtimeConfigService.getCodingModel()).thenReturn("openai/gpt-5.2");
        when(runtimeConfigService.getCodingModelReasoning()).thenReturn("medium");
        when(runtimeConfigService.getDeepModel()).thenReturn("openai/gpt-5.2");
        when(runtimeConfigService.getDeepModelReasoning()).thenReturn("xhigh");
        when(runtimeConfigService.getRoutingModel()).thenReturn("openai/gpt-5.2-codex");
        when(runtimeConfigService.getRoutingModelReasoning()).thenReturn("none");
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(50000);
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("openai", "anthropic"));
        when(runtimeConfigService.getModelTierBinding("special1"))
                .thenReturn(RuntimeConfig.TierBinding.builder().build());
        when(runtimeConfigService.getModelTierBinding("special2"))
                .thenReturn(RuntimeConfig.TierBinding.builder().build());
        when(runtimeConfigService.getModelTierBinding("special3"))
                .thenReturn(RuntimeConfig.TierBinding.builder().build());
        when(runtimeConfigService.getModelTierBinding("special4"))
                .thenReturn(RuntimeConfig.TierBinding.builder().build());
        when(runtimeConfigService.getModelTierBinding("special5"))
                .thenReturn(RuntimeConfig.TierBinding.builder().build());

        modelRegistry = new LinkedHashMap<>();
        modelRegistry.put("openai/gpt-5.1", modelSettings("openai"));
        modelRegistry.put("openai/gpt-5.2", modelSettings("openai"));
        modelRegistry.put("openai/gpt-5.2-codex", modelSettings("openai"));
        modelRegistry.put("openai/gpt-4o", modelSettings("openai"));
        modelRegistry.put("anthropic/claude-sonnet-4", modelSettings("anthropic"));
        when(modelConfigService.getAllModels()).thenReturn(modelRegistry);
        when(modelConfigService.getModelSettings(anyString())).thenAnswer(invocation -> {
            String model = invocation.getArgument(0);
            ModelConfigService.ModelSettings settings = modelRegistry.get(model);
            return settings != null ? settings : modelSettings("openai");
        });
        when(modelConfigService.isReasoningRequired(anyString())).thenReturn(false);
        when(modelConfigService.isReasoningRequired("openai/gpt-5.1")).thenReturn(true);
        when(modelConfigService.isReasoningRequired("openai/gpt-5.2")).thenReturn(true);
        when(modelConfigService.isReasoningRequired("openai/gpt-5.2-codex")).thenReturn(true);
        when(modelConfigService.getLowestReasoningLevel(anyString())).thenReturn("none");
        when(modelConfigService.getAvailableReasoningLevels(anyString()))
                .thenReturn(List.of("none", "low", "medium", "high", "xhigh"));

        userPreferences = UserPreferences.builder().build();
        when(preferencesService.getPreferences()).thenReturn(userPreferences);

        service = new ModelSelectionService(runtimeConfigService, modelConfigService, preferencesService);
    }

    // =====================================================
    // resolveForTier()
    // =====================================================

    @Test
    void shouldUseUserOverrideWhenTierOverrideExists() {
        // Arrange
        Map<String, UserPreferences.TierOverride> overrides = new HashMap<>();
        overrides.put("balanced", new UserPreferences.TierOverride("anthropic/claude-sonnet-4", "high"));
        userPreferences.setTierOverrides(overrides);

        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("balanced");

        // Assert
        assertEquals("anthropic/claude-sonnet-4", result.model());
        assertEquals("high", result.reasoning());
    }

    @Test
    void shouldAutoFillDefaultReasoningWhenOverrideHasNoReasoningAndModelRequiresIt() {
        // Arrange
        Map<String, UserPreferences.TierOverride> overrides = new HashMap<>();
        overrides.put("smart", new UserPreferences.TierOverride("openai/gpt-5.1", null));
        userPreferences.setTierOverrides(overrides);

        when(modelConfigService.isReasoningRequired("openai/gpt-5.1")).thenReturn(true);
        when(modelConfigService.getLowestReasoningLevel("openai/gpt-5.1")).thenReturn("none");

        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("smart");

        // Assert
        assertEquals("openai/gpt-5.1", result.model());
        assertEquals("none", result.reasoning());
    }

    @Test
    void shouldNotAutoFillReasoningWhenOverrideHasNoReasoningAndModelDoesNotRequireIt() {
        // Arrange
        Map<String, UserPreferences.TierOverride> overrides = new HashMap<>();
        overrides.put("balanced", new UserPreferences.TierOverride("openai/gpt-4o", null));
        userPreferences.setTierOverrides(overrides);

        when(modelConfigService.isReasoningRequired("openai/gpt-4o")).thenReturn(false);

        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("balanced");

        // Assert
        assertEquals("openai/gpt-4o", result.model());
        assertNull(result.reasoning());
    }

    @Test
    void shouldFallBackToRouterConfigWhenNoUserOverrideExists() {
        // Arrange — no overrides set (default empty map)

        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("balanced");

        // Assert
        assertEquals("openai/gpt-5.1", result.model());
        assertEquals("medium", result.reasoning());
    }

    @Test
    void shouldFallBackToRouterConfigWhenTierOverridesMapIsNull() {
        // Arrange
        userPreferences.setTierOverrides(null);

        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("coding");

        // Assert
        assertEquals("openai/gpt-5.2", result.model());
        assertEquals("medium", result.reasoning());
    }

    @Test
    void shouldResolveSmartTierFromRouter() {
        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("smart");

        // Assert
        assertEquals("openai/gpt-5.1", result.model());
        assertEquals("high", result.reasoning());
    }

    @Test
    void shouldResolveCodingTierFromRouter() {
        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("coding");

        // Assert
        assertEquals("openai/gpt-5.2", result.model());
        assertEquals("medium", result.reasoning());
    }

    @Test
    void shouldResolveDeepTierFromRouter() {
        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("deep");

        // Assert
        assertEquals("openai/gpt-5.2", result.model());
        assertEquals("xhigh", result.reasoning());
    }

    @Test
    void shouldDefaultToBalancedTierWhenTierIsNull() {
        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier(null);

        // Assert
        assertEquals("openai/gpt-5.1", result.model());
        assertEquals("medium", result.reasoning());
    }

    @Test
    void shouldRejectUnknownExplicitTier() {
        // Act
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.resolveForTier("unknown-tier"));

        // Assert
        assertTrue(error.getMessage().contains("unknown-tier"));
    }

    @Test
    void shouldResolveExplicitSpecialTierWhenConfigured() {
        when(runtimeConfigService.getModelTierBinding("special1"))
                .thenReturn(RuntimeConfig.TierBinding.builder()
                        .model("anthropic/claude-sonnet-4")
                        .reasoning("high")
                        .build());

        ModelSelectionService.ModelSelection result = service.resolveForTier("special1");

        assertEquals("anthropic/claude-sonnet-4", result.model());
        assertEquals("high", result.reasoning());
    }

    @Test
    void shouldRejectExplicitSpecialTierWhenUnconfigured() {
        when(runtimeConfigService.getModelTierBinding("special3"))
                .thenReturn(RuntimeConfig.TierBinding.builder()
                        .model(null)
                        .reasoning("none")
                        .build());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveForTier("special3"));

        assertTrue(error.getMessage().contains("special3"));
    }

    @Test
    void shouldRejectExplicitSpecialTierWhenModelIsUnknown() {
        when(runtimeConfigService.getModelTierBinding("special2"))
                .thenReturn(RuntimeConfig.TierBinding.builder()
                        .model("custom/ghost-model")
                        .reasoning("none")
                        .build());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveForTier("special2"));

        assertTrue(error.getMessage().contains("custom/ghost-model"));
    }

    @Test
    void shouldRejectExplicitSpecialTierWhenProviderIsNotConfigured() {
        modelRegistry.put("custom/special4-model", modelSettings("custom"));
        when(runtimeConfigService.getModelTierBinding("special4"))
                .thenReturn(RuntimeConfig.TierBinding.builder()
                        .model("custom/special4-model")
                        .reasoning("none")
                        .build());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveForTier("special4"));

        assertTrue(error.getMessage().contains("special4"));
        assertTrue(error.getMessage().contains("provider"));
    }

    @Test
    void shouldSkipOverrideWhenOverrideModelIsNull() {
        // Arrange
        Map<String, UserPreferences.TierOverride> overrides = new HashMap<>();
        overrides.put("balanced", new UserPreferences.TierOverride(null, "high"));
        userPreferences.setTierOverrides(overrides);

        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("balanced");

        // Assert — skips override because model is null, falls back to router
        assertEquals("openai/gpt-5.1", result.model());
        assertEquals("medium", result.reasoning());
    }

    @Test
    void shouldUseUserOverrideReasoningWhenExplicitlySet() {
        // Arrange
        Map<String, UserPreferences.TierOverride> overrides = new HashMap<>();
        overrides.put("deep", new UserPreferences.TierOverride("openai/gpt-5.2", "low"));
        userPreferences.setTierOverrides(overrides);

        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("deep");

        // Assert
        assertEquals("openai/gpt-5.2", result.model());
        assertEquals("low", result.reasoning());
    }

    // =====================================================
    // resolveModelName()
    // =====================================================

    @Test
    void shouldDelegateToResolveForTierAndReturnModel() {
        // Act
        String model = service.resolveModelName("coding");

        // Assert
        assertEquals("openai/gpt-5.2", model);
    }

    @Test
    void shouldReturnBalancedModelWhenTierIsNull() {
        // Act
        String model = service.resolveModelName(null);

        // Assert
        assertEquals("openai/gpt-5.1", model);
    }

    // =====================================================
    // resolveMaxInputTokens()
    // =====================================================

    @Test
    void shouldReturnModelSpecificTokenLimitWhenModelIsNotNull() {
        // Arrange
        when(modelConfigService.getMaxInputTokens("openai/gpt-5.1", "medium")).thenReturn(1000000);

        // Act
        int result = service.resolveMaxInputTokens("balanced");

        // Assert
        assertEquals(1000000, result);
    }

    @Test
    void shouldReturnAutoCompactFallbackWhenModelIsNull() {
        // Arrange
        when(runtimeConfigService.getBalancedModel()).thenReturn(null);
        when(runtimeConfigService.getBalancedModelReasoning()).thenReturn(null);

        // Act
        int result = service.resolveMaxInputTokens("balanced");

        // Assert
        assertEquals(50000, result);
    }

    @Test
    void shouldPassReasoningLevelToModelConfigService() {
        // Arrange
        when(modelConfigService.getMaxInputTokens("openai/gpt-5.2", "xhigh")).thenReturn(250000);

        // Act
        int result = service.resolveMaxInputTokens("deep");

        // Assert
        assertEquals(250000, result);
        verify(modelConfigService).getMaxInputTokens("openai/gpt-5.2", "xhigh");
    }

    @Test
    void shouldResolveContextTokenLimitFromActiveSkillTier() {
        when(modelConfigService.getMaxInputTokens("openai/gpt-5.2", "xhigh")).thenReturn(320000);

        AgentContext context = AgentContext.builder()
                .activeSkill(Skill.builder().name("reviewer").modelTier("deep").build())
                .build();

        int result = service.resolveMaxInputTokensForContext(context);

        assertEquals(320000, result);
    }

    @Test
    void shouldPreferForcedUserTierOverActiveSkillWhenResolvingContextTokenLimit() {
        userPreferences.setTierForce(true);
        userPreferences.setModelTier("smart");
        when(modelConfigService.getMaxInputTokens("openai/gpt-5.1", "high")).thenReturn(210000);

        AgentContext context = AgentContext.builder()
                .activeSkill(Skill.builder().name("reviewer").modelTier("deep").build())
                .build();

        int result = service.resolveMaxInputTokensForContext(context);

        assertEquals(210000, result);
    }

    @Test
    void shouldPreferContextModelTierOverForcedUserTierAndActiveSkillForContextResolution() {
        userPreferences.setTierForce(true);
        userPreferences.setModelTier("smart");
        when(modelConfigService.getMaxInputTokens("openai/gpt-5.2", "medium")).thenReturn(180000);

        AgentContext context = AgentContext.builder()
                .modelTier("coding")
                .activeSkill(Skill.builder().name("reviewer").modelTier("deep").build())
                .build();

        int result = service.resolveMaxInputTokensForContext(context);

        assertEquals(180000, result);
        verify(modelConfigService).getMaxInputTokens("openai/gpt-5.2", "medium");
    }

    @Test
    void shouldReturnCompactionFallbackWhenContextResolutionFindsNoModel() {
        when(runtimeConfigService.getBalancedModel()).thenReturn(null);
        when(runtimeConfigService.getBalancedModelReasoning()).thenReturn(null);

        int result = service.resolveMaxInputTokensForContext(null);

        assertEquals(50000, result);
    }

    @Test
    void shouldUseUserOverrideModelForTokenResolution() {
        // Arrange
        Map<String, UserPreferences.TierOverride> overrides = new HashMap<>();
        overrides.put("balanced", new UserPreferences.TierOverride("anthropic/claude-sonnet-4", null));
        userPreferences.setTierOverrides(overrides);

        when(modelConfigService.isReasoningRequired("anthropic/claude-sonnet-4")).thenReturn(false);
        when(modelConfigService.getMaxInputTokens("anthropic/claude-sonnet-4", null)).thenReturn(200000);

        // Act
        int result = service.resolveMaxInputTokens("balanced");

        // Assert
        assertEquals(200000, result);
    }

    // =====================================================
    // getAvailableModels()
    // =====================================================

    @Test
    void shouldReturnModelsFilteredByAllowedProviders() {
        // Arrange
        Map<String, ModelConfigService.ModelSettings> filteredModels = new LinkedHashMap<>();

        ModelConfigService.ModelSettings gpt4oSettings = new ModelConfigService.ModelSettings();
        gpt4oSettings.setProvider("openai");
        gpt4oSettings.setDisplayName("GPT-4o");
        filteredModels.put("gpt-4o", gpt4oSettings);

        ModelConfigService.ModelSettings claudeSettings = new ModelConfigService.ModelSettings();
        claudeSettings.setProvider("anthropic");
        claudeSettings.setDisplayName("Claude Sonnet 4");
        filteredModels.put("claude-sonnet-4", claudeSettings);

        when(modelConfigService.getModelsForProviders(List.of("openai", "anthropic"))).thenReturn(filteredModels);
        when(modelConfigService.isReasoningRequired("gpt-4o")).thenReturn(false);
        when(modelConfigService.isReasoningRequired("claude-sonnet-4")).thenReturn(false);

        // Act
        List<ModelSelectionService.AvailableModel> result = service.getAvailableModels();

        // Assert
        assertEquals(2, result.size());
        assertEquals("gpt-4o", result.get(0).id());
        assertEquals("openai", result.get(0).provider());
        assertEquals("GPT-4o", result.get(0).displayName());
        assertFalse(result.get(0).hasReasoning());
        assertTrue(result.get(0).reasoningLevels().isEmpty());
        assertTrue(result.get(0).supportsVision());
    }

    @Test
    void shouldIncludeReasoningLevelsForReasoningModels() {
        // Arrange
        Map<String, ModelConfigService.ModelSettings> filteredModels = new LinkedHashMap<>();

        ModelConfigService.ModelSettings gpt51Settings = new ModelConfigService.ModelSettings();
        gpt51Settings.setProvider("openai");
        gpt51Settings.setDisplayName("GPT-5.1");
        filteredModels.put("gpt-5.1", gpt51Settings);

        when(modelConfigService.getModelsForProviders(List.of("openai", "anthropic"))).thenReturn(filteredModels);
        when(modelConfigService.isReasoningRequired("gpt-5.1")).thenReturn(true);
        when(modelConfigService.getAvailableReasoningLevels("gpt-5.1")).thenReturn(List.of("low", "medium", "high"));

        // Act
        List<ModelSelectionService.AvailableModel> result = service.getAvailableModels();

        // Assert
        assertEquals(1, result.size());
        assertTrue(result.get(0).hasReasoning());
        assertEquals(List.of("low", "medium", "high"), result.get(0).reasoningLevels());
        assertTrue(result.get(0).supportsVision());
    }

    @Test
    void shouldUseModelIdAsDisplayNameWhenDisplayNameIsNull() {
        // Arrange
        Map<String, ModelConfigService.ModelSettings> filteredModels = new LinkedHashMap<>();

        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider("openai");
        settings.setDisplayName(null);
        filteredModels.put("custom-model", settings);

        when(modelConfigService.getModelsForProviders(List.of("openai", "anthropic"))).thenReturn(filteredModels);
        when(modelConfigService.isReasoningRequired("custom-model")).thenReturn(false);

        // Act
        List<ModelSelectionService.AvailableModel> result = service.getAvailableModels();

        // Assert
        assertEquals(1, result.size());
        assertEquals("custom-model", result.get(0).displayName());
    }

    @Test
    void shouldReturnEmptyListWhenNoModelsMatchAllowedProviders() {
        // Arrange
        when(modelConfigService.getModelsForProviders(List.of("openai", "anthropic")))
                .thenReturn(new LinkedHashMap<>());

        // Act
        List<ModelSelectionService.AvailableModel> result = service.getAvailableModels();

        // Assert
        assertTrue(result.isEmpty());
    }

    // =====================================================
    // getAvailableModelsGrouped()
    // =====================================================

    @Test
    void shouldGroupModelsByProvider() {
        // Arrange
        Map<String, ModelConfigService.ModelSettings> filteredModels = new LinkedHashMap<>();

        ModelConfigService.ModelSettings gpt4oSettings = new ModelConfigService.ModelSettings();
        gpt4oSettings.setProvider("openai");
        gpt4oSettings.setDisplayName("GPT-4o");
        filteredModels.put("gpt-4o", gpt4oSettings);

        ModelConfigService.ModelSettings gpt51Settings = new ModelConfigService.ModelSettings();
        gpt51Settings.setProvider("openai");
        gpt51Settings.setDisplayName("GPT-5.1");
        filteredModels.put("gpt-5.1", gpt51Settings);

        ModelConfigService.ModelSettings claudeSettings = new ModelConfigService.ModelSettings();
        claudeSettings.setProvider("anthropic");
        claudeSettings.setDisplayName("Claude Sonnet 4");
        filteredModels.put("claude-sonnet-4", claudeSettings);

        when(modelConfigService.getModelsForProviders(List.of("openai", "anthropic"))).thenReturn(filteredModels);
        when(modelConfigService.isReasoningRequired("gpt-4o")).thenReturn(false);
        when(modelConfigService.isReasoningRequired("gpt-5.1")).thenReturn(false);
        when(modelConfigService.isReasoningRequired("claude-sonnet-4")).thenReturn(false);

        // Act
        Map<String, List<ModelSelectionService.AvailableModel>> result = service.getAvailableModelsGrouped();

        // Assert
        assertEquals(2, result.size());
        assertTrue(result.containsKey("openai"));
        assertTrue(result.containsKey("anthropic"));
        assertEquals(2, result.get("openai").size());
        assertEquals(1, result.get("anthropic").size());
    }

    @Test
    void shouldReturnEmptyMapWhenNoModelsAvailable() {
        // Arrange
        when(modelConfigService.getModelsForProviders(List.of("openai", "anthropic")))
                .thenReturn(new LinkedHashMap<>());

        // Act
        Map<String, List<ModelSelectionService.AvailableModel>> result = service.getAvailableModelsGrouped();

        // Assert
        assertTrue(result.isEmpty());
    }

    // =====================================================
    // validateModel()
    // =====================================================

    @Test
    void shouldReturnValidWhenModelExistsAndProviderIsAllowed() {
        // Arrange
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider("openai");
        when(modelConfigService.getModelSettings("openai/gpt-5.1")).thenReturn(settings);

        Map<String, ModelConfigService.ModelSettings> allModels = new HashMap<>();
        allModels.put("gpt-5.1", new ModelConfigService.ModelSettings());
        when(modelConfigService.getAllModels()).thenReturn(allModels);

        // Act
        ModelSelectionService.ValidationResult result = service.validateModel("openai/gpt-5.1");

        // Assert
        assertTrue(result.valid());
        assertNull(result.error());
    }

    @Test
    void shouldReturnValidWhenExactKeyMatchesInAllModels() {
        // Arrange
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider("anthropic");
        when(modelConfigService.getModelSettings("claude-sonnet-4")).thenReturn(settings);

        Map<String, ModelConfigService.ModelSettings> allModels = new HashMap<>();
        allModels.put("claude-sonnet-4", new ModelConfigService.ModelSettings());
        when(modelConfigService.getAllModels()).thenReturn(allModels);

        // Act
        ModelSelectionService.ValidationResult result = service.validateModel("claude-sonnet-4");

        // Assert
        assertTrue(result.valid());
        assertNull(result.error());
    }

    @Test
    void shouldReturnValidWhenPrefixMatchesInAllModels() {
        // Arrange
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider("openai");
        when(modelConfigService.getModelSettings("gpt-5.1-preview")).thenReturn(settings);

        Map<String, ModelConfigService.ModelSettings> allModels = new HashMap<>();
        allModels.put("gpt-5.1", new ModelConfigService.ModelSettings());
        when(modelConfigService.getAllModels()).thenReturn(allModels);

        // Act
        ModelSelectionService.ValidationResult result = service.validateModel("gpt-5.1-preview");

        // Assert
        assertTrue(result.valid());
        assertNull(result.error());
    }

    @Test
    void shouldReturnModelNotFoundWhenModelDoesNotExist() {
        // Arrange
        ModelConfigService.ModelSettings defaults = new ModelConfigService.ModelSettings();
        defaults.setProvider("openai");
        when(modelConfigService.getModelSettings("nonexistent-model")).thenReturn(defaults);
        when(modelConfigService.getAllModels()).thenReturn(new HashMap<>());

        // Act
        ModelSelectionService.ValidationResult result = service.validateModel("nonexistent-model");

        // Assert
        assertFalse(result.valid());
        assertEquals("model.not.found", result.error());
    }

    @Test
    void shouldReturnProviderNotConfiguredWhenProviderIsNotInRuntimeConfig() {
        // Arrange
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("openai"));

        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider("anthropic");
        when(modelConfigService.getModelSettings("claude-sonnet-4")).thenReturn(settings);

        Map<String, ModelConfigService.ModelSettings> allModels = new HashMap<>();
        allModels.put("claude-sonnet-4", new ModelConfigService.ModelSettings());
        when(modelConfigService.getAllModels()).thenReturn(allModels);

        // Act
        ModelSelectionService.ValidationResult result = service.validateModel("claude-sonnet-4");

        // Assert
        assertFalse(result.valid());
        assertEquals("provider.not.configured", result.error());
    }

    @Test
    void shouldReturnModelEmptyWhenModelSpecIsNull() {
        // Act
        ModelSelectionService.ValidationResult result = service.validateModel(null);

        // Assert
        assertFalse(result.valid());
        assertEquals("model.empty", result.error());
    }

    @Test
    void shouldReturnModelEmptyWhenModelSpecIsBlank() {
        // Act
        ModelSelectionService.ValidationResult result = service.validateModel("   ");

        // Assert
        assertFalse(result.valid());
        assertEquals("model.empty", result.error());
    }

    @Test
    void shouldReturnModelEmptyWhenModelSpecIsEmptyString() {
        // Act
        ModelSelectionService.ValidationResult result = service.validateModel("");

        // Assert
        assertFalse(result.valid());
        assertEquals("model.empty", result.error());
    }

    @Test
    void shouldStripProviderPrefixWhenCheckingExactMatch() {
        // Arrange — model stored without provider prefix, queried with prefix
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider("openai");
        when(modelConfigService.getModelSettings("openai/gpt-4o")).thenReturn(settings);

        Map<String, ModelConfigService.ModelSettings> allModels = new HashMap<>();
        allModels.put("gpt-4o", new ModelConfigService.ModelSettings());
        when(modelConfigService.getAllModels()).thenReturn(allModels);

        // Act
        ModelSelectionService.ValidationResult result = service.validateModel("openai/gpt-4o");

        // Assert
        assertTrue(result.valid());
        assertNull(result.error());
    }

    // =====================================================
    // validateReasoning()
    // =====================================================

    @Test
    void shouldReturnValidWhenReasoningLevelIsAvailable() {
        // Arrange
        when(modelConfigService.isReasoningRequired("openai/gpt-5.1")).thenReturn(true);
        when(modelConfigService.getAvailableReasoningLevels("openai/gpt-5.1"))
                .thenReturn(List.of("low", "medium", "high"));

        // Act
        ModelSelectionService.ValidationResult result = service.validateReasoning("openai/gpt-5.1", "medium");

        // Assert
        assertTrue(result.valid());
        assertNull(result.error());
    }

    @Test
    void shouldReturnInvalidWhenReasoningLevelIsNotAvailable() {
        // Arrange
        when(modelConfigService.isReasoningRequired("openai/gpt-5.1")).thenReturn(true);
        when(modelConfigService.getAvailableReasoningLevels("openai/gpt-5.1"))
                .thenReturn(List.of("low", "medium", "high"));

        // Act
        ModelSelectionService.ValidationResult result = service.validateReasoning("openai/gpt-5.1", "xhigh");

        // Assert
        assertFalse(result.valid());
        assertEquals("level.not.available", result.error());
    }

    @Test
    void shouldReturnNoReasoningWhenModelDoesNotSupportReasoning() {
        // Arrange
        when(modelConfigService.isReasoningRequired("openai/gpt-4o")).thenReturn(false);

        // Act
        ModelSelectionService.ValidationResult result = service.validateReasoning("openai/gpt-4o", "high");

        // Assert
        assertFalse(result.valid());
        assertEquals("no.reasoning", result.error());
    }

    @Test
    void shouldReturnValidForAllAvailableReasoningLevels() {
        // Arrange
        when(modelConfigService.isReasoningRequired("openai/gpt-5.2")).thenReturn(true);
        when(modelConfigService.getAvailableReasoningLevels("openai/gpt-5.2"))
                .thenReturn(List.of("low", "medium", "high", "xhigh"));

        // Act & Assert
        for (String level : List.of("low", "medium", "high", "xhigh")) {
            ModelSelectionService.ValidationResult result = service.validateReasoning("openai/gpt-5.2", level);
            assertTrue(result.valid(), "Expected valid for level: " + level);
        }
    }

    // =====================================================
    // resolveForTier() with null override for non-matching tier
    // =====================================================

    @Test
    void shouldFallBackToRouterWhenOverrideExistsForDifferentTier() {
        // Arrange — override exists for "coding" but we resolve "deep"
        Map<String, UserPreferences.TierOverride> overrides = new HashMap<>();
        overrides.put("coding", new UserPreferences.TierOverride("anthropic/claude-sonnet-4", null));
        userPreferences.setTierOverrides(overrides);

        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("deep");

        // Assert
        assertEquals("openai/gpt-5.2", result.model());
        assertEquals("xhigh", result.reasoning());
    }

    @Test
    void shouldPreserveExplicitReasoningInOverrideEvenWhenModelHasNoReasoning() {
        // Arrange — user sets reasoning explicitly on a non-reasoning model
        Map<String, UserPreferences.TierOverride> overrides = new HashMap<>();
        overrides.put("balanced", new UserPreferences.TierOverride("openai/gpt-4o", "high"));
        userPreferences.setTierOverrides(overrides);

        // Act
        ModelSelectionService.ModelSelection result = service.resolveForTier("balanced");

        // Assert — the service preserves the user-specified reasoning as-is
        assertEquals("openai/gpt-4o", result.model());
        assertEquals("high", result.reasoning());
    }

    private ModelConfigService.ModelSettings modelSettings(String provider) {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider(provider);
        settings.setDisplayName(provider + " model");
        settings.setSupportsTemperature(true);
        settings.setSupportsVision(true);
        settings.setMaxInputTokens(128000);
        return settings;
    }
}

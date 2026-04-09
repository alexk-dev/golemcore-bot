package me.golemcore.bot.application.command;

import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelSelectionCommandServiceTest {

    private UserPreferencesService preferencesService;
    private ModelSelectionService modelSelectionService;
    private RuntimeConfigService runtimeConfigService;
    private UserPreferences preferences;
    private ModelSelectionCommandService service;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class, invocation -> {
            if ("getMessage".equals(invocation.getMethod().getName())) {
                String key = (String) invocation.getArguments()[0];
                StringBuilder builder = new StringBuilder(key);
                Object[] args = java.util.Arrays.copyOfRange(
                        invocation.getArguments(),
                        1,
                        invocation.getArguments().length);
                for (Object arg : args) {
                    builder.append(" ").append(arg);
                }
                return builder.toString();
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        preferences = UserPreferences.builder()
                .tierOverrides(new LinkedHashMap<>())
                .build();
        when(preferencesService.getPreferences()).thenReturn(preferences);
        service = new ModelSelectionCommandService(preferencesService, modelSelectionService, runtimeConfigService);
    }

    @Test
    void shouldShowCurrentTierWhenNoArgs() {
        preferences.setModelTier("coding");
        preferences.setTierForce(true);

        ModelSelectionCommandService.CommandOutcome result = service.handleTier(List.of());

        assertTrue(result.success());
        assertTrue(result.output().contains("command.tier.current"));
        assertTrue(result.output().contains("coding"));
        assertTrue(result.output().contains("on"));
    }

    @Test
    void shouldSetTierAndForceWhenRequested() {
        ModelSelectionCommandService.CommandOutcome result = service.handleTier(List.of("smart", "force"));

        assertTrue(result.success());
        assertEquals("smart", preferences.getModelTier());
        assertTrue(preferences.isTierForce());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldClearForceWhenSettingWithoutForce() {
        preferences.setModelTier("smart");
        preferences.setTierForce(true);

        ModelSelectionCommandService.CommandOutcome result = service.handleTier(List.of("balanced"));

        assertTrue(result.success());
        assertEquals("balanced", preferences.getModelTier());
        assertFalse(preferences.isTierForce());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldRejectInvalidTier() {
        ModelSelectionCommandService.CommandOutcome result = service.handleTier(List.of("turbo"));

        assertTrue(result.success());
        assertEquals("command.tier.invalid", result.output());
        verify(preferencesService, never()).savePreferences(any());
    }

    @Test
    void shouldAcceptAllSelectableTiers() {
        for (String tier : ModelTierCatalog.orderedExplicitTiers()) {
            preferences.setTierForce(true);

            ModelSelectionCommandService.CommandOutcome result = service.handleTier(List.of(tier));

            assertTrue(result.success());
            assertEquals(tier, preferences.getModelTier());
            assertFalse(preferences.isTierForce());
        }
    }

    @Test
    void shouldShowResolvedModelsForEachTierIncludingSpecials() {
        preferences.getTierOverrides().put("balanced", new UserPreferences.TierOverride("openai/gpt-5", "medium"));
        stubSelections("model-for-");

        ModelSelectionCommandService.CommandOutcome result = service.handleModel(List.of());

        assertTrue(result.success());
        assertTrue(result.output().contains("command.model.show.title"));
        assertTrue(result.output().contains("command.model.show.tier.override"));
        assertTrue(result.output().contains("command.model.show.tier.default"));
        assertTrue(result.output().contains("special1"));
        assertTrue(result.output().contains("special5"));
    }

    @Test
    void shouldListModelsGroupedByProvider() {
        when(modelSelectionService.getAvailableModelsGrouped()).thenReturn(Map.of(
                "openai", List.of(
                        new ModelSelectionService.AvailableModel(
                                "gpt-5",
                                "openai",
                                "GPT-5",
                                true,
                                List.of("low", "medium"),
                                false))));

        ModelSelectionCommandService.CommandOutcome result = service.handleModel(List.of("list"));

        assertTrue(result.success());
        assertTrue(result.output().contains("command.model.list.provider"));
        assertTrue(result.output().contains("gpt-5"));
        assertTrue(result.output().contains("[reasoning: low, medium]"));
    }

    @Test
    void shouldReturnFriendlyMessageWhenNoModelsAvailable() {
        when(modelSelectionService.getAvailableModelsGrouped()).thenReturn(Map.of());

        ModelSelectionCommandService.CommandOutcome result = service.handleModel(List.of("list"));

        assertTrue(result.success());
        assertTrue(result.output().contains("command.model.list.title"));
        assertTrue(result.output().contains("No models available."));
    }

    @Test
    void shouldRejectInvalidModelTier() {
        ModelSelectionCommandService.CommandOutcome result = service.handleModel(List.of("unknown", "openai/gpt-5"));

        assertTrue(result.success());
        assertEquals("command.model.invalid.tier", result.output());
    }

    @Test
    void shouldReturnUsageWhenModelTierMissingAction() {
        ModelSelectionCommandService.CommandOutcome result = service.handleModel(List.of("coding"));

        assertTrue(result.success());
        assertEquals("command.model.usage", result.output());
    }

    @Test
    void shouldAcceptSpecialTierForModelUsage() {
        ModelSelectionCommandService.CommandOutcome result = service.handleModel(List.of("special3"));

        assertTrue(result.success());
        assertEquals("command.model.usage", result.output());
    }

    @Test
    void shouldSetModelOverrideWithDefaultReasoning() {
        when(modelSelectionService.validateModel("openai/gpt-5"))
                .thenReturn(new ModelSelectionService.ValidationResult(true, null));
        when(modelSelectionService.getAvailableModels()).thenReturn(List.of(
                new ModelSelectionService.AvailableModel(
                        "gpt-5",
                        "openai",
                        "GPT-5",
                        true,
                        List.of("low", "medium"),
                        false)));

        ModelSelectionCommandService.CommandOutcome result = service.handleModel(List.of("balanced", "openai/gpt-5"));

        assertTrue(result.success());
        assertEquals("openai/gpt-5", preferences.getTierOverrides().get("balanced").getModel());
        assertEquals("medium", preferences.getTierOverrides().get("balanced").getReasoning());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldRejectUnknownModel() {
        when(modelSelectionService.validateModel("unknown/bad"))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "model.not.found"));

        ModelSelectionCommandService.CommandOutcome result = service.handleModel(List.of("coding", "unknown/bad"));

        assertTrue(result.success());
        assertEquals("command.model.invalid.model unknown/bad", result.output());
        verify(preferencesService, never()).savePreferences(any());
    }

    @Test
    void shouldExplainProviderMismatchUsingConfiguredProviders() {
        when(modelSelectionService.validateModel("anthropic/claude"))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "provider.not.configured"));
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("openai", "google"));

        ModelSelectionCommandService.CommandOutcome result = service
                .handleModel(List.of("balanced", "anthropic/claude"));

        assertTrue(result.success());
        assertTrue(result.output().contains("command.model.invalid.provider"));
        assertTrue(result.output().contains("openai, google"));
    }

    @Test
    void shouldSetReasoningLevelForExistingOverride() {
        preferences.getTierOverrides().put("balanced", new UserPreferences.TierOverride("openai/gpt-5", "low"));
        when(modelSelectionService.validateReasoning("openai/gpt-5", "medium"))
                .thenReturn(new ModelSelectionService.ValidationResult(true, null));

        ModelSelectionCommandService.CommandOutcome result = service
                .handleModel(List.of("balanced", "reasoning", "medium"));

        assertTrue(result.success());
        assertEquals("medium", preferences.getTierOverrides().get("balanced").getReasoning());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldRejectReasoningWhenOverrideMissing() {
        ModelSelectionCommandService.CommandOutcome result = service
                .handleModel(List.of("coding", "reasoning", "high"));

        assertTrue(result.success());
        assertEquals("command.model.no.override coding", result.output());
    }

    @Test
    void shouldRejectReasoningForNonReasoningModel() {
        preferences.getTierOverrides().put("coding", new UserPreferences.TierOverride("openai/gpt-5", null));
        when(modelSelectionService.validateReasoning("openai/gpt-5", "high"))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "no.reasoning"));

        ModelSelectionCommandService.CommandOutcome result = service
                .handleModel(List.of("coding", "reasoning", "high"));

        assertTrue(result.success());
        assertEquals("command.model.no.reasoning openai/gpt-5", result.output());
    }

    @Test
    void shouldExplainInvalidReasoningLevel() {
        preferences.getTierOverrides().put("coding", new UserPreferences.TierOverride("openai/gpt-5", "medium"));
        when(modelSelectionService.validateReasoning("openai/gpt-5", "xhigh"))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "level.not.available"));
        when(modelSelectionService.getAvailableModels()).thenReturn(List.of(
                new ModelSelectionService.AvailableModel(
                        "gpt-5",
                        "openai",
                        "GPT-5",
                        true,
                        List.of("low", "medium", "high"),
                        false)));

        ModelSelectionCommandService.CommandOutcome result = service
                .handleModel(List.of("coding", "reasoning", "xhigh"));

        assertTrue(result.success());
        assertEquals("command.model.invalid.reasoning xhigh low, medium, high", result.output());
    }

    @Test
    void shouldReturnUsageWhenReasoningLevelMissing() {
        ModelSelectionCommandService.CommandOutcome result = service.handleModel(List.of("coding", "reasoning"));

        assertTrue(result.success());
        assertEquals("command.model.usage", result.output());
    }

    @Test
    void shouldResetTierOverride() {
        preferences.getTierOverrides().put("balanced", new UserPreferences.TierOverride("openai/gpt-5", "low"));

        ModelSelectionCommandService.CommandOutcome result = service.handleModel(List.of("balanced", "reset"));

        assertTrue(result.success());
        assertTrue(preferences.getTierOverrides().isEmpty());
        verify(preferencesService).savePreferences(preferences);
    }

    private void stubSelections(String prefix) {
        for (String tier : ModelTierCatalog.orderedExplicitTiers()) {
            when(modelSelectionService.resolveForTier(tier))
                    .thenReturn(new ModelSelectionService.ModelSelection(prefix + tier, "medium"));
        }
    }
}

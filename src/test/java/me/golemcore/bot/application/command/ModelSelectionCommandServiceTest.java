package me.golemcore.bot.application.command;

import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
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

        CommandPort.CommandResult result = service.handleTier(List.of());

        assertTrue(result.success());
        assertTrue(result.output().contains("command.tier.current"));
        assertTrue(result.output().contains("coding"));
        assertTrue(result.output().contains("on"));
    }

    @Test
    void shouldSetTierAndForceWhenRequested() {
        CommandPort.CommandResult result = service.handleTier(List.of("smart", "force"));

        assertTrue(result.success());
        assertEquals("smart", preferences.getModelTier());
        assertTrue(preferences.isTierForce());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldRejectInvalidTier() {
        CommandPort.CommandResult result = service.handleTier(List.of("turbo"));

        assertTrue(result.success());
        assertEquals("command.tier.invalid", result.output());
    }

    @Test
    void shouldShowResolvedModelsForEachTier() {
        preferences.getTierOverrides().put("balanced", new UserPreferences.TierOverride("openai/gpt-5", "medium"));
        for (String tier : me.golemcore.bot.domain.model.ModelTierCatalog.orderedExplicitTiers()) {
            when(modelSelectionService.resolveForTier(tier))
                    .thenReturn(new ModelSelectionService.ModelSelection("openai/" + tier, "medium"));
        }

        CommandPort.CommandResult result = service.handleModel(List.of());

        assertTrue(result.success());
        assertTrue(result.output().contains("command.model.show.title"));
        assertTrue(result.output().contains("command.model.show.tier.override"));
        assertTrue(result.output().contains("command.model.show.tier.default"));
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

        CommandPort.CommandResult result = service.handleModel(List.of("list"));

        assertTrue(result.success());
        assertTrue(result.output().contains("command.model.list.provider"));
        assertTrue(result.output().contains("gpt-5"));
        assertTrue(result.output().contains("[reasoning: low, medium]"));
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

        CommandPort.CommandResult result = service.handleModel(List.of("balanced", "openai/gpt-5"));

        assertTrue(result.success());
        assertEquals("openai/gpt-5", preferences.getTierOverrides().get("balanced").getModel());
        assertEquals("medium", preferences.getTierOverrides().get("balanced").getReasoning());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldExplainProviderMismatchUsingConfiguredProviders() {
        when(modelSelectionService.validateModel("anthropic/claude"))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "provider.not.configured"));
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("openai", "google"));

        CommandPort.CommandResult result = service.handleModel(List.of("balanced", "anthropic/claude"));

        assertTrue(result.success());
        assertTrue(result.output().contains("command.model.invalid.provider"));
        assertTrue(result.output().contains("openai, google"));
    }

    @Test
    void shouldSetReasoningLevelForExistingOverride() {
        preferences.getTierOverrides().put("balanced", new UserPreferences.TierOverride("openai/gpt-5", "low"));
        when(modelSelectionService.validateReasoning("openai/gpt-5", "medium"))
                .thenReturn(new ModelSelectionService.ValidationResult(true, null));

        CommandPort.CommandResult result = service.handleModel(List.of("balanced", "reasoning", "medium"));

        assertTrue(result.success());
        assertEquals("medium", preferences.getTierOverrides().get("balanced").getReasoning());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldResetTierOverride() {
        preferences.getTierOverrides().put("balanced", new UserPreferences.TierOverride("openai/gpt-5", "low"));

        CommandPort.CommandResult result = service.handleModel(List.of("balanced", "reset"));

        assertTrue(result.success());
        assertTrue(preferences.getTierOverrides().isEmpty());
        verify(preferencesService).savePreferences(preferences);
    }
}

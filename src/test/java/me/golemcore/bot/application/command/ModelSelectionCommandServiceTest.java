package me.golemcore.bot.application.command;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelSelectionCommandServiceTest {

    private UserPreferencesService preferencesService;
    private ModelSelectionService modelSelectionService;
    private RuntimeConfigService runtimeConfigService;
    private SessionPort sessionPort;
    private UserPreferences preferences;
    private ModelSelectionCommandService service;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        sessionPort = mock(SessionPort.class);
        preferences = UserPreferences.builder()
                .tierOverrides(new LinkedHashMap<>())
                .build();
        when(preferencesService.getPreferences()).thenReturn(preferences);
        when(sessionPort.get(anyString())).thenReturn(Optional.empty());
        service = new ModelSelectionCommandService(preferencesService, modelSelectionService, runtimeConfigService,
                sessionPort);
    }

    @Test
    void shouldShowCurrentTierWhenNoArgs() {
        preferences.setModelTier("coding");
        preferences.setTierForce(true);

        ModelSelectionCommandService.CurrentTier result = assertInstanceOf(
                ModelSelectionCommandService.CurrentTier.class,
                service.handleTier(new ModelSelectionCommandService.ShowTierStatus()));

        assertEquals("coding", result.tier());
        assertTrue(result.force());
    }

    @Test
    void shouldShowCurrentTierFromSessionSettingsWhenSessionIsProvided() {
        AgentSession session = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("conv-1")
                .metadata(new LinkedHashMap<>(Map.of(
                        ContextAttributes.SESSION_MODEL_TIER, "deep",
                        ContextAttributes.SESSION_MODEL_TIER_FORCE, true)))
                .build();
        when(sessionPort.get("web:conv-1")).thenReturn(Optional.of(session));

        ModelSelectionCommandService.CurrentTier result = assertInstanceOf(
                ModelSelectionCommandService.CurrentTier.class,
                service.handleTier(new ModelSelectionCommandService.ShowTierStatus("web:conv-1")));

        assertEquals("deep", result.tier());
        assertTrue(result.force());
    }

    @Test
    void shouldSetTierAndForceWhenRequested() {
        ModelSelectionCommandService.TierUpdated result = assertInstanceOf(
                ModelSelectionCommandService.TierUpdated.class,
                service.handleTier(new ModelSelectionCommandService.SetTierSelection("smart", true)));

        assertEquals("smart", result.tier());
        assertTrue(result.force());
        assertEquals("smart", preferences.getModelTier());
        assertTrue(preferences.isTierForce());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldSetTierOnSessionWhenSessionIsProvided() {
        AgentSession session = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("conv-1")
                .metadata(new LinkedHashMap<>())
                .build();
        when(sessionPort.get("web:conv-1")).thenReturn(Optional.of(session));

        ModelSelectionCommandService.TierUpdated result = assertInstanceOf(
                ModelSelectionCommandService.TierUpdated.class,
                service.handleTier(new ModelSelectionCommandService.SetTierSelection("smart", true, "web:conv-1")));

        assertEquals("smart", result.tier());
        assertTrue(result.force());
        assertEquals("smart", session.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER));
        assertEquals(true, session.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER_FORCE));
        verify(sessionPort).save(session);
        verify(preferencesService, never()).savePreferences(any());
    }

    @Test
    void shouldCreateSessionBeforePersistingTierWhenSessionIsNotLoaded() {
        AgentSession session = AgentSession.builder()
                .id("web:conv-2")
                .channelType("web")
                .chatId("conv-2")
                .metadata(new LinkedHashMap<>())
                .build();
        when(sessionPort.get("web:conv-2")).thenReturn(Optional.empty());
        when(sessionPort.getOrCreate("web", "conv-2")).thenReturn(session);

        ModelSelectionCommandService.TierUpdated result = assertInstanceOf(
                ModelSelectionCommandService.TierUpdated.class,
                service.handleTier(new ModelSelectionCommandService.SetTierSelection("coding", false, "web:conv-2")));

        assertEquals("coding", result.tier());
        assertEquals("coding", session.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER));
        assertEquals(false, session.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER_FORCE));
        verify(sessionPort).save(session);
    }

    @Test
    void shouldClearForceWhenSettingWithoutForce() {
        preferences.setModelTier("smart");
        preferences.setTierForce(true);

        ModelSelectionCommandService.TierUpdated result = assertInstanceOf(
                ModelSelectionCommandService.TierUpdated.class,
                service.handleTier(new ModelSelectionCommandService.SetTierSelection("balanced", false)));

        assertEquals("balanced", result.tier());
        assertFalse(result.force());
        assertEquals("balanced", preferences.getModelTier());
        assertFalse(preferences.isTierForce());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldRejectInvalidTier() {
        ModelSelectionCommandService.InvalidTier result = assertInstanceOf(
                ModelSelectionCommandService.InvalidTier.class,
                service.handleTier(new ModelSelectionCommandService.SetTierSelection("turbo", false)));

        assertInstanceOf(ModelSelectionCommandService.InvalidTier.class, result);
        verify(preferencesService, never()).savePreferences(any());
    }

    @Test
    void shouldAcceptAllSelectableTiers() {
        for (String tier : ModelTierCatalog.orderedExplicitTiers()) {
            preferences.setTierForce(true);

            ModelSelectionCommandService.TierUpdated result = assertInstanceOf(
                    ModelSelectionCommandService.TierUpdated.class,
                    service.handleTier(new ModelSelectionCommandService.SetTierSelection(tier, false)));

            assertEquals(tier, result.tier());
            assertFalse(result.force());
            assertEquals(tier, preferences.getModelTier());
            assertFalse(preferences.isTierForce());
        }
    }

    @Test
    void shouldShowResolvedModelsForEachTierIncludingSpecials() {
        preferences.getTierOverrides().put("balanced", new UserPreferences.TierOverride("openai/gpt-5", "medium"));
        stubSelections("model-for-");

        ModelSelectionCommandService.ModelSelectionOverview result = assertInstanceOf(
                ModelSelectionCommandService.ModelSelectionOverview.class,
                service.handleModel(new ModelSelectionCommandService.ShowModelSelection()));

        assertEquals(ModelTierCatalog.orderedExplicitTiers().size(), result.tiers().size());
        assertTrue(result.tiers().stream()
                .anyMatch(selection -> "balanced".equals(selection.tier()) && selection.hasOverride()));
        assertTrue(result.tiers().stream().anyMatch(selection -> "special1".equals(selection.tier())));
        assertTrue(result.tiers().stream().anyMatch(selection -> "special5".equals(selection.tier())));
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
                                false,
                                true))));

        ModelSelectionCommandService.AvailableModels result = assertInstanceOf(
                ModelSelectionCommandService.AvailableModels.class,
                service.handleModel(new ModelSelectionCommandService.ListAvailableModels()));

        assertTrue(result.modelsByProvider().containsKey("openai"));
        assertEquals("gpt-5", result.modelsByProvider().get("openai").get(0).id());
        assertEquals(List.of("low", "medium"), result.modelsByProvider().get("openai").get(0).reasoningLevels());
    }

    @Test
    void shouldReturnEmptyCatalogWhenNoModelsAvailable() {
        when(modelSelectionService.getAvailableModelsGrouped()).thenReturn(Map.of());

        ModelSelectionCommandService.AvailableModels result = assertInstanceOf(
                ModelSelectionCommandService.AvailableModels.class,
                service.handleModel(new ModelSelectionCommandService.ListAvailableModels()));

        assertTrue(result.modelsByProvider().isEmpty());
    }

    @Test
    void shouldRejectInvalidModelTier() {
        ModelSelectionCommandService.InvalidModelTier result = assertInstanceOf(
                ModelSelectionCommandService.InvalidModelTier.class,
                service.handleModel(new ModelSelectionCommandService.SetModelOverride("unknown", "openai/gpt-5")));

        assertInstanceOf(ModelSelectionCommandService.InvalidModelTier.class, result);
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
                        false,
                        true)));

        ModelSelectionCommandService.ModelOverrideSet result = assertInstanceOf(
                ModelSelectionCommandService.ModelOverrideSet.class,
                service.handleModel(new ModelSelectionCommandService.SetModelOverride("balanced", "openai/gpt-5")));

        assertEquals("balanced", result.tier());
        assertEquals("openai/gpt-5", result.modelSpec());
        assertEquals("medium", result.defaultReasoning());
        assertEquals("openai/gpt-5", preferences.getTierOverrides().get("balanced").getModel());
        assertEquals("medium", preferences.getTierOverrides().get("balanced").getReasoning());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldInitializeTierOverridesWhenMissingAndAllowNullDefaultReasoning() {
        preferences.setTierOverrides(null);
        when(modelSelectionService.validateModel("openai/gpt-5-mini"))
                .thenReturn(new ModelSelectionService.ValidationResult(true, null));
        when(modelSelectionService.getAvailableModels()).thenReturn(List.of());

        ModelSelectionCommandService.ModelOverrideSet result = assertInstanceOf(
                ModelSelectionCommandService.ModelOverrideSet.class,
                service.handleModel(
                        new ModelSelectionCommandService.SetModelOverride("balanced", "openai/gpt-5-mini")));

        assertEquals("balanced", result.tier());
        assertEquals("openai/gpt-5-mini", result.modelSpec());
        assertNull(result.defaultReasoning());
        assertEquals("openai/gpt-5-mini", preferences.getTierOverrides().get("balanced").getModel());
        assertNull(preferences.getTierOverrides().get("balanced").getReasoning());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldKeepDefaultReasoningNullWhenCatalogContainsOnlyOtherModels() {
        when(modelSelectionService.validateModel("openai/gpt-5-mini"))
                .thenReturn(new ModelSelectionService.ValidationResult(true, null));
        when(modelSelectionService.getAvailableModels()).thenReturn(List.of(
                new ModelSelectionService.AvailableModel(
                        "gpt-5",
                        "openai",
                        "GPT-5",
                        true,
                        List.of("low", "medium"),
                        false,
                        true)));

        ModelSelectionCommandService.ModelOverrideSet result = assertInstanceOf(
                ModelSelectionCommandService.ModelOverrideSet.class,
                service.handleModel(
                        new ModelSelectionCommandService.SetModelOverride("balanced", "openai/gpt-5-mini")));

        assertNull(result.defaultReasoning());
        assertNull(preferences.getTierOverrides().get("balanced").getReasoning());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldRejectUnknownModel() {
        when(modelSelectionService.validateModel("unknown/bad"))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "model.not.found"));

        ModelSelectionCommandService.InvalidModel result = assertInstanceOf(
                ModelSelectionCommandService.InvalidModel.class,
                service.handleModel(new ModelSelectionCommandService.SetModelOverride("coding", "unknown/bad")));

        assertEquals("unknown/bad", result.modelSpec());
        verify(preferencesService, never()).savePreferences(any());
    }

    @Test
    void shouldExplainProviderMismatchUsingConfiguredProviders() {
        when(modelSelectionService.validateModel("anthropic/claude"))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "provider.not.configured"));
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("openai", "google"));

        ModelSelectionCommandService.ProviderNotConfigured result = assertInstanceOf(
                ModelSelectionCommandService.ProviderNotConfigured.class,
                service.handleModel(new ModelSelectionCommandService.SetModelOverride("balanced", "anthropic/claude")));

        assertEquals("anthropic/claude", result.modelSpec());
        assertEquals(List.of("openai", "google"), result.configuredProviders());
    }

    @Test
    void shouldSetReasoningLevelForExistingOverride() {
        preferences.getTierOverrides().put("balanced", new UserPreferences.TierOverride("openai/gpt-5", "low"));
        when(modelSelectionService.validateReasoning("openai/gpt-5", "medium"))
                .thenReturn(new ModelSelectionService.ValidationResult(true, null));

        ModelSelectionCommandService.ModelReasoningSet result = assertInstanceOf(
                ModelSelectionCommandService.ModelReasoningSet.class,
                service.handleModel(new ModelSelectionCommandService.SetReasoningLevel("balanced", "medium")));

        assertEquals("balanced", result.tier());
        assertEquals("medium", result.level());
        assertEquals("medium", preferences.getTierOverrides().get("balanced").getReasoning());
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldRejectReasoningWhenOverrideMissing() {
        ModelSelectionCommandService.MissingModelOverride result = assertInstanceOf(
                ModelSelectionCommandService.MissingModelOverride.class,
                service.handleModel(new ModelSelectionCommandService.SetReasoningLevel("coding", "high")));

        assertEquals("coding", result.tier());
    }

    @Test
    void shouldRejectReasoningWhenTierIsInvalid() {
        ModelSelectionCommandService.InvalidModelTier result = assertInstanceOf(
                ModelSelectionCommandService.InvalidModelTier.class,
                service.handleModel(new ModelSelectionCommandService.SetReasoningLevel("unknown", "high")));

        assertInstanceOf(ModelSelectionCommandService.InvalidModelTier.class, result);
        verify(preferencesService, never()).savePreferences(any());
    }

    @Test
    void shouldRejectReasoningForNonReasoningModel() {
        preferences.getTierOverrides().put("coding", new UserPreferences.TierOverride("openai/gpt-5", null));
        when(modelSelectionService.validateReasoning("openai/gpt-5", "high"))
                .thenReturn(new ModelSelectionService.ValidationResult(false, "no.reasoning"));

        ModelSelectionCommandService.MissingReasoningSupport result = assertInstanceOf(
                ModelSelectionCommandService.MissingReasoningSupport.class,
                service.handleModel(new ModelSelectionCommandService.SetReasoningLevel("coding", "high")));

        assertEquals("openai/gpt-5", result.modelSpec());
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
                        false,
                        true)));

        ModelSelectionCommandService.InvalidReasoningLevel result = assertInstanceOf(
                ModelSelectionCommandService.InvalidReasoningLevel.class,
                service.handleModel(new ModelSelectionCommandService.SetReasoningLevel("coding", "xhigh")));

        assertEquals("xhigh", result.requestedLevel());
        assertEquals(List.of("low", "medium", "high"), result.availableLevels());
    }

    @Test
    void shouldResetTierOverride() {
        preferences.getTierOverrides().put("balanced", new UserPreferences.TierOverride("openai/gpt-5", "low"));

        ModelSelectionCommandService.ModelOverrideReset result = assertInstanceOf(
                ModelSelectionCommandService.ModelOverrideReset.class,
                service.handleModel(new ModelSelectionCommandService.ResetModelOverride("balanced")));

        assertEquals("balanced", result.tier());
        assertNull(preferences.getTierOverrides().get("balanced"));
        verify(preferencesService).savePreferences(preferences);
    }

    @Test
    void shouldRejectResetWhenTierIsInvalid() {
        ModelSelectionCommandService.InvalidModelTier result = assertInstanceOf(
                ModelSelectionCommandService.InvalidModelTier.class,
                service.handleModel(new ModelSelectionCommandService.ResetModelOverride("unknown")));

        assertInstanceOf(ModelSelectionCommandService.InvalidModelTier.class, result);
        verify(preferencesService, never()).savePreferences(any());
    }

    private void stubSelections(String prefix) {
        for (String tier : ModelTierCatalog.orderedExplicitTiers()) {
            when(modelSelectionService.resolveForTier(tier))
                    .thenReturn(new ModelSelectionService.ModelSelection(prefix + tier, "medium"));
        }
    }
}

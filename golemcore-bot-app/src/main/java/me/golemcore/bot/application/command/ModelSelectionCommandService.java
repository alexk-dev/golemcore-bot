package me.golemcore.bot.application.command;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.sessions.SessionModelSettingsSupport;
import me.golemcore.bot.domain.support.StringValueSupport;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.port.outbound.SessionPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ModelSelectionCommandService {

    private static final String ERR_PROVIDER_NOT_CONFIGURED = "provider.not.configured";
    private static final String ERR_NO_REASONING = "no.reasoning";

    private final UserPreferencesService preferencesService;
    private final ModelSelectionService modelSelectionService;
    private final RuntimeConfigService runtimeConfigService;
    private final SessionPort sessionPort;

    public ModelSelectionCommandService(
            UserPreferencesService preferencesService,
            ModelSelectionService modelSelectionService,
            RuntimeConfigService runtimeConfigService,
            SessionPort sessionPort) {
        this.preferencesService = preferencesService;
        this.modelSelectionService = modelSelectionService;
        this.runtimeConfigService = runtimeConfigService;
        this.sessionPort = sessionPort;
    }

    public TierOutcome handleTier(TierRequest request) {
        if (request instanceof ShowTierStatus showTierStatus) {
            TierSettings tierSettings = resolveTierSettings(showTierStatus.sessionId());
            return new CurrentTier(tierSettings.tier(), tierSettings.force());
        }

        if (!(request instanceof SetTierSelection setTier)) {
            throw new IllegalArgumentException("Unsupported tier request: " + request.getClass().getSimpleName());
        }
        String tierArg = ModelTierCatalog.normalizeTierId(setTier.tier());
        if (!ModelTierCatalog.isExplicitSelectableTier(tierArg)) {
            return new InvalidTier();
        }

        boolean force = setTier.force();
        Optional<AgentSession> session = resolveSession(setTier.sessionId());
        if (session.isPresent()) {
            SessionModelSettingsSupport.writeModelSettings(session.get(), tierArg, force);
            sessionPort.save(session.get());
        } else {
            UserPreferences preferences = preferencesService.getPreferences();
            preferences.setModelTier(tierArg);
            preferences.setTierForce(force);
            preferencesService.savePreferences(preferences);
        }
        return new TierUpdated(tierArg, force);
    }

    public ModelOutcome handleModel(ModelRequest request) {
        if (request instanceof ShowModelSelection) {
            return handleModelShow();
        }
        if (request instanceof ListAvailableModels) {
            return handleModelList();
        }

        if (request instanceof SetModelOverride setModelOverride) {
            return handleModelSet(setModelOverride.tier(), setModelOverride.modelSpec());
        }
        if (request instanceof SetReasoningLevel setReasoningLevel) {
            return handleModelSetReasoning(setReasoningLevel.tier(), setReasoningLevel.level());
        }
        if (request instanceof ResetModelOverride resetModelOverride) {
            return handleModelReset(resetModelOverride.tier());
        }
        throw new IllegalArgumentException("Unsupported model request: " + request.getClass().getSimpleName());
    }

    private ModelOutcome handleModelShow() {
        UserPreferences preferences = preferencesService.getPreferences();
        List<TierSelection> selections = new ArrayList<>();
        for (String tier : ModelTierCatalog.orderedExplicitTiers()) {
            ModelSelectionService.ModelSelection selection = modelSelectionService.resolveForTier(tier);
            boolean hasOverride = preferences.getTierOverrides() != null
                    && preferences.getTierOverrides().containsKey(tier);
            selections.add(new TierSelection(tier, selection.model(), selection.reasoning(), hasOverride));
        }
        return new ModelSelectionOverview(List.copyOf(selections));
    }

    private ModelOutcome handleModelList() {
        Map<String, List<ModelSelectionService.AvailableModel>> grouped = modelSelectionService
                .getAvailableModelsGrouped();
        Map<String, List<AvailableModelOption>> modelsByProvider = new LinkedHashMap<>();
        for (Map.Entry<String, List<ModelSelectionService.AvailableModel>> entry : grouped.entrySet()) {
            List<AvailableModelOption> options = entry.getValue().stream()
                    .map(model -> new AvailableModelOption(
                            model.id(),
                            model.displayName(),
                            model.hasReasoning(),
                            List.copyOf(model.reasoningLevels())))
                    .toList();
            modelsByProvider.put(entry.getKey(), options);
        }
        return new AvailableModels(modelsByProvider);
    }

    private ModelOutcome handleModelSet(String tier, String modelSpec) {
        if (!ModelTierCatalog.isExplicitSelectableTier(tier)) {
            return new InvalidModelTier();
        }

        ModelSelectionService.ValidationResult validation = modelSelectionService.validateModel(modelSpec);
        if (!validation.valid()) {
            if (ERR_PROVIDER_NOT_CONFIGURED.equals(validation.error())) {
                return new ProviderNotConfigured(modelSpec,
                        List.copyOf(runtimeConfigService.getConfiguredLlmProviders()));
            }
            return new InvalidModel(modelSpec);
        }

        UserPreferences preferences = preferencesService.getPreferences();
        String defaultReasoning = findDefaultReasoning(modelSpec);
        Map<String, UserPreferences.TierOverride> tierOverrides = ensureTierOverrides(preferences);
        tierOverrides.put(tier, new UserPreferences.TierOverride(modelSpec, defaultReasoning));
        preferencesService.savePreferences(preferences);
        return new ModelOverrideSet(tier, modelSpec, defaultReasoning);
    }

    private ModelOutcome handleModelSetReasoning(String tier, String level) {
        if (!ModelTierCatalog.isExplicitSelectableTier(tier)) {
            return new InvalidModelTier();
        }

        UserPreferences preferences = preferencesService.getPreferences();
        UserPreferences.TierOverride existing = preferences.getTierOverrides() != null
                ? preferences.getTierOverrides().get(tier)
                : null;
        if (existing == null || existing.getModel() == null) {
            return new MissingModelOverride(tier);
        }

        ModelSelectionService.ValidationResult validation = modelSelectionService.validateReasoning(
                existing.getModel(), level);
        if (!validation.valid()) {
            if (ERR_NO_REASONING.equals(validation.error())) {
                return new MissingReasoningSupport(existing.getModel());
            }
            List<String> available = modelSelectionService.getAvailableModels().stream()
                    .filter(model -> model.id().equals(existing.getModel())
                            || existing.getModel().endsWith("/" + model.id()))
                    .flatMap(model -> model.reasoningLevels().stream())
                    .toList();
            return new InvalidReasoningLevel(level, available);
        }

        existing.setReasoning(level);
        preferencesService.savePreferences(preferences);
        return new ModelReasoningSet(tier, level);
    }

    private ModelOutcome handleModelReset(String tier) {
        if (!ModelTierCatalog.isExplicitSelectableTier(tier)) {
            return new InvalidModelTier();
        }

        UserPreferences preferences = preferencesService.getPreferences();
        if (preferences.getTierOverrides() != null) {
            preferences.getTierOverrides().remove(tier);
            preferencesService.savePreferences(preferences);
        }
        return new ModelOverrideReset(tier);
    }

    private String findDefaultReasoning(String modelSpec) {
        List<ModelSelectionService.AvailableModel> models = modelSelectionService.getAvailableModels();
        for (ModelSelectionService.AvailableModel model : models) {
            if (model.id().equals(modelSpec) || modelSpec.endsWith("/" + model.id())) {
                if (model.hasReasoning() && !model.reasoningLevels().isEmpty()) {
                    List<String> levels = model.reasoningLevels();
                    return levels.contains("medium") ? "medium" : levels.get(0);
                }
            }
        }
        return null;
    }

    private Map<String, UserPreferences.TierOverride> ensureTierOverrides(UserPreferences preferences) {
        if (preferences.getTierOverrides() == null) {
            preferences.setTierOverrides(new LinkedHashMap<>());
        }
        return preferences.getTierOverrides();
    }

    private TierSettings resolveTierSettings(String sessionId) {
        Optional<AgentSession> session = resolveSession(sessionId);
        if (session.isPresent() && SessionModelSettingsSupport.hasModelSettings(session.get())) {
            String tier = SessionModelSettingsSupport.readModelTier(session.get());
            return new TierSettings(tier != null ? tier : "balanced",
                    SessionModelSettingsSupport.readForce(session.get()));
        }

        UserPreferences preferences = preferencesService.getPreferences();
        String tier = preferences.getModelTier() != null ? preferences.getModelTier() : "balanced";
        return new TierSettings(tier, preferences.isTierForce());
    }

    private Optional<AgentSession> resolveSession(String sessionId) {
        if (sessionPort == null || StringValueSupport.isBlank(sessionId)) {
            return Optional.empty();
        }

        Optional<AgentSession> existing = sessionPort.get(sessionId);
        if (existing.isPresent()) {
            return existing;
        }

        int separatorIndex = sessionId.indexOf(':');
        if (separatorIndex <= 0 || separatorIndex + 1 >= sessionId.length()) {
            return Optional.empty();
        }
        String channelType = sessionId.substring(0, separatorIndex);
        String chatId = sessionId.substring(separatorIndex + 1);
        return Optional.ofNullable(sessionPort.getOrCreate(channelType, chatId));
    }

    private record TierSettings(String tier, boolean force) {
    }

    public sealed

    interface TierRequest
    permits ShowTierStatus, SetTierSelection
    {
        }

    public record ShowTierStatus(String sessionId) implements TierRequest {

        public ShowTierStatus() {
            this(null);
        }
    }

    public record SetTierSelection(String tier, boolean force, String sessionId) implements TierRequest {

    public SetTierSelection(String tier, boolean force) {
            this(tier, force, null);
        }
}

public sealed

interface TierOutcome
permits CurrentTier, TierUpdated, InvalidTier
{
    }

    public record CurrentTier(String tier, boolean force) implements TierOutcome {}

    public record TierUpdated(String tier, boolean force) implements TierOutcome {}

    public record InvalidTier() implements TierOutcome {}

    public sealed

    interface ModelRequest
    permits ShowModelSelection, ListAvailableModels, SetModelOverride, SetReasoningLevel, ResetModelOverride
    {
        }

    public record ShowModelSelection() implements ModelRequest {}

    public record ListAvailableModels() implements ModelRequest {}

    public record SetModelOverride(String tier, String modelSpec) implements ModelRequest {}

    public record SetReasoningLevel(String tier, String level) implements ModelRequest {}

    public record ResetModelOverride(String tier) implements ModelRequest {}

    public sealed

        interface ModelOutcome
        permits ModelSelectionOverview, AvailableModels, InvalidModelTier, ModelOverrideSet,
            ProviderNotConfigured, InvalidModel, MissingModelOverride, MissingReasoningSupport,
            InvalidReasoningLevel, ModelReasoningSet, ModelOverrideReset
        {
    }

    public record ModelSelectionOverview(List<TierSelection> tiers) implements ModelOutcome {}

    public record TierSelection(String tier, String model, String reasoning, boolean hasOverride) {}

    public record AvailableModels(Map<String, List<AvailableModelOption>> modelsByProvider) implements ModelOutcome {}

    public record AvailableModelOption(
            String id,
            String displayName,
            boolean hasReasoning,
            List<String> reasoningLevels
    ) {}

    public record InvalidModelTier() implements ModelOutcome {}

    public record ModelOverrideSet(String tier, String modelSpec, String defaultReasoning) implements ModelOutcome {}

    public record ProviderNotConfigured(String modelSpec, List<String> configuredProviders) implements ModelOutcome {}

    public record InvalidModel(String modelSpec) implements ModelOutcome {}

    public record MissingModelOverride(String tier) implements ModelOutcome {}

    public record MissingReasoningSupport(String modelSpec) implements ModelOutcome {}

    public record InvalidReasoningLevel(String requestedLevel, List<String> availableLevels) implements ModelOutcome {}

    public record ModelReasoningSet(String tier, String level) implements ModelOutcome {}

    public record ModelOverrideReset(String tier) implements ModelOutcome {}
}

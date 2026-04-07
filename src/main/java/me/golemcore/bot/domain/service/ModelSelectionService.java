package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralizes tier-to-model resolution with user override support. Used by
 * DefaultToolLoopSystem and AutoCompactionSystem to avoid duplicated
 * tier-to-model mapping logic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelSelectionService {

    private final RuntimeConfigService runtimeConfigService;
    private final ModelConfigService modelConfigService;
    private final UserPreferencesService preferencesService;

    /**
     * Resolve the model and reasoning level for a tier. Checks user overrides
     * first, falls back to router config defaults. Auto-fills default reasoning for
     * reasoning-required models.
     */
    public ModelSelection resolveForTier(String tier) {
        if (isImplicitDefaultTier(tier)) {
            return resolveForImplicitTier(tier);
        }
        return resolveForExplicitTier(tier);
    }

    public ModelSelection resolveForExplicitTier(String tier) {
        if (tier == null || tier.isBlank()) {
            throw new IllegalArgumentException("Model tier must not be blank");
        }
        if (!ModelTierCatalog.isKnownTier(tier)) {
            throw new IllegalArgumentException("Unknown model tier: " + tier);
        }

        ModelSelection override = resolveOverrideSelection(tier);
        if (override != null) {
            return validateResolvedSelection(tier, override);
        }
        return validateResolvedSelection(tier, resolveFromRouter(tier));
    }

    /**
     * Alias used by judge and evolution subsystems to make explicit tier resolution
     * intent obvious at call sites.
     */
    public ModelSelection resolveExplicitTier(String tier) {
        return resolveForExplicitTier(tier);
    }

    public ModelSelection resolveForImplicitTier(String tier) {
        String effectiveTier = normalizeImplicitTier(tier);
        ModelSelection override = resolveOverrideSelection(effectiveTier);
        if (override != null) {
            return validateResolvedSelection(effectiveTier, override);
        }
        return validateResolvedSelection(effectiveTier, resolveFromRouter(effectiveTier));
    }

    public ModelSelection resolveForContext(AgentContext context) {
        String effectiveTier = resolveEffectiveTier(context);
        if (isImplicitDefaultTier(effectiveTier)) {
            return resolveForImplicitTier(effectiveTier);
        }
        return resolveForExplicitTier(effectiveTier);
    }

    /**
     * Convenience method — resolve just the model name for a tier.
     */
    public String resolveModelName(String tier) {
        return resolveForTier(tier).model();
    }

    /**
     * Resolve the maximum input tokens for a tier, accounting for user overrides
     * and per-reasoning-level limits.
     */
    public int resolveMaxInputTokens(String tier) {
        ModelSelection selection;
        try {
            selection = resolveForTier(tier);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return runtimeConfigService.getCompactionMaxContextTokens();
        }
        return modelConfigService.getMaxInputTokens(selection.model(), selection.reasoning());
    }

    /**
     * Resolve the maximum input tokens for the effective model that would be used
     * for the provided context.
     */
    public int resolveMaxInputTokensForContext(AgentContext context) {
        ModelSelection selection;
        try {
            selection = resolveForContext(context);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return runtimeConfigService.getCompactionMaxContextTokens();
        }
        return modelConfigService.getMaxInputTokens(selection.model(), selection.reasoning());
    }

    /**
     * Get list of available models filtered by configured LLM providers in
     * RuntimeConfig.
     */
    public List<AvailableModel> getAvailableModels() {
        List<String> configuredProviders = runtimeConfigService.getConfiguredLlmProviders();
        Map<String, ModelConfigService.ModelSettings> filtered = modelConfigService
                .getModelsForProviders(configuredProviders);

        List<AvailableModel> result = new ArrayList<>();
        for (Map.Entry<String, ModelConfigService.ModelSettings> entry : filtered.entrySet()) {
            ModelConfigService.ModelSettings settings = entry.getValue();
            String displayName = settings.getDisplayName() != null ? settings.getDisplayName() : entry.getKey();
            boolean hasReasoning = modelConfigService.isReasoningRequired(entry.getKey());
            List<String> reasoningLevels = hasReasoning
                    ? modelConfigService.getAvailableReasoningLevels(entry.getKey())
                    : List.of();
            result.add(new AvailableModel(entry.getKey(), settings.getProvider(), displayName,
                    hasReasoning, reasoningLevels, settings.isSupportsVision()));
        }
        return result;
    }

    /**
     * Get available models grouped by provider.
     */
    public Map<String, List<AvailableModel>> getAvailableModelsGrouped() {
        Map<String, List<AvailableModel>> grouped = new LinkedHashMap<>();
        for (AvailableModel model : getAvailableModels()) {
            grouped.computeIfAbsent(model.provider(), k -> new ArrayList<>()).add(model);
        }
        return grouped;
    }

    /**
     * Validate that a model spec (provider/model) exists and its provider is
     * allowed.
     */
    public ValidationResult validateModel(String modelSpec) {
        return validateModel(modelSpec, runtimeConfigService.getConfiguredLlmProviders());
    }

    public ValidationResult validateModel(String modelSpec, List<String> configuredProviders) {
        String canonicalModelSpec = canonicalizeModelSpec(modelSpec, configuredProviders);
        if (canonicalModelSpec == null || canonicalModelSpec.isBlank()) {
            return new ValidationResult(false, "model.empty");
        }

        String provider = resolveProviderForModel(canonicalModelSpec);
        if (provider == null) {
            return new ValidationResult(false, "model.not.found");
        }

        List<String> allowedProviders = configuredProviders != null ? configuredProviders : List.of();
        if (!allowedProviders.contains(provider)) {
            return new ValidationResult(false, "provider.not.configured");
        }

        return new ValidationResult(true, null);
    }

    public String resolveProviderForModel(String modelSpec) {
        String canonicalModelSpec = canonicalizeModelSpec(modelSpec, runtimeConfigService.getConfiguredLlmProviders());
        if (canonicalModelSpec == null || canonicalModelSpec.isBlank()) {
            return null;
        }

        String normalizedSpec = canonicalModelSpec.trim();
        if (!hasKnownModelMatch(normalizedSpec)) {
            return null;
        }
        return modelConfigService.getModelSettings(normalizedSpec).getProvider();
    }

    /**
     * Validate that a reasoning level is available for a given model.
     */
    public ValidationResult validateReasoning(String modelSpec, String level) {
        if (!modelConfigService.isReasoningRequired(modelSpec)) {
            return new ValidationResult(false, "no.reasoning");
        }
        List<String> available = modelConfigService.getAvailableReasoningLevels(modelSpec);
        if (!available.contains(level)) {
            return new ValidationResult(false, "level.not.available");
        }
        return new ValidationResult(true, null);
    }

    private ModelSelection resolveFromRouter(String tier) {
        ModelSelection selected = switch (tier) {
        case "routing" ->
            new ModelSelection(runtimeConfigService.getRoutingModel(), runtimeConfigService.getRoutingModelReasoning());
        case "balanced" ->
            new ModelSelection(runtimeConfigService.getBalancedModel(),
                    runtimeConfigService.getBalancedModelReasoning());
        case "deep" ->
            new ModelSelection(runtimeConfigService.getDeepModel(), runtimeConfigService.getDeepModelReasoning());
        case "coding" ->
            new ModelSelection(runtimeConfigService.getCodingModel(), runtimeConfigService.getCodingModelReasoning());
        case "smart" ->
            new ModelSelection(runtimeConfigService.getSmartModel(), runtimeConfigService.getSmartModelReasoning());
        default -> resolveSpecialBinding(tier);
        };
        return selected;
    }

    private String resolveEffectiveTier(AgentContext context) {
        if (context != null && context.getModelTier() != null) {
            return context.getModelTier();
        }

        UserPreferences prefs = preferencesService.getPreferences();
        String userTier = prefs.getModelTier();
        if (prefs.isTierForce() && userTier != null) {
            return userTier;
        }

        Skill activeSkill = context != null ? context.getActiveSkill() : null;
        if (activeSkill != null && activeSkill.getModelTier() != null) {
            return activeSkill.getModelTier();
        }

        return userTier;
    }

    private boolean isImplicitDefaultTier(String tier) {
        return tier == null || tier.isBlank() || "default".equalsIgnoreCase(tier);
    }

    private String normalizeImplicitTier(String tier) {
        if (isImplicitDefaultTier(tier)) {
            return "balanced";
        }
        return ModelTierCatalog.isImplicitRoutingTier(tier) ? tier : "balanced";
    }

    private ModelSelection resolveOverrideSelection(String tier) {
        UserPreferences prefs = preferencesService.getPreferences();
        if (prefs.getTierOverrides() == null) {
            return null;
        }
        UserPreferences.TierOverride override = prefs.getTierOverrides().get(tier);
        if (override == null || override.getModel() == null || override.getModel().isBlank()) {
            return null;
        }
        return new ModelSelection(override.getModel(), override.getReasoning());
    }

    private ModelSelection resolveSpecialBinding(String tier) {
        RuntimeConfig.TierBinding binding = runtimeConfigService.getModelTierBinding(tier);
        if (binding == null) {
            return new ModelSelection(null, null);
        }
        return new ModelSelection(binding.getModel(), binding.getReasoning());
    }

    private ModelSelection validateResolvedSelection(String tier, ModelSelection selection) {
        if (selection == null || selection.model() == null || selection.model().isBlank()) {
            throw new IllegalStateException("Tier '" + tier + "' is not configured");
        }

        String canonicalModel = canonicalizeModelSpec(selection.model(),
                runtimeConfigService.getConfiguredLlmProviders());
        ValidationResult modelValidation = validateModel(canonicalModel);
        if (!modelValidation.valid()) {
            throw switch (modelValidation.error()) {
            case "model.not.found" ->
                new IllegalStateException("Tier '" + tier + "' points to unknown model '" + selection.model() + "'");
            case "provider.not.configured" ->
                new IllegalStateException("Tier '" + tier + "' points to model '" + selection.model()
                        + "' whose provider is not configured");
            default ->
                new IllegalStateException("Tier '" + tier + "' is invalid: " + modelValidation.error());
            };
        }

        String reasoning = selection.reasoning();
        if (reasoning == null && modelConfigService.isReasoningRequired(canonicalModel)) {
            reasoning = modelConfigService.getLowestReasoningLevel(canonicalModel);
        } else if (reasoning != null && modelConfigService.isReasoningRequired(canonicalModel)) {
            ValidationResult reasoningValidation = validateReasoning(canonicalModel, reasoning);
            if (!reasoningValidation.valid()) {
                throw new IllegalStateException("Tier '" + tier + "' uses unsupported reasoning '" + reasoning
                        + "' for model '" + canonicalModel + "'");
            }
        }

        log.debug("[ModelSelection] Resolved tier {}: model={}, reasoning={}",
                tier, canonicalModel, reasoning);
        return new ModelSelection(canonicalModel, reasoning);
    }

    private String canonicalizeModelSpec(String modelSpec, List<String> configuredProviders) {
        if (modelSpec == null || modelSpec.isBlank()) {
            return modelSpec;
        }

        String normalizedSpec = modelSpec.trim();
        Map<String, ModelConfigService.ModelSettings> allModels = modelConfigService.getAllModels();
        if (allModels.containsKey(normalizedSpec)) {
            return normalizedSpec;
        }

        String exactAliasCandidate = findUniqueCanonicalCandidate(normalizedSpec, allModels, configuredProviders, true);
        if (exactAliasCandidate != null) {
            return exactAliasCandidate;
        }

        String prefixAliasCandidate = findUniqueCanonicalCandidate(normalizedSpec, allModels, configuredProviders,
                false);
        if (prefixAliasCandidate != null) {
            return prefixAliasCandidate;
        }

        return normalizedSpec;
    }

    private String findUniqueCanonicalCandidate(
            String modelSpec,
            Map<String, ModelConfigService.ModelSettings> allModels,
            List<String> configuredProviders,
            boolean exactOnly) {
        List<String> matchingCandidates = allModels.entrySet().stream()
                .filter(entry -> configuredProviders == null
                        || configuredProviders.isEmpty()
                        || configuredProviders.contains(entry.getValue().getProvider()))
                .map(Map.Entry::getKey)
                .filter(candidate -> matchesCanonicalAlias(modelSpec, candidate, exactOnly))
                .toList();
        return matchingCandidates.size() == 1 ? matchingCandidates.getFirst() : null;
    }

    private boolean matchesCanonicalAlias(String modelSpec, String candidate, boolean exactOnly) {
        String normalizedCandidate = normalizeModelName(candidate);
        String lastSegment = candidate.contains("/") ? candidate.substring(candidate.lastIndexOf('/') + 1) : candidate;
        if (exactOnly) {
            return normalizedCandidate.equals(modelSpec) || lastSegment.equals(modelSpec);
        }
        return normalizedCandidate.startsWith(modelSpec) || lastSegment.startsWith(modelSpec);
    }

    private boolean hasKnownModelMatch(String modelSpec) {
        Map<String, ModelConfigService.ModelSettings> allModels = modelConfigService.getAllModels();
        String name = normalizeModelName(modelSpec);
        if (allModels.containsKey(modelSpec) || allModels.containsKey(name)) {
            return true;
        }
        return allModels.keySet().stream()
                .map(this::normalizeModelName)
                .anyMatch(name::startsWith);
    }

    private String normalizeModelName(String modelSpec) {
        if (modelSpec == null) {
            return "";
        }
        return modelSpec.contains("/") ? modelSpec.substring(modelSpec.indexOf('/') + 1) : modelSpec;
    }

    /** Resolved model + reasoning for a tier. */
    public record ModelSelection(String model, String reasoning) {
    }

    /** Available model for display in /model list. */
    public record AvailableModel(String id, String provider, String displayName,
            boolean hasReasoning, List<String> reasoningLevels, boolean supportsVision) {
    }

    /** Validation result for model/reasoning checks. */
    public record ValidationResult(boolean valid, String error) {
    }
}

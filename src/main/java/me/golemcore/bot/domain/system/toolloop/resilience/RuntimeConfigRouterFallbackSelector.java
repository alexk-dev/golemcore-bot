package me.golemcore.bot.domain.system.toolloop.resilience;

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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FallbackModes;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

@Slf4j
public class RuntimeConfigRouterFallbackSelector implements RouterFallbackSelector {

    private static final String DEFAULT_TIER = "balanced";

    private final RuntimeConfigService runtimeConfigService;
    private final RandomGenerator random;

    public RuntimeConfigRouterFallbackSelector(RuntimeConfigService runtimeConfigService) {
        this(runtimeConfigService, RandomGenerator.getDefault());
    }

    RuntimeConfigRouterFallbackSelector(RuntimeConfigService runtimeConfigService, RandomGenerator random) {
        this.runtimeConfigService = runtimeConfigService;
        this.random = random;
    }

    @Override
    public Optional<Selection> selectNext(AgentContext context) {
        return selectNext(context, ignored -> true);
    }

    @Override
    public Optional<Selection> selectNext(AgentContext context, Predicate<String> modelAvailable) {
        if (context == null || runtimeConfigService == null) {
            return Optional.empty();
        }

        String tier = normalizeTier(context.getModelTier());
        RuntimeConfig.TierBinding binding = runtimeConfigService.getModelTierBinding(tier);
        if (binding == null || binding.getFallbacks() == null || binding.getFallbacks().isEmpty()) {
            return Optional.empty();
        }

        List<FallbackCandidate> candidates = buildCandidates(binding.getFallbacks());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Set<String> attemptedModels = readAttemptedModels(context);
        Set<String> excludedModels = new LinkedHashSet<>(attemptedModels);
        addIfPresent(excludedModels, readString(context, ContextAttributes.LLM_MODEL));
        addIfPresent(excludedModels, binding.getModel());

        List<FallbackCandidate> eligible = candidates.stream()
                .filter(candidate -> !excludedModels.contains(candidate.model()))
                .filter(candidate -> isModelAvailable(modelAvailable, candidate.model()))
                .toList();
        if (eligible.isEmpty()) {
            log.debug("[Resilience] L2 router fallback exhausted for tier {}", tier);
            return Optional.empty();
        }

        String mode = FallbackModes.normalize(binding.getFallbackMode());
        FallbackCandidate selected = switch (mode) {
        case FallbackModes.ROUND_ROBIN -> selectRoundRobin(context, candidates, eligible);
        case FallbackModes.WEIGHTED -> selectWeighted(eligible);
        default -> eligible.getFirst();
        };

        Selection selection = new Selection(tier, mode, selected.model(), selected.fallback().getReasoning());
        applySelection(context, selection, attemptedModels);
        return Optional.of(selection);
    }

    private boolean isModelAvailable(Predicate<String> modelAvailable, String model) {
        return modelAvailable == null || modelAvailable.test(model);
    }

    @Override
    public void clear(AgentContext context) {
        if (context == null || context.getAttributes() == null) {
            return;
        }
        context.getAttributes().remove(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL);
        context.getAttributes().remove(ContextAttributes.RESILIENCE_L2_FALLBACK_REASONING);
        context.getAttributes().remove(ContextAttributes.RESILIENCE_L2_FALLBACK_MODE);
        context.getAttributes().remove(ContextAttributes.RESILIENCE_L2_ATTEMPTED_MODELS);
        context.getAttributes().remove(ContextAttributes.RESILIENCE_L2_ROUND_ROBIN_CURSOR);
    }

    private List<FallbackCandidate> buildCandidates(List<RuntimeConfig.TierFallback> fallbacks) {
        List<FallbackCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < fallbacks.size(); index++) {
            RuntimeConfig.TierFallback fallback = fallbacks.get(index);
            if (fallback == null) {
                continue;
            }
            String model = normalizeString(fallback.getModel());
            if (model != null) {
                candidates.add(new FallbackCandidate(index, fallback, model));
            }
        }
        return candidates;
    }

    private FallbackCandidate selectRoundRobin(AgentContext context, List<FallbackCandidate> candidates,
            List<FallbackCandidate> eligible) {
        int cursor = readRoundRobinCursor(context);
        for (int offset = 0; offset < candidates.size(); offset++) {
            int index = Math.floorMod(cursor + offset, candidates.size());
            FallbackCandidate candidate = candidates.get(index);
            if (eligible.contains(candidate)) {
                context.setAttribute(ContextAttributes.RESILIENCE_L2_ROUND_ROBIN_CURSOR,
                        Math.floorMod(index + 1, candidates.size()));
                return candidate;
            }
        }
        return eligible.getFirst();
    }

    private FallbackCandidate selectWeighted(List<FallbackCandidate> eligible) {
        double totalWeight = eligible.stream()
                .mapToDouble(candidate -> normalizedWeight(candidate.fallback().getWeight()))
                .sum();
        if (totalWeight <= 0.0d) {
            return eligible.getFirst();
        }

        double target = random.nextDouble() * totalWeight;
        double cursor = 0.0d;
        for (FallbackCandidate candidate : eligible) {
            cursor += normalizedWeight(candidate.fallback().getWeight());
            if (target < cursor) {
                return candidate;
            }
        }
        return eligible.getLast();
    }

    private double normalizedWeight(Double weight) {
        if (weight == null) {
            return 1.0d;
        }
        if (!Double.isFinite(weight) || weight <= 0.0d) {
            return 0.0d;
        }
        return weight;
    }

    private void applySelection(AgentContext context, Selection selection, Set<String> attemptedModels) {
        attemptedModels.add(selection.model());
        context.setAttribute(ContextAttributes.RESILIENCE_L2_ATTEMPTED_MODELS, new ArrayList<>(attemptedModels));
        context.setAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL, selection.model());
        putOptionalString(context, ContextAttributes.RESILIENCE_L2_FALLBACK_REASONING, selection.reasoning());
        context.setAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODE, selection.mode());
        context.setAttribute(ContextAttributes.LLM_MODEL, selection.model());
        putOptionalString(context, ContextAttributes.LLM_REASONING, selection.reasoning());
    }

    private Set<String> readAttemptedModels(AgentContext context) {
        Object value = context.getAttribute(ContextAttributes.RESILIENCE_L2_ATTEMPTED_MODELS);
        Set<String> attempted = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addIfPresent(attempted, item instanceof String stringValue ? stringValue : null);
            }
        } else if (value instanceof String stringValue) {
            addIfPresent(attempted, stringValue);
        }
        return attempted;
    }

    private int readRoundRobinCursor(AgentContext context) {
        Object value = context.getAttribute(ContextAttributes.RESILIENCE_L2_ROUND_ROBIN_CURSOR);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private String normalizeTier(String tier) {
        String normalized = ModelTierCatalog.normalizeTierId(tier);
        if (normalized == null || "default".equals(normalized)) {
            return DEFAULT_TIER;
        }
        return normalized;
    }

    private String readString(AgentContext context, String key) {
        Object value = context.getAttribute(key);
        return value instanceof String stringValue ? normalizeString(stringValue) : null;
    }

    private void putOptionalString(AgentContext context, String key, String value) {
        String normalized = normalizeString(value);
        if (normalized == null) {
            if (context.getAttributes() != null) {
                context.getAttributes().remove(key);
            }
            return;
        }
        context.setAttribute(key, normalized);
    }

    private void addIfPresent(Set<String> values, String value) {
        String normalized = normalizeString(value);
        if (normalized != null) {
            values.add(normalized);
        }
    }

    private String normalizeString(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record FallbackCandidate(int index, RuntimeConfig.TierFallback fallback, String model) {
    }
}

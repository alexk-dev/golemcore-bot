package me.golemcore.bot.domain.context;

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

import java.util.Comparator;
import java.util.List;

/**
 * Orchestrates context assembly for a single agent turn.
 *
 * <p>
 * Context assembly follows a layered architecture inspired by progressive
 * disclosure: each {@link ContextLayer} contributes an independent section of
 * the system prompt with its own lifecycle, priority, and token budget. The
 * assembler coordinates resolution of cross-cutting concerns (active skill,
 * model tier) first, then invokes layers in declared order, and finally
 * composes the system prompt via {@link PromptComposer}.
 *
 * <h2>Assembly Pipeline</h2>
 *
 * <pre>
 * 1. Resolve active skill       ({@link ContextResolver})
 * 2. Resolve model tier          ({@link ContextResolver})
 * 3. Collect context layers      (each {@link ContextLayer#assemble})
 * 4. Compose system prompt       ({@link PromptComposer})
 * 5. Publish metadata            ({@link ContextAttributes})
 * </pre>
 *
 * <p>
 * Each layer is self-contained: it reads what it needs from
 * {@link AgentContext}, produces a {@link ContextLayerResult}, and never
 * modifies other layers' state. The assembler enforces ordering via
 * {@link ContextLayer#getOrder()} and skips layers that return {@code false}
 * from {@link ContextLayer#appliesTo}.
 *
 * <h3>Diagnostics</h3>
 * <p>
 * The assembled {@link ContextBlueprint} is retained on the context for
 * downstream inspection via {@code ContextAttributes}. It records which layers
 * contributed, their token estimates, and any metadata they produced.
 */
@Slf4j
public class ContextAssembler {

    private final ContextResolver skillResolver;
    private final ContextResolver tierResolver;
    private final List<ContextLayer> layers;
    private final PromptComposer promptComposer;
    private final SystemPromptBudgetPolicy systemPromptBudgetPolicy;

    public ContextAssembler(ContextResolver skillResolver,
            ContextResolver tierResolver,
            List<ContextLayer> layers,
            PromptComposer promptComposer,
            SystemPromptBudgetPolicy systemPromptBudgetPolicy) {
        this.skillResolver = skillResolver;
        this.tierResolver = tierResolver;
        this.layers = layers;
        this.promptComposer = promptComposer;
        this.systemPromptBudgetPolicy = systemPromptBudgetPolicy;
    }

    /**
     * Assembles the full context for the current agent turn.
     *
     * <p>
     * This is the main entry point called by {@code ContextBuildingSystem}. It
     * resolves cross-cutting concerns, invokes all applicable context layers in
     * order, composes the system prompt, and publishes metadata.
     *
     * @param context
     *            the current agent context (mutated in place)
     * @return the same context with system prompt and metadata populated
     */
    public AgentContext assemble(AgentContext context) {
        log.debug("[ContextAssembler] Starting context assembly...");

        // Phase 1: Resolve cross-cutting concerns
        skillResolver.resolve(context);
        tierResolver.resolve(context);

        // Phase 2: Assemble all applicable context layers in order
        ContextBlueprint blueprint = ContextBlueprint.create();
        List<ContextLayer> orderedLayers = layers.stream()
                .sorted(Comparator.comparingInt(ContextLayer::getOrder))
                .toList();

        for (ContextLayer layer : orderedLayers) {
            if (layer.appliesTo(context)) {
                try {
                    ContextLayerResult result = applyLayerPolicy(layer, layer.assemble(context));
                    blueprint.add(result);
                    log.debug("[ContextAssembler] Layer '{}': {} chars, ~{} tokens",
                            layer.getName(),
                            result.hasContent() ? result.getContent().length() : 0,
                            result.getEstimatedTokens());
                } catch (Exception e) {
                    log.warn("[ContextAssembler] Layer '{}' failed: {}", layer.getName(), e.getMessage());
                    blueprint.add(applyLayerPolicy(layer, ContextLayerResult.empty(layer.getName())));
                }
            } else {
                log.debug("[ContextAssembler] Layer '{}' skipped (not applicable)", layer.getName());
            }
        }

        // Phase 3: Compose final system prompt
        int promptBudget = resolvePromptBudget(context);
        String systemPrompt = promptComposer.compose(blueprint, promptBudget);
        context.setSystemPrompt(systemPrompt);

        // Phase 4: Publish active skill metadata
        if (context.getActiveSkill() != null && context.getActiveSkill().getName() != null
                && !context.getActiveSkill().getName().isBlank()) {
            context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, context.getActiveSkill().getName());
        }

        log.info("[ContextAssembler] Assembled context: {} layers, ~{} assembled tokens, budget={}, {} chars",
                blueprint.getContentResults().size(),
                blueprint.getTotalEstimatedTokens(),
                promptBudget == Integer.MAX_VALUE ? "unlimited" : promptBudget,
                systemPrompt.length());

        return context;
    }

    private ContextLayerResult applyLayerPolicy(ContextLayer layer, ContextLayerResult result) {
        if (result == null) {
            result = ContextLayerResult.empty(layer.getName());
        }
        return result.toBuilder()
                .priority(layer.getPriority())
                .lifecycle(layer.getLifecycle())
                .tokenBudget(layer.getTokenBudget())
                .required(layer.isRequired())
                .criticality(layer.getCriticality())
                .build();
    }

    private int resolvePromptBudget(AgentContext context) {
        if (systemPromptBudgetPolicy == null) {
            return Integer.MAX_VALUE;
        }
        return systemPromptBudgetPolicy.resolveSystemPromptThreshold(context);
    }
}

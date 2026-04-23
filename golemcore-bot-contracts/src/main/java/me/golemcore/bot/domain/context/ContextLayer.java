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

import me.golemcore.bot.domain.model.AgentContext;

/**
 * A discrete, self-contained layer of agent context.
 *
 * <p>
 * Each layer encapsulates one logical section of the system prompt (identity,
 * memory, skills, tools, etc.). Layers declare their prompt order via
 * {@link #getOrder()}, their budget priority via {@link #getPriority()}, and
 * whether they apply to the current turn via {@link #appliesTo(AgentContext)}.
 * The runtime invokes applicable layers in order and delegates final prompt
 * composition to the prompt composer.
 *
 * <h2>Contract</h2>
 * <ul>
 * <li>{@link #appliesTo} — fast guard; return {@code false} to skip
 * entirely</li>
 * <li>{@link #assemble} — produce content; may read {@link AgentContext} but
 * must not modify other layers' output</li>
 * <li>{@link #getOrder} — determines position in the assembled prompt (lower =
 * earlier in prompt)</li>
 * </ul>
 *
 * <h2>Side Effects</h2>
 * <p>
 * Layers are allowed to mutate {@link AgentContext} fields that are explicitly
 * within their responsibility (e.g., {@code ToolLayer} sets
 * {@code availableTools}, {@code SkillLayer} sets {@code skillsSummary}).
 * However, layers must never modify content produced by other layers.
 */
public interface ContextLayer {

    /** Default selector priority for optional layers. Higher values win budget. */
    int DEFAULT_PRIORITY = 50;

    /** Priority for layers that should normally survive prompt budget pressure. */
    int REQUIRED_PRIORITY = 100;

    /** Marker for layers without their own per-layer token cap. */
    int UNLIMITED_TOKEN_BUDGET = Integer.MAX_VALUE;

    /**
     * Returns the unique name of this layer, used for identification in assembly
     * diagnostics and logging.
     *
     * @return layer name, never {@code null}
     */
    String getName();

    /**
     * Returns the assembly order. Lower values produce earlier sections in the
     * system prompt. Conventional ranges:
     * <ul>
     * <li>10–19: Identity and rules</li>
     * <li>20–29: Workspace instructions</li>
     * <li>30–39: Memory and RAG</li>
     * <li>40–49: Skills</li>
     * <li>50–59: Tools</li>
     * <li>60–69: Model/tier awareness</li>
     * <li>70–79: Mode-specific context (auto, plan, hive)</li>
     * </ul>
     *
     * @return ordering value
     */
    int getOrder();

    /**
     * Returns the selector priority used when the global system-prompt budget is
     * tight. Higher values are kept before lower values. The final prompt still
     * preserves {@link #getOrder()} among selected layers.
     *
     * @return priority, higher means more important
     */
    default int getPriority() {
        return DEFAULT_PRIORITY;
    }

    /**
     * Returns the expected lifecycle of this layer's content for diagnostics and
     * future relevance policies.
     *
     * @return lifecycle category
     */
    default ContextLayerLifecycle getLifecycle() {
        return ContextLayerLifecycle.TURN;
    }

    /**
     * Returns the per-layer soft token budget. Optional layers that exceed this cap
     * can be dropped even before the global prompt budget is considered.
     *
     * @return positive token budget, or {@link #UNLIMITED_TOKEN_BUDGET}
     */
    default int getTokenBudget() {
        return UNLIMITED_TOKEN_BUDGET;
    }

    /**
     * Returns whether this layer must be included even when it exceeds the prompt
     * budget. Required layers should be reserved for safety, mode semantics, and
     * hard response contracts.
     *
     * @return {@code true} if this layer cannot be dropped by prompt budgeting
     */
    default boolean isRequired() {
        return getPriority() >= REQUIRED_PRIORITY;
    }

    /**
     * Returns how prompt budgeting may treat this layer after rendering.
     *
     * @return layer hard-budget criticality
     */
    default LayerCriticality getCriticality() {
        return isRequired() ? LayerCriticality.REQUIRED_COMPRESSIBLE : LayerCriticality.OPTIONAL;
    }

    /**
     * Fast guard to determine if this layer should be included in the current
     * turn's context. Implementations should be lightweight (no I/O, no heavy
     * computation).
     *
     * @param context
     *            the current agent context
     * @return {@code true} if this layer should contribute to the prompt
     */
    boolean appliesTo(AgentContext context);

    /**
     * Assembles this layer's contribution to the system prompt.
     *
     * <p>
     * Implementations may read from {@link AgentContext} and external services, but
     * must return their output as a {@link ContextLayerResult} rather than directly
     * modifying the system prompt.
     *
     * @param context
     *            the current agent context
     * @return the assembled layer result, never {@code null}
     */
    ContextLayerResult assemble(AgentContext context);
}

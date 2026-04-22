package me.golemcore.bot.domain.context.layer;

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

import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.ContextLayerLifecycle;
import me.golemcore.bot.domain.context.ContextLayerResult;

/**
 * Shared metadata and result helpers for system-prompt context layers.
 */
public abstract class AbstractContextLayer implements ContextLayer {

    private final String name;
    private final int order;
    private final int priority;
    private final ContextLayerLifecycle lifecycle;
    private final int tokenBudget;
    private final boolean required;

    protected AbstractContextLayer(String name, int order, int priority, ContextLayerLifecycle lifecycle) {
        this(name, order, priority, lifecycle, UNLIMITED_TOKEN_BUDGET, false);
    }

    protected AbstractContextLayer(String name, int order, int priority,
            ContextLayerLifecycle lifecycle, int tokenBudget) {
        this(name, order, priority, lifecycle, tokenBudget, false);
    }

    protected AbstractContextLayer(String name, int order, int priority,
            ContextLayerLifecycle lifecycle, boolean required) {
        this(name, order, priority, lifecycle, UNLIMITED_TOKEN_BUDGET, required);
    }

    protected AbstractContextLayer(String name, int order, int priority,
            ContextLayerLifecycle lifecycle, int tokenBudget, boolean required) {
        this.name = name;
        this.order = order;
        this.priority = priority;
        this.lifecycle = lifecycle;
        this.tokenBudget = tokenBudget;
        this.required = required;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public ContextLayerLifecycle getLifecycle() {
        return lifecycle;
    }

    @Override
    public int getTokenBudget() {
        return tokenBudget;
    }

    @Override
    public boolean isRequired() {
        return required || priority >= REQUIRED_PRIORITY;
    }

    protected ContextLayerResult empty() {
        return ContextLayerResult.empty(name);
    }

    protected ContextLayerResult result(String content) {
        return ContextLayerResult.builder()
                .layerName(name)
                .content(content)
                .estimatedTokens(TokenEstimator.estimate(content))
                .build();
    }
}

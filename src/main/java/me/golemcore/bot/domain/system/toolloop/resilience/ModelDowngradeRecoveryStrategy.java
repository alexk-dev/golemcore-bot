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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L4b — Model downgrade as a degradation strategy.
 *
 * <p>
 * When the primary model (e.g., claude-opus) fails with a 500, this strategy
 * temporarily downgrades the model tier to a lighter alternative (e.g.,
 * claude-haiku). A degraded-quality response is better than no response for an
 * autonomous agent.
 *
 * <p>
 * The downgrade is applied by overriding the model tier in the context
 * attributes. The original tier is preserved so it can be restored on the next
 * successful turn.
 *
 * <p>
 * Applied at most once per turn. Only triggers for internal server errors.
 */
public class ModelDowngradeRecoveryStrategy implements RecoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(ModelDowngradeRecoveryStrategy.class);

    @Override
    public String name() {
        return "model_downgrade";
    }

    @Override
    public boolean isApplicable(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
        if (!config.getDegradationDowngradeModel()) {
            return false;
        }
        if (!LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER.equals(errorCode)
                && !LlmErrorClassifier.LANGCHAIN4J_TIMEOUT.equals(errorCode)) {
            return false;
        }
        Boolean alreadyAttempted = context.getAttribute(ContextAttributes.RESILIENCE_L4_MODEL_DOWNGRADE_ATTEMPTED);
        if (Boolean.TRUE.equals(alreadyAttempted)) {
            return false;
        }
        String fallbackTier = config.getDegradationFallbackModelTier();
        if (fallbackTier == null || fallbackTier.isBlank()) {
            return false;
        }
        String currentTier = context.getModelTier();
        return !fallbackTier.equals(currentTier);
    }

    @Override
    public RecoveryResult apply(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
        context.setAttribute(ContextAttributes.RESILIENCE_L4_MODEL_DOWNGRADE_ATTEMPTED, true);
        String originalTier = context.getModelTier();
        String fallbackTier = config.getDegradationFallbackModelTier();
        context.setAttribute(ContextAttributes.RESILIENCE_L4_ORIGINAL_MODEL_TIER, originalTier);
        context.setModelTier(fallbackTier);
        clearRouterFallbackState(context);
        log.info("[Resilience] L4 model downgrade: {} → {}", originalTier, fallbackTier);
        return RecoveryResult.success("downgraded model tier from " + originalTier + " to " + fallbackTier);
    }

    private void clearRouterFallbackState(AgentContext context) {
        if (context.getAttributes() == null) {
            return;
        }
        context.getAttributes().remove(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL);
        context.getAttributes().remove(ContextAttributes.RESILIENCE_L2_FALLBACK_REASONING);
        context.getAttributes().remove(ContextAttributes.RESILIENCE_L2_FALLBACK_MODE);
        context.getAttributes().remove(ContextAttributes.RESILIENCE_L2_ATTEMPTED_MODELS);
        context.getAttributes().remove(ContextAttributes.RESILIENCE_L2_ROUND_ROBIN_CURSOR);
    }
}

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.service.UserPreferencesService;

/**
 * Informs the LLM when a skill-recommended model tier is active.
 *
 * <p>
 * Only applies when a skill explicitly recommends a tier and the user has not
 * forced a different tier. Produces a brief note so the LLM is aware of the
 * tier context.
 */
@RequiredArgsConstructor
@Slf4j
public class TierAwarenessLayer implements ContextLayer {

    private final UserPreferencesService userPreferencesService;

    @Override
    public String getName() {
        return "tier_awareness";
    }

    @Override
    public int getOrder() {
        return 60;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        if (userPreferencesService.getPreferences().isTierForce()) {
            return false;
        }
        return context.getActiveSkill() != null
                && context.getActiveSkill().getModelTier() != null;
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        String content = "# Model Tier\n"
                + "The active skill '" + context.getActiveSkill().getName()
                + "' recommends the '" + context.getActiveSkill().getModelTier()
                + "' model tier. The system has switched to this tier.";

        return ContextLayerResult.builder()
                .layerName(getName())
                .content(content)
                .estimatedTokens((int) Math.ceil(content.length() / 3.5))
                .build();
    }
}

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
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Renders the agent's identity and behavioral rules from file-based prompt
 * sections.
 *
 * <p>
 * Loads modular prompt sections (identity, rules, voice, etc.) via
 * {@link PromptSectionService}, renders template variables (bot name, date,
 * timezone), and joins them into a single identity block.
 *
 * <p>
 * If no sections are configured or loaded, falls back to a minimal "You are a
 * helpful AI assistant." identity.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdentityLayer implements ContextLayer {

    private static final String FALLBACK = "You are a helpful AI assistant.";

    private final PromptSectionService promptSectionService;
    private final UserPreferencesService userPreferencesService;

    @Override
    public String getName() {
        return "identity";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return true;
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        StringBuilder sb = new StringBuilder();

        if (promptSectionService.isEnabled()) {
            Map<String, String> vars = promptSectionService
                    .buildTemplateVariables(userPreferencesService.getPreferences());
            for (PromptSection section : promptSectionService.getEnabledSections()) {
                String rendered = promptSectionService.renderSection(section, vars);
                if (rendered != null && !rendered.isBlank()) {
                    sb.append(rendered).append("\n\n");
                }
            }
        }

        if (sb.isEmpty()) {
            sb.append(FALLBACK);
        }

        String content = sb.toString().trim();
        return ContextLayerResult.builder()
                .layerName(getName())
                .content(content)
                .estimatedTokens(estimateTokens(content))
                .build();
    }

    private int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 3.5);
    }
}

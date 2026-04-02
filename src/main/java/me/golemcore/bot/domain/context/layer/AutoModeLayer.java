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
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.AutoModeService;
import org.springframework.stereotype.Component;

/**
 * Injects autonomous execution context (goals, tasks, diary) when the current
 * turn is an auto-mode message.
 *
 * <p>
 * Only applies when the last message has the {@code AUTO_MODE} flag. If the
 * turn is a reflection/recovery run, an additional preamble is added to guide
 * the LLM toward diagnosing failures.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutoModeLayer implements ContextLayer {

    private final AutoModeService autoModeService;

    @Override
    public String getName() {
        return "auto_mode";
    }

    @Override
    public int getOrder() {
        return 70;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return isAutoModeMessage(context);
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Autonomous Execution Guidelines\n");
        sb.append("This is an autonomous scheduled run. Your response must be a concise report ");
        sb.append("of what was accomplished. Do NOT include follow-up questions, suggestions ");
        sb.append("to continue, or offers to do more work. The user cannot respond in this context.\n\n");

        if (isAutoReflectionContext(context)) {
            sb.append("# Auto Reflection Mode\n");
            sb.append("This autonomous run is a recovery/reflection step after repeated failures. ");
            sb.append("Diagnose the failure, identify why the previous approach failed, ");
            sb.append("and propose a concrete alternative strategy for the next run.\n\n");
        }

        String autoContext = autoModeService.buildAutoContext();
        if (autoContext != null && !autoContext.isBlank()) {
            sb.append(autoContext);
        }

        String content = sb.toString().trim();
        if (content.isBlank()) {
            return ContextLayerResult.empty(getName());
        }

        return ContextLayerResult.builder()
                .layerName(getName())
                .content(content)
                .estimatedTokens((int) Math.ceil(content.length() / 3.5))
                .build();
    }

    private boolean isAutoModeMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null
                && Boolean.TRUE.equals(last.getMetadata().get(ContextAttributes.AUTO_MODE));
    }

    private boolean isAutoReflectionContext(AgentContext context) {
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE))) {
            return true;
        }
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null
                && Boolean.TRUE.equals(last.getMetadata().get(ContextAttributes.AUTO_REFLECTION_ACTIVE));
    }
}

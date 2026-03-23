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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.tools.HiveLifecycleSignalTool;
import org.springframework.stereotype.Component;

/**
 * Injects Hive card lifecycle guidance when the current session is bound to a
 * Hive card.
 *
 * <p>
 * Only applies when the session channel type is "hive". Instructs the LLM to
 * use the {@code hive_lifecycle_signal} tool for structured board state
 * transitions.
 */
@Component
@Slf4j
public class HiveLayer implements ContextLayer {

    @Override
    public String getName() {
        return "hive";
    }

    @Override
    public int getOrder() {
        return 75;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return context != null
                && context.getSession() != null
                && "hive".equalsIgnoreCase(context.getSession().getChannelType());
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        String content = "# Hive Card Lifecycle\n"
                + "This thread is bound to a Hive card. Use the `"
                + HiveLifecycleSignalTool.TOOL_NAME
                + "` tool whenever you need to report structured board-relevant state "
                + "such as blocker raised, blocker cleared, review requested, work completed, "
                + "progress reported, or intentional work failure. "
                + "Do not rely on plain text alone to move Hive card state. "
                + "`WORK_STARTED` and interruption-driven cancellation are emitted automatically.";

        return ContextLayerResult.builder()
                .layerName(getName())
                .content(content)
                .estimatedTokens((int) Math.ceil(content.length() / 3.5))
                .build();
    }
}

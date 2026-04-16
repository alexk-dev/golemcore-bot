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
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * L4c — Tool stripping as a degradation strategy.
 *
 * <p>
 * Some providers return 500 when the request carries a large tool-spec payload.
 * This strategy temporarily strips all tools from the request, giving the agent
 * a "think-only" turn. The agent loses the ability to execute tools for this
 * one iteration, but the autonomous loop stays alive.
 *
 * <p>
 * This is the most aggressive degradation — applied last in the chain, only
 * when context compaction and model downgrade have already failed.
 *
 * <p>
 * Applied at most once per turn. The original tool list is preserved in a
 * context attribute for restoration on the next iteration.
 */
public class ToolStripRecoveryStrategy implements RecoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(ToolStripRecoveryStrategy.class);
    private static final String STRIP_ATTEMPTED_FLAG = "resilience.l4.tool_strip_attempted";
    private static final String ORIGINAL_TOOLS_KEY = "resilience.l4.original_tools";

    @Override
    public String name() {
        return "tool_strip";
    }

    @Override
    public boolean isApplicable(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
        if (!config.getDegradationStripTools()) {
            return false;
        }
        if (!LlmErrorClassifier.isTransientCode(errorCode)) {
            return false;
        }
        Boolean alreadyAttempted = context.getAttribute(STRIP_ATTEMPTED_FLAG);
        if (Boolean.TRUE.equals(alreadyAttempted)) {
            return false;
        }
        List<ToolDefinition> tools = context.getAvailableTools();
        return tools != null && !tools.isEmpty();
    }

    @Override
    public RecoveryResult apply(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
        context.setAttribute(STRIP_ATTEMPTED_FLAG, true);
        List<ToolDefinition> originalTools = context.getAvailableTools();
        int toolCount = originalTools != null ? originalTools.size() : 0;
        context.setAttribute(ORIGINAL_TOOLS_KEY, originalTools);
        context.setAvailableTools(List.of());
        log.info("[Resilience] L4 tool strip: removed {} tools for think-only turn", toolCount);
        return RecoveryResult.success("stripped " + toolCount + " tools for think-only turn");
    }
}

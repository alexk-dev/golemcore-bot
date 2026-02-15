package me.golemcore.bot.tools;

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

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.PlanService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Plan-mode-only tool used as a deterministic signal that plan collection is
 * complete.
 *
 * <p>
 * Invariants:
 * <ul>
 * <li>Must be advertised to the LLM only when plan mode is active</li>
 * <li>Must be denied if called outside plan mode</li>
 * <li>Must have no external side effects (only plan state + user-facing output
 * downstream)</li>
 * </ul>
 */
@Component
public class PlanFinalizeTool implements ToolComponent {

    public static final String TOOL_NAME = "plan_finalize";

    private final PlanService planService;

    public PlanFinalizeTool(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Finalize the current plan. Use when you finished proposing plan steps. "
                        + "This does not execute tools; it only signals that planning is complete.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "summary", Map.of(
                                        "type", "string",
                                        "description", "Optional brief plan summary for the user"))))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        if (!planService.isPlanModeActive()) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(ToolFailureKind.POLICY_DENIED, "Plan mode is not active"));
        }

        // Intentionally no mutations here. Finalization is performed by the plan
        // finalization/interception system.
        return CompletableFuture.completedFuture(ToolResult.success("[Plan finalized]"));
    }

    @Override
    public boolean isEnabled() {
        return planService.isFeatureEnabled();
    }
}

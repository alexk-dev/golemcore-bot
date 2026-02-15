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
 * Plan-work-only tool used as a deterministic signal that a canonical plan
 * draft is ready to be persisted.
 */
@Component
public class PlanSetContentTool implements ToolComponent {

    public static final String TOOL_NAME = "plan_set_content";

    private final PlanService planService;

    public PlanSetContentTool(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Persist the canonical plan markdown for the active plan work. "
                        + "Provide the full plan as a Markdown document in plan_markdown.")
                .inputSchema(Map.of(
                        "type", "object",
                        "required", java.util.List.of("plan_markdown"),
                        "properties", Map.of(
                                "plan_markdown", Map.of(
                                        "type", "string",
                                        "description", "Full canonical plan draft as a single Markdown document."),
                                "title", Map.of(
                                        "type", "string",
                                        "description", "Optional short title for the plan."))))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        if (!planService.isPlanModeActive()) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(ToolFailureKind.POLICY_DENIED, "Plan work is not active"));
        }

        // Intentionally no mutations here. Finalization is performed by the plan
        // finalization system.
        return CompletableFuture.completedFuture(ToolResult.success("[Plan finalize requested]"));
    }

    @Override
    public boolean isEnabled() {
        return planService.isFeatureEnabled();
    }
}

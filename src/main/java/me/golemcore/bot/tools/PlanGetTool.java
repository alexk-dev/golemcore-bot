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
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.PlanService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only tool that returns the canonical plan markdown (SSOT) for the
 * current active plan.
 */
@Component
public class PlanGetTool implements ToolComponent {

    public static final String TOOL_NAME = "plan_get";

    private final PlanService planService;

    public PlanGetTool(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Get the current canonical plan markdown (SSOT) for the active plan work.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of()))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        if (!planService.isPlanModeActive()) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(ToolFailureKind.POLICY_DENIED, "Plan work is not active"));
        }

        return CompletableFuture.completedFuture(planService.getActivePlan()
                .map(Plan::getMarkdown)
                .filter(md -> md != null && !md.isBlank())
                .map(ToolResult::success)
                .orElseGet(() -> ToolResult.failure(ToolFailureKind.POLICY_DENIED, "No plan markdown available")));
    }

    @Override
    public boolean isEnabled() {
        return planService.isFeatureEnabled();
    }
}

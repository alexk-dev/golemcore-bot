package me.golemcore.bot.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.planning.PlanService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import org.springframework.stereotype.Component;

/**
 * Tool used by the model to finish an active planning turn.
 */
@Component
public class PlanExitTool implements ToolComponent {

    private final PlanService planService;

    public PlanExitTool(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(ToolNames.PLAN_EXIT)
                .description("Finish Plan Mode after the final user-visible plan is ready.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "plan_markdown", Map.of(
                                        "type", "string",
                                        "description", "The final plan to show to the user before execution.")),
                        "required", java.util.List.of("plan_markdown")))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        AgentContext context = AgentContextHolder.get();
        SessionIdentity sessionIdentity = context != null
                ? SessionIdentitySupport.resolveSessionIdentity(context.getSession())
                : null;
        if (sessionIdentity != null) {
            if (!planService.isPlanModeActive(sessionIdentity)) {
                return completedFailure();
            }
            if (readPlanMarkdown(parameters) == null) {
                return completedPlanMarkdownFailure();
            }
            planService.completePlanMode(sessionIdentity);
        } else {
            if (!planService.isPlanModeActive()) {
                return completedFailure();
            }
            if (readPlanMarkdown(parameters) == null) {
                return completedPlanMarkdownFailure();
            }
            planService.completePlanMode();
        }
        return CompletableFuture.completedFuture(ToolResult.success(
                "Plan mode has ended. Wait for the user before executing the plan."));
    }

    private String readPlanMarkdown(Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get("plan_markdown");
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text;
    }

    private CompletableFuture<ToolResult> completedFailure() {
        return CompletableFuture.completedFuture(ToolResult.failure("Plan mode is not active"));
    }

    private CompletableFuture<ToolResult> completedPlanMarkdownFailure() {
        return CompletableFuture.completedFuture(ToolResult.failure("plan_markdown is required"));
    }
}

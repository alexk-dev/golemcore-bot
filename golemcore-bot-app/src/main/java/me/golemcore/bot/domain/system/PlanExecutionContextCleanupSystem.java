package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.planning.PlanService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;

public class PlanExecutionContextCleanupSystem implements AgentSystem {

    private final PlanService planService;

    public PlanExecutionContextCleanupSystem(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public String getName() {
        return "PlanExecutionContextCleanupSystem";
    }

    @Override
    public int getOrder() {
        return 59;
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        if (context == null
                || !Boolean.TRUE.equals(context.getAttribute(ContextAttributes.PLAN_EXECUTION_CONTEXT_PENDING))) {
            return false;
        }
        TurnOutcome outcome = context.getTurnOutcome();
        if (outcome != null) {
            return outcome.getFinishReason() == FinishReason.SUCCESS;
        }
        return Boolean.TRUE.equals(context.getAttribute(ContextAttributes.FINAL_ANSWER_READY))
                && context.getAttribute(ContextAttributes.LLM_ERROR) == null
                && !Boolean.TRUE.equals(context.getAttribute(ContextAttributes.ITERATION_LIMIT_REACHED));
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (!shouldProcess(context)) {
            return context;
        }
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(context.getSession());
        if (sessionIdentity != null) {
            planService.consumeExecutionContext(sessionIdentity);
        } else {
            planService.consumeExecutionContext();
        }
        if (context.getAttributes() != null) {
            context.getAttributes().remove(ContextAttributes.PLAN_EXECUTION_CONTEXT_PENDING);
        }
        return context;
    }
}

package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.planning.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PlanExecutionContextCleanupSystemTest {

    private PlanService planService;
    private PlanExecutionContextCleanupSystem system;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        system = new PlanExecutionContextCleanupSystem(planService);
    }

    @Test
    void shouldDeclareOrderAfterTurnOutcomeAndBeforeResponseRouting() {
        assertEquals("PlanExecutionContextCleanupSystem", system.getName());
        assertEquals(59, system.getOrder());
    }

    @Test
    void shouldConsumePendingExecutionContextAfterFinalAnswerReady() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        context.setAttribute(ContextAttributes.PLAN_EXECUTION_CONTEXT_PENDING, true);
        context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);

        system.process(context);

        verify(planService).consumeExecutionContext(any());
        assertNull(context.getAttribute(ContextAttributes.PLAN_EXECUTION_CONTEXT_PENDING));
    }

    @Test
    void shouldKeepPendingExecutionContextWhenFinalAnswerIsNotReady() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        context.setAttribute(ContextAttributes.PLAN_EXECUTION_CONTEXT_PENDING, true);

        system.process(context);

        verify(planService, never()).consumeExecutionContext(any());
        assertEquals(Boolean.TRUE, context.getAttribute(ContextAttributes.PLAN_EXECUTION_CONTEXT_PENDING));
    }

    @Test
    void shouldKeepPendingExecutionContextWhenTurnFinishedWithError() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .turnOutcome(TurnOutcome.builder().finishReason(FinishReason.ERROR).build())
                .build();
        context.setAttribute(ContextAttributes.PLAN_EXECUTION_CONTEXT_PENDING, true);
        context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);

        system.process(context);

        verify(planService, never()).consumeExecutionContext(any());
        assertEquals(Boolean.TRUE, context.getAttribute(ContextAttributes.PLAN_EXECUTION_CONTEXT_PENDING));
    }

    @Test
    void shouldNotProcessWithoutPendingExecutionContext() {
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, true);

        assertFalse(system.shouldProcess(context));

        system.process(context);

        verify(planService, never()).consumeExecutionContext();
        verify(planService, never()).consumeExecutionContext(any());
    }
}

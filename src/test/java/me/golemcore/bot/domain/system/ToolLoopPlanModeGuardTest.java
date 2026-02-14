package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.system.toolloop.ToolLoopSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolLoopPlanModeGuardTest {

    @Test
    void shouldNotProcessWhenPlanModeIsActive() {
        ToolLoopSystem toolLoopSystem = mock(ToolLoopSystem.class);
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(true);

        ToolLoopExecutionSystem system = new ToolLoopExecutionSystem(toolLoopSystem, planService);

        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("telegram").chatId("1").build())
                .build();

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenPlanModeIsNotActive() {
        ToolLoopSystem toolLoopSystem = mock(ToolLoopSystem.class);
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(false);

        ToolLoopExecutionSystem system = new ToolLoopExecutionSystem(toolLoopSystem, planService);

        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("telegram").chatId("1").build())
                .build();

        assertTrue(system.shouldProcess(context));
    }
}

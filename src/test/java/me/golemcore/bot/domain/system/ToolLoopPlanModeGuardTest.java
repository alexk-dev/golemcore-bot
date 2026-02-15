package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.system.toolloop.ToolLoopSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies that ToolLoopExecutionSystem no longer blocks plan mode. Plan-mode
 * interception is now handled inside DefaultToolLoopSystem.
 */
class ToolLoopPlanModeGuardTest {

    @Test
    void shouldProcessEvenWhenPlanModeIsActive() {
        ToolLoopSystem toolLoopSystem = mock(ToolLoopSystem.class);

        ToolLoopExecutionSystem system = new ToolLoopExecutionSystem(toolLoopSystem);

        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("telegram").chatId("1").build())
                .build();

        // Plan mode guard was removed from ToolLoopExecutionSystem;
        // plan interception is now inside DefaultToolLoopSystem.
        assertTrue(system.shouldProcess(context));
    }
}

package me.golemcore.bot.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.PlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanExitToolTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-23T12:00:00Z"), ZoneOffset.UTC);
    private static final SessionIdentity SESSION = new SessionIdentity("web", "chat-1");

    private PlanService planService;
    private PlanExitTool tool;
    private AgentContext context;

    @BeforeEach
    void setUp() {
        planService = new PlanService(CLOCK);
        tool = new PlanExitTool(planService);
        context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("chat-1").build())
                .build();
        AgentContextHolder.set(context);
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void shouldExposePlanExitDefinition() {
        assertTrue(tool.isEnabled());
        assertTrue(tool.getDefinition().getName().equals(ToolNames.PLAN_EXIT));
        assertTrue(tool.getDefinition().getDescription().contains("Finish"));
    }

    @Test
    void shouldFinishActivePlanModeAndPrepareNextTurnContext() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        ToolResult result = tool.execute(Map.of()).join();

        assertTrue(result.isSuccess(), result.getError());
        assertFalse(planService.isPlanModeActive(SESSION));
        assertTrue(planService.hasPendingExecutionContext(SESSION));
    }

    @Test
    void shouldFailWhenPlanModeIsInactive() {
        ToolResult result = tool.execute(Map.of()).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not active"));
    }
}

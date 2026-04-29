package me.golemcore.bot.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import me.golemcore.bot.domain.planning.PlanService;
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
        planService = new PlanService(CLOCK, null);
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
        assertEquals(ToolNames.PLAN_EXIT, tool.getDefinition().getName());
        assertTrue(tool.getDefinition().getDescription().contains("Finish"));
        assertTrue(tool.getDefinition().getInputSchema().toString().contains("plan_markdown"));
    }

    @Test
    void shouldFinishActivePlanModeAndPrepareNextTurnContext() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        ToolResult result = tool.execute(Map.of("plan_markdown", "1. Inspect\n2. Implement")).join();

        assertTrue(result.isSuccess(), result.getError());
        assertFalse(planService.isPlanModeActive(SESSION));
        assertTrue(planService.hasPendingExecutionContext(SESSION));
    }

    @Test
    void shouldRejectBlankPlanMarkdownWithoutLeavingPlanMode() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        ToolResult result = tool.execute(Map.of("plan_markdown", " ")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("plan_markdown"));
        assertTrue(planService.isPlanModeActive(SESSION));
        assertFalse(planService.hasPendingExecutionContext(SESSION));
    }

    @Test
    void shouldRejectMissingPlanMarkdownWithoutLeavingPlanMode() {
        planService.activatePlanMode(SESSION, "chat-1", null);

        ToolResult result = tool.execute(Map.of()).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("plan_markdown"));
        assertTrue(planService.isPlanModeActive(SESSION));
        assertFalse(planService.hasPendingExecutionContext(SESSION));
    }

    @Test
    void shouldFailWhenPlanModeIsInactive() {
        ToolResult result = tool.execute(Map.of()).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not active"));
    }
}

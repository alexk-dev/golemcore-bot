package me.golemcore.bot.tools;

import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.PlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanSetContentToolTest {

    @BeforeEach
    void setUp() {
        AgentContextHolder.set(AgentContext.builder()
                .session(AgentSession.builder()
                        .channelType("telegram")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build());
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void shouldDenyOutsidePlanWork() {
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive(any())).thenReturn(false);

        PlanSetContentTool tool = new PlanSetContentTool(planService);
        ToolResult result = tool.execute(Map.of("plan_markdown", "# plan")).join();

        assertEquals(false, result.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, result.getFailureKind());
    }

    @Test
    void shouldSucceedInsidePlanWork() {
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive(any())).thenReturn(true);

        PlanSetContentTool tool = new PlanSetContentTool(planService);
        ToolResult result = tool.execute(Map.of("plan_markdown", "# plan")).join();

        assertEquals(true, result.isSuccess());
    }
}

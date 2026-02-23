package me.golemcore.bot.tools;

import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Plan;
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

class PlanGetToolTest {

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
    void shouldDenyWhenPlanWorkInactive() {
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive(any())).thenReturn(false);

        PlanGetTool tool = new PlanGetTool(planService);
        ToolResult result = tool.execute(Map.of()).join();

        assertEquals(false, result.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, result.getFailureKind());
    }

    @Test
    void shouldReturnMarkdownWhenActive() {
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive(any())).thenReturn(true);
        when(planService.getActivePlan(any()))
                .thenReturn(java.util.Optional.of(Plan.builder().markdown("# My plan").build()));

        PlanGetTool tool = new PlanGetTool(planService);
        ToolResult result = tool.execute(Map.of()).join();

        assertEquals(true, result.isSuccess());
        assertEquals("# My plan", result.getOutput());
    }
}

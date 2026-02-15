package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.ToolLoopTurnResult;
import me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.tools.PlanSetContentTool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolLoopPlanModeSetContentToolTest {

    private static final String PLAN_ID = "plan-123";
    private static final Instant NOW = Instant.parse("2026-02-15T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    @Test
    void shouldTreatPlanSetContentToolAsControlToolWithoutFinalizingPlan() {
        // GIVEN
        AgentSession session = AgentSession.builder()
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(session.getMessages())
                .build();

        LlmResponse response1 = LlmResponse.builder()
                .content("Plan is ready")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("tc1")
                        .name(PlanSetContentTool.TOOL_NAME)
                        .arguments(null)
                        .build()))
                .build();

        // The tool loop must continue after producing synthetic tool results.
        LlmResponse response2 = LlmResponse.builder()
                .content("Ok")
                .toolCalls(List.of())
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(response1),
                        java.util.concurrent.CompletableFuture.completedFuture(response2));

        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn(PLAN_ID);

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC)),
                new DefaultConversationViewBuilder(new FlatteningToolMessageMasker()),
                new BotProperties.TurnProperties(),
                new BotProperties.ToolLoopProperties(),
                new BotProperties.ModelRouterProperties(),
                planService,
                Clock.fixed(FAR_FUTURE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN
        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());

        // Finalization must be handled downstream (PlanFinalizationSystem)
        verify(planService, never()).finalizePlan(any());
        verify(toolExecutor, never()).execute(any(), any());

        // PlanFinalizationSystem depends on this attribute being set.
        assertEquals(true, ctx.getAttribute(ContextAttributes.PLAN_SET_CONTENT_REQUESTED));

        // Raw history should contain assistant tool-call message
        Message last = session.getMessages().get(session.getMessages().size() - 1);
        assertEquals("assistant", last.getRole());
        assertEquals("Ok", last.getContent());
    }
}

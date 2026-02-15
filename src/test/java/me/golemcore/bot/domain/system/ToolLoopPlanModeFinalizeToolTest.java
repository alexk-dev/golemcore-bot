package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmRequest;
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
import me.golemcore.bot.tools.PlanFinalizeTool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolLoopPlanModeFinalizeToolTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2026-02-01T00:00:00Z");
    private static final String PLAN_ID = "plan-123";

    @Test
    void shouldFinalizePlanWhenPlanFinalizeToolCallIsPresent() {
        // GIVEN: a session with an incoming user message
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("chat1")
                .messages(new ArrayList<>())
                .build();

        session.addMessage(Message.builder()
                .role("user")
                .content("Make a plan")
                .timestamp(NOW)
                .channelType("telegram")
                .chatId("chat1")
                .build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        // AND: LLM responds with plan_finalize tool call
        LlmPort llmPort = mock(LlmPort.class);
        LlmResponse response = LlmResponse.builder()
                .content("Plan is ready")
                .toolCalls(List.of(
                        Message.ToolCall.builder()
                                .id("tc-final")
                                .name(PlanFinalizeTool.TOOL_NAME)
                                .arguments(Map.of("summary", "ok"))
                                .build()))
                .finishReason("tool_calls")
                .build();

        when(llmPort.chat(any(LlmRequest.class))).thenReturn(CompletableFuture.completedFuture(response));

        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn(PLAN_ID);

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC)),
                new DefaultConversationViewBuilder(new FlatteningToolMessageMasker()),
                new BotProperties.ToolLoopProperties(),
                new BotProperties.ModelRouterProperties(),
                planService,
                Clock.fixed(FAR_FUTURE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN
        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        verify(planService).finalizePlan(PLAN_ID);
        verify(toolExecutor, never()).execute(any(), any());

        // Raw history should contain assistant tool-call message
        Message last = session.getMessages().get(session.getMessages().size() - 1);
        assertEquals("assistant", last.getRole());
        assertEquals("Plan is ready", last.getContent());
    }
}

package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.ToolLoopTurnResult;
import me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that plan mode intercepts tool calls inside DefaultToolLoopSystem:
 * steps are recorded via PlanService, synthetic "[Planned]" results are
 * written, and tools are never actually executed.
 */
class ToolLoopPlanModeInterceptionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-02-01T00:00:00Z");
    private static final String PLAN_ID = "plan-123";
    private static final String CHANNEL_TYPE = "telegram";
    private static final String CHAT_ID = "chat1";
    private static final String TOOL_FILE_SYSTEM = "file_system";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_PATH = "path";

    @Test
    void shouldRecordPlanStepsAndWriteSyntheticResultsWhenPlanModeActive() {
        // GIVEN: a session with an incoming user message
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .messages(new ArrayList<>())
                .build();

        session.addMessage(Message.builder()
                .role("user")
                .content("Plan: list files then read one")
                .timestamp(NOW)
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        // AND: LLM returns tool calls on first call, then a final text answer
        LlmPort llmPort = mock(LlmPort.class);
        AtomicInteger llmCalls = new AtomicInteger();

        LlmResponse firstResponse = LlmResponse.builder()
                .content("I will list files first")
                .toolCalls(List.of(
                        Message.ToolCall.builder()
                                .id("tc1")
                                .name(TOOL_FILE_SYSTEM)
                                .arguments(Map.of(PARAM_ACTION, "list", PARAM_PATH, "/tmp"))
                                .build(),
                        Message.ToolCall.builder()
                                .id("tc2")
                                .name(TOOL_FILE_SYSTEM)
                                .arguments(Map.of(PARAM_ACTION, "read", PARAM_PATH, "/tmp/a.txt"))
                                .build()))
                .build();

        LlmResponse finalResponse = LlmResponse.builder()
                .content("Plan complete: 2 steps collected")
                .toolCalls(List.of())
                .finishReason("stop")
                .build();

        when(llmPort.chat(any(LlmRequest.class))).thenAnswer(inv -> {
            int n = llmCalls.incrementAndGet();
            return CompletableFuture.completedFuture(n == 1 ? firstResponse : finalResponse);
        });

        // AND: PlanService is active
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn(PLAN_ID);

        // AND: a tool executor that should NOT be called
        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(any())).thenReturn(
                new ModelSelectionService.ModelSelection("gpt-4o", null));

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new DefaultConversationViewBuilder(new FlatteningToolMessageMasker()),
                settings,
                modelSelectionService,
                planService,
                Clock.fixed(DEADLINE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN: final answer is reached
        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());

        // AND: plan steps were recorded (2 tool calls)
        verify(planService).addStep(eq(PLAN_ID), eq(TOOL_FILE_SYSTEM),
                eq(Map.of(PARAM_ACTION, "list", PARAM_PATH, "/tmp")), anyString());
        verify(planService).addStep(eq(PLAN_ID), eq(TOOL_FILE_SYSTEM),
                eq(Map.of(PARAM_ACTION, "read", PARAM_PATH, "/tmp/a.txt")), anyString());

        // AND: tool executor was NEVER called (tools not executed in plan mode)
        verify(toolExecutor, never()).execute(any(), any());

        // AND: raw history contains synthetic "[Planned]" tool results
        List<Message> history = session.getMessages();
        long plannedCount = history.stream()
                .filter(m -> "tool".equals(m.getRole()) && "[Planned]".equals(m.getContent()))
                .count();
        assertEquals(2, plannedCount, "Expected 2 synthetic [Planned] tool results");

        // AND: final assistant message is present
        Message lastMsg = history.get(history.size() - 1);
        assertEquals("assistant", lastMsg.getRole());
        assertEquals("Plan complete: 2 steps collected", lastMsg.getContent());
    }

    @Test
    void shouldNotInterferWithFinalTextResponseInPlanMode() {
        // GIVEN: a session with a user message
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .messages(new ArrayList<>())
                .build();

        session.addMessage(Message.builder()
                .role("user")
                .content("Summarize what we planned")
                .timestamp(NOW)
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        // AND: LLM returns a final text answer (no tool calls)
        LlmPort llmPort = mock(LlmPort.class);
        LlmResponse finalResponse = LlmResponse.builder()
                .content("Here is your plan summary.")
                .toolCalls(List.of())
                .finishReason("stop")
                .build();
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(finalResponse));

        // AND: PlanService is active (but no tool calls to intercept)
        PlanService planService = mock(PlanService.class);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn(PLAN_ID);

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(any())).thenReturn(
                new ModelSelectionService.ModelSelection("gpt-4o", null));

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new DefaultConversationViewBuilder(new FlatteningToolMessageMasker()),
                settings,
                modelSelectionService,
                planService,
                Clock.fixed(DEADLINE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN: final answer produced normally
        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());

        // AND: no plan steps added (no tool calls)
        verify(planService, never()).addStep(anyString(), anyString(), any(), anyString());

        // AND: tool executor not called
        verify(toolExecutor, never()).execute(any(), any());

        // AND: final assistant message in history
        Message lastMsg = session.getMessages().get(session.getMessages().size() - 1);
        assertEquals("assistant", lastMsg.getRole());
        assertEquals("Here is your plan summary.", lastMsg.getContent());
    }
}

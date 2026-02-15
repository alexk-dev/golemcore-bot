package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DefaultHistoryWriterTest {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String TC_ID = "tc-1";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-02-14T00:00:00Z");

    private DefaultHistoryWriter writer;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"));
        writer = new DefaultHistoryWriter(clock);
    }

    private AgentContext buildContext(boolean withSession) {
        AgentSession session = null;
        if (withSession) {
            session = AgentSession.builder()
                    .id("sess-1")
                    .build();
        }

        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .maxIterations(1)
                .build();
    }

    // ==================== appendAssistantToolCalls ====================

    @Test
    void shouldAppendAssistantToolCallsWithContent() {
        AgentContext context = buildContext(true);
        LlmResponse response = LlmResponse.builder().content("thinking...").build();
        List<Message.ToolCall> toolCalls = List.of(
                Message.ToolCall.builder().id(TC_ID).name("test").build());

        writer.appendAssistantToolCalls(context, response, toolCalls);

        assertEquals(1, context.getMessages().size());
        Message msg = context.getMessages().get(0);
        assertEquals(ROLE_ASSISTANT, msg.getRole());
        assertEquals("thinking...", msg.getContent());
        assertEquals(1, msg.getToolCalls().size());
        assertEquals(FIXED_INSTANT, msg.getTimestamp());
        assertEquals(1, context.getSession().getMessages().size());
    }

    @Test
    void shouldAppendAssistantToolCallsWithNullLlmResponse() {
        AgentContext context = buildContext(true);
        List<Message.ToolCall> toolCalls = List.of(
                Message.ToolCall.builder().id(TC_ID).name("test").build());

        writer.appendAssistantToolCalls(context, null, toolCalls);

        assertEquals(1, context.getMessages().size());
        assertNull(context.getMessages().get(0).getContent());
    }

    @Test
    void shouldAppendAssistantToolCallsWithoutSession() {
        AgentContext context = buildContext(false);
        LlmResponse response = LlmResponse.builder().content("text").build();

        writer.appendAssistantToolCalls(context, response, List.of());

        assertEquals(1, context.getMessages().size());
    }

    // ==================== appendToolResult ====================

    @Test
    void shouldAppendToolResultWithSession() {
        AgentContext context = buildContext(true);
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TC_ID, "test_tool", ToolResult.success("result"), "result", false, null);

        writer.appendToolResult(context, outcome);

        assertEquals(1, context.getMessages().size());
        Message msg = context.getMessages().get(0);
        assertEquals(ROLE_TOOL, msg.getRole());
        assertEquals("tc-1", msg.getToolCallId());
        assertEquals("test_tool", msg.getToolName());
        assertEquals("result", msg.getContent());
        assertEquals(FIXED_INSTANT, msg.getTimestamp());
        assertEquals(1, context.getSession().getMessages().size());
    }

    @Test
    void shouldAppendToolResultWithoutSession() {
        AgentContext context = buildContext(false);
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TC_ID, "test_tool", ToolResult.success("ok"), "ok", false, null);

        writer.appendToolResult(context, outcome);

        assertEquals(1, context.getMessages().size());
    }

    // ==================== appendFinalAssistantAnswer ====================

    @Test
    void shouldAppendFinalAnswerWithLlmResponse() {
        AgentContext context = buildContext(true);
        LlmResponse response = LlmResponse.builder()
                .toolCalls(List.of(Message.ToolCall.builder().id(TC_ID).name("test").build()))
                .build();

        writer.appendFinalAssistantAnswer(context, response, "Final text");

        assertEquals(1, context.getMessages().size());
        Message msg = context.getMessages().get(0);
        assertEquals(ROLE_ASSISTANT, msg.getRole());
        assertEquals("Final text", msg.getContent());
        assertNotNull(msg.getToolCalls());
        assertEquals(1, context.getSession().getMessages().size());
    }

    @Test
    void shouldAppendFinalAnswerWithNullLlmResponse() {
        AgentContext context = buildContext(true);

        writer.appendFinalAssistantAnswer(context, null, "Fallback text");

        assertEquals(1, context.getMessages().size());
        Message msg = context.getMessages().get(0);
        assertEquals("Fallback text", msg.getContent());
        assertNull(msg.getToolCalls());
    }

    @Test
    void shouldAppendFinalAnswerWithoutSession() {
        AgentContext context = buildContext(false);
        LlmResponse response = LlmResponse.builder().content("text").build();

        writer.appendFinalAssistantAnswer(context, response, "Final");

        assertEquals(1, context.getMessages().size());
    }

    // ==================== Null clock ====================

    @Test
    void shouldFallbackToInstantNowWhenClockIsNull() {
        DefaultHistoryWriter nullClockWriter = new DefaultHistoryWriter(null);
        AgentContext context = buildContext(false);

        nullClockWriter.appendToolResult(context, new ToolExecutionOutcome(
                TC_ID, "tool", ToolResult.success("ok"), "ok", false, null));

        assertEquals(1, context.getMessages().size());
        assertNotNull(context.getMessages().get(0).getTimestamp());
    }
}

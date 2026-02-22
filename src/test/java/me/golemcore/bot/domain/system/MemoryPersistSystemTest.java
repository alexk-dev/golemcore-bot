package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import me.golemcore.bot.domain.model.TurnOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MemoryPersistSystemTest {

    private static final String ROLE_USER = "user";
    private static final String SESSION_ID = "test";

    private MemoryComponent memoryComponent;
    private MemoryPersistSystem system;

    @BeforeEach
    void setUp() {
        memoryComponent = mock(MemoryComponent.class);
        system = new MemoryPersistSystem(memoryComponent);
    }

    private AgentContext contextWith(List<Message> messages, LlmResponse response) {
        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id(SESSION_ID).build())
                .messages(new ArrayList<>(messages))
                .build();
        if (response != null) {
            ctx.setAttribute(ContextAttributes.LLM_RESPONSE, response);
        }
        return ctx;
    }

    @Test
    void orderIsFifty() {
        assertEquals(50, system.getOrder());
    }

    @Test
    void nameIsMemoryPersistSystem() {
        assertEquals("MemoryPersistSystem", system.getName());
    }

    @Test
    void processSkipsWhenNoMessages() {
        AgentContext ctx = contextWith(List.of(), LlmResponse.builder().content("response").build());

        system.process(ctx);

        verify(memoryComponent, never()).persistTurnMemory(any());
    }

    @Test
    void processSkipsWhenNoUserMessages() {
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("assistant").content("only assistant").timestamp(Instant.now()).build()),
                LlmResponse.builder().content("response").build());

        system.process(ctx);

        verify(memoryComponent, never()).persistTurnMemory(any());
    }

    @Test
    void processSkipsWhenNoAssistantResponse() {
        AgentContext ctx = contextWith(
                List.of(Message.builder().role(ROLE_USER).content("hello").timestamp(Instant.now()).build()),
                null);

        system.process(ctx);

        verify(memoryComponent, never()).persistTurnMemory(any());
    }

    @Test
    void processUsesAssistantTextFromTurnOutcome() {
        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id(SESSION_ID).build())
                .messages(new ArrayList<>(List.of(
                        Message.builder().role(ROLE_USER).content("question").timestamp(Instant.now()).build())))
                .build();
        ctx.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .assistantText("answer from outcome")
                .build());
        ctx.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content("legacy answer").build());

        system.process(ctx);

        verify(memoryComponent).persistTurnMemory(argThat(event -> event != null
                && "question".equals(event.getUserText())
                && "answer from outcome".equals(event.getAssistantText())));
    }

    @Test
    void processFallsBackToLlmResponseWhenNoTurnOutcome() {
        AgentContext ctx = contextWith(
                List.of(Message.builder().role(ROLE_USER).content("hi").timestamp(Instant.now()).build()),
                LlmResponse.builder().content("legacy reply").build());

        system.process(ctx);

        verify(memoryComponent).persistTurnMemory(argThat(event -> event != null
                && "legacy reply".equals(event.getAssistantText())));
    }

    @Test
    void processPersistsStructuredTurnMemoryEvent() {
        AgentContext ctx = contextWith(
                List.of(Message.builder().role(ROLE_USER).content("user input").timestamp(Instant.now()).build()),
                LlmResponse.builder().content("assistant reply").build());
        ctx.setToolResults(Map.of("t1", ToolResult.success("tool output")));

        system.process(ctx);

        verify(memoryComponent).persistTurnMemory(argThat(event -> event != null
                && "user input".equals(event.getUserText())
                && "assistant reply".equals(event.getAssistantText())
                && event.getToolOutputs() != null
                && event.getToolOutputs().stream().anyMatch(output -> output.contains("tool output"))));
    }

    @Test
    void processTruncatesToolOutputsToExpectedLimit() {
        String longToolOutput = "x".repeat(1200);
        AgentContext ctx = contextWith(
                List.of(Message.builder().role(ROLE_USER).content("user").timestamp(Instant.now()).build()),
                LlmResponse.builder().content("assistant").build());
        ctx.setToolResults(Map.of("tool", ToolResult.success(longToolOutput)));

        system.process(ctx);

        verify(memoryComponent).persistTurnMemory(argThat(event -> event != null
                && event.getToolOutputs() != null
                && !event.getToolOutputs().isEmpty()
                && event.getToolOutputs().get(0).length() == 800
                && event.getToolOutputs().get(0).endsWith("...")));
    }

    @Test
    void processPersistsErrorToolOutputWithPrefix() {
        AgentContext ctx = contextWith(
                List.of(Message.builder().role(ROLE_USER).content("user").timestamp(Instant.now()).build()),
                LlmResponse.builder().content("assistant").build());
        ctx.setToolResults(Map.of("tool", ToolResult.failure("boom")));

        system.process(ctx);

        verify(memoryComponent).persistTurnMemory(argThat(event -> event != null
                && event.getToolOutputs() != null
                && event.getToolOutputs().stream().anyMatch(output -> output.startsWith("Error: boom"))));
    }

    @Test
    void processHandlesStructuredPersistFailureGracefully() {
        doThrow(new RuntimeException("write error")).when(memoryComponent).persistTurnMemory(any(TurnMemoryEvent.class));

        AgentContext ctx = contextWith(
                List.of(Message.builder().role(ROLE_USER).content("hello").timestamp(Instant.now()).build()),
                LlmResponse.builder().content("reply").build());

        assertDoesNotThrow(() -> system.process(ctx));
    }

    @Test
    void shouldNotProcessWhenFinalAnswerNotReady() {
        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id(SESSION_ID).build())
                .messages(new ArrayList<>())
                .attributes(Map.of(ContextAttributes.FINAL_ANSWER_READY, false))
                .build();

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcessWhenFinalAnswerReady() {
        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id(SESSION_ID).build())
                .messages(new ArrayList<>())
                .attributes(Map.of(ContextAttributes.FINAL_ANSWER_READY, true))
                .build();

        assertTrue(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcessWhenTurnOutcomeHasAssistantText() {
        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id(SESSION_ID).build())
                .messages(new ArrayList<>())
                .build();
        ctx.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .assistantText("hello")
                .build());

        assertTrue(system.shouldProcess(ctx));
    }

    @Test
    void shouldNotProcessWhenTurnOutcomeHasBlankAssistantText() {
        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id(SESSION_ID).build())
                .messages(new ArrayList<>())
                .build();
        ctx.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .assistantText("   ")
                .build());

        assertFalse(system.shouldProcess(ctx));
    }
}

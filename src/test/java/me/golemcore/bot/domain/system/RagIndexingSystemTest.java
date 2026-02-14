package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.*;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.RagPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagIndexingSystemTest {

    private RagPort ragPort;
    private BotProperties properties;
    private RagIndexingSystem system;

    @BeforeEach
    void setUp() {
        ragPort = mock(RagPort.class);
        when(ragPort.isAvailable()).thenReturn(true);
        when(ragPort.index(anyString())).thenReturn(CompletableFuture.completedFuture(null));

        properties = new BotProperties();
        properties.getRag().setEnabled(true);
        properties.getRag().setIndexMinLength(50);

        system = new RagIndexingSystem(ragPort, properties);
    }

    @Test
    void nameAndOrder() {
        assertEquals("RagIndexingSystem", system.getName());
        assertEquals(55, system.getOrder());
    }

    @Test
    void isEnabledDelegatesToRagPort() {
        when(ragPort.isAvailable()).thenReturn(true);
        assertTrue(system.isEnabled());

        when(ragPort.isAvailable()).thenReturn(false);
        assertFalse(system.isEnabled());
    }

    @Test
    void indexesConversation() {
        AgentContext context = buildContext("What is the capital of France?",
                "The capital of France is Paris, known for the Eiffel Tower.");

        system.process(context);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(ragPort).index(captor.capture());
        String document = captor.getValue();
        assertTrue(document.contains("User: What is the capital of France?"));
        assertTrue(document.contains("Assistant: The capital of France is Paris"));
        assertTrue(document.contains("Date:"));
    }

    @Test
    void includesSkillNameInDocument() {
        AgentContext context = buildContext("Explain recursion",
                "Recursion is when a function calls itself to solve smaller subproblems.");
        context.setActiveSkill(Skill.builder().name("coding-help").build());

        system.process(context);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(ragPort).index(captor.capture());
        assertTrue(captor.getValue().contains("Skill: coding-help"));
    }

    @Test
    void skipsTrivialGreetings() {
        AgentContext context = buildContext("hello", "Hi there! How can I help?");
        system.process(context);
        verify(ragPort, never()).index(anyString());
    }

    @Test
    void skipsTrivialGreetingsWithPunctuation() {
        AgentContext context = buildContext("Hello!", "Hi! How can I help?");
        system.process(context);
        verify(ragPort, never()).index(anyString());
    }

    @Test
    void skipsShortExchanges() {
        AgentContext context = buildContext("yes", "ok");
        system.process(context);
        verify(ragPort, never()).index(anyString());
    }

    @Test
    void skipsWhenNoUserMessage() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content("response").build());

        system.process(context);
        verify(ragPort, never()).index(anyString());
    }

    @Test
    void skipsWhenNoLlmResponse() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("test question about something").build())))
                .build();

        system.process(context);
        verify(ragPort, never()).index(anyString());
    }

    @Test
    void skipsWhenLlmResponseEmpty() {
        AgentContext context = buildContext("test question about something", "");
        // Override the response to have blank content
        context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content("").build());

        system.process(context);
        verify(ragPort, never()).index(anyString());
    }

    @Test
    void formatDocumentContainsAllFields() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();
        context.setActiveSkill(Skill.builder().name("test-skill").build());

        String doc = system.formatDocument("user question here", "assistant answer here", context);
        assertTrue(doc.contains("Date:"));
        assertTrue(doc.contains("Skill: test-skill"));
        assertTrue(doc.contains("User: user question here"));
        assertTrue(doc.contains("Assistant: assistant answer here"));
    }

    @Test
    void formatDocumentWithoutSkill() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();

        String doc = system.formatDocument("question", "answer", context);
        assertFalse(doc.contains("Skill:"));
        assertTrue(doc.contains("User: question"));
        assertTrue(doc.contains("Assistant: answer"));
    }

    // ==================== shouldProcess (finality gate) ====================

    @Test
    void shouldNotProcessWhenFinalAnswerNotReady() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>())
                .finalAnswerReady(false)
                .build();

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWhenFinalAnswerReady() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>())
                .finalAnswerReady(true)
                .build();

        assertTrue(system.shouldProcess(context));
    }

    // ==================== TurnOutcome-based tests ====================

    @Test
    void shouldProcessWhenTurnOutcomeHasAssistantText() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .assistantText("substantive answer about something important")
                .build());

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenTurnOutcomeHasNullAssistantText() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.ERROR)
                .assistantText(null)
                .build());

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWhenTurnOutcomeHasBlankAssistantText() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .assistantText("  ")
                .build());

        assertFalse(system.shouldProcess(context));
    }

    @Test
    void processUsesAssistantTextFromTurnOutcome() {
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>(List.of(
                        Message.builder().role("user").content("What is the capital of France?")
                                .timestamp(Instant.now()).build())))
                .build();
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .assistantText("The capital of France is Paris, known for the Eiffel Tower.")
                .build());
        // Also set legacy attribute — TurnOutcome should take priority
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("LEGACY should be ignored").build());

        system.process(context);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(ragPort).index(captor.capture());
        assertTrue(captor.getValue().contains("Assistant: The capital of France is Paris"));
        assertFalse(captor.getValue().contains("LEGACY"));
    }

    @Test
    void processFallsBackToLlmResponseWhenNoTurnOutcome() {
        AgentContext context = buildContext("What is the capital of France?",
                "The capital of France is Paris, known for the Eiffel Tower.");

        system.process(context);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(ragPort).index(captor.capture());
        assertTrue(captor.getValue().contains("Assistant: The capital of France is Paris"));
    }

    private AgentContext buildContext(String userText, String assistantText) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
                .role("user")
                .content(userText)
                .timestamp(Instant.now())
                .build());

        AgentContext context = AgentContext.builder()
                .messages(messages)
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content(assistantText).build());
        return context;
    }
}

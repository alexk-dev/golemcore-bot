package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompactionServiceTest {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String HELLO = "Hello";

    private LlmPort llmPort;
    private Clock clock;
    private CompactionService service;

    @BeforeEach
    void setUp() {
        llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(true);
        clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

        BotProperties properties = new BotProperties();
        service = new CompactionService(llmPort, properties, clock);
    }

    @Test
    void summarizeMessages() {
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("What is Java?").timestamp(Instant.now()).build(),
                Message.builder().role(ROLE_ASSISTANT).content("Java is a programming language.")
                        .timestamp(Instant.now())
                        .build(),
                Message.builder().role(ROLE_USER).content("How about Spring?").timestamp(Instant.now()).build(),
                Message.builder().role(ROLE_ASSISTANT).content("Spring is a Java framework.").timestamp(Instant.now())
                        .build());

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(
                LlmResponse.builder()
                        .content(
                                "User asked about Java and Spring. Bot explained Java is a programming language and Spring is a Java framework.")
                        .build()));

        String summary = service.summarize(messages);

        assertNotNull(summary);
        assertTrue(summary.contains("Java"));
        verify(llmPort).chat(any());
    }

    @Test
    void summarizeReturnsNullWhenLlmUnavailable() {
        when(llmPort.isAvailable()).thenReturn(false);

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content(HELLO).timestamp(Instant.now()).build());

        String summary = service.summarize(messages);
        assertNull(summary);
    }

    @Test
    void summarizeReturnsNullWhenNoAdapter() {
        BotProperties properties = new BotProperties();
        CompactionService nullService = new CompactionService(null, properties, clock);

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content(HELLO).timestamp(Instant.now()).build());

        String summary = nullService.summarize(messages);
        assertNull(summary);
    }

    @Test
    void summarizeEmptyMessages() {
        assertNull(service.summarize(List.of()));
        assertNull(service.summarize(null));
    }

    @Test
    void summarizeHandlesLlmError() {
        when(llmPort.chat(any())).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("LLM error")));

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content(HELLO).timestamp(Instant.now()).build());

        String summary = service.summarize(messages);
        assertNull(summary);
    }

    @Test
    void createSummaryMessage() {
        Message msg = service.createSummaryMessage("User discussed Java.");

        assertEquals("system", msg.getRole());
        assertTrue(msg.getContent().contains("[Conversation summary]"));
        assertTrue(msg.getContent().contains("User discussed Java."));
        assertNotNull(msg.getTimestamp());
    }

    @Test
    void summarizeFiltersToolMessages() {
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Run a command").timestamp(Instant.now()).build(),
                Message.builder().role(ROLE_ASSISTANT).content("Running...").timestamp(Instant.now()).build(),
                Message.builder().role("tool").content("{\"result\": \"ok\"}").timestamp(Instant.now()).build(),
                Message.builder().role(ROLE_ASSISTANT).content("Done!").timestamp(Instant.now()).build());

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(
                LlmResponse.builder().content("User ran a command.").build()));

        String summary = service.summarize(messages);
        assertNotNull(summary);

        // Verify the prompt sent to LLM doesn't contain tool message content
        verify(llmPort).chat(argThat(req -> {
            String content = req.getMessages().get(0).getContent();
            return !content.contains("\"result\"");
        }));
    }

    // ===== Edge Cases =====

    @Test
    void summarizeReturnsNullWhenLlmReturnsBlankContent() {
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(
                LlmResponse.builder().content("   ").build()));

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content(HELLO).timestamp(Instant.now()).build());

        assertNull(service.summarize(messages));
    }

    @Test
    void summarizeReturnsNullWhenLlmReturnsNullContent() {
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(
                LlmResponse.builder().content(null).build()));

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content(HELLO).timestamp(Instant.now()).build());

        assertNull(service.summarize(messages));
    }

    @Test
    void summarizeFiltersBlankContentMessages() {
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(
                LlmResponse.builder().content("Summary").build()));

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content(HELLO).timestamp(Instant.now()).build(),
                Message.builder().role(ROLE_ASSISTANT).content("   ").timestamp(Instant.now()).build(),
                Message.builder().role(ROLE_USER).content(null).timestamp(Instant.now()).build(),
                Message.builder().role(ROLE_USER).content("World").timestamp(Instant.now()).build());

        String summary = service.summarize(messages);
        assertNotNull(summary);

        // Verify only non-blank messages are included
        verify(llmPort).chat(argThat(req -> {
            String content = req.getMessages().get(0).getContent();
            return content.contains(HELLO) && content.contains("World") && !content.contains("   ");
        }));
    }

    @Test
    void summarizeTruncatesLongMessages() {
        String longMsg = "x".repeat(500);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(
                LlmResponse.builder().content("Summary").build()));

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content(longMsg).timestamp(Instant.now()).build());

        service.summarize(messages);

        // Message should be truncated to 300 chars + "..."
        verify(llmPort).chat(argThat(req -> {
            String content = req.getMessages().get(0).getContent();
            return content.contains("...") && !content.contains(longMsg);
        }));
    }

    @Test
    void summarizeUsesCorrectModel() {
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(
                LlmResponse.builder().content("Summary").build()));

        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content(HELLO).timestamp(Instant.now()).build());

        service.summarize(messages);

        verify(llmPort).chat(argThat(req -> req.getModel() != null &&
                req.getTemperature() == 0.3 &&
                req.getMaxTokens() == 500));
    }

    @Test
    void createSummaryMessageHasCorrectFormat() {
        Message msg = service.createSummaryMessage("The user discussed Java.");

        assertEquals("system", msg.getRole());
        assertEquals("[Conversation summary]\nThe user discussed Java.", msg.getContent());
        assertNotNull(msg.getTimestamp());
    }
}

package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryPersistSystemTest {

    private MemoryComponent memoryComponent;
    private MemoryPersistSystem system;

    @BeforeEach
    void setUp() {
        memoryComponent = mock(MemoryComponent.class);
        system = new MemoryPersistSystem(memoryComponent);
    }

    private AgentContext contextWith(List<Message> messages, LlmResponse response) {
        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id("test").build())
                .messages(new ArrayList<>(messages))
                .build();
        if (response != null) {
            ctx.setAttribute("llm.response", response);
        }
        return ctx;
    }

    // ==================== getOrder / getName ====================

    @Test
    void orderIsFifty() {
        assertEquals(50, system.getOrder());
    }

    @Test
    void nameIsMemoryPersistSystem() {
        assertEquals("MemoryPersistSystem", system.getName());
    }

    // ==================== process ====================

    @Test
    void processAppendsMemoryEntry() {
        LlmResponse response = LlmResponse.builder().content("Hello there!").build();
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("user").content("Hi").timestamp(Instant.now()).build()),
                response);

        system.process(ctx);

        verify(memoryComponent)
                .appendToday(argThat(entry -> entry.contains("User: Hi") && entry.contains("Assistant: Hello there!")));
    }

    @Test
    void processSkipsWhenNoMessages() {
        AgentContext ctx = contextWith(List.of(),
                LlmResponse.builder().content("response").build());

        system.process(ctx);

        verify(memoryComponent, never()).appendToday(any());
    }

    @Test
    void processSkipsWhenNullMessages() {
        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id("test").build())
                .messages(null)
                .build();
        ctx.setAttribute("llm.response", LlmResponse.builder().content("response").build());

        system.process(ctx);

        verify(memoryComponent, never()).appendToday(any());
    }

    @Test
    void processSkipsWhenNoUserMessages() {
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("assistant").content("only assistant").timestamp(Instant.now()).build()),
                LlmResponse.builder().content("response").build());

        system.process(ctx);

        verify(memoryComponent, never()).appendToday(any());
    }

    @Test
    void processSkipsWhenNoLlmResponse() {
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("user").content("hello").timestamp(Instant.now()).build()),
                null);

        system.process(ctx);

        verify(memoryComponent, never()).appendToday(any());
    }

    @Test
    void processSkipsWhenLlmResponseContentIsNull() {
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("user").content("hello").timestamp(Instant.now()).build()),
                LlmResponse.builder().content(null).build());

        system.process(ctx);

        verify(memoryComponent, never()).appendToday(any());
    }

    @Test
    void processFindsLastUserMessage() {
        LlmResponse response = LlmResponse.builder().content("reply").build();
        AgentContext ctx = contextWith(
                List.of(
                        Message.builder().role("user").content("first").timestamp(Instant.now()).build(),
                        Message.builder().role("assistant").content("response1").timestamp(Instant.now()).build(),
                        Message.builder().role("user").content("second").timestamp(Instant.now()).build()),
                response);

        system.process(ctx);

        verify(memoryComponent).appendToday(argThat(entry -> entry.contains("User: second")));
    }

    @Test
    void processTruncatesLongUserMessage() {
        String longMsg = "x".repeat(300);
        LlmResponse response = LlmResponse.builder().content("short").build();
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("user").content(longMsg).timestamp(Instant.now()).build()),
                response);

        system.process(ctx);

        verify(memoryComponent).appendToday(argThat(entry -> {
            // User content truncated to 200 chars (197 + "...")
            String userPart = entry.substring(entry.indexOf("User: ") + 6, entry.indexOf(" | "));
            return userPart.length() == 200 && userPart.endsWith("...");
        }));
    }

    @Test
    void processTruncatesLongAssistantContent() {
        String longResponse = "y".repeat(400);
        LlmResponse response = LlmResponse.builder().content(longResponse).build();
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("user").content("q").timestamp(Instant.now()).build()),
                response);

        system.process(ctx);

        verify(memoryComponent).appendToday(argThat(entry -> {
            String assistantPart = entry.substring(entry.indexOf("Assistant: ") + 11).trim();
            return assistantPart.length() == 300 && assistantPart.endsWith("...");
        }));
    }

    @Test
    void processDoesNotTruncateAtExactMaxLength() {
        String exactMsg = "a".repeat(200);
        LlmResponse response = LlmResponse.builder().content("short").build();
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("user").content(exactMsg).timestamp(Instant.now()).build()),
                response);

        system.process(ctx);

        verify(memoryComponent)
                .appendToday(argThat(entry -> entry.contains("User: " + exactMsg) && !entry.contains("...")));
    }

    @Test
    void processHandlesNullUserContent() {
        LlmResponse response = LlmResponse.builder().content("reply").build();
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("user").content(null).timestamp(Instant.now()).build()),
                response);

        system.process(ctx);

        // null content becomes empty string via truncate, appendToday IS called
        verify(memoryComponent)
                .appendToday(argThat(entry -> entry.contains("User:") && entry.contains("Assistant: reply")));
    }

    @Test
    void processReplacesNewlinesInContent() {
        LlmResponse response = LlmResponse.builder().content("line1\nline2").build();
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("user").content("hello\nworld").timestamp(Instant.now()).build()),
                response);

        system.process(ctx);

        verify(memoryComponent).appendToday(
                argThat(entry -> entry.contains("User: hello world") && entry.contains("Assistant: line1 line2")));
    }

    @Test
    void processHandlesMemoryAppendFailure() {
        doThrow(new RuntimeException("write error")).when(memoryComponent).appendToday(any());

        LlmResponse response = LlmResponse.builder().content("reply").build();
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("user").content("hello").timestamp(Instant.now()).build()),
                response);

        assertDoesNotThrow(() -> system.process(ctx));
    }

    @Test
    void processEntryIncludesTimestamp() {
        LlmResponse response = LlmResponse.builder().content("reply").build();
        AgentContext ctx = contextWith(
                List.of(Message.builder().role("user").content("hello").timestamp(Instant.now()).build()),
                response);

        system.process(ctx);

        verify(memoryComponent).appendToday(
                argThat(entry -> entry.matches("\\[\\d{2}:\\d{2}\\] User: hello \\| Assistant: reply\\n")));
    }
}

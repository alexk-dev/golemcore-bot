package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.CompactionService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoCompactionSystemTest {

    private static final String SESSION_ID = "test-session";
    private static final String ROLE_USER = "user";

    private SessionPort sessionService;
    private CompactionService compactionService;
    private BotProperties properties;
    private ModelConfigService modelConfigService;
    private AutoCompactionSystem system;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionPort.class);
        compactionService = mock(CompactionService.class);
        modelConfigService = mock(ModelConfigService.class);
        when(modelConfigService.getMaxInputTokens(anyString())).thenReturn(128000);

        properties = new BotProperties();
        properties.getAutoCompact().setEnabled(true);
        properties.getAutoCompact().setMaxContextTokens(1000);
        properties.getAutoCompact().setKeepLastMessages(5);
        properties.getAutoCompact().setSystemPromptOverheadTokens(100);
        properties.getAutoCompact().setCharsPerToken(1.0); // 1:1 for easy test math

        system = new AutoCompactionSystem(sessionService, compactionService, properties, modelConfigService);
    }

    @Test
    void nameAndOrder() {
        assertEquals("AutoCompactionSystem", system.getName());
        assertEquals(18, system.getOrder());
    }

    @Test
    void isEnabledReflectsConfig() {
        assertTrue(system.isEnabled());

        properties.getAutoCompact().setEnabled(false);
        assertFalse(system.isEnabled());
    }

    @Test
    void shouldNotProcessEmptyMessages() {
        AgentContext context = buildContext(List.of());
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessNullMessages() {
        AgentContext context = AgentContext.builder()
                .session(buildSession())
                .messages(null)
                .build();
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void belowThresholdNoCompaction() {
        // 11 chars + 100 overhead = 111 tokens, well below 1000 threshold
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Hello world").timestamp(Instant.now()).build());
        AgentContext context = buildContext(messages);

        AgentContext result = system.process(context);

        verifyNoInteractions(compactionService);
        verifyNoInteractions(sessionService);
        assertSame(context, result);
    }

    @Test
    void aboveThresholdCompactsWithLlmSummary() {
        // 50 messages * 50 chars = 2500 chars + 100 overhead = 2600 tokens > 1000
        // threshold
        List<Message> messages = buildLargeMessageList(50, 50);
        AgentSession session = buildSession();
        session.getMessages().addAll(messages);

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .build();

        List<Message> toCompact = messages.subList(0, 45);
        when(sessionService.getMessagesToCompact(SESSION_ID, 5)).thenReturn(toCompact);
        when(compactionService.summarize(toCompact)).thenReturn("Summary of conversation");

        Message summaryMsg = Message.builder().role("system").content("[Conversation summary]\nSummary of conversation")
                .build();
        when(compactionService.createSummaryMessage("Summary of conversation")).thenReturn(summaryMsg);
        when(sessionService.compactWithSummary(SESSION_ID, 5, summaryMsg)).thenReturn(45);

        // After compaction, session has summary + 5 kept messages
        List<Message> compactedMessages = new ArrayList<>();
        compactedMessages.add(summaryMsg);
        compactedMessages.addAll(messages.subList(45, 50));
        session.getMessages().clear();
        session.getMessages().addAll(compactedMessages);

        AgentContext result = system.process(context);

        verify(compactionService).summarize(toCompact);
        verify(compactionService).createSummaryMessage("Summary of conversation");
        verify(sessionService).compactWithSummary(SESSION_ID, 5, summaryMsg);
        assertEquals(6, result.getMessages().size()); // summary + 5 kept
    }

    @Test
    void aboveThresholdFallsBackToSimpleTruncation() {
        List<Message> messages = buildLargeMessageList(50, 50);
        AgentSession session = buildSession();
        session.getMessages().addAll(messages);

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .build();

        List<Message> toCompact = messages.subList(0, 45);
        when(sessionService.getMessagesToCompact(SESSION_ID, 5)).thenReturn(toCompact);
        when(compactionService.summarize(toCompact)).thenReturn(null); // LLM unavailable
        when(sessionService.compactMessages(SESSION_ID, 5)).thenReturn(45);

        // After truncation, session has 5 kept messages
        List<Message> truncatedMessages = new ArrayList<>(messages.subList(45, 50));
        session.getMessages().clear();
        session.getMessages().addAll(truncatedMessages);

        AgentContext result = system.process(context);

        verify(compactionService).summarize(toCompact);
        verify(compactionService, never()).createSummaryMessage(any());
        verify(sessionService).compactMessages(SESSION_ID, 5);
        assertEquals(5, result.getMessages().size());
    }

    @Test
    void noMessagesToCompactSkips() {
        // Above threshold in token count but getMessagesToCompact returns empty
        List<Message> messages = buildLargeMessageList(50, 50);
        AgentContext context = buildContext(messages);

        when(sessionService.getMessagesToCompact(SESSION_ID, 5)).thenReturn(List.of());

        AgentContext result = system.process(context);

        verify(compactionService, never()).summarize(any());
        assertSame(context, result);
    }

    @Test
    void contextMessagesNotUpdatedWhenNothingRemoved() {
        List<Message> messages = buildLargeMessageList(50, 50);
        AgentContext context = buildContext(messages);

        List<Message> toCompact = messages.subList(0, 45);
        when(sessionService.getMessagesToCompact(SESSION_ID, 5)).thenReturn(toCompact);
        when(compactionService.summarize(toCompact)).thenReturn("Summary");

        Message summaryMsg = Message.builder().role("system").content("[Conversation summary]\nSummary").build();
        when(compactionService.createSummaryMessage("Summary")).thenReturn(summaryMsg);
        when(sessionService.compactWithSummary(SESSION_ID, 5, summaryMsg)).thenReturn(0);

        AgentContext result = system.process(context);

        // removed == 0, so messages should not be synced
        assertEquals(50, result.getMessages().size());
    }

    @Test
    void nullContentMessagesHandledGracefully() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role(ROLE_USER).content(null).timestamp(Instant.now()).build());
        messages.add(Message.builder().role("assistant").content(null).timestamp(Instant.now()).build());

        AgentContext context = buildContext(messages);

        // 0 chars + 100 overhead = 100 tokens, below 1000
        AgentContext result = system.process(context);

        verifyNoInteractions(sessionService);
        assertSame(context, result);
    }

    private AgentContext buildContext(List<Message> messages) {
        return AgentContext.builder()
                .session(buildSession())
                .messages(new ArrayList<>(messages))
                .build();
    }

    private AgentSession buildSession() {
        return AgentSession.builder()
                .id(SESSION_ID)
                .channelType("telegram")
                .chatId("123")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void usesModelMaxInputTokensWithCap() {
        // Model has 16K limit, 80% = 12800 tokens. Config max is 1000. Should use
        // min(12800, 1000) = 1000
        when(modelConfigService.getMaxInputTokens(anyString())).thenReturn(16000);

        // 20 chars + 100 overhead = 120 tokens, below 1000
        List<Message> small = List.of(
                Message.builder().role(ROLE_USER).content("x".repeat(20)).timestamp(Instant.now()).build());
        AgentContext context = buildContext(small);

        system.process(context);

        verifyNoInteractions(compactionService); // below threshold
    }

    @Test
    void compactionExceptionPropagates() {
        List<Message> messages = buildLargeMessageList(50, 50);
        AgentContext context = buildContext(messages);

        List<Message> toCompact = messages.subList(0, 45);
        when(sessionService.getMessagesToCompact(SESSION_ID, 5)).thenReturn(toCompact);
        when(compactionService.summarize(toCompact)).thenThrow(new RuntimeException("LLM error"));

        // No try-catch in process() — exception propagates
        assertThrows(RuntimeException.class, () -> system.process(context));
    }

    @Test
    void shouldProcessWithSufficientMessages() {
        // 1000 chars + 100 overhead = 1100, above 1000 threshold
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("x".repeat(1000)).timestamp(Instant.now()).build());
        AgentContext context = buildContext(messages);

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void singleMessageBelowThresholdNotCompacted() {
        // 500 chars + 100 overhead = 600, below 1000
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("x".repeat(500)).timestamp(Instant.now()).build());
        AgentContext context = buildContext(messages);

        system.process(context);

        verifyNoInteractions(sessionService);
    }

    private List<Message> buildLargeMessageList(int count, int contentLength) {
        List<Message> messages = new ArrayList<>();
        String content = "x".repeat(contentLength);
        for (int i = 0; i < count; i++) {
            messages.add(Message.builder()
                    .role(i % 2 == 0 ? ROLE_USER : "assistant")
                    .content(content)
                    .timestamp(Instant.now())
                    .build());
        }
        return messages;
    }
}

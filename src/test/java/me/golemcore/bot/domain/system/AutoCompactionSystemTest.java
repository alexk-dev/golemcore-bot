package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AutoCompactionSystemTest {

    private static final String SESSION_ID = "test-session";
    private static final String ROLE_USER = "user";

    private CompactionOrchestrationService compactionOrchestrationService;
    private RuntimeConfigService runtimeConfigService;
    private ModelSelectionService modelSelectionService;
    private AutoCompactionSystem system;

    @BeforeEach
    void setUp() {
        compactionOrchestrationService = mock(CompactionOrchestrationService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);

        when(modelSelectionService.resolveMaxInputTokens(any())).thenReturn(128000);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(1000);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(5);

        system = new AutoCompactionSystem(compactionOrchestrationService, runtimeConfigService, modelSelectionService);
    }

    @Test
    void nameAndOrder() {
        assertEquals("AutoCompactionSystem", system.getName());
        assertEquals(18, system.getOrder());
    }

    @Test
    void isEnabledReflectsConfig() {
        assertTrue(system.isEnabled());

        when(runtimeConfigService.isCompactionEnabled()).thenReturn(false);
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
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(20000);
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("Hello world").timestamp(Instant.now()).build());
        AgentContext context = buildContext(messages);

        AgentContext result = system.process(context);

        verifyNoInteractions(compactionOrchestrationService);
        assertSame(context, result);
    }

    @Test
    void aboveThresholdCompactsAndSyncsContextMessages() {
        List<Message> messages = buildLargeMessageList(50, 50);
        AgentSession session = buildSession();
        session.getMessages().addAll(messages);

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .build();

        when(compactionOrchestrationService.compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5))
                .thenAnswer(invocation -> {
                    List<Message> compactedMessages = new ArrayList<>();
                    compactedMessages.add(Message.builder()
                            .role("system")
                            .content("[Conversation summary]\nsummary")
                            .timestamp(Instant.now())
                            .build());
                    compactedMessages.addAll(messages.subList(45, 50));
                    session.getMessages().clear();
                    session.getMessages().addAll(compactedMessages);
                    return CompactionResult.builder()
                            .removed(45)
                            .usedSummary(true)
                            .build();
                });

        AgentContext result = system.process(context);

        verify(compactionOrchestrationService).compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5);
        assertEquals(6, result.getMessages().size());
    }

    @Test
    void noMessagesRemovedKeepsContextMessages() {
        List<Message> messages = buildLargeMessageList(50, 50);
        AgentContext context = buildContext(messages);

        when(compactionOrchestrationService.compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());

        AgentContext result = system.process(context);

        assertEquals(50, result.getMessages().size());
    }

    @Test
    void nullContentMessagesHandledGracefully() {
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(20000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role(ROLE_USER).content(null).timestamp(Instant.now()).build());
        messages.add(Message.builder().role("assistant").content(null).timestamp(Instant.now()).build());

        AgentContext context = buildContext(messages);

        AgentContext result = system.process(context);

        verifyNoInteractions(compactionOrchestrationService);
        assertSame(context, result);
    }

    @Test
    void compactionExceptionPropagates() {
        List<Message> messages = buildLargeMessageList(50, 50);
        AgentContext context = buildContext(messages);

        when(compactionOrchestrationService.compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5))
                .thenThrow(new RuntimeException("LLM error"));

        assertThrows(RuntimeException.class, () -> system.process(context));
    }

    @Test
    void shouldProcessWithSufficientMessages() {
        List<Message> messages = List.of(
                Message.builder().role(ROLE_USER).content("x".repeat(1000)).timestamp(Instant.now()).build());
        AgentContext context = buildContext(messages);

        assertTrue(system.shouldProcess(context));
    }

    private AgentContext buildContext(List<Message> messages) {
        AgentSession session = buildSession();
        session.getMessages().addAll(messages);
        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(messages))
                .build();
    }

    private AgentSession buildSession() {
        return AgentSession.builder()
                .id(SESSION_ID)
                .channelType("telegram")
                .chatId("123")
                .messages(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
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

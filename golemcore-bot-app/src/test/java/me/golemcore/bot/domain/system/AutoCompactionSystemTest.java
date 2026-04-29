package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.CompactionDetails;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.context.compaction.CompactionOrchestrationService;
import me.golemcore.bot.domain.context.compaction.ContextCompactionPolicy;
import me.golemcore.bot.domain.context.compaction.ContextTokenEstimator;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private ContextTokenEstimator contextTokenEstimator;
    private ContextCompactionPolicy contextCompactionPolicy;
    private AutoCompactionSystem system;

    @BeforeEach
    void setUp() {
        compactionOrchestrationService = mock(CompactionOrchestrationService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        contextTokenEstimator = new ContextTokenEstimator();
        runtimeConfigService = mock(RuntimeConfigService.class);

        when(modelSelectionService.resolveMaxInputTokensForContext(any(AgentContext.class))).thenReturn(128000);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.95d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(1000);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(5);

        contextCompactionPolicy = new ContextCompactionPolicy(runtimeConfigService, modelSelectionService);
        system = new AutoCompactionSystem(compactionOrchestrationService, contextTokenEstimator,
                contextCompactionPolicy);
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
    void ratioModeShouldIgnoreLegacyAbsoluteThresholdWhenModelWindowIsLarger() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.95d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(1000);
        when(modelSelectionService.resolveMaxInputTokensForContext(any(AgentContext.class))).thenReturn(20000);

        List<Message> messages = buildLargeMessageList(50, 50);
        AgentContext context = buildContext(messages);

        AgentContext result = system.process(context);

        verifyNoInteractions(compactionOrchestrationService);
        assertSame(context, result);
    }

    @Test
    void ratioModeShouldCompactBeforeLegacyAbsoluteThresholdWhenRatioThresholdIsLower() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.9d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(50000);
        when(modelSelectionService.resolveMaxInputTokensForContext(any(AgentContext.class))).thenReturn(10000);
        when(compactionOrchestrationService.compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5))
                .thenReturn(CompactionResult.builder().removed(3).usedSummary(true).build());

        List<Message> messages = buildLargeMessageList(100, 500);
        AgentContext context = buildContext(messages);

        system.process(context);

        verify(compactionOrchestrationService).compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5);
    }

    @Test
    void aboveThresholdCompactsAndSyncsContextMessages() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("token_threshold");
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
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("token_threshold");
        List<Message> messages = buildLargeMessageList(50, 50);
        AgentContext context = buildContext(messages);

        when(compactionOrchestrationService.compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());

        AgentContext result = system.process(context);

        assertEquals(50, result.getMessages().size());
    }

    @Test
    void tokenThresholdModeShouldPreserveLegacyModelSafetyCap() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("token_threshold");
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(20000);
        when(modelSelectionService.resolveMaxInputTokensForContext(any(AgentContext.class))).thenReturn(10000);
        when(compactionOrchestrationService.compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5))
                .thenReturn(CompactionResult.builder().removed(1).usedSummary(true).build());

        List<Message> messages = buildLargeMessageList(100, 500);
        AgentContext context = buildContext(messages);

        system.process(context);

        verify(compactionOrchestrationService).compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5);
    }

    @Test
    void tokenThresholdModeShouldFallBackToConfiguredThresholdWhenModelLookupFails() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("token_threshold");
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(1000);
        when(modelSelectionService.resolveMaxInputTokensForContext(any(AgentContext.class)))
                .thenThrow(new IllegalStateException("missing model"));
        when(compactionOrchestrationService.compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5))
                .thenReturn(CompactionResult.builder().removed(2).usedSummary(true).build());

        List<Message> messages = buildLargeMessageList(50, 50);
        AgentContext context = buildContext(messages);

        system.process(context);

        verify(compactionOrchestrationService).compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5);
    }

    @Test
    void compactionShouldExposeStructuredDetailsInContextAttributes() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("token_threshold");
        when(compactionOrchestrationService.compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5))
                .thenReturn(CompactionResult.builder()
                        .removed(10)
                        .usedSummary(true)
                        .details(CompactionDetails.builder()
                                .reason(CompactionReason.AUTO_THRESHOLD)
                                .summarizedCount(12)
                                .keptCount(5)
                                .summaryLength(320)
                                .durationMs(42L)
                                .toolCount(2)
                                .readFilesCount(3)
                                .modifiedFilesCount(1)
                                .splitTurnDetected(true)
                                .fileChanges(List.of())
                                .build())
                        .build());

        AgentContext context = buildContext(buildLargeMessageList(50, 50));

        AgentContext result = system.process(context);

        Map<String, Object> details = result.getAttribute(ContextAttributes.COMPACTION_LAST_DETAILS);
        assertNotNull(details);
        assertEquals(10, details.get("removed"));
        assertEquals(true, details.get("usedSummary"));
        assertEquals("AUTO_THRESHOLD", details.get("reason"));
        assertEquals(12, details.get("summarizedCount"));
        assertEquals(5, details.get("keptCount"));
        assertEquals(true, details.get("splitTurnDetected"));
    }

    @Test
    void ratioModeShouldFallBackToConfiguredThresholdWhenModelLookupFails() {
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(1000);
        when(modelSelectionService.resolveMaxInputTokensForContext(any(AgentContext.class)))
                .thenThrow(new IllegalStateException("missing model"));
        when(compactionOrchestrationService.compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5))
                .thenReturn(CompactionResult.builder().removed(1).usedSummary(true).build());

        List<Message> messages = buildLargeMessageList(50, 50);
        AgentContext context = buildContext(messages);

        system.process(context);

        verify(compactionOrchestrationService).compact(SESSION_ID, CompactionReason.AUTO_THRESHOLD, 5);
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
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("token_threshold");
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

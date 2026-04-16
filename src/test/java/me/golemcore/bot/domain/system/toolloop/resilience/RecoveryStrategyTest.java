package me.golemcore.bot.domain.system.toolloop.resilience;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TurnProgressService;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import me.golemcore.bot.domain.system.toolloop.ContextCompactionCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryStrategyTest {

    private static final Instant NOW = Instant.parse("2026-04-16T02:50:00Z");

    private RuntimeConfig.ResilienceConfig config;

    @BeforeEach
    void setUp() {
        config = RuntimeConfig.ResilienceConfig.builder()
                .degradationCompactContext(true)
                .degradationCompactMinMessages(2)
                .degradationDowngradeModel(true)
                .degradationFallbackModelTier("balanced")
                .degradationStripTools(true)
                .build();
    }

    @Test
    void contextCompactionShouldApplyOnlyOnceForTransientLargeContexts() {
        AgentContext context = contextWithMessages(5);
        ContextCompactionPolicy policy = mock(ContextCompactionPolicy.class);
        when(policy.isCompactionEnabled()).thenReturn(true);
        when(policy.resolveCompactionKeepLast()).thenReturn(2);
        CompactionOrchestrationService compactionService = mock(CompactionOrchestrationService.class);
        when(compactionService.compact("session-1", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2))
                .thenAnswer(invocation -> {
                    context.getSession().setMessages(new ArrayList<>(context.getSession().getMessages()
                            .subList(3, 5)));
                    return CompactionResult.builder().removed(3).usedSummary(false).build();
                });
        ContextCompactionCoordinator coordinator = new ContextCompactionCoordinator(
                policy, compactionService, new RuntimeEventService(fixedClock()), mock(TurnProgressService.class));
        ContextCompactionRecoveryStrategy strategy = new ContextCompactionRecoveryStrategy(coordinator);

        assertTrue(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, config));

        RecoveryStrategy.RecoveryResult result = strategy.apply(
                context, LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, config);

        assertTrue(result.recovered());
        assertTrue(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.RESILIENCE_L4_COMPACTION_ATTEMPTED)));
        assertEquals(2, context.getMessages().size());
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, config));
        verify(compactionService).compact("session-1", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2);
    }

    @Test
    void contextCompactionShouldRejectDisabledNonTransientSmallAndAlreadyAttemptedInputs() {
        ContextCompactionRecoveryStrategy strategy = new ContextCompactionRecoveryStrategy(mock(
                ContextCompactionCoordinator.class));
        AgentContext context = contextWithMessages(3);

        RuntimeConfig.ResilienceConfig disabled = RuntimeConfig.ResilienceConfig.builder()
                .degradationCompactContext(false)
                .degradationCompactMinMessages(2)
                .build();
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, disabled));
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.UNKNOWN, config));

        AgentContext smallContext = contextWithMessages(2);
        assertFalse(strategy.isApplicable(smallContext, LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, config));

        context.setAttribute(ContextAttributes.RESILIENCE_L4_COMPACTION_ATTEMPTED, true);
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, config));
    }

    @Test
    void contextCompactionShouldReportNotApplicableWhenCoordinatorDeclines() {
        AgentContext context = contextWithMessages(5);
        ContextCompactionCoordinator coordinator = mock(ContextCompactionCoordinator.class);
        ContextCompactionRecoveryStrategy strategy = new ContextCompactionRecoveryStrategy(coordinator);

        RecoveryStrategy.RecoveryResult result = strategy.apply(
                context, LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, config);

        assertFalse(result.recovered());
        assertEquals("compaction coordinator declined", result.detail());
        assertTrue(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.RESILIENCE_L4_COMPACTION_ATTEMPTED)));
        verify(coordinator).recoverFromContextOverflow(context, 0, 0);
    }

    @Test
    void contextCompactionShouldHandleNullContextMessageList() {
        ContextCompactionRecoveryStrategy strategy = new ContextCompactionRecoveryStrategy(mock(
                ContextCompactionCoordinator.class));
        AgentContext context = AgentContext.builder().messages(null).build();

        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, config));
    }

    @Test
    void contextCompactionShouldHandleNullContextMessageListAfterDeclinedApply() {
        ContextCompactionCoordinator coordinator = mock(ContextCompactionCoordinator.class);
        ContextCompactionRecoveryStrategy strategy = new ContextCompactionRecoveryStrategy(coordinator);
        AgentContext context = AgentContext.builder().messages(null).build();

        RecoveryStrategy.RecoveryResult result = strategy.apply(
                context, LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, config);

        assertFalse(result.recovered());
        verify(coordinator).recoverFromContextOverflow(context, 0, 0);
    }

    @Test
    void modelDowngradeShouldStoreOriginalTierAndPreventRepeatedDowngrades() {
        ModelDowngradeRecoveryStrategy strategy = new ModelDowngradeRecoveryStrategy();
        AgentContext context = AgentContext.builder().modelTier("deep").build();

        assertTrue(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config));

        RecoveryStrategy.RecoveryResult result = strategy.apply(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT,
                config);

        assertTrue(result.recovered());
        assertEquals("balanced", context.getModelTier());
        assertEquals("deep", context.getAttribute(ContextAttributes.RESILIENCE_L4_ORIGINAL_MODEL_TIER));
        assertTrue(Boolean.TRUE.equals(
                context.getAttribute(ContextAttributes.RESILIENCE_L4_MODEL_DOWNGRADE_ATTEMPTED)));
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config));
    }

    @Test
    void modelDowngradeShouldRejectUnsupportedConfigurationsAndEquivalentTier() {
        ModelDowngradeRecoveryStrategy strategy = new ModelDowngradeRecoveryStrategy();
        AgentContext context = AgentContext.builder().modelTier("balanced").build();

        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT, config));
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config));

        RuntimeConfig.ResilienceConfig disabled = RuntimeConfig.ResilienceConfig.builder()
                .degradationDowngradeModel(false)
                .degradationFallbackModelTier("fast")
                .build();
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, disabled));

        RuntimeConfig.ResilienceConfig blankFallback = RuntimeConfig.ResilienceConfig.builder()
                .degradationDowngradeModel(true)
                .degradationFallbackModelTier(" ")
                .build();
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, blankFallback));

        RuntimeConfig.ResilienceConfig nullFallback = RuntimeConfig.ResilienceConfig.builder()
                .degradationDowngradeModel(true)
                .degradationFallbackModelTier(null)
                .build();
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, nullFallback));
    }

    @Test
    void toolStripShouldSaveOriginalToolsAndReplaceThemWithEmptyList() {
        ToolStripRecoveryStrategy strategy = new ToolStripRecoveryStrategy();
        List<ToolDefinition> tools = List.of(ToolDefinition.simple("search", "Search"));
        AgentContext context = AgentContext.builder().availableTools(new ArrayList<>(tools)).build();

        assertTrue(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT, config));

        RecoveryStrategy.RecoveryResult result = strategy.apply(context, LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT,
                config);

        assertTrue(result.recovered());
        assertTrue(context.getAvailableTools().isEmpty());
        assertSame(tools.get(0), ((List<?>) context.getAttribute(ContextAttributes.RESILIENCE_L4_ORIGINAL_TOOLS))
                .get(0));
        assertTrue(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.RESILIENCE_L4_TOOL_STRIP_ATTEMPTED)));
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT, config));
    }

    @Test
    void toolStripShouldRejectDisabledNonTransientAlreadyAttemptedAndEmptyTools() {
        ToolStripRecoveryStrategy strategy = new ToolStripRecoveryStrategy();
        AgentContext context = AgentContext.builder()
                .availableTools(new ArrayList<>(List.of(ToolDefinition.simple("search", "Search"))))
                .build();
        RuntimeConfig.ResilienceConfig disabled = RuntimeConfig.ResilienceConfig.builder()
                .degradationStripTools(false)
                .build();

        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT, disabled));
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.UNKNOWN, config));

        context.setAttribute(ContextAttributes.RESILIENCE_L4_TOOL_STRIP_ATTEMPTED, true);
        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT, config));

        AgentContext emptyTools = AgentContext.builder().availableTools(new ArrayList<>()).build();
        assertFalse(strategy.isApplicable(emptyTools, LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT, config));
    }

    @Test
    void modelDowngradeShouldAcceptInternalServerCodeAndNullCurrentTier() {
        ModelDowngradeRecoveryStrategy strategy = new ModelDowngradeRecoveryStrategy();
        AgentContext context = AgentContext.builder().modelTier(null).build();

        assertTrue(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, config));
    }

    @Test
    void toolStripShouldHandleNullAvailableToolsAfterApplicabilitySkipsNullTools() {
        ToolStripRecoveryStrategy strategy = new ToolStripRecoveryStrategy();
        AgentContext context = AgentContext.builder().availableTools(null).build();

        assertFalse(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT, config));

        RecoveryStrategy.RecoveryResult result = strategy.apply(context, LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT,
                config);

        assertTrue(result.recovered());
        assertTrue(context.getAvailableTools().isEmpty());
        assertTrue(context.getAttribute(ContextAttributes.RESILIENCE_L4_ORIGINAL_TOOLS) == null);
    }

    @Test
    void recoveryResultShouldExposeSuccessAndNotApplicableFactories() {
        RecoveryStrategy.RecoveryResult success = RecoveryStrategy.RecoveryResult.success("ok");
        RecoveryStrategy.RecoveryResult skipped = RecoveryStrategy.RecoveryResult.notApplicable("skip");

        assertTrue(success.recovered());
        assertEquals("ok", success.detail());
        assertFalse(skipped.recovered());
        assertEquals("skip", skipped.detail());
    }

    private AgentContext contextWithMessages(int count) {
        AgentSession session = AgentSession.builder()
                .id("session-1")
                .channelType("telegram")
                .chatId("chat-1")
                .metadata(new LinkedHashMap<>())
                .messages(new ArrayList<>())
                .build();
        for (int index = 0; index < count; index++) {
            session.addMessage(Message.builder()
                    .role(index % 2 == 0 ? "user" : "assistant")
                    .content("message-" + index)
                    .timestamp(NOW)
                    .build());
        }
        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}

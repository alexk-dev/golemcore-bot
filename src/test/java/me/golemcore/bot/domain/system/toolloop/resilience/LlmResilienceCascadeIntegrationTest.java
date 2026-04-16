package me.golemcore.bot.domain.system.toolloop.resilience;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.FallbackModes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmResilienceCascadeIntegrationTest {

    private static final String ERROR_CODE = LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER;
    private static final RuntimeException ERROR = new RuntimeException("provider returned 500");

    @Test
    void shouldCascadeFromHotRetryThroughFallbackBreakerDegradationAndColdRetry() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(RuntimeConfig.TierBinding.builder()
                .model("provider-primary")
                .fallbackMode(FallbackModes.SEQUENTIAL)
                .fallbacks(List.of(RuntimeConfig.TierFallback.builder()
                        .model("provider-fallback")
                        .reasoning("low")
                        .build()))
                .build());
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        ProviderCircuitBreaker circuitBreaker = new ProviderCircuitBreaker(fixedClock(), 2, 60, 120);
        OneShotRecoveryStrategy degradation = new OneShotRecoveryStrategy();
        LlmResilienceOrchestrator orchestrator = new LlmResilienceOrchestrator(
                new ImmediateRetryPolicy(),
                circuitBreaker,
                new RuntimeConfigRouterFallbackSelector(runtimeConfigService),
                List.of(degradation),
                new SuspendedTurnManager(delayedActionService, fixedClock()));
        AgentContext context = context();
        RuntimeConfig.ResilienceConfig config = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(1)
                .hotRetryBaseDelayMs(0L)
                .hotRetryCapMs(1L)
                .coldRetryEnabled(true)
                .coldRetryMaxAttempts(3)
                .build();

        LlmResilienceOrchestrator.ResilienceOutcome first = orchestrator.handle(context, ERROR, ERROR_CODE, 0,
                config);
        LlmResilienceOrchestrator.ResilienceOutcome.RetryNow l1 = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.RetryNow.class, first);
        assertEquals("L1", l1.layer());

        LlmResilienceOrchestrator.ResilienceOutcome second = orchestrator.handle(context, ERROR, ERROR_CODE, 1,
                config);
        LlmResilienceOrchestrator.ResilienceOutcome.RetryNow l2 = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.RetryNow.class, second);
        assertEquals("L2", l2.layer());
        assertEquals("provider-fallback", context.getAttribute(ContextAttributes.LLM_MODEL));
        assertEquals(ProviderCircuitBreaker.State.OPEN, circuitBreaker.getState("provider-primary"));

        LlmResilienceOrchestrator.ResilienceOutcome third = orchestrator.handle(context, ERROR, ERROR_CODE, 1,
                config);
        LlmResilienceOrchestrator.ResilienceOutcome.RetryNow l4 = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.RetryNow.class, third);
        assertEquals("L4:one_shot", l4.layer());
        assertTrue(Boolean.TRUE.equals(context.getAttribute("test.l4.degraded")));

        LlmResilienceOrchestrator.ResilienceOutcome fourth = orchestrator.handle(context, ERROR, ERROR_CODE, 1,
                config);
        LlmResilienceOrchestrator.ResilienceOutcome.Suspended l5 = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.Suspended.class, fourth);
        assertTrue(l5.userMessage().contains(ERROR_CODE));
        assertEquals(ProviderCircuitBreaker.State.OPEN, circuitBreaker.getState("provider-fallback"));
        verify(delayedActionService).schedule(any(DelayedSessionAction.class));
    }

    private AgentContext context() {
        AgentSession session = AgentSession.builder()
                .id("session-1")
                .channelType("telegram")
                .chatId("chat-1")
                .messages(List.of(Message.builder()
                        .role("user")
                        .content("run resilience cascade")
                        .timestamp(Instant.parse("2026-04-16T04:00:00Z"))
                        .build()))
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(session.getMessages())
                .modelTier("balanced")
                .build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "provider-primary");
        return context;
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-16T04:00:00Z"), ZoneOffset.UTC);
    }

    private static final class OneShotRecoveryStrategy implements RecoveryStrategy {
        private boolean applied;

        @Override
        public String name() {
            return "one_shot";
        }

        @Override
        public boolean isApplicable(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
            return !applied;
        }

        @Override
        public RecoveryResult apply(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
            applied = true;
            context.setAttribute("test.l4.degraded", true);
            return RecoveryResult.success("reduced request complexity");
        }
    }

    private static final class ImmediateRetryPolicy extends LlmRetryPolicy {
        @Override
        public long computeDelay(int attempt, RuntimeConfig.ResilienceConfig config) {
            return 0L;
        }

        @Override
        public void sleep(long delayMs) {
            // Keep the integration test deterministic and fast.
        }
    }
}

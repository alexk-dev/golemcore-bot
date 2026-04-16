package me.golemcore.bot.domain.system.toolloop.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Five-layer resilience orchestrator for LLM API failures.
 *
 * <p>
 * When a remote LLM provider returns a transient error (HTTP 500, timeout, rate
 * limit), the autonomous agent loop must not break. This orchestrator cascades
 * through five defense layers, each with its own timescale:
 *
 * <ol>
 * <li><b>L1 — Hot Retry</b> (seconds): Exponential backoff with full jitter.
 * Handles brief provider hiccups that resolve within seconds.</li>
 * <li><b>L2 — Provider Fallback</b> (seconds): Route the same request to an
 * alternate LLM provider. <em>Stub — requires user-configured fallback chain in
 * llm-router settings.</em></li>
 * <li><b>L3 — Circuit Breaker</b> (minutes): Per-provider state machine (CLOSED
 * → OPEN → HALF_OPEN) that fast-fails requests to a known-broken provider,
 * giving it time to recover.</li>
 * <li><b>L4 — Graceful Degradation</b> (seconds): Reduce request complexity
 * when all providers fail — compact context, downgrade model, strip tools — to
 * increase the chance of a successful call.</li>
 * <li><b>L5 — Cold Retry</b> (minutes to hours): Suspend the turn, notify the
 * user, and schedule a delayed retry. The autonomous loop moves on to other
 * work and resumes this turn later.</li>
 * </ol>
 *
 * <p>
 * The orchestrator is stateless per invocation — all mutable state lives in
 * {@link ProviderCircuitBreaker} (L3) and the delayed action registry (L5). It
 * is designed to be called from {@code LlmCallPhase.handleLlmError()} as a
 * replacement for the inline retry logic.
 *
 * @see LlmRetryPolicy
 * @see ProviderCircuitBreaker
 * @see RecoveryStrategy
 * @see SuspendedTurnManager
 */
public class LlmResilienceOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LlmResilienceOrchestrator.class);

    private final LlmRetryPolicy retryPolicy;
    private final ProviderCircuitBreaker circuitBreaker;
    private final List<RecoveryStrategy> degradationStrategies;
    private final SuspendedTurnManager suspendedTurnManager;

    public LlmResilienceOrchestrator(LlmRetryPolicy retryPolicy,
            ProviderCircuitBreaker circuitBreaker,
            List<RecoveryStrategy> degradationStrategies,
            SuspendedTurnManager suspendedTurnManager) {
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
        this.degradationStrategies = degradationStrategies;
        this.suspendedTurnManager = suspendedTurnManager;
    }

    /**
     * Outcome of the resilience orchestration — tells the caller what to do next.
     */
    public sealed

    interface ResilienceOutcome {

        /** L1/L3/L4 recovered — caller should retry the LLM call immediately. */
        record RetryNow(String layer, String detail) implements ResilienceOutcome {
        }

        /** L5 suspended the turn — caller should stop this turn gracefully. */
        record Suspended(String userMessage) implements ResilienceOutcome {
        }

        /** All layers exhausted — caller should fail the turn permanently. */
        record Exhausted(String reason) implements ResilienceOutcome {
        }
    }

    /**
     * Attempt to recover from a transient LLM error by cascading through all
     * defense layers in order.
     *
     * @param context       the current agent context
     * @param error         the exception thrown by the LLM call
     * @param errorCode     machine-readable code from {@link LlmErrorClassifier}
     * @param attempt       current retry attempt number (0-based)
     * @param config        resilience configuration
     * @return outcome instructing the caller what to do next
     */
    public ResilienceOutcome handle(AgentContext context, RuntimeException error, String errorCode,
                                    int attempt, RuntimeConfig.ResilienceConfig config) {
        String providerId = resolveProviderId(context);

        // ── L1: Hot Retry ──────────────────────────────────────────────
        if (LlmErrorClassifier.isTransientCode(errorCode) && retryPolicy.shouldRetry(attempt, config)) {
            long delayMs = retryPolicy.computeDelay(attempt, config);
            log.warn("[Resilience] L1 hot retry: code={}, attempt={}, delayMs={}, provider={}",
                    errorCode, attempt, delayMs, providerId);
            retryPolicy.sleep(delayMs);
            circuitBreaker.recordFailure(providerId);
            return new ResilienceOutcome.RetryNow("L1", "hot retry after " + delayMs + "ms");
        }

        // ── L2: Provider Fallback (stub) ───────────────────────────────
        // TODO: Implement when llm-router fallback chain configuration is designed.
        //       This layer will route the same LlmRequest to an alternate provider
        //       from the user-configured chain (e.g., anthropic → openai → gemini).
        //       Requires: RuntimeConfig.ModelRouterConfig.fallbackChain field,
        //       FallbackLlmPort decorator, and LlmAdapterFactory.getAdapter() lookup.
        log.debug("[Resilience] L2 provider fallback: not yet configured, skipping");

        // ── L3: Circuit Breaker ────────────────────────────────────────
        if (circuitBreaker.isOpen(providerId)) {
            log.warn("[Resilience] L3 circuit breaker OPEN for provider={}, fast-failing", providerId);
            // Provider is known-broken — skip to L4 immediately
        } else {
            circuitBreaker.recordFailure(providerId);
        }

        // ── L4: Graceful Degradation ───────────────────────────────────
        for (RecoveryStrategy strategy : degradationStrategies) {
            if (strategy.isApplicable(context, errorCode, config)) {
                RecoveryStrategy.RecoveryResult result = strategy.apply(context, errorCode, config);
                if (result.recovered()) {
                    log.info("[Resilience] L4 degradation succeeded: strategy={}, detail={}",
                            strategy.name(), result.detail());
                    return new ResilienceOutcome.RetryNow("L4:" + strategy.name(), result.detail());
                }
                log.debug("[Resilience] L4 strategy {} not effective: {}", strategy.name(), result.detail());
            }
        }

        // ── L5: Cold Retry (suspend and schedule) ──────────────────────
        if (config.getColdRetryEnabled()) {
            String userMessage = suspendedTurnManager.suspend(context, errorCode, config);
            log.info("[Resilience] L5 cold retry: turn suspended, provider={}, code={}", providerId, errorCode);
            return new ResilienceOutcome.Suspended(userMessage);
        }

        // ── All layers exhausted ───────────────────────────────────────
        log.error("[Resilience] All layers exhausted: code={}, provider={}, attempt={}",
                errorCode, providerId, attempt);
        return new ResilienceOutcome.Exhausted(
                "LLM provider " + providerId + " unavailable after all recovery attempts (code=" + errorCode + ")");
    }

    /**
     * Notify the orchestrator that an LLM call succeeded — resets circuit
     * breaker state for the provider.
     */
    public void recordSuccess(AgentContext context) {
        String providerId = resolveProviderId(context);
        circuitBreaker.recordSuccess(providerId);
    }

        private String resolveProviderId(AgentContext context) {
            if (context == null) {
                return "unknown";
            }
            Object model = context.getAttributes().get("llm.model");
            return model instanceof String s && !s.isBlank() ? s : "unknown";
        }
}

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

package me.golemcore.bot.domain.system.toolloop.resilience;

import static me.golemcore.bot.domain.system.toolloop.resilience.ResilienceTraceSupport.traceAttributes;
import static me.golemcore.bot.domain.system.toolloop.resilience.ResilienceTraceSupport.traceStep;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates the L1-L5 resilience cascade for transient LLM failures.
 *
 * <p>
 * Mutable state lives in {@link ProviderCircuitBreaker} and delayed actions;
 * this class chooses the next recovery outcome for the tool loop.
 */
public class LlmResilienceOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LlmResilienceOrchestrator.class);

    private final LlmRetryPolicy retryPolicy;
    private final ProviderCircuitBreaker circuitBreaker;
    private final RouterFallbackSelector routerFallbackSelector;
    private final List<RecoveryStrategy> degradationStrategies;
    private final SuspendedTurnManager suspendedTurnManager;
    private final LlmOpenCircuitPreflight openCircuitPreflight;

    public LlmResilienceOrchestrator(LlmRetryPolicy retryPolicy,
            ProviderCircuitBreaker circuitBreaker,
            List<RecoveryStrategy> degradationStrategies,
            SuspendedTurnManager suspendedTurnManager) {
        this(retryPolicy, circuitBreaker, RouterFallbackSelector.NOOP, degradationStrategies, suspendedTurnManager);
    }

    public LlmResilienceOrchestrator(LlmRetryPolicy retryPolicy,
            ProviderCircuitBreaker circuitBreaker,
            RouterFallbackSelector routerFallbackSelector,
            List<RecoveryStrategy> degradationStrategies,
            SuspendedTurnManager suspendedTurnManager) {
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
        this.routerFallbackSelector = routerFallbackSelector != null ? routerFallbackSelector
                : RouterFallbackSelector.NOOP;
        this.degradationStrategies = degradationStrategies;
        this.suspendedTurnManager = suspendedTurnManager;
        this.openCircuitPreflight = new LlmOpenCircuitPreflight(this.circuitBreaker, this.routerFallbackSelector,
                this.suspendedTurnManager);
    }

    /**
     * Outcome of the resilience orchestration — tells the caller what to do next.
     */
    public interface ResilienceOutcome {

        /**
         * Domain-level trace steps emitted while the orchestrator chose this outcome.
         *
         * <p>
         * The orchestrator deliberately does not depend on {@code TraceService};
         * callers can map these steps to tracing spans, logs, or diagnostics without
         * pulling infrastructure concerns into the resilience layer.
         *
         * @return ordered trace steps for the recovery decision
         */
        default List<ResilienceTraceStep> traceSteps() {
            return List.of();
        }

        /**
         * L1, L2, or L4 recovered the failure and the caller should retry the LLM
         * request immediately.
         *
         * @param layer
         *            resilience layer that selected the retry, for example {@code L2} or
         *            {@code L4:model_downgrade}
         * @param detail
         *            human-readable recovery detail
         * @param traceSteps
         *            ordered trace steps leading to this retry decision
         */
        record RetryNow(String layer, String detail, List<ResilienceTraceStep> traceSteps)
                implements ResilienceOutcome {
        }

        /**
         * L5 suspended the turn and scheduled a delayed retry.
         *
         * @param userMessage
         *            message that can be surfaced to the user
         * @param traceSteps
         *            ordered trace steps leading to this suspension
         */
        record Suspended(String userMessage, List<ResilienceTraceStep> traceSteps) implements ResilienceOutcome {
        }

        /**
         * All configured resilience layers failed to recover the request.
         *
         * @param reason
         *            terminal failure reason
         * @param traceSteps
         *            ordered trace steps leading to exhaustion
         */
        record Exhausted(String reason, List<ResilienceTraceStep> traceSteps) implements ResilienceOutcome {
        }
    }

    /**
     * Lightweight tracing DTO produced by resilience code and consumed by the tool
     * loop tracing adapter.
     *
     * @param layer
     *            resilience layer, for example {@code L2} or {@code L4}
     * @param action
     *            machine-readable action such as {@code retry_now} or
     *            {@code state_transition}
     * @param detail
     *            human-readable detail for the span
     * @param attributes
     *            additional span attributes; copied defensively on construction
     */
    public record ResilienceTraceStep(String layer, String action, String detail, Map<String, Object> attributes) {
        public ResilienceTraceStep {
            attributes = attributes != null ? new LinkedHashMap<>(attributes) : new LinkedHashMap<>();
        }
    }

    /**
     * Attempt to recover from a transient LLM error by cascading through all
     * defense layers in order.
     *
     * @param context
     *            the current agent context
     * @param error
     *            the exception thrown by the LLM call
     * @param errorCode
     *            machine-readable code from {@link LlmErrorClassifier}
     * @param attempt
     *            current retry attempt number (0-based)
     * @param config
     *            resilience configuration
     * @return outcome instructing the caller what to do next
     */
    public ResilienceOutcome handle(AgentContext context, RuntimeException error, String errorCode, int attempt,
            RuntimeConfig.ResilienceConfig config) {
        List<ResilienceTraceStep> traceSteps = new ArrayList<>();
        String providerId = resolveProviderId(context);
        // The breaker is keyed by concrete model ID, even though the historical
        // class name says "provider". This keeps one broken model from muting other
        // models exposed by the same upstream.
        ProviderCircuitBreaker.State breakerStateBeforeCheck = circuitBreaker.getState(providerId);
        boolean breakerOpen = circuitBreaker.isOpen(providerId);
        ProviderCircuitBreaker.State breakerStateAfterCheck = circuitBreaker.getState(providerId);
        appendCircuitTransition(traceSteps, providerId, breakerStateBeforeCheck,
                breakerStateAfterCheck, "availability_check");
        boolean halfOpenProbeFailure = breakerStateBeforeCheck == ProviderCircuitBreaker.State.HALF_OPEN
                || (!breakerOpen && breakerStateAfterCheck == ProviderCircuitBreaker.State.HALF_OPEN);

        // L1: hot retry is deliberately synchronous because it lasts seconds and
        // keeps the current turn state in-memory; longer waits are delegated to L5.
        // We refuse L1 when the breaker is already OPEN — retrying would defeat the
        // breaker's whole purpose (stop hammering a provider in cooldown).
        if (LlmErrorClassifier.isTransientCode(errorCode)
                && retryPolicy.shouldRetry(attempt, config)
                && !breakerOpen
                && !halfOpenProbeFailure) {
            long delayMs = retryPolicy.computeDelay(attempt, config);
            log.warn("[Resilience] L1 hot retry: code={}, attempt={}, delayMs={}, provider={}",
                    errorCode, attempt, delayMs, providerId);
            String detail = "hot retry after " + delayMs + "ms";
            traceSteps.add(traceStep("L1", "retry_now", detail,
                    traceAttributes("provider", providerId, "llm.error.code", errorCode,
                            "retry.delay.ms", delayMs, "retry.attempt", attempt)));
            recordFailureWithTrace(traceSteps, providerId, "failure_recorded");
            return new ResilienceOutcome.RetryNow("L1", detail, traceSteps);
        }

        if (halfOpenProbeFailure) {
            recordFailureWithTrace(traceSteps, providerId, "probe_failure_recorded");
            breakerOpen = true;
        } else if (!breakerOpen) {
            recordFailureWithTrace(traceSteps, providerId, "failure_recorded");
        }

        if (LlmErrorClassifier.isTransientCode(errorCode)) {
            java.util.Optional<RouterFallbackSelector.Selection> fallbackSelection = routerFallbackSelector
                    .selectNext(context, circuitBreaker::isAvailable);
            if (fallbackSelection.isPresent()) {
                RouterFallbackSelector.Selection selection = fallbackSelection.get();
                log.warn("[Resilience] L2 router fallback: provider={}, tier={}, mode={}, fallbackModel={}",
                        providerId, selection.tier(), selection.mode(), selection.model());
                String detail = "router fallback to " + selection.model() + " (tier=" + selection.tier()
                        + ", mode=" + selection.mode() + ")";
                traceSteps.add(traceStep("L2", "retry_now", detail,
                        traceAttributes("provider", providerId, "fallback.tier", selection.tier(),
                                "fallback.mode", selection.mode(), "fallback.model", selection.model(),
                                "fallback.reasoning", selection.reasoning())));
                return new ResilienceOutcome.RetryNow("L2", detail, traceSteps);
            }
            log.debug("[Resilience] L2 router fallback: no eligible fallback for provider={}, skipping", providerId);
        } else {
            log.debug("[Resilience] L2 router fallback: code={} is not transient, skipping", errorCode);
        }

        // L3 fast-fails L4 when the breaker is already OPEN: degradation addresses
        // request-side complexity, but an OPEN breaker signals the provider itself
        // needs cooldown. Skip straight to L5 so the provider gets its rest window.
        if (breakerOpen) {
            log.warn("[Resilience] L3 breaker OPEN, bypassing L4 degradation: provider={}", providerId);
            traceSteps.add(traceStep("L3", "open_bypass", "circuit breaker open; bypassing L4 degradation",
                    traceAttributes("circuit.provider", providerId, "circuit.state.before",
                            circuitBreaker.getState(providerId).name(), "circuit.state.after",
                            circuitBreaker.getState(providerId).name())));
        } else {
            for (RecoveryStrategy strategy : degradationStrategies) {
                if (strategy.isApplicable(context, errorCode, config)) {
                    RecoveryStrategy.RecoveryResult result = strategy.apply(context, errorCode, config);
                    if (result.recovered()) {
                        log.info("[Resilience] L4 degradation succeeded: strategy={}, detail={}",
                                strategy.name(), result.detail());
                        traceSteps.add(traceStep("L4", "retry_now", result.detail(),
                                traceAttributes("provider", providerId, "resilience.strategy",
                                        strategy.name())));
                        return new ResilienceOutcome.RetryNow("L4:" + strategy.name(), result.detail(), traceSteps);
                    }
                    log.debug("[Resilience] L4 strategy {} not effective: {}", strategy.name(), result.detail());
                }
            }
        }

        if (config != null && Boolean.TRUE.equals(config.getColdRetryEnabled())) {
            return suspendForColdRetry(context, errorCode, config, providerId, traceSteps);
        }

        log.error("[Resilience] All layers exhausted: code={}, provider={}, attempt={}",
                errorCode, providerId, attempt);
        String reason = "LLM provider " + providerId + " unavailable after all recovery attempts (code=" + errorCode
                + ")";
        traceSteps.add(traceStep("L5", "exhausted", reason,
                traceAttributes("provider", providerId, "llm.error.code", errorCode,
                        "cold_retry.enabled", false)));
        return new ResilienceOutcome.Exhausted(reason, traceSteps);
    }

    /** Fast-fail before a provider call when the selected model's circuit is open. */
    public ResilienceOutcome preflightOpenCircuit(AgentContext context, RuntimeConfig.ResilienceConfig config) {
        return openCircuitPreflight.handle(context, config);
    }

    /**
     * Notify the orchestrator that an LLM call succeeded — resets circuit breaker
     * state for the provider used by the latest request.
     */
    public void recordSuccess(AgentContext context) {
        String providerId = resolveProviderId(context);
        circuitBreaker.recordSuccess(providerId);
    }

    /**
     * Clears turn-scoped resilience mutations once the full tool-loop turn has
     * reached a successful final answer.
     */
    public void recordTurnSuccess(AgentContext context) {
        routerFallbackSelector.clear(context);
        ResilienceContextState.restoreAfterSuccess(context);
    }

    /**
     * Waits for the retry delay after callers have published user-facing progress
     * for the selected recovery step.
     */
    public void pauseBeforeRetry(ResilienceOutcome.RetryNow retryNow) {
        if (retryPolicy == null || retryNow == null) {
            return;
        }
        long delayMs = retryDelayMs(retryNow.traceSteps());
        if (delayMs > 0L) {
            retryPolicy.sleep(delayMs);
        }
    }

        /**
         * Records a provider failure and appends an L3 trace step only when the breaker
         * actually changes state.
         */
        private void recordFailureWithTrace(List<ResilienceTraceStep> traceSteps, String providerId, String action) {
            ProviderCircuitBreaker.State before = circuitBreaker.getState(providerId);
            circuitBreaker.recordFailure(providerId);
            appendCircuitTransition(traceSteps, providerId, before, circuitBreaker.getState(providerId), action);
        }

        private ResilienceOutcome suspendForColdRetry(AgentContext context, String errorCode,
                RuntimeConfig.ResilienceConfig config, String providerId, List<ResilienceTraceStep> traceSteps) {
            if (suspendedTurnManager == null) {
                String reason = "Cold retry scheduling failed for provider " + providerId
                        + " (code=" + errorCode + "): suspended turn manager is not configured";
                log.warn("[Resilience] L5 cold retry unavailable: provider={}, code={}", providerId, errorCode);
                markTerminalL5Failure(context, reason);
                traceSteps.add(traceStep("L5", "exhausted", reason,
                        traceAttributes("provider", providerId, "llm.error.code", errorCode,
                                "cold_retry.enabled", true)));
                return new ResilienceOutcome.Exhausted(reason, traceSteps);
            }
            try {
                String userMessage = suspendedTurnManager.suspend(context, errorCode, config);
                clearTerminalL5Failure(context);
                log.info("[Resilience] L5 cold retry: turn suspended, provider={}, code={}", providerId, errorCode);
                traceSteps.add(traceStep("L5", "suspend", userMessage,
                        traceAttributes("provider", providerId, "llm.error.code", errorCode,
                                "cold_retry.enabled", true)));
                return new ResilienceOutcome.Suspended(userMessage, traceSteps);
            } catch (RuntimeException exception) {
                String message = exception.getMessage() != null ? exception.getMessage()
                        : exception.getClass().getSimpleName();
                log.warn("[Resilience] L5 cold retry scheduling failed: provider={}, code={}, error={}",
                        providerId, errorCode, message, exception);
                String reason = "Cold retry scheduling failed for provider " + providerId
                        + " (code=" + errorCode + "): " + message;
                markTerminalL5Failure(context, reason);
                traceSteps.add(traceStep("L5", "exhausted", reason,
                        traceAttributes("provider", providerId, "llm.error.code", errorCode,
                                "cold_retry.enabled", true)));
                return new ResilienceOutcome.Exhausted(reason, traceSteps);
            }
        }

        private void markTerminalL5Failure(AgentContext context, String reason) {
            if (context == null) {
                return;
            }
            context.setAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_FAILURE, true);
            context.setAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_REASON, reason);
        }

        private void clearTerminalL5Failure(AgentContext context) {
            if (context == null || context.getAttributes() == null) {
                return;
            }
            context.getAttributes().remove(ContextAttributes.RESILIENCE_L5_TERMINAL_FAILURE);
            context.getAttributes().remove(ContextAttributes.RESILIENCE_L5_TERMINAL_REASON);
        }

        private long retryDelayMs(List<ResilienceTraceStep> traceSteps) {
            if (traceSteps == null) {
                return 0L;
            }
            for (ResilienceTraceStep step : traceSteps) {
                if (step == null || step.attributes() == null) {
                    continue;
                }
                Object delay = step.attributes().get("retry.delay.ms");
                if (delay instanceof Number number) {
                    return Math.max(0L, number.longValue());
                }
                if (delay instanceof String stringValue && !stringValue.isBlank()) {
                    try {
                        return Math.max(0L, Long.parseLong(stringValue));
                    } catch (NumberFormatException ignored) {
                        return 0L;
                    }
                }
            }
            return 0L;
        }

        /**
         * Keeps L3 trace spans semantic: normal failure accounting is not emitted as a
         * span, but CLOSED/OPEN/HALF_OPEN transitions are.
         */
        private void appendCircuitTransition(List<ResilienceTraceStep> traceSteps, String providerId,
                ProviderCircuitBreaker.State before, ProviderCircuitBreaker.State after, String action) {
            if (before == null || after == null || before == after) {
                return;
            }
            traceSteps.add(traceStep("L3", "state_transition", "circuit breaker " + before + " -> " + after,
                    traceAttributes("circuit.provider", providerId, "circuit.action", action,
                            "circuit.state.before", before.name(), "circuit.state.after", after.name())));
        }

        private String resolveProviderId(AgentContext context) {
            if (context == null || context.getAttributes() == null) {
                return "unknown";
            }
            Object model = context.getAttributes().get(ContextAttributes.LLM_MODEL);
            return model instanceof String stringValue && !stringValue.isBlank() ? stringValue : "unknown";
        }

}

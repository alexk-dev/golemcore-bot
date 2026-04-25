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

import static me.golemcore.bot.domain.system.toolloop.resilience.ResilienceTraceSupport.traceAttributes;
import static me.golemcore.bot.domain.system.toolloop.resilience.ResilienceTraceSupport.traceStep;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class LlmOpenCircuitPreflight {

    private static final Logger log = LoggerFactory.getLogger(LlmOpenCircuitPreflight.class);

    private final ProviderCircuitBreaker circuitBreaker;
    private final RouterFallbackSelector routerFallbackSelector;
    private final SuspendedTurnManager suspendedTurnManager;

    LlmOpenCircuitPreflight(ProviderCircuitBreaker circuitBreaker, RouterFallbackSelector routerFallbackSelector,
            SuspendedTurnManager suspendedTurnManager) {
        this.circuitBreaker = circuitBreaker;
        this.routerFallbackSelector = routerFallbackSelector;
        this.suspendedTurnManager = suspendedTurnManager;
    }

    LlmResilienceOrchestrator.ResilienceOutcome handle(AgentContext context,
            RuntimeConfig.ResilienceConfig config) {
        List<LlmResilienceOrchestrator.ResilienceTraceStep> traceSteps = new ArrayList<>();
        String providerId = resolveProviderId(context);
        ProviderCircuitBreaker.State before = circuitBreaker.getState(providerId);
        boolean breakerOpen = circuitBreaker.isOpen(providerId);
        ProviderCircuitBreaker.State after = circuitBreaker.getState(providerId);
        appendCircuitTransition(traceSteps, providerId, before, after);
        if (!breakerOpen) {
            return null;
        }

        log.warn("[Resilience] L3 breaker OPEN, skipping LLM call: provider={}", providerId);
        traceSteps.add(traceStep("L3", "open_bypass", "circuit breaker open; skipping provider call",
                traceAttributes("circuit.provider", providerId, "circuit.state.before",
                        before.name(), "circuit.state.after", after.name())));

        Optional<RouterFallbackSelector.Selection> fallbackSelection = routerFallbackSelector
                .selectNext(context, circuitBreaker::isAvailable);
        if (fallbackSelection.isPresent()) {
            return retryWithFallback(providerId, fallbackSelection.get(), traceSteps);
        }
        if (config != null && Boolean.TRUE.equals(config.getColdRetryEnabled())) {
            return suspendForColdRetry(context, config, providerId, traceSteps);
        }
        return exhausted(providerId, traceSteps);
    }

    private LlmResilienceOrchestrator.ResilienceOutcome retryWithFallback(String providerId,
            RouterFallbackSelector.Selection selection,
            List<LlmResilienceOrchestrator.ResilienceTraceStep> traceSteps) {
        log.warn("[Resilience] L2 router fallback before provider call: provider={}, tier={}, mode={}, "
                + "fallbackModel={}", providerId, selection.tier(), selection.mode(), selection.model());
        String detail = "router fallback to " + selection.model() + " (tier=" + selection.tier()
                + ", mode=" + selection.mode() + ")";
        traceSteps.add(traceStep("L2", "retry_now", detail,
                traceAttributes("provider", providerId, "fallback.tier", selection.tier(),
                        "fallback.mode", selection.mode(), "fallback.model", selection.model(),
                        "fallback.reasoning", selection.reasoning())));
        return new LlmResilienceOrchestrator.ResilienceOutcome.RetryNow("L2", detail, traceSteps);
    }

    private LlmResilienceOrchestrator.ResilienceOutcome suspendForColdRetry(AgentContext context,
            RuntimeConfig.ResilienceConfig config, String providerId,
            List<LlmResilienceOrchestrator.ResilienceTraceStep> traceSteps) {
        if (suspendedTurnManager == null) {
            String reason = "Cold retry scheduling failed for provider " + providerId
                    + " (code=" + LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN
                    + "): suspended turn manager is not configured";
            markTerminalL5Failure(context, reason);
            traceSteps.add(traceStep("L5", "exhausted", reason,
                    traceAttributes("provider", providerId, "llm.error.code",
                            LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN, "cold_retry.enabled", true)));
            return new LlmResilienceOrchestrator.ResilienceOutcome.Exhausted(reason, traceSteps);
        }
        try {
            String userMessage = suspendedTurnManager.suspend(context, LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN,
                    config);
            clearTerminalL5Failure(context);
            traceSteps.add(traceStep("L5", "suspend", userMessage,
                    traceAttributes("provider", providerId, "llm.error.code",
                            LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN, "cold_retry.enabled", true)));
            return new LlmResilienceOrchestrator.ResilienceOutcome.Suspended(userMessage, traceSteps);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() != null ? exception.getMessage()
                    : exception.getClass().getSimpleName();
            String reason = "Cold retry scheduling failed for provider " + providerId
                    + " (code=" + LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN + "): " + message;
            markTerminalL5Failure(context, reason);
            traceSteps.add(traceStep("L5", "exhausted", reason,
                    traceAttributes("provider", providerId, "llm.error.code",
                            LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN, "cold_retry.enabled", true)));
            return new LlmResilienceOrchestrator.ResilienceOutcome.Exhausted(reason, traceSteps);
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

    private LlmResilienceOrchestrator.ResilienceOutcome exhausted(String providerId,
            List<LlmResilienceOrchestrator.ResilienceTraceStep> traceSteps) {
        String reason = "LLM provider " + providerId
                + " skipped because its circuit breaker is OPEN (code="
                + LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN + ")";
        traceSteps.add(traceStep("L5", "exhausted", reason,
                traceAttributes("provider", providerId, "llm.error.code",
                        LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN, "cold_retry.enabled", false)));
        return new LlmResilienceOrchestrator.ResilienceOutcome.Exhausted(reason, traceSteps);
    }

    private void appendCircuitTransition(List<LlmResilienceOrchestrator.ResilienceTraceStep> traceSteps,
            String providerId, ProviderCircuitBreaker.State before, ProviderCircuitBreaker.State after) {
        if (before == null || after == null || before == after) {
            return;
        }
        traceSteps.add(traceStep("L3", "state_transition", "circuit breaker " + before + " -> " + after,
                traceAttributes("circuit.provider", providerId, "circuit.action",
                        "preflight_availability_check", "circuit.state.before", before.name(),
                        "circuit.state.after", after.name())));
    }

    private String resolveProviderId(AgentContext context) {
        if (context == null || context.getAttributes() == null) {
            return "unknown";
        }
        Object model = context.getAttributes().get(ContextAttributes.LLM_MODEL);
        return model instanceof String stringValue && !stringValue.isBlank() ? stringValue : "unknown";
    }
}

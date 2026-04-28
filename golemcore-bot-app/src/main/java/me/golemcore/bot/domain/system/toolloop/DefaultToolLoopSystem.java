package me.golemcore.bot.domain.system.toolloop;

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
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.context.compaction.CompactionOrchestrationService;
import me.golemcore.bot.domain.context.compaction.ContextCompactionPolicy;
import me.golemcore.bot.domain.context.compaction.ContextTokenEstimator;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.domain.tools.PlanModeToolRestrictionService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.tracing.TraceRuntimeConfigSupport;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.domain.progress.TurnProgressService;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.ToolRuntimeSettingsPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Thin orchestrator for the tool loop (single-turn internal loop).
 *
 * <p>
 * Implements the Scenario A contract: the LLM returns tool calls, tools are
 * executed, and the LLM returns a final answer - all inside a single
 * {@link #processTurn} call.
 *
 * <p>
 * The orchestrator delegates to phase classes for implementation details:
 * <ul>
 * <li>{@link LlmCallPhase} - LLM invocation, retry, context overflow
 * recovery</li>
 * <li>{@link ToolExecutionPhase} - tool call iteration, plan intercept,
 * tracing</li>
 * <li>{@link ToolFailurePolicy} - stop conditions, recovery decisions</li>
 * </ul>
 *
 * <p>
 * All mutable turn state is encapsulated in {@link TurnState}.
 *
 * @see LlmCallPhase
 * @see ToolExecutionPhase
 * @see ToolFailurePolicy
 * @see TurnState
 */
public class DefaultToolLoopSystem implements ToolLoopSystem {

    private final HistoryWriter historyWriter;
    private final ToolRuntimeSettingsPort.TurnSettings turnSettings;
    private final ToolRuntimeSettingsPort.ToolLoopSettings settings;
    private final RuntimeConfigService runtimeConfigService;
    private final RuntimeEventService runtimeEventService;
    private final Clock clock;

    private final LlmCallPhase llmCallPhase;
    private final ToolExecutionPhase toolExecutionPhase;

    private DefaultToolLoopSystem(Builder builder) {
        this.historyWriter = builder.historyWriter;
        this.turnSettings = builder.turnSettings;
        this.settings = builder.settings;
        this.runtimeConfigService = builder.runtimeConfigService;
        this.runtimeEventService = builder.runtimeEventService;
        this.clock = builder.clock;

        ContextTokenEstimator contextTokenEstimator = builder.contextTokenEstimator != null
                ? builder.contextTokenEstimator
                : new ContextTokenEstimator();
        // Preflight's budget resolver has exactly one production assembly site
        // (ToolLoopAutoConfiguration). Forcing callers to wire it removes the
        // temptation to let the fallback quietly cover a broken bean graph -
        // any missing wiring now fails loudly at startup instead of at the
        // first LLM call.
        ContextCompactionPolicy contextCompactionPolicy = Objects.requireNonNull(builder.contextCompactionPolicy,
                "contextCompactionPolicy required; wire it explicitly on the builder");
        // The coordinator is shared by request preflight, context-overflow
        // recovery, and the L4 resilience compaction strategy. Spring injects a
        // singleton coordinator; tests and hand-built systems may still omit it,
        // in which case we assemble the same collaborator locally.
        ContextCompactionCoordinator compactionCoordinator = builder.contextCompactionCoordinator != null
                ? builder.contextCompactionCoordinator
                : new ContextCompactionCoordinator(
                        contextCompactionPolicy,
                        builder.compactionOrchestrationService,
                        builder.runtimeEventService,
                        builder.turnProgressService);
        LlmRequestPreflightPhase preflightPhase = new LlmRequestPreflightPhase(
                contextTokenEstimator,
                contextCompactionPolicy,
                compactionCoordinator);

        this.llmCallPhase = new LlmCallPhase(
                builder.llmPort, builder.viewBuilder, builder.modelSelectionService,
                builder.runtimeConfigService, preflightPhase,
                compactionCoordinator,
                builder.runtimeEventService, builder.turnProgressService,
                builder.traceService, builder.resilienceOrchestrator, builder.clock);

        ToolFailurePolicy failurePolicy = new ToolFailurePolicy(
                builder.toolFailureRecoveryService != null
                        ? builder.toolFailureRecoveryService
                        : new ToolFailureRecoveryService(),
                builder.clock);

        this.toolExecutionPhase = new ToolExecutionPhase(
                builder.toolExecutor, failurePolicy, builder.runtimeEventService,
                builder.turnProgressService, builder.traceService,
                builder.runtimeConfigService, builder.clock,
                builder.planModeToolRestrictionService);
    }

    /**
     * Creates a new builder for constructing a {@link DefaultToolLoopSystem}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Executes a single tool loop turn.
     *
     * <p>
     * High-level flow:
     * <ol>
     * <li>Initialize turn state with resolved limits and configuration</li>
     * <li>While limits allow, repeat:
     * <ol type="a">
     * <li>Call the LLM</li>
     * <li>If no tool calls: finalize as final answer</li>
     * <li>Execute tool call batch with failure policy</li>
     * <li>If stopped or interrupted: return result</li>
     * </ol>
     * </li>
     * <li>Handle limit reached</li>
     * </ol>
     *
     * @param context
     *            the agent context for this turn
     * @return the turn result
     */
    @Override
    public ToolLoopTurnResult processTurn(AgentContext context) {
        TurnState turnState = initializeTurn(context);

        while (turnState.canContinue(clock.instant())) {
            // --- Phase 1: LLM call ---
            LlmCallPhase.LlmCallOutcome llmOutcome = llmCallPhase.execute(turnState, historyWriter);

            if (llmOutcome instanceof LlmCallPhase.LlmCallOutcome.RetryScheduled) {
                continue;
            }
            if (llmOutcome instanceof LlmCallPhase.LlmCallOutcome.Failed failed) {
                return failed.result();
            }
            if (llmOutcome instanceof LlmCallPhase.LlmCallOutcome.Interrupted interrupted) {
                return interrupted.result();
            }

            LlmResponse response = ((LlmCallPhase.LlmCallOutcome.Success) llmOutcome).response();

            // --- Phase 2: Check for final answer ---
            boolean hasToolCalls = response != null && response.hasToolCalls();
            if (!hasToolCalls) {
                LlmCallPhase.EmptyResponseCheck emptyCheck = llmCallPhase.checkEmptyFinalResponse(
                        turnState, response, historyWriter);

                if (emptyCheck instanceof LlmCallPhase.EmptyResponseCheck.Failed failed) {
                    return failed.result();
                }
                if (emptyCheck instanceof LlmCallPhase.EmptyResponseCheck.RetryScheduled) {
                    continue;
                }
                return llmCallPhase.finalizeFinalAnswer(turnState, response, historyWriter);
            }

            // --- Phase 3: Execute tool calls ---
            ToolExecutionPhase.ToolBatchOutcome batchOutcome = toolExecutionPhase.execute(
                    turnState, response, historyWriter, llmCallPhase);

            if (batchOutcome instanceof ToolExecutionPhase.ToolBatchOutcome.StopTurn stop) {
                return stop.result();
            }
            if (batchOutcome instanceof ToolExecutionPhase.ToolBatchOutcome.Interrupted interrupted) {
                return interrupted.result();
            }
            // RecoveryHintInjected and Continue both loop back to LLM call
        }

        // --- Limit reached ---
        return llmCallPhase.handleLimitReached(turnState, historyWriter);
    }

    // ==================== Turn initialization ====================

    private TurnState initializeTurn(AgentContext context) {
        ensureMessageLists(context);
        emitRuntimeEvent(context, RuntimeEventType.TURN_STARTED, eventPayload());
        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(runtimeConfigService,
                shouldForceTracingPayloadCapture());

        int maxLlmCalls = resolveMaxLlmCalls();
        int maxToolExecutions = resolveMaxToolExecutions();
        Duration turnDeadline = resolveTurnDeadline();
        Instant deadline = clock.instant().plus(turnDeadline);
        boolean stopOnToolFailure = settings != null && settings.stopOnToolFailure();
        boolean stopOnConfirmationDenied = settings == null || settings.stopOnConfirmationDenied();
        boolean stopOnToolPolicyDenied = settings != null && settings.stopOnToolPolicyDenied();
        int maxRetries = resolveAutoRetryMaxAttempts();
        long retryBaseDelayMs = resolveAutoRetryBaseDelayMs();
        boolean retryEnabled = isAutoRetryEnabled();

        return new TurnState(context, tracingConfig, maxLlmCalls, maxToolExecutions, deadline,
                stopOnToolFailure, stopOnConfirmationDenied, stopOnToolPolicyDenied,
                maxRetries, retryBaseDelayMs, retryEnabled);
    }

    // ==================== Configuration resolution ====================

    private int resolveMaxLlmCalls() {
        if (runtimeConfigService != null) {
            int maxLlmCalls = runtimeConfigService.getToolLoopMaxLlmCalls();
            if (maxLlmCalls > 0) {
                return maxLlmCalls;
            }
            return runtimeConfigService.getTurnMaxLlmCalls();
        }
        return settings != null ? settings.maxLlmCalls()
                : turnSettings != null ? turnSettings.maxLlmCalls() : 200;
    }

    private int resolveMaxToolExecutions() {
        if (runtimeConfigService != null) {
            int maxToolExecutions = runtimeConfigService.getToolLoopMaxToolExecutions();
            if (maxToolExecutions > 0) {
                return maxToolExecutions;
            }
            return runtimeConfigService.getTurnMaxToolExecutions();
        }
        return settings != null ? settings.maxToolExecutions()
                : turnSettings != null ? turnSettings.maxToolExecutions() : 500;
    }

    private Duration resolveTurnDeadline() {
        if (runtimeConfigService != null) {
            return runtimeConfigService.getTurnDeadline();
        }
        return turnSettings != null ? turnSettings.deadline() : Duration.ofHours(1);
    }

    private int resolveAutoRetryMaxAttempts() {
        if (runtimeConfigService == null) {
            return 0;
        }
        return Math.max(0, runtimeConfigService.getTurnAutoRetryMaxAttempts());
    }

    private long resolveAutoRetryBaseDelayMs() {
        if (runtimeConfigService == null) {
            return 500L;
        }
        return Math.max(1L, runtimeConfigService.getTurnAutoRetryBaseDelayMs());
    }

    private boolean isAutoRetryEnabled() {
        if (runtimeConfigService == null) {
            return false;
        }
        return runtimeConfigService.isTurnAutoRetryEnabled();
    }

    private boolean shouldForceTracingPayloadCapture() {
        return runtimeConfigService != null
                && runtimeConfigService.isSelfEvolvingEnabled()
                && runtimeConfigService.isSelfEvolvingTracePayloadOverrideEnabled();
    }

    // ==================== Utility ====================

    private void ensureMessageLists(AgentContext context) {
        if (context.getMessages() == null) {
            context.setMessages(new ArrayList<>());
        }
        if (context.getSession() != null && context.getSession().getMessages() == null) {
            context.getSession().setMessages(new ArrayList<>());
        }
        if (context.getSession() != null && context.getSession().getMetadata() == null) {
            context.getSession().setMetadata(new LinkedHashMap<>());
        }
    }

    private void emitRuntimeEvent(AgentContext context, RuntimeEventType type, Map<String, Object> payload) {
        if (runtimeEventService == null || context == null) {
            return;
        }
        runtimeEventService.emit(context, type, payload);
    }

    private Map<String, Object> eventPayload(Object... entries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (entries == null || entries.length == 0) {
            return payload;
        }
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Runtime event payload entries must be key/value pairs");
        }
        for (int index = 0; index < entries.length; index += 2) {
            Object keyObject = entries[index];
            if (!(keyObject instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException("Runtime event payload keys must be non-blank strings");
            }
            payload.put(key, entries[index + 1]);
        }
        return payload;
    }

    // ==================== Builder ====================

    /**
     * Builder for constructing a {@link DefaultToolLoopSystem} instance.
     *
     * <p>
     * Required fields: llmPort, toolExecutor, historyWriter, viewBuilder,
     * modelSelectionService, contextCompactionPolicy, clock. All other fields are
     * optional.
     */
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    public static class Builder {

        private LlmPort llmPort;
        private ToolExecutorPort toolExecutor;
        private HistoryWriter historyWriter;
        private ConversationViewBuilder viewBuilder;
        private ToolRuntimeSettingsPort.TurnSettings turnSettings;
        private ToolRuntimeSettingsPort.ToolLoopSettings settings;
        private ModelSelectionService modelSelectionService;
        private RuntimeConfigService runtimeConfigService;
        private CompactionOrchestrationService compactionOrchestrationService;
        private ContextTokenEstimator contextTokenEstimator;
        private ContextCompactionPolicy contextCompactionPolicy;
        private ContextCompactionCoordinator contextCompactionCoordinator;
        private RuntimeEventService runtimeEventService;
        private TurnProgressService turnProgressService;
        private TraceService traceService;
        private ToolFailureRecoveryService toolFailureRecoveryService;
        private PlanModeToolRestrictionService planModeToolRestrictionService;
        private me.golemcore.bot.domain.system.toolloop.resilience.LlmResilienceOrchestrator resilienceOrchestrator;
        private Clock clock;

        /** Sets the LLM invocation port (required). */
        public Builder llmPort(LlmPort llmPort) {
            this.llmPort = llmPort;
            return this;
        }

        /** Sets the tool execution port (required). */
        public Builder toolExecutor(ToolExecutorPort toolExecutor) {
            this.toolExecutor = toolExecutor;
            return this;
        }

        /** Sets the history writer (required). */
        public Builder historyWriter(HistoryWriter historyWriter) {
            this.historyWriter = historyWriter;
            return this;
        }

        /** Sets the conversation view builder (required). */
        public Builder viewBuilder(ConversationViewBuilder viewBuilder) {
            this.viewBuilder = viewBuilder;
            return this;
        }

        /** Sets the turn-level configuration properties (optional). */
        public Builder turnSettings(ToolRuntimeSettingsPort.TurnSettings turnSettings) {
            this.turnSettings = turnSettings;
            return this;
        }

        /** Sets the tool loop configuration properties (optional). */
        public Builder settings(ToolRuntimeSettingsPort.ToolLoopSettings settings) {
            this.settings = settings;
            return this;
        }

        /** Sets the model selection service (required). */
        public Builder modelSelectionService(ModelSelectionService modelSelectionService) {
            this.modelSelectionService = modelSelectionService;
            return this;
        }

        /** Sets the runtime configuration service (optional). */
        public Builder runtimeConfigService(RuntimeConfigService runtimeConfigService) {
            this.runtimeConfigService = runtimeConfigService;
            return this;
        }

        /** Sets the compaction orchestration service (optional). */
        public Builder compactionOrchestrationService(CompactionOrchestrationService compactionOrchestrationService) {
            this.compactionOrchestrationService = compactionOrchestrationService;
            return this;
        }

        /** Sets the token estimator used by request preflight checks (optional). */
        public Builder contextTokenEstimator(ContextTokenEstimator contextTokenEstimator) {
            this.contextTokenEstimator = contextTokenEstimator;
            return this;
        }

        /**
         * Sets the context compaction policy used by preflight and overflow recovery
         * (required).
         */
        public Builder contextCompactionPolicy(ContextCompactionPolicy contextCompactionPolicy) {
            this.contextCompactionPolicy = contextCompactionPolicy;
            return this;
        }

        /**
         * Sets the coordinator shared with the resilience compaction strategy. Passing
         * the same instance keeps LLM preflight and L4 recovery diagnostics consistent
         * within a turn.
         */
        public Builder contextCompactionCoordinator(ContextCompactionCoordinator contextCompactionCoordinator) {
            this.contextCompactionCoordinator = contextCompactionCoordinator;
            return this;
        }

        /** Sets the runtime event service (optional). */
        public Builder runtimeEventService(RuntimeEventService runtimeEventService) {
            this.runtimeEventService = runtimeEventService;
            return this;
        }

        /** Sets the turn progress service (optional). */
        public Builder turnProgressService(TurnProgressService turnProgressService) {
            this.turnProgressService = turnProgressService;
            return this;
        }

        /** Sets the distributed tracing service (optional). */
        public Builder traceService(TraceService traceService) {
            this.traceService = traceService;
            return this;
        }

        /**
         * Sets the tool failure recovery service (optional, defaults to new instance).
         */
        public Builder toolFailureRecoveryService(ToolFailureRecoveryService toolFailureRecoveryService) {
            this.toolFailureRecoveryService = toolFailureRecoveryService;
            return this;
        }

        /** Sets the plan-mode tool restriction service (optional). */
        public Builder planModeToolRestrictionService(
                PlanModeToolRestrictionService planModeToolRestrictionService) {
            this.planModeToolRestrictionService = planModeToolRestrictionService;
            return this;
        }

        /** Sets the LLM resilience orchestrator (optional). */
        public Builder resilienceOrchestrator(
                me.golemcore.bot.domain.system.toolloop.resilience.LlmResilienceOrchestrator resilienceOrchestrator) {
            this.resilienceOrchestrator = resilienceOrchestrator;
            return this;
        }

        /** Sets the clock (required). */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Builds the {@link DefaultToolLoopSystem} instance.
         *
         * @return a new DefaultToolLoopSystem
         */
        public DefaultToolLoopSystem build() {
            return new DefaultToolLoopSystem(this);
        }
    }
}

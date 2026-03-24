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
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable state bag for a single tool loop turn.
 *
 * <p>
 * Encapsulates all counters, accumulators, and resolved configuration that were
 * previously scattered as local variables inside
 * {@link DefaultToolLoopSystem#processTurn}. Created once at the beginning of a
 * turn and threaded through every phase.
 *
 * <p>
 * This class is intentionally <b>not</b> thread-safe — a single turn always
 * executes on one thread.
 */
public class TurnState {

    private final AgentContext context;
    private final RuntimeConfig.TracingConfig tracingConfig;

    // --- Loop counters ---
    private int llmCalls;
    private int toolExecutions;
    private int emptyFinalResponseRetries;
    private int retryAttempt;
    private String lastRetryCode = "";

    // --- Resolved limits ---
    private final int maxLlmCalls;
    private final int maxToolExecutions;
    private final Instant deadline;
    private final boolean stopOnToolFailure;
    private final boolean stopOnConfirmationDenied;
    private final boolean stopOnToolPolicyDenied;

    // --- Retry config ---
    private final int maxRetries;
    private final long retryBaseDelayMs;
    private final boolean retryEnabled;

    // --- Accumulators ---
    private final List<Attachment> accumulatedAttachments = new ArrayList<>();
    private final List<Map<String, Object>> turnFileChanges = new ArrayList<>();
    private final Map<String, Integer> toolFailureCounts = new LinkedHashMap<>();
    private final Map<String, Integer> toolRecoveryCounts = new LinkedHashMap<>();

    // --- Last LLM response for stop-turn bookkeeping ---
    private LlmResponse lastLlmResponse;

    /**
     * Creates a new turn state with pre-resolved configuration.
     *
     * @param context
     *            the agent context for this turn
     * @param tracingConfig
     *            resolved tracing configuration (may be null)
     * @param maxLlmCalls
     *            maximum LLM invocations allowed
     * @param maxToolExecutions
     *            maximum tool executions allowed
     * @param deadline
     *            absolute deadline instant
     * @param stopOnToolFailure
     *            whether to stop on any tool failure
     * @param stopOnConfirmationDenied
     *            whether to stop when user denies confirmation
     * @param stopOnToolPolicyDenied
     *            whether to stop when tool policy denies
     * @param maxRetries
     *            maximum auto-retry attempts for transient LLM errors
     * @param retryBaseDelayMs
     *            base delay in milliseconds for exponential backoff
     * @param retryEnabled
     *            whether auto-retry is enabled
     */
    public TurnState(AgentContext context, RuntimeConfig.TracingConfig tracingConfig, int maxLlmCalls,
            int maxToolExecutions, Instant deadline, boolean stopOnToolFailure, boolean stopOnConfirmationDenied,
            boolean stopOnToolPolicyDenied, int maxRetries, long retryBaseDelayMs, boolean retryEnabled) {
        this.context = context;
        this.tracingConfig = tracingConfig;
        this.maxLlmCalls = maxLlmCalls;
        this.maxToolExecutions = maxToolExecutions;
        this.deadline = deadline;
        this.stopOnToolFailure = stopOnToolFailure;
        this.stopOnConfirmationDenied = stopOnConfirmationDenied;
        this.stopOnToolPolicyDenied = stopOnToolPolicyDenied;
        this.maxRetries = maxRetries;
        this.retryBaseDelayMs = retryBaseDelayMs;
        this.retryEnabled = retryEnabled;
    }

    /**
     * Whether the main loop should continue iterating.
     *
     * @param now
     *            current instant from the clock
     * @return true if neither LLM call nor tool execution limits are exceeded and
     *         the deadline has not passed
     */
    public boolean canContinue(Instant now) {
        return llmCalls < maxLlmCalls && toolExecutions < maxToolExecutions && now.isBefore(deadline);
    }

    /** Increments the LLM call counter and returns the new value. */
    public int incrementLlmCalls() {
        llmCalls++;
        return llmCalls;
    }

    /** Increments the tool execution counter. */
    public void incrementToolExecutions() {
        toolExecutions++;
    }

    /** Increments empty final response retry counter and returns the new value. */
    public int incrementEmptyFinalResponseRetries() {
        emptyFinalResponseRetries++;
        return emptyFinalResponseRetries;
    }

    /** Increments retry attempt and returns the new value. */
    public int incrementRetryAttempt() {
        retryAttempt++;
        return retryAttempt;
    }

    /** Resets retry state after a successful recovery. */
    public void resetRetryState() {
        retryAttempt = 0;
        lastRetryCode = "";
    }

    // --- Getters ---

    public AgentContext getContext() {
        return context;
    }

    public RuntimeConfig.TracingConfig getTracingConfig() {
        return tracingConfig;
    }

    public int getLlmCalls() {
        return llmCalls;
    }

    public int getToolExecutions() {
        return toolExecutions;
    }

    public int getEmptyFinalResponseRetries() {
        return emptyFinalResponseRetries;
    }

    public int getRetryAttempt() {
        return retryAttempt;
    }

    public String getLastRetryCode() {
        return lastRetryCode;
    }

    public void setLastRetryCode(String lastRetryCode) {
        this.lastRetryCode = lastRetryCode;
    }

    public int getMaxLlmCalls() {
        return maxLlmCalls;
    }

    public int getMaxToolExecutions() {
        return maxToolExecutions;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public boolean isStopOnToolFailure() {
        return stopOnToolFailure;
    }

    public boolean isStopOnConfirmationDenied() {
        return stopOnConfirmationDenied;
    }

    public boolean isStopOnToolPolicyDenied() {
        return stopOnToolPolicyDenied;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryBaseDelayMs() {
        return retryBaseDelayMs;
    }

    public boolean isRetryEnabled() {
        return retryEnabled;
    }

    public List<Attachment> getAccumulatedAttachments() {
        return accumulatedAttachments;
    }

    public List<Map<String, Object>> getTurnFileChanges() {
        return turnFileChanges;
    }

    public Map<String, Integer> getToolFailureCounts() {
        return toolFailureCounts;
    }

    public Map<String, Integer> getToolRecoveryCounts() {
        return toolRecoveryCounts;
    }

    public LlmResponse getLastLlmResponse() {
        return lastLlmResponse;
    }

    public void setLastLlmResponse(LlmResponse lastLlmResponse) {
        this.lastLlmResponse = lastLlmResponse;
    }
}

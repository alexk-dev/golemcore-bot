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
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import me.golemcore.bot.domain.service.ContextTokenEstimator;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Performs request-size preflight immediately before an LLM provider call.
 *
 * <p>
 * The goal is to catch oversized requests locally (cheap, with accurate
 * diagnostics) instead of letting the provider reject them remotely (slow, with
 * opaque errors). Each preflight call re-estimates the request, and if it
 * exceeds the resolved threshold, runs one compaction attempt, rebuilds the
 * request, and re-checks. Up to {@value #PREFLIGHT_MAX_ATTEMPTS} attempts are
 * allowed; each attempt halves keepLast so later attempts can free more room
 * when earlier ones weren't aggressive enough.
 */
@Slf4j
class LlmRequestPreflightPhase {

    // Three attempts is the empirical sweet spot: one attempt handles normal
    // growth, two handles a long-tail turn where the first compaction couldn't
    // fit below threshold, three is the hard ceiling before we give up and let
    // the provider reject. More attempts burn latency without paying off -
    // keepLast halving saturates quickly.
    private static final int PREFLIGHT_MAX_ATTEMPTS = 3;

    private static final String COMPACTION_OUTCOME_SKIPPED_DISABLED = "skipped_disabled";
    private static final String COMPACTION_OUTCOME_SKIPPED_NO_MESSAGES = "skipped_no_messages";
    // Preflight-diagnostics-only outcome. No COMPACTION_FINISHED event is emitted
    // when a request is already within budget, so this intentionally does not live
    // in CompactionFinishedPayloads.
    private static final String COMPACTION_OUTCOME_NOT_ATTEMPTED = "not_attempted";

    private final ContextTokenEstimator contextTokenEstimator;
    private final ContextCompactionPolicy contextCompactionPolicy;
    private final ContextCompactionCoordinator compactionCoordinator;

    public LlmRequestPreflightPhase(
            ContextTokenEstimator contextTokenEstimator,
            ContextCompactionPolicy contextCompactionPolicy,
            ContextCompactionCoordinator compactionCoordinator) {
        this.contextTokenEstimator = contextTokenEstimator;
        this.contextCompactionPolicy = contextCompactionPolicy;
        this.compactionCoordinator = compactionCoordinator;
    }

    LlmRequest preflight(AgentContext context, Supplier<LlmRequest> requestSupplier, int llmCall) {
        PreflightDiagnostics diagnostics = new PreflightDiagnostics();
        boolean terminalPublishStarted = false;
        try {
            LlmRequest request = requestSupplier.get();
            for (int attempt = 1; attempt <= PREFLIGHT_MAX_ATTEMPTS; attempt++) {
                // Re-estimate on every attempt: after a successful compaction the
                // message list shrinks, and threshold can shift if the model
                // selection changed (e.g. tier upgrade mid-turn).
                int estimatedTokens = contextTokenEstimator.estimateRequest(request);
                int threshold = contextCompactionPolicy.resolveFullRequestThreshold(context);
                diagnostics.recordAttempt(estimatedTokens, threshold, attempt);
                if (estimatedTokens <= threshold) {
                    terminalPublishStarted = true;
                    diagnostics.publish(context, true);
                    return request;
                }
                // runCompactionAttempt returns false when compaction is disabled,
                // there's nothing to compact, or the last attempt was a no-op.
                // In those cases we stop retrying and send the request anyway so
                // the provider can reject with a precise error - better than
                // spinning forever on an unfixable state.
                if (!runCompactionAttempt(context, diagnostics, estimatedTokens, threshold, attempt, llmCall)) {
                    terminalPublishStarted = true;
                    diagnostics.publish(context, true);
                    log.warn("[ToolLoop] LLM request still exceeds context budget after preflight check "
                            + "(~{} tokens > threshold {}), sending request so provider can fail fast",
                            estimatedTokens, threshold);
                    return request;
                }
                // Compaction mutated the session; ask the caller to rebuild the
                // request from the now-shorter message list. The supplier is
                // responsible for pulling fresh state - we never reuse the old
                // LlmRequest instance because it still points at the pre-compact
                // messages.
                request = requestSupplier.get();
            }

            int finalEstimatedTokens = contextTokenEstimator.estimateRequest(request);
            int finalThreshold = contextCompactionPolicy.resolveFullRequestThreshold(context);
            diagnostics.recordAttempt(finalEstimatedTokens, finalThreshold, PREFLIGHT_MAX_ATTEMPTS);
            terminalPublishStarted = true;
            diagnostics.publish(context, true);
            if (finalEstimatedTokens > finalThreshold) {
                log.warn("[ToolLoop] LLM request remains above context budget after {} preflight compaction attempts "
                        + "(~{} tokens > threshold {})", PREFLIGHT_MAX_ATTEMPTS, finalEstimatedTokens, finalThreshold);
            }
            return request;
        } catch (RuntimeException e) {
            if (!terminalPublishStarted) {
                diagnostics.recordPreflightError();
                diagnostics.publish(context, true);
            }
            throw e;
        }
    }

    private boolean runCompactionAttempt(AgentContext context, PreflightDiagnostics diagnostics, int estimatedTokens,
            int threshold, int attempt, int llmCall) {
        if (!contextCompactionPolicy.isCompactionEnabled()) {
            diagnostics.recordCompactionSkipped(COMPACTION_OUTCOME_SKIPPED_DISABLED);
            return false;
        }
        if (context.getSession() == null || context.getSession().getMessages() == null
                || context.getSession().getMessages().isEmpty()) {
            diagnostics.recordCompactionSkipped(COMPACTION_OUTCOME_SKIPPED_NO_MESSAGES);
            return false;
        }
        int total = context.getSession().getMessages().size();
        if (total <= 1) {
            diagnostics.recordCompactionSkipped(COMPACTION_OUTCOME_SKIPPED_NO_MESSAGES);
            return false;
        }
        int keepLast = contextCompactionPolicy.resolvePreflightKeepLast(total, attempt);

        log.info("[ToolLoop] LLM request exceeds context budget before provider call: "
                + "~{} tokens > threshold {}. Running preflight compaction (attempt {}/{}, keepLast={})",
                estimatedTokens, threshold, attempt, PREFLIGHT_MAX_ATTEMPTS, keepLast);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("estimatedTokens", estimatedTokens);
        extra.put("threshold", threshold);
        extra.put("attempt", attempt);

        CompactionResult compacted = compactionCoordinator.runCompaction(
                context, CompactionReason.REQUEST_PREFLIGHT, keepLast, llmCall, extra);
        if (compacted != null) {
            diagnostics.recordCompactionRun(compacted.removed(), compacted.usedSummary(),
                    CompactionFinishedPayloads.OUTCOME_COMPACTED);
            return true;
        }
        diagnostics.recordCompactionRun(0, false, CompactionFinishedPayloads.OUTCOME_ATTEMPTED_NO_CHANGE);
        return false;
    }

    /**
     * Mutable per-call diagnostic state. Centralising every field in one place
     * guarantees that all publish sites emit the same schema - there is only one
     * {@link #toMap(boolean)} method, so a new field can never be accidentally
     * skipped on one of the exit paths. The class is private to the phase and reset
     * on every {@link LlmRequestPreflightPhase#preflight} entry, so stale state
     * from an earlier turn cannot leak forward.
     *
     * <p>
     * {@code compactionOutcome=error} is a preflight diagnostic outcome: it means
     * an uncaught failure happened somewhere in preflight (request rebuild,
     * estimate, budget resolution, or compaction), not necessarily that the
     * compaction service itself failed. If that happens before the first loop
     * attempt, {@code attempt=0} is the pre-loop sentinel.
     * </p>
     */
    private static final class PreflightDiagnostics {

        private int estimatedTokens;
        private int threshold;
        private int attempt;
        private boolean overThreshold;
        private boolean compactionAttempted;
        // Cumulative across every preflight attempt within a single preflight()
        // call - a later no-op attempt must not hide earlier successful
        // removals. Reset-per-call happens implicitly because this instance is
        // freshly constructed at the top of preflight().
        private int compactionRemoved;
        private boolean compactionUsedSummary;
        private String compactionOutcome = COMPACTION_OUTCOME_NOT_ATTEMPTED;

        void recordAttempt(int estimatedTokens, int threshold, int attempt) {
            this.estimatedTokens = estimatedTokens;
            this.threshold = threshold;
            this.attempt = attempt;
            this.overThreshold = estimatedTokens > threshold;
        }

        void recordCompactionSkipped(String outcome) {
            this.compactionOutcome = outcome;
        }

        void recordCompactionRun(int removedThisAttempt, boolean usedSummary, String outcome) {
            this.compactionAttempted = true;
            if (removedThisAttempt < 0) {
                log.warn("[ToolLoop] recordCompactionRun received negative removedThisAttempt={}; clamping to 0. "
                        + "This indicates a programming error upstream of this call site.", removedThisAttempt);
            }
            this.compactionRemoved += Math.max(0, removedThisAttempt);
            this.compactionUsedSummary = this.compactionUsedSummary || usedSummary;
            this.compactionOutcome = outcome;
        }

        void recordPreflightError() {
            this.compactionAttempted = this.compactionAttempted || overThreshold;
            this.compactionOutcome = CompactionFinishedPayloads.OUTCOME_ERROR;
        }

        void publish(AgentContext context, boolean terminal) {
            context.setAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT, toMap(terminal));
        }

        private Map<String, Object> toMap(boolean terminal) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("estimatedTokens", estimatedTokens);
            map.put("threshold", threshold);
            map.put("attempt", attempt);
            map.put("maxAttempts", PREFLIGHT_MAX_ATTEMPTS);
            map.put("overThreshold", overThreshold);
            map.put("terminal", terminal);
            map.put("compactionAttempted", compactionAttempted);
            map.put("compactionRemoved", compactionRemoved);
            map.put("compactionUsedSummary", compactionUsedSummary);
            map.put("compactionOutcome", compactionOutcome);
            return map;
        }
    }
}

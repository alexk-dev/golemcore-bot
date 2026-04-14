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
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.ContextBudgetPolicy;
import me.golemcore.bot.domain.service.ContextTokenEstimator;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TurnProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Performs request-size preflight immediately before an LLM provider call and
 * owns the shared compaction cycle reused by the post-call overflow recovery
 * path in {@link LlmCallPhase}.
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
@RequiredArgsConstructor
@Slf4j
class LlmRequestPreflightPhase {

    // Three attempts is the empirical sweet spot: one attempt handles normal
    // growth, two handles a long-tail turn where the first compaction couldn't
    // fit below threshold, three is the hard ceiling before we give up and let
    // the provider reject. More attempts burn latency without paying off —
    // keepLast halving saturates quickly.
    private static final int PREFLIGHT_MAX_ATTEMPTS = 3;

    private static final String COMPACTION_OUTCOME_SKIPPED_DISABLED = "skipped_disabled";
    private static final String COMPACTION_OUTCOME_SKIPPED_NO_MESSAGES = "skipped_no_messages";

    private final RuntimeConfigService runtimeConfigService;
    private final CompactionOrchestrationService compactionOrchestrationService;
    private final ContextTokenEstimator contextTokenEstimator;
    private final RuntimeEventService runtimeEventService;
    private final TurnProgressService turnProgressService;
    private final ContextBudgetPolicy contextBudgetPolicy;

    private final AtomicBoolean compactionDisabledOverflowLogged = new AtomicBoolean();

    LlmRequest preflight(AgentContext context, Supplier<LlmRequest> requestSupplier, int llmCall) {
        // Reset per-turn diagnostics first so a later no-op preflight doesn't
        // inherit stale "compacted" flags from a previous turn on the same
        // context instance.
        resetCompactionDiagnostics(context);
        LlmRequest request = requestSupplier.get();
        for (int attempt = 1; attempt <= PREFLIGHT_MAX_ATTEMPTS; attempt++) {
            // Re-estimate on every attempt: after a successful compaction the
            // message list shrinks, and threshold can shift if the model
            // selection changed (e.g. tier upgrade mid-turn).
            int estimatedTokens = contextTokenEstimator.estimateRequest(request);
            int threshold = resolveThreshold(context);
            if (estimatedTokens <= threshold) {
                publishDiagnostics(context, estimatedTokens, threshold, attempt, true);
                return request;
            }
            // preflightCompact returns false when compaction is disabled,
            // there's nothing to compact, or the last attempt was a no-op.
            // In those cases we stop retrying and send the request anyway so
            // the provider can reject with a precise error — better than
            // spinning forever on an unfixable state.
            if (!preflightCompact(context, estimatedTokens, threshold, attempt, llmCall)) {
                publishDiagnostics(context, estimatedTokens, threshold, attempt, true);
                log.warn("[ToolLoop] LLM request still exceeds context budget after preflight check "
                        + "(~{} tokens > threshold {}), sending request so provider can fail fast",
                        estimatedTokens, threshold);
                return request;
            }
            // Compaction mutated the session; ask the caller to rebuild the
            // request from the now-shorter message list. The supplier is
            // responsible for pulling fresh state — we never reuse the old
            // LlmRequest instance because it still points at the pre-compact
            // messages.
            request = requestSupplier.get();
        }

        int finalEstimatedTokens = contextTokenEstimator.estimateRequest(request);
        int finalThreshold = resolveThreshold(context);
        publishDiagnostics(context, finalEstimatedTokens, finalThreshold, PREFLIGHT_MAX_ATTEMPTS, true);
        if (finalEstimatedTokens > finalThreshold) {
            log.warn("[ToolLoop] LLM request remains above context budget after {} preflight compaction attempts "
                    + "(~{} tokens > threshold {})", PREFLIGHT_MAX_ATTEMPTS, finalEstimatedTokens, finalThreshold);
        }
        return request;
    }

    /**
     * Shared compaction cycle reused by post-call overflow recovery. Emits the
     * canonical COMPACTION_STARTED / COMPACTION_FINISHED runtime events and
     * synchronizes the {@link AgentContext} message view with the session.
     *
     * @return the {@link CompactionResult} if at least one message was removed,
     *         otherwise {@code null}.
     */
    CompactionResult runCompaction(AgentContext context, CompactionReason reason, int keepLast, int llmCall,
            Map<String, Object> extraStartedPayload) {
        // Guard clauses cover the "nothing to do" cases: no context, no
        // compaction service wired, or an empty session. We return null (not
        // throw) because preflight is best-effort — a missing dependency
        // should degrade gracefully to "no compaction" rather than blow up
        // the whole turn.
        if (context == null || compactionOrchestrationService == null || context.getSession() == null
                || context.getSession().getMessages() == null || context.getSession().getMessages().isEmpty()) {
            return null;
        }
        int total = context.getSession().getMessages().size();
        // A single-message session has nothing to compact: keeping ≥1 message
        // means there's nothing older to drop. Short-circuit before emitting
        // runtime events that would only add noise.
        if (total <= 1) {
            return null;
        }
        // boundedKeepLast = clamp(keepLast, 1, total-1):
        // - floor at 1 so we never keep zero messages (would erase context)
        // - ceiling at total-1 so we always compact at least one message
        // (otherwise the call is a no-op and wastes an attempt slot).
        int boundedKeepLast = Math.max(1, Math.min(keepLast, total - 1));
        int toCompact = Math.max(0, total - boundedKeepLast);

        // Flush any buffered tool progress before we start mutating the
        // session, so the UI sees a clean "compaction starts here" boundary
        // instead of a torn stream.
        flushProgress(context, progressReasonFor(reason));

        Map<String, Object> startedPayload = new LinkedHashMap<>();
        startedPayload.put("llmCall", llmCall);
        startedPayload.put("messages", toCompact);
        startedPayload.put("keepLast", boundedKeepLast);
        startedPayload.put("reason", reason.name());
        startedPayload.put("rawCutIndex", toCompact);
        startedPayload.put("adjustedCutIndex", toCompact);
        startedPayload.put("splitTurnDetected", false);
        startedPayload.put("toCompactCount", toCompact);
        if (extraStartedPayload != null) {
            startedPayload.putAll(extraStartedPayload);
        }
        emitRuntimeEvent(context, RuntimeEventType.COMPACTION_STARTED, startedPayload);

        // The STARTED/FINISHED balance invariant must cover the entire critical
        // section — not just the compact() call. Post-compact mutations
        // (setMessages, setAttribute x4) can also throw (e.g. a downstream
        // attribute guard), and without this try/catch the FINISHED event would
        // never fire, leaving dashboards with an orphan STARTED and breaking the
        // observability contract.
        boolean finishedEmitted = false;
        try {
            CompactionResult compactionResult = compactionOrchestrationService.compact(
                    context.getSession().getId(), reason, boundedKeepLast);
            if (compactionResult.removed() <= 0) {
                emitRuntimeEvent(context, RuntimeEventType.COMPACTION_FINISHED,
                        CompactionFinishedPayloads.noChange(boundedKeepLast, reason));
                finishedEmitted = true;
                return null;
            }

            // Resynchronize the context's message view with the session after
            // compaction mutated the session in place. A fresh ArrayList copy
            // decouples the two from future aliasing bugs (a downstream system
            // modifying one must not silently affect the other).
            context.setMessages(new ArrayList<>(context.getSession().getMessages()));
            // Clear any stale LLM error state — a successful compaction means
            // we're about to retry, and leaving the old error attached would
            // confuse downstream systems into thinking the retry failed.
            context.setAttribute(ContextAttributes.LLM_ERROR, null);
            context.setAttribute(ContextAttributes.LLM_ERROR_CODE, null);
            if (compactionResult.details() != null) {
                // Publish details for observability (dashboard shows what was
                // dropped) and expose file changes so the turn summary picks
                // them up even if compaction replaced the messages that
                // originally recorded them.
                context.setAttribute(ContextAttributes.COMPACTION_LAST_DETAILS, compactionResult.details());
                context.setAttribute(ContextAttributes.TURN_FILE_CHANGES, compactionResult.details().fileChanges());
            }

            emitRuntimeEvent(context, RuntimeEventType.COMPACTION_FINISHED,
                    CompactionFinishedPayloads.success(compactionResult, boundedKeepLast, reason));
            finishedEmitted = true;
            return compactionResult;
        } catch (RuntimeException e) {
            if (!finishedEmitted) {
                int observedSessionSize = context.getSession() != null && context.getSession().getMessages() != null
                        ? context.getSession().getMessages().size()
                        : 0;
                emitRuntimeEvent(context, RuntimeEventType.COMPACTION_FINISHED,
                        CompactionFinishedPayloads.error(observedSessionSize, reason, e));
            }
            throw e;
        }
    }

    boolean recoverFromContextOverflow(AgentContext context, int llmCall, int retryAttempt) {
        if (retryAttempt > 0) {
            return false;
        }
        if (runtimeConfigService == null || !runtimeConfigService.isCompactionEnabled()) {
            if (compactionDisabledOverflowLogged.compareAndSet(false, true)) {
                log.warn("[ToolLoop] Context overflow detected but compaction is disabled "
                        + "(runtimeConfig.compactionEnabled=false) — overflow recovery will not fire. "
                        + "Enable compaction or lower the context-budget threshold to recover automatically.");
            }
            return false;
        }
        if (context == null || context.getSession() == null || context.getSession().getMessages() == null) {
            return false;
        }
        int keepLast = runtimeConfigService.getCompactionKeepLastMessages();
        if (context.getSession().getMessages().size() <= keepLast) {
            return false;
        }
        CompactionResult compactionResult = runCompaction(
                context, CompactionReason.CONTEXT_OVERFLOW_RECOVERY, keepLast, llmCall, null);
        return compactionResult != null;
    }

    private static String progressReasonFor(CompactionReason reason) {
        return reason == CompactionReason.REQUEST_PREFLIGHT ? "request_preflight_compaction" : "compaction";
    }

    private boolean preflightCompact(AgentContext context, int estimatedTokens, int threshold, int attempt,
            int llmCall) {
        if (runtimeConfigService == null || !runtimeConfigService.isCompactionEnabled()) {
            writeCompactionOutcome(context, false, 0, false, COMPACTION_OUTCOME_SKIPPED_DISABLED);
            return false;
        }
        if (context.getSession() == null || context.getSession().getMessages() == null
                || context.getSession().getMessages().isEmpty()) {
            writeCompactionOutcome(context, false, 0, false, COMPACTION_OUTCOME_SKIPPED_NO_MESSAGES);
            return false;
        }
        int total = context.getSession().getMessages().size();
        if (total <= 1) {
            writeCompactionOutcome(context, false, 0, false, COMPACTION_OUTCOME_SKIPPED_NO_MESSAGES);
            return false;
        }
        int keepLast = resolveKeepLast(total, attempt);

        log.info("[ToolLoop] LLM request exceeds context budget before provider call: "
                + "~{} tokens > threshold {}. Running preflight compaction (attempt {}/{}, keepLast={})",
                estimatedTokens, threshold, attempt, PREFLIGHT_MAX_ATTEMPTS, keepLast);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("estimatedTokens", estimatedTokens);
        extra.put("threshold", threshold);
        extra.put("attempt", attempt);

        CompactionResult compacted = runCompaction(context, CompactionReason.REQUEST_PREFLIGHT, keepLast, llmCall,
                extra);
        if (compacted != null) {
            writeCompactionOutcome(context, true, compacted.removed(), compacted.usedSummary(),
                    CompactionFinishedPayloads.OUTCOME_COMPACTED);
            return true;
        }
        writeCompactionOutcome(context, true, 0, false, CompactionFinishedPayloads.OUTCOME_ATTEMPTED_NO_CHANGE);
        return false;
    }

    private void writeCompactionOutcome(AgentContext context, boolean attempted, int removedThisAttempt,
            boolean usedSummary, String outcome) {
        Map<String, Object> current = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        Map<String, Object> diagnostics = current != null ? new LinkedHashMap<>(current) : new LinkedHashMap<>();
        diagnostics.put("compactionAttempted", attempted);
        if (removedThisAttempt < 0) {
            log.warn("[ToolLoop] writeCompactionOutcome received negative removedThisAttempt={}; clamping to 0. "
                    + "This indicates a programming error upstream of this call site.", removedThisAttempt);
        }
        // compactionRemoved is the cumulative count across all preflight attempts
        // within a single preflight() call. A later no-op attempt must not hide
        // removals done on earlier attempts, so each call accumulates rather
        // than overwrites.
        int previousTotal = readIntOrZero(diagnostics.get("compactionRemoved"));
        diagnostics.put("compactionRemoved", previousTotal + Math.max(0, removedThisAttempt));
        diagnostics.put("compactionUsedSummary", usedSummary);
        diagnostics.put("compactionOutcome", outcome);
        context.setAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT, diagnostics);
    }

    private static int readIntOrZero(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private void resetCompactionDiagnostics(AgentContext context) {
        Map<String, Object> current = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        Map<String, Object> diagnostics = current != null ? new LinkedHashMap<>(current) : new LinkedHashMap<>();
        diagnostics.put("compactionAttempted", false);
        diagnostics.put("compactionRemoved", 0);
        diagnostics.remove("compactionUsedSummary");
        diagnostics.remove("compactionOutcome");
        context.setAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT, diagnostics);
    }

    private int resolveThreshold(AgentContext context) {
        return contextBudgetPolicy.resolveFullRequestThreshold(context);
    }

    private void publishDiagnostics(AgentContext context, int estimatedTokens, int threshold, int attempt,
            boolean finalAttempt) {
        Map<String, Object> previous = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        Map<String, Object> diagnostics = previous != null ? new LinkedHashMap<>(previous) : new LinkedHashMap<>();
        diagnostics.put("estimatedTokens", estimatedTokens);
        diagnostics.put("threshold", threshold);
        diagnostics.put("attempt", attempt);
        diagnostics.put("maxAttempts", PREFLIGHT_MAX_ATTEMPTS);
        diagnostics.put("overThreshold", estimatedTokens > threshold);
        // Use putIfAbsent for compaction counters so publishDiagnostics never
        // clobbers values already written by writeCompactionOutcome in the same
        // preflight() call. When adding a new compaction diagnostic key to
        // writeCompactionOutcome, mirror it here with putIfAbsent.
        diagnostics.putIfAbsent("compactionAttempted", false);
        diagnostics.putIfAbsent("compactionRemoved", 0);
        diagnostics.put("finalAttempt", finalAttempt);
        context.setAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT, diagnostics);
    }

    private int resolveKeepLast(int totalMessages, int attempt) {
        // keepLast halves on each successive attempt so if attempt 1 couldn't
        // free enough tokens with the configured keepLast, attempt 2 keeps
        // half as many messages and tries again. The bit-shift form
        // (1 << (attempt-1)) gives 1, 2, 4, 8... — exponential shrinkage that
        // saturates keepLast to 1 quickly. Upper bound of 16 on the shift is
        // defensive; in practice we never run more than PREFLIGHT_MAX_ATTEMPTS.
        int configuredKeepLast = runtimeConfigService != null ? runtimeConfigService.getCompactionKeepLastMessages()
                : 20;
        int divisor = 1 << Math.max(0, Math.min(16, attempt - 1));
        int reducedKeepLast = Math.max(1, configuredKeepLast / divisor);
        // Clamp to totalMessages-1 so we always drop at least one message.
        return Math.max(1, Math.min(reducedKeepLast, totalMessages - 1));
    }

    private void emitRuntimeEvent(AgentContext context, RuntimeEventType type, Map<String, Object> payload) {
        if (runtimeEventService == null || context == null) {
            return;
        }
        runtimeEventService.emit(context, type, payload);
    }

    private void flushProgress(AgentContext context, String reason) {
        if (turnProgressService == null || context == null) {
            return;
        }
        turnProgressService.flushBufferedTools(context, reason);
    }
}

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
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.context.compaction.CompactionOrchestrationService;
import me.golemcore.bot.domain.context.compaction.CompactionPayloadMapper;
import me.golemcore.bot.domain.context.compaction.ContextCompactionPolicy;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.progress.TurnProgressService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates context-compaction side effects shared by request preflight and
 * post-provider context-overflow recovery.
 *
 * <p>
 * This class stays package-private and framework-free: it is a domain
 * collaborator assembled by {@link DefaultToolLoopSystem}, not a Spring
 * service.
 */
@Slf4j
public class ContextCompactionCoordinator {

    private static final String OVERFLOW_OUTCOME_SKIPPED_DISABLED = "skipped_disabled";
    private static final String OVERFLOW_OUTCOME_SKIPPED_TOO_SMALL = "skipped_too_small";

    private final ContextCompactionPolicy contextCompactionPolicy;
    private final CompactionOrchestrationService compactionOrchestrationService;
    private final RuntimeEventService runtimeEventService;
    private final TurnProgressService turnProgressService;

    private final AtomicBoolean compactionDisabledOverflowLogged = new AtomicBoolean();

    public ContextCompactionCoordinator(
            ContextCompactionPolicy contextCompactionPolicy,
            CompactionOrchestrationService compactionOrchestrationService,
            RuntimeEventService runtimeEventService,
            TurnProgressService turnProgressService) {
        this.contextCompactionPolicy = contextCompactionPolicy;
        this.compactionOrchestrationService = compactionOrchestrationService;
        this.runtimeEventService = runtimeEventService;
        this.turnProgressService = turnProgressService;
    }

    /**
     * Shared compaction cycle. Emits the canonical COMPACTION_STARTED /
     * COMPACTION_FINISHED runtime events and synchronizes the {@link AgentContext}
     * message view with the compacted session.
     *
     * @return the {@link CompactionResult} if at least one message was removed,
     *         otherwise {@code null}.
     */
    CompactionResult runCompaction(AgentContext context, CompactionReason reason, int keepLast, int llmCall,
            Map<String, Object> extraStartedPayload) {
        if (context == null || compactionOrchestrationService == null || context.getSession() == null
                || context.getSession().getMessages() == null || context.getSession().getMessages().isEmpty()) {
            return null;
        }
        int total = context.getSession().getMessages().size();
        if (total <= 1) {
            return null;
        }

        int boundedKeepLast = Math.max(1, Math.min(keepLast, total - 1));
        int toCompact = Math.max(0, total - boundedKeepLast);

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

        try {
            CompactionResult compactionResult = compactionOrchestrationService.compact(
                    context.getSession().getId(), reason, boundedKeepLast);
            if (compactionResult.removed() <= 0) {
                emitRuntimeEvent(context, RuntimeEventType.COMPACTION_FINISHED,
                        CompactionFinishedPayloads.noChange(boundedKeepLast, reason));
                return null;
            }

            context.setMessages(new ArrayList<>(context.getSession().getMessages()));
            context.setAttribute(ContextAttributes.LLM_ERROR, null);
            context.setAttribute(ContextAttributes.LLM_ERROR_CODE, null);
            if (compactionResult.details() != null) {
                CompactionPayloadMapper.publishToContext(context, compactionResult);
                context.setAttribute(ContextAttributes.TURN_FILE_CHANGES, compactionResult.details().fileChanges());
            }

            emitRuntimeEvent(context, RuntimeEventType.COMPACTION_FINISHED,
                    CompactionFinishedPayloads.success(compactionResult, boundedKeepLast, reason));
            publishCompactionFallbackProgress(context, reason, compactionResult);
            return compactionResult;
        } catch (RuntimeException e) {
            int observedSessionSize = context.getSession() != null && context.getSession().getMessages() != null
                    ? context.getSession().getMessages().size()
                    : 0;
            emitRuntimeEvent(context, RuntimeEventType.COMPACTION_FINISHED,
                    CompactionFinishedPayloads.error(observedSessionSize, reason, e));
            throw e;
        }
    }

    /**
     * One-shot compaction attempt triggered when the provider reports a
     * context-overflow error. For first-attempt calls with a usable context and
     * session, publishes a {@code llm.context.overflow.recovery} diagnostic
     * attribute so dashboards can see whether recovery compacted, skipped, or
     * failed. Later retry attempts and malformed contexts return without
     * overwriting the previous diagnostic because no recovery cycle is run.
     *
     * @param retryAttempt
     *            the tool-loop retry counter; recovery fires only when this is
     *            {@code 0} - any value above zero means the previous recovery has
     *            already run for this provider call and a second attempt would loop
     *            on an unrecoverable state.
     * @return {@code true} iff compaction removed at least one message, telling the
     *         caller it is safe to retry the provider with the shorter session.
     */
    public boolean recoverFromContextOverflow(AgentContext context, int llmCall, int retryAttempt) {
        if (retryAttempt > 0) {
            return false;
        }
        if (context == null || context.getSession() == null || context.getSession().getMessages() == null) {
            return false;
        }
        if (!contextCompactionPolicy.isCompactionEnabled()) {
            if (compactionDisabledOverflowLogged.compareAndSet(false, true)) {
                log.warn("[ToolLoop] Context overflow detected but compaction is disabled "
                        + "(runtimeConfig.compactionEnabled=false) - overflow recovery will not fire. "
                        + "Enable compaction or lower the context-budget threshold to recover automatically.");
            }
            writeOverflowRecoveryDiagnostic(context, false, 0, false, OVERFLOW_OUTCOME_SKIPPED_DISABLED, llmCall);
            return false;
        }
        int keepLast = contextCompactionPolicy.resolveCompactionKeepLast();
        if (context.getSession().getMessages().size() <= keepLast) {
            writeOverflowRecoveryDiagnostic(context, false, 0, false, OVERFLOW_OUTCOME_SKIPPED_TOO_SMALL, llmCall);
            return false;
        }
        CompactionResult compactionResult;
        try {
            compactionResult = runCompaction(
                    context, CompactionReason.CONTEXT_OVERFLOW_RECOVERY, keepLast, llmCall, null);
        } catch (RuntimeException e) {
            log.warn("[ToolLoop] Context overflow recovery compaction failed (llmCall={})", llmCall, e);
            writeOverflowRecoveryDiagnostic(context, true, 0, false, CompactionFinishedPayloads.OUTCOME_ERROR, llmCall);
            return false;
        }
        if (compactionResult != null) {
            writeOverflowRecoveryDiagnostic(context, true, compactionResult.removed(), compactionResult.usedSummary(),
                    CompactionFinishedPayloads.OUTCOME_COMPACTED, llmCall);
            return true;
        }
        writeOverflowRecoveryDiagnostic(context, true, 0, false,
                CompactionFinishedPayloads.OUTCOME_ATTEMPTED_NO_CHANGE, llmCall);
        return false;
    }

    private void writeOverflowRecoveryDiagnostic(AgentContext context, boolean attempted, int removed,
            boolean usedSummary, String outcome, int llmCall) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("recoveryAttempted", attempted);
        diagnostics.put("recoveryRemoved", Math.max(0, removed));
        diagnostics.put("recoveryUsedSummary", usedSummary);
        diagnostics.put("recoveryOutcome", outcome);
        diagnostics.put("llmCall", llmCall);
        context.setAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY, diagnostics);
    }

    private static String progressReasonFor(CompactionReason reason) {
        if (reason == CompactionReason.REQUEST_PREFLIGHT) {
            return "request_preflight_compaction";
        }
        if (reason == CompactionReason.CONTEXT_OVERFLOW_RECOVERY) {
            return "context_overflow_recovery";
        }
        return "compaction";
    }

    private void publishCompactionFallbackProgress(AgentContext context, CompactionReason reason,
            CompactionResult result) {
        if (turnProgressService == null || context == null || result == null || result.removed() <= 0) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "context_compaction_fallback");
        metadata.put("reason", reason.name());
        metadata.put("removed", result.removed());
        metadata.put("usedSummary", result.usedSummary());
        turnProgressService.publishSummary(context, compactionFallbackNotice(reason), metadata);
    }

    private static String compactionFallbackNotice(CompactionReason reason) {
        if (reason == CompactionReason.REQUEST_PREFLIGHT) {
            return "I shortened the conversation context so this request fits the model window.";
        }
        if (reason == CompactionReason.CONTEXT_OVERFLOW_RECOVERY) {
            return "The model rejected the request as too large, so I shortened the conversation context and retried.";
        }
        return "I shortened the conversation context before continuing.";
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

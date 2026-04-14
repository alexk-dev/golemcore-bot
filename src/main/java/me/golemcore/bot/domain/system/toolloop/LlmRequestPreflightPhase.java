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
import me.golemcore.bot.domain.service.ContextTokenEstimator;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TurnProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Performs request-size preflight immediately before an LLM provider call.
 */
@RequiredArgsConstructor
@Slf4j
class LlmRequestPreflightPhase {

    private static final int PREFLIGHT_MAX_ATTEMPTS = 3;

    private final ModelSelectionService modelSelectionService;
    private final RuntimeConfigService runtimeConfigService;
    private final CompactionOrchestrationService compactionOrchestrationService;
    private final ContextTokenEstimator contextTokenEstimator;
    private final RuntimeEventService runtimeEventService;
    private final TurnProgressService turnProgressService;

    LlmRequest preflight(AgentContext context, Supplier<LlmRequest> requestSupplier, int llmCall) {
        LlmRequest request = requestSupplier.get();
        for (int attempt = 1; attempt <= PREFLIGHT_MAX_ATTEMPTS; attempt++) {
            int estimatedTokens = contextTokenEstimator.estimateRequest(request);
            int threshold = resolveThreshold(context);
            publishDiagnostics(context, estimatedTokens, threshold, attempt, false);
            if (estimatedTokens <= threshold) {
                return request;
            }
            if (!compact(context, estimatedTokens, threshold, attempt, llmCall)) {
                log.warn("[ToolLoop] LLM request still exceeds context budget after preflight check "
                        + "(~{} tokens > threshold {}), sending request so provider can fail fast",
                        estimatedTokens, threshold);
                return request;
            }
            request = requestSupplier.get();
        }

        int estimatedTokens = contextTokenEstimator.estimateRequest(request);
        int threshold = resolveThreshold(context);
        publishDiagnostics(context, estimatedTokens, threshold, PREFLIGHT_MAX_ATTEMPTS, true);
        if (estimatedTokens > threshold) {
            log.warn("[ToolLoop] LLM request remains above context budget after {} preflight compaction attempts "
                    + "(~{} tokens > threshold {})", PREFLIGHT_MAX_ATTEMPTS, estimatedTokens, threshold);
        }
        return request;
    }

    private int resolveThreshold(AgentContext context) {
        if (runtimeConfigService == null || modelSelectionService == null) {
            return Integer.MAX_VALUE;
        }
        int modelMax = resolveModelMaxTokens(context);
        if (modelMax < 1) {
            return Integer.MAX_VALUE;
        }
        int modelSafeThreshold = Math.max(1, modelMax - resolveOutputReserveTokens(modelMax));
        String triggerMode = runtimeConfigService.getCompactionTriggerMode();
        int configuredThreshold = runtimeConfigService.getCompactionMaxContextTokens();
        long threshold;
        if ("token_threshold".equals(triggerMode)) {
            long modelThreshold = Math.max(1L, (long) Math.floor(modelMax * 0.8d));
            threshold = configuredThreshold > 0 ? Math.min(modelThreshold, configuredThreshold) : modelThreshold;
        } else {
            double ratio = runtimeConfigService.getCompactionModelThresholdRatio();
            if (ratio <= 0.0d || ratio > 1.0d) {
                ratio = 0.95d;
            }
            threshold = Math.max(1L, (long) Math.floor(modelMax * ratio));
        }
        return saturatingToPositiveInt(Math.min(threshold, modelSafeThreshold));
    }

    private int resolveModelMaxTokens(AgentContext context) {
        try {
            int modelMax = modelSelectionService.resolveMaxInputTokensForContext(context);
            if (modelMax > 0) {
                return modelMax;
            }
        } catch (RuntimeException e) {
            log.debug("[ToolLoop] Failed to resolve model max tokens for request preflight", e);
        }
        int configuredThreshold = runtimeConfigService.getCompactionMaxContextTokens();
        return configuredThreshold > 0 ? configuredThreshold : Integer.MAX_VALUE;
    }

    private int saturatingToPositiveInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) value);
    }

    private int resolveOutputReserveTokens(int modelMax) {
        int proportionalReserve = Math.max(1024, (int) Math.floor(modelMax * 0.05d));
        int cappedReserve = Math.min(32768, proportionalReserve);
        int maximumReserve = Math.max(1, modelMax / 4);
        return Math.min(cappedReserve, maximumReserve);
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
        diagnostics.putIfAbsent("compactionAttempted", false);
        diagnostics.putIfAbsent("compactionRemoved", 0);
        diagnostics.put("finalAttempt", finalAttempt);
        context.setAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT, diagnostics);
    }

    private void updateCompactionDiagnostics(AgentContext context, CompactionResult compactionResult) {
        Map<String, Object> current = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        Map<String, Object> diagnostics = current != null ? new LinkedHashMap<>(current) : new LinkedHashMap<>();
        diagnostics.put("compactionAttempted", true);
        diagnostics.put("compactionRemoved", compactionResult != null ? compactionResult.removed() : 0);
        diagnostics.put("compactionUsedSummary", compactionResult != null && compactionResult.usedSummary());
        context.setAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT, diagnostics);
    }

    private boolean compact(AgentContext context, int estimatedTokens, int threshold, int attempt, int llmCall) {
        if (runtimeConfigService == null || !runtimeConfigService.isCompactionEnabled()) {
            return false;
        }
        if (compactionOrchestrationService == null || context.getSession() == null
                || context.getSession().getMessages() == null || context.getSession().getMessages().isEmpty()) {
            return false;
        }
        int total = context.getSession().getMessages().size();
        if (total <= 1) {
            return false;
        }
        int keepLast = resolveKeepLast(total, attempt);

        flushProgress(context, "request_preflight_compaction");
        emitRuntimeEvent(context, RuntimeEventType.COMPACTION_STARTED,
                eventPayload("llmCall", llmCall,
                        "messages", total - keepLast,
                        "keepLast", keepLast,
                        "estimatedTokens", estimatedTokens,
                        "threshold", threshold,
                        "attempt", attempt,
                        "reason", CompactionReason.REQUEST_PREFLIGHT.name(),
                        "rawCutIndex", Math.max(0, total - keepLast),
                        "adjustedCutIndex", Math.max(0, total - keepLast),
                        "splitTurnDetected", false,
                        "toCompactCount", Math.max(0, total - keepLast)));

        log.info("[ToolLoop] LLM request exceeds context budget before provider call: "
                + "~{} tokens > threshold {}. Running preflight compaction (attempt {}/{}, keepLast={})",
                estimatedTokens, threshold, attempt, PREFLIGHT_MAX_ATTEMPTS, keepLast);

        CompactionResult compactionResult = compactionOrchestrationService.compact(
                context.getSession().getId(), CompactionReason.REQUEST_PREFLIGHT, keepLast);
        if (compactionResult.removed() <= 0) {
            updateCompactionDiagnostics(context, compactionResult);
            return false;
        }

        updateCompactionDiagnostics(context, compactionResult);
        context.setMessages(new ArrayList<>(context.getSession().getMessages()));
        context.setAttribute(ContextAttributes.LLM_ERROR, null);
        context.setAttribute(ContextAttributes.LLM_ERROR_CODE, null);
        if (compactionResult.details() != null) {
            context.setAttribute(ContextAttributes.COMPACTION_LAST_DETAILS, compactionResult.details());
            context.setAttribute(ContextAttributes.TURN_FILE_CHANGES, compactionResult.details().fileChanges());
        }

        emitRuntimeEvent(context, RuntimeEventType.COMPACTION_FINISHED,
                eventPayload("summaryLength", compactionResult.details() != null
                        ? compactionResult.details().summaryLength()
                        : 0,
                        "removed", compactionResult.removed(),
                        "kept", keepLast,
                        "splitTurnDetected", compactionResult.details() != null
                                && compactionResult.details().splitTurnDetected(),
                        "usedSummary", compactionResult.usedSummary(),
                        "reason", CompactionReason.REQUEST_PREFLIGHT.name(),
                        "toolCount", compactionResult.details() != null ? compactionResult.details().toolCount() : 0,
                        "readFilesCount", compactionResult.details() != null
                                ? compactionResult.details().readFilesCount()
                                : 0,
                        "modifiedFilesCount", compactionResult.details() != null
                                ? compactionResult.details().modifiedFilesCount()
                                : 0,
                        "durationMs",
                        compactionResult.details() != null ? compactionResult.details().durationMs() : 0));
        return true;
    }

    private int resolveKeepLast(int totalMessages, int attempt) {
        int configuredKeepLast = runtimeConfigService != null ? runtimeConfigService.getCompactionKeepLastMessages()
                : 20;
        int divisor = 1 << Math.max(0, Math.min(16, attempt - 1));
        int reducedKeepLast = Math.max(1, configuredKeepLast / divisor);
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
}

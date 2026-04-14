package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves context-budget thresholds for both conversation-history compaction
 * and full LLM request preflight.
 */
@Service
@Slf4j
public class ContextBudgetPolicy {

    private static final String TRIGGER_MODE_MODEL_RATIO = "model_ratio";
    private static final String TRIGGER_MODE_TOKEN_THRESHOLD = "token_threshold";
    private static final double TOKEN_THRESHOLD_MODEL_MAX_SAFETY_RATIO = 0.8d;
    private static final double DEFAULT_FULL_REQUEST_RATIO = 0.95d;

    private final RuntimeConfigService runtimeConfigService;
    private final ModelSelectionService modelSelectionService;
    private final AtomicBoolean fullRequestResolutionDisabledLogged = new AtomicBoolean();
    private final AtomicBoolean fullRequestBypassLogged = new AtomicBoolean();

    public ContextBudgetPolicy(RuntimeConfigService runtimeConfigService, ModelSelectionService modelSelectionService) {
        this.runtimeConfigService = runtimeConfigService;
        this.modelSelectionService = modelSelectionService;
    }

    /**
     * Budget used by AutoCompactionSystem for raw conversation history before the
     * tool loop builds the full provider request.
     */
    public int resolveHistoryThreshold(AgentContext context) {
        if (runtimeConfigService == null) {
            return Integer.MAX_VALUE;
        }
        if (TRIGGER_MODE_MODEL_RATIO.equals(runtimeConfigService.getCompactionTriggerMode())) {
            return resolveHistoryModelRatioThreshold(context);
        }
        return resolveHistoryTokenThreshold(context);
    }

    /**
     * Budget used by LLM request preflight for the fully serialized request shape.
     * It reserves output room so input does not consume the whole model window.
     */
    public int resolveFullRequestThreshold(AgentContext context) {
        if (runtimeConfigService == null || modelSelectionService == null) {
            if (fullRequestResolutionDisabledLogged.compareAndSet(false, true)) {
                log.warn("[ToolLoop] Preflight threshold resolution disabled: "
                        + "runtimeConfigService={}, modelSelectionService={} — all requests will bypass size check",
                        runtimeConfigService != null, modelSelectionService != null);
            }
            return Integer.MAX_VALUE;
        }
        int modelMax = resolveFullRequestModelMaxTokens(context);
        if (modelMax == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        int modelSafeThreshold = Math.max(1, modelMax - resolveOutputReserveTokens(modelMax));
        String triggerMode = runtimeConfigService.getCompactionTriggerMode();
        int configuredThreshold = runtimeConfigService.getCompactionMaxContextTokens();
        long threshold;
        if (TRIGGER_MODE_TOKEN_THRESHOLD.equals(triggerMode)) {
            long modelThreshold = Math.max(1L,
                    (long) Math.floor(modelMax * TOKEN_THRESHOLD_MODEL_MAX_SAFETY_RATIO));
            threshold = configuredThreshold > 0 ? Math.min(modelThreshold, configuredThreshold) : modelThreshold;
        } else {
            double ratio = runtimeConfigService.getCompactionModelThresholdRatio();
            if (ratio <= 0.0d || ratio > 1.0d) {
                ratio = DEFAULT_FULL_REQUEST_RATIO;
            }
            threshold = Math.max(1L, (long) Math.floor(modelMax * ratio));
        }
        return saturatingToPositiveInt(Math.min(threshold, modelSafeThreshold));
    }

    private int resolveHistoryModelRatioThreshold(AgentContext context) {
        try {
            int modelMax = modelSelectionService.resolveMaxInputTokensForContext(context);
            double ratio = runtimeConfigService.getCompactionModelThresholdRatio();
            return Math.max(1, (int) Math.floor(modelMax * ratio));
        } catch (RuntimeException e) {
            log.debug("[AutoCompact] Failed to resolve model max tokens for ratio mode, using config default", e);
        }
        return runtimeConfigService.getCompactionMaxContextTokens();
    }

    private int resolveHistoryTokenThreshold(AgentContext context) {
        try {
            int modelMax = modelSelectionService.resolveMaxInputTokensForContext(context);
            int modelThreshold = (int) (modelMax * TOKEN_THRESHOLD_MODEL_MAX_SAFETY_RATIO);
            return Math.min(modelThreshold, runtimeConfigService.getCompactionMaxContextTokens());
        } catch (RuntimeException e) {
            log.debug("[AutoCompact] Failed to resolve model max tokens for token-threshold mode, using config default",
                    e);
        }
        return runtimeConfigService.getCompactionMaxContextTokens();
    }

    private int resolveFullRequestModelMaxTokens(AgentContext context) {
        try {
            int modelMax = modelSelectionService.resolveMaxInputTokensForContext(context);
            if (modelMax > 0) {
                return modelMax;
            }
        } catch (RuntimeException e) {
            log.debug("[ToolLoop] Failed to resolve model max tokens for request preflight", e);
        }
        int configuredThreshold = runtimeConfigService.getCompactionMaxContextTokens();
        if (configuredThreshold > 0) {
            return configuredThreshold;
        }
        if (fullRequestBypassLogged.compareAndSet(false, true)) {
            log.warn("[ToolLoop] Preflight threshold bypass: model registry returned no max-input-tokens and "
                    + "runtimeConfig.compactionMaxContextTokens is unset — all requests will pass through without "
                    + "a size check. Configure one of the two to re-enable preflight compaction.");
        }
        return Integer.MAX_VALUE;
    }

    private int resolveOutputReserveTokens(int modelMax) {
        int proportionalReserve = Math.max(1024, (int) Math.floor(modelMax * 0.05d));
        int cappedReserve = Math.min(32768, proportionalReserve);
        int maximumReserve = Math.max(1, modelMax / 4);
        return Math.min(cappedReserve, maximumReserve);
    }

    private int saturatingToPositiveInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) value);
    }
}

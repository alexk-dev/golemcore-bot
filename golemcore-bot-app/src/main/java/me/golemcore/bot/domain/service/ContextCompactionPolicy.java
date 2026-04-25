package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves context compaction settings for both conversation-history compaction
 * and full LLM request preflight.
 */
@Slf4j
public class ContextCompactionPolicy {

    private static final String TRIGGER_MODE_MODEL_RATIO = "model_ratio";
    private static final String TRIGGER_MODE_TOKEN_THRESHOLD = "token_threshold";
    private static final double TOKEN_THRESHOLD_MODEL_MAX_SAFETY_RATIO = 0.8d;
    private static final double DEFAULT_RATIO_FALLBACK = 0.95d;
    private static final double SYSTEM_PROMPT_BUDGET_RATIO = 0.35d;
    private static final int SYSTEM_PROMPT_MIN_TOKENS = 1_500;
    private static final int SYSTEM_PROMPT_MAX_TOKENS = 12_000;

    private final RuntimeConfigService runtimeConfigService;
    private final ModelSelectionService modelSelectionService;
    private final AtomicBoolean fullRequestBypassLogged = new AtomicBoolean();
    private final AtomicBoolean historyBypassLogged = new AtomicBoolean();

    public ContextCompactionPolicy(RuntimeConfigService runtimeConfigService,
            ModelSelectionService modelSelectionService) {
        this.runtimeConfigService = Objects.requireNonNull(runtimeConfigService, "runtimeConfigService");
        this.modelSelectionService = Objects.requireNonNull(modelSelectionService, "modelSelectionService");
    }

    /**
     * Global switch shared by all compaction entry points.
     */
    public boolean isCompactionEnabled() {
        return runtimeConfigService.isCompactionEnabled();
    }

    /**
     * Keep-last policy shared by auto history compaction and provider
     * context-overflow recovery. Both paths currently retain the same number of
     * trailing messages because the operator configures a single
     * {@code compactionKeepLastMessages} value; preflight is intentionally the only
     * branch that deviates (it halves per attempt, see
     * {@link #resolvePreflightKeepLast}).
     */
    public int resolveCompactionKeepLast() {
        return runtimeConfigService.getCompactionKeepLastMessages();
    }

    /**
     * Keep-last policy for request preflight. Each retry becomes more aggressive
     * while still leaving at least one message eligible for compaction.
     */
    public int resolvePreflightKeepLast(int totalMessages, int attempt) {
        int configuredKeepLast = Math.max(1, runtimeConfigService.getCompactionKeepLastMessages());
        int divisor = 1 << Math.max(0, Math.min(16, attempt - 1));
        int reducedKeepLast = Math.max(1, configuredKeepLast / divisor);
        return Math.max(1, Math.min(reducedKeepLast, Math.max(1, totalMessages - 1)));
    }

    /**
     * Budget used by AutoCompactionSystem for raw conversation history before the
     * tool loop builds the full provider request.
     */
    public int resolveHistoryThreshold(AgentContext context) {
        if (TRIGGER_MODE_MODEL_RATIO.equals(runtimeConfigService.getCompactionTriggerMode())) {
            return resolveHistoryModelRatioThreshold(context);
        }
        return resolveHistoryTokenThreshold(context);
    }

    /**
     * Budget used by LLM request preflight for the fully serialized request shape.
     * When the model registry knows the provider's max-input-tokens we apply
     * ratio/safety math and reserve output room so input does not consume the whole
     * model window. When the registry is silent, the configured fallback
     * ({@code runtimeConfig.compactionMaxContextTokens}) is treated as a
     * user-declared wire cap and returned verbatim - treating it as modelMax would
     * silently shrink the operator's intended budget by the output reserve.
     */
    public int resolveFullRequestThreshold(AgentContext context) {
        int registryModelMax = resolveRegistryModelMax(context);
        if (registryModelMax > 0) {
            fullRequestBypassLogged.set(false);
            return computeFullRequestThresholdFromModelMax(registryModelMax);
        }
        int configuredThreshold = runtimeConfigService.getCompactionMaxContextTokens();
        if (configuredThreshold > 0) {
            fullRequestBypassLogged.set(false);
            return configuredThreshold;
        }
        if (fullRequestBypassLogged.compareAndSet(false, true)) {
            log.warn("[ToolLoop] Preflight threshold bypass: model registry returned no max-input-tokens and "
                    + "runtimeConfig.compactionMaxContextTokens is unset - all requests will pass through without "
                    + "a size check. Configure one of the two to re-enable preflight compaction.");
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Budget used by ContextAssembler for the rendered system prompt. This keeps
     * layered prompt content from consuming the entire provider request window
     * before conversation history and tool schemas are added.
     */
    public int resolveSystemPromptThreshold(AgentContext context) {
        int fullRequestThreshold = resolveFullRequestThreshold(context);
        if (fullRequestThreshold == Integer.MAX_VALUE) {
            return SYSTEM_PROMPT_MAX_TOKENS;
        }
        int proportionalBudget = saturatingToPositiveInt(
                (long) Math.floor(fullRequestThreshold * SYSTEM_PROMPT_BUDGET_RATIO));
        return Math.min(SYSTEM_PROMPT_MAX_TOKENS,
                Math.max(SYSTEM_PROMPT_MIN_TOKENS, proportionalBudget));
    }

    private int resolveRegistryModelMax(AgentContext context) {
        try {
            return Math.max(0, modelSelectionService.resolveMaxInputTokensForContext(context));
        } catch (RuntimeException e) {
            log.warn("[ToolLoop] Failed to resolve model max tokens for request preflight, using config fallback", e);
            return 0;
        }
    }

    private int computeFullRequestThresholdFromModelMax(int modelMax) {
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
                ratio = DEFAULT_RATIO_FALLBACK;
            }
            threshold = Math.max(1L, (long) Math.floor(modelMax * ratio));
        }
        return saturatingToPositiveInt(Math.min(threshold, modelSafeThreshold));
    }

    private int resolveHistoryModelRatioThreshold(AgentContext context) {
        try {
            int modelMax = modelSelectionService.resolveMaxInputTokensForContext(context);
            if (modelMax > 0) {
                historyBypassLogged.set(false);
                double ratio = runtimeConfigService.getCompactionModelThresholdRatio();
                if (ratio <= 0.0d || ratio > 1.0d) {
                    ratio = DEFAULT_RATIO_FALLBACK;
                }
                return saturatingToPositiveInt((long) Math.floor(modelMax * ratio));
            }
        } catch (RuntimeException e) {
            log.warn("[AutoCompact] Failed to resolve model max tokens for ratio mode, using config fallback", e);
        }
        return resolveHistoryConfiguredFallback();
    }

    private int resolveHistoryTokenThreshold(AgentContext context) {
        try {
            int modelMax = modelSelectionService.resolveMaxInputTokensForContext(context);
            if (modelMax > 0) {
                historyBypassLogged.set(false);
                long modelThreshold = Math.max(1L,
                        (long) Math.floor(modelMax * TOKEN_THRESHOLD_MODEL_MAX_SAFETY_RATIO));
                int configured = runtimeConfigService.getCompactionMaxContextTokens();
                long threshold = configured > 0 ? Math.min(modelThreshold, configured) : modelThreshold;
                return saturatingToPositiveInt(threshold);
            }
        } catch (RuntimeException e) {
            log.warn("[AutoCompact] Failed to resolve model max tokens for token-threshold mode, using config fallback",
                    e);
        }
        return resolveHistoryConfiguredFallback();
    }

    private int resolveHistoryConfiguredFallback() {
        int configured = runtimeConfigService.getCompactionMaxContextTokens();
        if (configured > 0) {
            historyBypassLogged.set(false);
            return configured;
        }
        if (historyBypassLogged.compareAndSet(false, true)) {
            log.warn("[AutoCompact] History threshold bypass: model registry returned no max-input-tokens and "
                    + "runtimeConfig.compactionMaxContextTokens is unset - auto-compaction will not fire. "
                    + "Configure one of the two to re-enable history compaction.");
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

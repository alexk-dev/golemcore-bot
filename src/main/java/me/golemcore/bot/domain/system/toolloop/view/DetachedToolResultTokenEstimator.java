package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.context.layer.TokenEstimator;
import me.golemcore.bot.domain.model.ToolResult;

import java.util.Map;

/**
 * Conservative budget reservation for detached tool result bookkeeping.
 */
final class DetachedToolResultTokenEstimator {

    int estimate(Map<String, ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return 0;
        }
        long tokens = 0;
        for (Map.Entry<String, ToolResult> entry : toolResults.entrySet()) {
            tokens += TokenEstimator.estimate(entry.getKey());
            tokens += estimateResult(entry.getValue());
        }
        return saturatingToInt(tokens);
    }

    private int estimateResult(ToolResult result) {
        if (result == null) {
            return 0;
        }
        long tokens = 0;
        tokens += TokenEstimator.estimate(result.getOutput());
        tokens += TokenEstimator.estimate(result.getError());
        tokens += TokenEstimator.estimate(String.valueOf(result.isSuccess()));
        tokens += TokenEstimator.estimate(String.valueOf(result.getFailureKind()));
        tokens += estimateOpaque(result.getData());
        return saturatingToInt(tokens);
    }

    private int estimateOpaque(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return TokenEstimator.estimate(String.valueOf(value));
        } catch (RuntimeException e) {
            return 8;
        }
    }

    private int saturatingToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < 0) {
            return 0;
        }
        return (int) value;
    }
}

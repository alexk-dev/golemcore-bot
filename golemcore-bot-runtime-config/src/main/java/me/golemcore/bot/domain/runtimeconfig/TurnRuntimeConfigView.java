package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_AUTO_RETRY_BASE_DELAY_MS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_AUTO_RETRY_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_AUTO_RETRY_MAX_ATTEMPTS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_DEADLINE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_MAX_LLM_CALLS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_MAX_SKILL_TRANSITIONS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_MAX_TOOL_EXECUTIONS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_PROGRESS_BATCH_SIZE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_PROGRESS_INTENT_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_PROGRESS_MAX_SILENCE_SECONDS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_PROGRESS_SUMMARY_TIMEOUT_MS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_PROGRESS_UPDATES_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_QUEUE_FOLLOW_UP_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_QUEUE_STEERING_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TURN_QUEUE_STEERING_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigSupport.normalizeQueueMode;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import me.golemcore.bot.domain.model.RuntimeConfig;

public interface TurnRuntimeConfigView extends RuntimeConfigSource {
    default int getTurnMaxSkillTransitions() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_MAX_SKILL_TRANSITIONS;
        }
        Integer val = turnConfig.getMaxSkillTransitions();
        return val != null ? val : DEFAULT_TURN_MAX_SKILL_TRANSITIONS;
    }

    default int getTurnMaxLlmCalls() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_MAX_LLM_CALLS;
        }
        Integer val = turnConfig.getMaxLlmCalls();
        return val != null ? val : DEFAULT_TURN_MAX_LLM_CALLS;
    }

    default int getTurnMaxToolExecutions() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_MAX_TOOL_EXECUTIONS;
        }
        Integer val = turnConfig.getMaxToolExecutions();
        return val != null ? val : DEFAULT_TURN_MAX_TOOL_EXECUTIONS;
    }

    default int getToolLoopMaxLlmCalls() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getRuntimeConfig().getToolLoop();
        if (toolLoopConfig != null && toolLoopConfig.getMaxLlmCalls() != null) {
            return toolLoopConfig.getMaxLlmCalls();
        }
        return getTurnMaxLlmCalls();
    }

    default int getToolLoopMaxToolExecutions() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getRuntimeConfig().getToolLoop();
        if (toolLoopConfig != null && toolLoopConfig.getMaxToolExecutions() != null) {
            return toolLoopConfig.getMaxToolExecutions();
        }
        return getTurnMaxToolExecutions();
    }

    default Duration getTurnDeadline() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null || turnConfig.getDeadline() == null || turnConfig.getDeadline().isBlank()) {
            return DEFAULT_TURN_DEADLINE;
        }
        try {
            return Duration.parse(turnConfig.getDeadline());
        } catch (DateTimeParseException e) {
            return DEFAULT_TURN_DEADLINE;
        }
    }

    default boolean isTurnAutoRetryEnabled() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_AUTO_RETRY_ENABLED;
        }
        Boolean val = turnConfig.getAutoRetryEnabled();
        return val != null ? val : DEFAULT_TURN_AUTO_RETRY_ENABLED;
    }

    default int getTurnAutoRetryMaxAttempts() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_AUTO_RETRY_MAX_ATTEMPTS;
        }
        Integer val = turnConfig.getAutoRetryMaxAttempts();
        return val != null ? val : DEFAULT_TURN_AUTO_RETRY_MAX_ATTEMPTS;
    }

    default long getTurnAutoRetryBaseDelayMs() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_AUTO_RETRY_BASE_DELAY_MS;
        }
        Long val = turnConfig.getAutoRetryBaseDelayMs();
        return val != null ? val : DEFAULT_TURN_AUTO_RETRY_BASE_DELAY_MS;
    }

    default boolean isTurnQueueSteeringEnabled() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_QUEUE_STEERING_ENABLED;
        }
        Boolean val = turnConfig.getQueueSteeringEnabled();
        return val != null ? val : DEFAULT_TURN_QUEUE_STEERING_ENABLED;
    }

    default String getTurnQueueSteeringMode() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null || turnConfig.getQueueSteeringMode() == null
                || turnConfig.getQueueSteeringMode().isBlank()) {
            return DEFAULT_TURN_QUEUE_STEERING_MODE;
        }
        return normalizeQueueMode(turnConfig.getQueueSteeringMode());
    }

    default String getTurnQueueFollowUpMode() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null || turnConfig.getQueueFollowUpMode() == null
                || turnConfig.getQueueFollowUpMode().isBlank()) {
            return DEFAULT_TURN_QUEUE_FOLLOW_UP_MODE;
        }
        return normalizeQueueMode(turnConfig.getQueueFollowUpMode());
    }

    default boolean isTurnProgressUpdatesEnabled() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_PROGRESS_UPDATES_ENABLED;
        }
        Boolean val = turnConfig.getProgressUpdatesEnabled();
        return val != null ? val : DEFAULT_TURN_PROGRESS_UPDATES_ENABLED;
    }

    default boolean isTurnProgressIntentEnabled() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_PROGRESS_INTENT_ENABLED;
        }
        Boolean val = turnConfig.getProgressIntentEnabled();
        return val != null ? val : DEFAULT_TURN_PROGRESS_INTENT_ENABLED;
    }

    default int getTurnProgressBatchSize() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_PROGRESS_BATCH_SIZE;
        }
        Integer val = turnConfig.getProgressBatchSize();
        return val != null ? val : DEFAULT_TURN_PROGRESS_BATCH_SIZE;
    }

    default Duration getTurnProgressMaxSilence() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return Duration.ofSeconds(DEFAULT_TURN_PROGRESS_MAX_SILENCE_SECONDS);
        }
        Integer seconds = turnConfig.getProgressMaxSilenceSeconds();
        int safeSeconds = seconds != null ? seconds : DEFAULT_TURN_PROGRESS_MAX_SILENCE_SECONDS;
        return Duration.ofSeconds(safeSeconds);
    }

    default int getTurnProgressSummaryTimeoutMs() {
        RuntimeConfig.TurnConfig turnConfig = getRuntimeConfig().getTurn();
        if (turnConfig == null) {
            return DEFAULT_TURN_PROGRESS_SUMMARY_TIMEOUT_MS;
        }
        Integer val = turnConfig.getProgressSummaryTimeoutMs();
        return val != null ? val : DEFAULT_TURN_PROGRESS_SUMMARY_TIMEOUT_MS;
    }
}

package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_LOOP_MAX_LLM_CALLS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_LOOP_MAX_TOOL_EXECUTIONS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_AUTO_LEDGER_TTL_MINUTES;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_MAX_BLOCKED_REPEATS_PER_TURN;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_MAX_SAME_OBSERVE_PER_TURN;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_MAX_SAME_UNKNOWN_PER_TURN;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_MIN_POLL_INTERVAL_SECONDS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_REPEAT_GUARD_SHADOW_MODE;

import me.golemcore.bot.domain.model.RuntimeConfig;

public interface ToolLoopRuntimeConfigView extends TurnRuntimeConfigView {

    default int getToolLoopMaxLlmCalls() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getToolLoopConfig();
        if (toolLoopConfig != null && toolLoopConfig.getMaxLlmCalls() != null) {
            return toolLoopConfig.getMaxLlmCalls();
        }
        return getRuntimeConfig().getToolLoop() == null ? getTurnMaxLlmCalls() : DEFAULT_TOOL_LOOP_MAX_LLM_CALLS;
    }

    default int getToolLoopMaxToolExecutions() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getToolLoopConfig();
        if (toolLoopConfig != null && toolLoopConfig.getMaxToolExecutions() != null) {
            return toolLoopConfig.getMaxToolExecutions();
        }
        return getRuntimeConfig().getToolLoop() == null ? getTurnMaxToolExecutions()
                : DEFAULT_TOOL_LOOP_MAX_TOOL_EXECUTIONS;
    }

    default boolean isToolRepeatGuardEnabled() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getToolLoopConfig();
        Boolean val = toolLoopConfig != null ? toolLoopConfig.getRepeatGuardEnabled() : null;
        return val != null ? val : DEFAULT_TOOL_REPEAT_GUARD_ENABLED;
    }

    default boolean isToolRepeatGuardShadowMode() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getToolLoopConfig();
        Boolean val = toolLoopConfig != null ? toolLoopConfig.getRepeatGuardShadowMode() : null;
        return val != null ? val : DEFAULT_TOOL_REPEAT_GUARD_SHADOW_MODE;
    }

    default int getToolRepeatGuardMaxSameObservePerTurn() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getToolLoopConfig();
        Integer val = toolLoopConfig != null ? toolLoopConfig.getRepeatGuardMaxSameObservePerTurn() : null;
        return val != null ? val : DEFAULT_TOOL_REPEAT_GUARD_MAX_SAME_OBSERVE_PER_TURN;
    }

    default int getToolRepeatGuardMaxSameUnknownPerTurn() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getToolLoopConfig();
        Integer val = toolLoopConfig != null ? toolLoopConfig.getRepeatGuardMaxSameUnknownPerTurn() : null;
        return val != null ? val : DEFAULT_TOOL_REPEAT_GUARD_MAX_SAME_UNKNOWN_PER_TURN;
    }

    default int getToolRepeatGuardMaxBlockedRepeatsPerTurn() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getToolLoopConfig();
        Integer val = toolLoopConfig != null ? toolLoopConfig.getRepeatGuardMaxBlockedRepeatsPerTurn() : null;
        return val != null ? val : DEFAULT_TOOL_REPEAT_GUARD_MAX_BLOCKED_REPEATS_PER_TURN;
    }

    default long getToolRepeatGuardMinPollIntervalSeconds() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getToolLoopConfig();
        Long val = toolLoopConfig != null ? toolLoopConfig.getRepeatGuardMinPollIntervalSeconds() : null;
        return val != null ? val : DEFAULT_TOOL_REPEAT_GUARD_MIN_POLL_INTERVAL_SECONDS;
    }

    default long getToolRepeatGuardAutoLedgerTtlMinutes() {
        RuntimeConfig.ToolLoopConfig toolLoopConfig = getToolLoopConfig();
        Long val = toolLoopConfig != null ? toolLoopConfig.getRepeatGuardAutoLedgerTtlMinutes() : null;
        return val != null ? val : DEFAULT_TOOL_REPEAT_GUARD_AUTO_LEDGER_TTL_MINUTES;
    }

    private RuntimeConfig.ToolLoopConfig getToolLoopConfig() {
        RuntimeConfig runtimeConfig = getRuntimeConfig();
        return runtimeConfig != null ? runtimeConfig.getToolLoop() : null;
    }
}

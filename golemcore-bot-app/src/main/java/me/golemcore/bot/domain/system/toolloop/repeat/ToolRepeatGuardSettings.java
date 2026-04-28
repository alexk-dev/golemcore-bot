package me.golemcore.bot.domain.system.toolloop.repeat;

import java.time.Duration;
import me.golemcore.bot.domain.runtimeconfig.ToolLoopRuntimeConfigView;

/**
 * Runtime policy knobs for repeated tool use protection.
 */
// @formatter:off
public record ToolRepeatGuardSettings(
        boolean enabled,
        boolean shadowMode,
        int maxSameObservePerTurn,
        int maxSameUnknownPerTurn,
        int maxBlockedRepeatsPerTurn,
        Duration minPollInterval,
        Duration autoLedgerTtl) {

    public ToolRepeatGuardSettings {
        maxSameObservePerTurn = Math.max(1, maxSameObservePerTurn);
        maxSameUnknownPerTurn = Math.max(1, maxSameUnknownPerTurn);
        maxBlockedRepeatsPerTurn = Math.max(1, maxBlockedRepeatsPerTurn);
        minPollInterval = minPollInterval != null && !minPollInterval.isNegative()
                ? minPollInterval
                : Duration.ofSeconds(60);
        autoLedgerTtl = autoLedgerTtl != null && !autoLedgerTtl.isNegative()
                ? autoLedgerTtl
                : Duration.ofMinutes(120);
    }

    public static ToolRepeatGuardSettings defaults() {
        return new ToolRepeatGuardSettings(
                true,
                false,
                2,
                2,
                4,
                Duration.ofSeconds(60),
                Duration.ofMinutes(120));
    }

    public static ToolRepeatGuardSettings disabled() {
        return new ToolRepeatGuardSettings(
                false,
                false,
                2,
                2,
                4,
                Duration.ofSeconds(60),
                Duration.ofMinutes(120));
    }

    public static ToolRepeatGuardSettings from(ToolLoopRuntimeConfigView view) {
        if (view == null) {
            return defaults();
        }
        return new ToolRepeatGuardSettings(
                view.isToolRepeatGuardEnabled(),
                view.isToolRepeatGuardShadowMode(),
                view.getToolRepeatGuardMaxSameObservePerTurn(),
                view.getToolRepeatGuardMaxSameUnknownPerTurn(),
                view.getToolRepeatGuardMaxBlockedRepeatsPerTurn(),
                Duration.ofSeconds(view.getToolRepeatGuardMinPollIntervalSeconds()),
                Duration.ofMinutes(view.getToolRepeatGuardAutoLedgerTtlMinutes()));
    }
}
// @formatter:on

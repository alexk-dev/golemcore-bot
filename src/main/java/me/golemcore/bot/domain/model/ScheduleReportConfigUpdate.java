package me.golemcore.bot.domain.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Explicit update semantics for scheduler report configuration.
 */
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScheduleReportConfigUpdate {

    private Mode mode;
    private ScheduleReportConfig config;

    public static ScheduleReportConfigUpdate noChange() {
        return new ScheduleReportConfigUpdate(Mode.NO_CHANGE, null);
    }

    public static ScheduleReportConfigUpdate clear() {
        return new ScheduleReportConfigUpdate(Mode.CLEAR, null);
    }

    public static ScheduleReportConfigUpdate set(ScheduleReportConfig config) {
        return new ScheduleReportConfigUpdate(Mode.SET, config);
    }

    public boolean applies() {
        return mode != Mode.NO_CHANGE;
    }

    public boolean clears() {
        return mode == Mode.CLEAR;
    }

    public enum Mode {
        NO_CHANGE, CLEAR, SET
    }
}

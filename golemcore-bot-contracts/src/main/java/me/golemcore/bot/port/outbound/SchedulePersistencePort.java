package me.golemcore.bot.port.outbound;

import java.util.List;
import me.golemcore.bot.domain.model.ScheduleEntry;

/**
 * Loads and persists autonomous schedule entries.
 */
public interface SchedulePersistencePort {

    List<ScheduleEntry> loadSchedules();

    void saveSchedules(List<ScheduleEntry> schedules);
}

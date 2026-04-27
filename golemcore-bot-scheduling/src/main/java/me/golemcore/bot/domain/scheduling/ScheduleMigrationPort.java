package me.golemcore.bot.domain.scheduling;

/**
 * Optional migration hook run before the scheduling read model is loaded.
 */
public interface ScheduleMigrationPort {

    void migrateIfNeeded();
}

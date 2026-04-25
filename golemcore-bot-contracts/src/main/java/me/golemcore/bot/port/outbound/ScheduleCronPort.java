package me.golemcore.bot.port.outbound;

import java.time.Instant;

/**
 * Validates schedule cron expressions and computes the next execution time.
 */
public interface ScheduleCronPort {

    String normalize(String cronExpression);

    Instant nextExecution(String normalizedCronExpression, Instant from);
}

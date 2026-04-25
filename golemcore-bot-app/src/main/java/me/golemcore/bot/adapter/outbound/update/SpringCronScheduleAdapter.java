package me.golemcore.bot.adapter.outbound.update;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import me.golemcore.bot.port.outbound.ScheduleCronPort;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Component
public class SpringCronScheduleAdapter implements ScheduleCronPort {

    private static final int CRON_FIVE_FIELDS = 5;
    private static final int CRON_SIX_FIELDS = 6;

    @Override
    public String normalize(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new IllegalArgumentException("Cron expression cannot be empty");
        }

        String trimmed = cronExpression.trim();
        String[] parts = trimmed.split("\\s+");
        String sixFieldCron;
        if (parts.length == CRON_FIVE_FIELDS) {
            sixFieldCron = "0 " + trimmed;
        } else if (parts.length == CRON_SIX_FIELDS) {
            sixFieldCron = trimmed;
        } else {
            throw new IllegalArgumentException("Invalid cron expression: expected 5 or 6 fields, got " + parts.length);
        }

        try {
            CronExpression.parse(sixFieldCron);
            return sixFieldCron;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid cron expression '" + trimmed + "': " + exception.getMessage(),
                    exception);
        }
    }

    @Override
    public Instant nextExecution(String normalizedCronExpression, Instant from) {
        CronExpression cronExpression = CronExpression.parse(normalizedCronExpression);
        LocalDateTime nextTime = cronExpression.next(LocalDateTime.ofInstant(from, ZoneOffset.UTC));
        if (nextTime == null) {
            throw new IllegalArgumentException("Cron expression never fires: " + normalizedCronExpression);
        }
        return nextTime.toInstant(ZoneOffset.UTC);
    }
}

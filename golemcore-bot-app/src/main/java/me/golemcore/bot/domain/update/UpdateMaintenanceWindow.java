package me.golemcore.bot.domain.update;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

@Service
public class UpdateMaintenanceWindow {

    public Status evaluate(boolean enabled, String startUtc, String endUtc, Instant now) {
        if (!enabled) {
            return new Status(true, null);
        }

        LocalTime start = LocalTime.parse(startUtc);
        LocalTime end = LocalTime.parse(endUtc);
        if (start.equals(end)) {
            return new Status(true, null);
        }

        LocalDate date = now.atOffset(ZoneOffset.UTC).toLocalDate();
        LocalTime currentTime = now.atOffset(ZoneOffset.UTC).toLocalTime();

        if (start.isBefore(end)) {
            boolean open = !currentTime.isBefore(start) && currentTime.isBefore(end);
            if (open) {
                return new Status(true, null);
            }
            LocalDate nextDate = currentTime.isBefore(start) ? date : date.plusDays(1);
            return new Status(false, nextDate.atTime(start).toInstant(ZoneOffset.UTC));
        }

        boolean open = !currentTime.isBefore(start) || currentTime.isBefore(end);
        if (open) {
            return new Status(true, null);
        }
        return new Status(false, date.atTime(start).toInstant(ZoneOffset.UTC));
    }

    public record Status(boolean open, Instant nextEligibleAt) {
    }
}

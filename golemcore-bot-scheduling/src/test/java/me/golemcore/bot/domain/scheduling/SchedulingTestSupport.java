package me.golemcore.bot.domain.scheduling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.port.channel.ChannelPort;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.ScheduleCronPort;
import me.golemcore.bot.port.outbound.SchedulePersistencePort;
import me.golemcore.bot.port.outbound.ScheduledTaskPersistencePort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.scheduling.support.CronExpression;

final class SchedulingTestSupport {

    private static final String AUTO_DIR = "auto";
    private static final String SCHEDULES_FILE = "schedules.json";
    private static final String SCHEDULED_TASKS_FILE = "scheduled-tasks.json";
    private static final TypeReference<List<ScheduleEntry>> SCHEDULE_LIST_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<List<ScheduledTask>> SCHEDULED_TASK_LIST_TYPE_REF = new TypeReference<>() {
    };

    private SchedulingTestSupport() {
    }

    static ChannelRuntimePort runtime(List<ChannelPort> channels) {
        List<ChannelDeliveryPort> deliveryPorts = channels == null ? List.of() : List.copyOf(channels);
        return new ChannelRuntimePort() {
            @Override
            public Optional<ChannelDeliveryPort> findChannel(String channelType) {
                if (channelType == null || channelType.isBlank()) {
                    return Optional.empty();
                }
                return deliveryPorts.stream().filter(channel -> channelType.equalsIgnoreCase(channel.getChannelType()))
                        .findFirst();
            }

            @Override
            public List<ChannelDeliveryPort> listChannels() {
                return deliveryPorts;
            }

            @Override
            public boolean isChannelRunning(String channelType) {
                return findChannel(channelType).filter(ChannelPort.class::isInstance).map(ChannelPort.class::cast)
                        .map(ChannelPort::isRunning).orElse(false);
            }
        };
    }

    static final class CronSchedulePortStub implements ScheduleCronPort {

        @Override
        public String normalize(String cronExpression) {
            if (cronExpression == null || cronExpression.isBlank()) {
                throw new IllegalArgumentException("Cron expression cannot be empty");
            }

            String trimmed = cronExpression.trim();
            String[] parts = trimmed.split("\\s+");
            String normalized = switch (parts.length) {
                case 5 -> "0 " + trimmed;
                case 6 -> trimmed;
                default -> throw new IllegalArgumentException(
                        "Invalid cron expression: expected 5 or 6 fields, got " + parts.length);
            };

            try {
                CronExpression.parse(normalized);
                return normalized;
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Invalid cron expression '" + trimmed + "': " + exception.getMessage(), exception);
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

    static final class JsonSchedulePersistencePort implements SchedulePersistencePort {

        private final StoragePort storagePort;
        private final ObjectMapper objectMapper;

        JsonSchedulePersistencePort(StoragePort storagePort, ObjectMapper objectMapper) {
            this.storagePort = storagePort;
            this.objectMapper = objectMapper;
        }

        @Override
        public List<ScheduleEntry> loadSchedules() {
            try {
                String json = storagePort.getText(AUTO_DIR, SCHEDULES_FILE).join();
                if (json == null || json.isBlank()) {
                    return new ArrayList<>();
                }
                List<ScheduleEntry> schedules = objectMapper.readValue(json, SCHEDULE_LIST_TYPE_REF);
                return schedules != null ? new ArrayList<>(schedules) : new ArrayList<>();
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Failed to load schedules", unwrap(exception));
            }
        }

        @Override
        public void saveSchedules(List<ScheduleEntry> schedules) {
            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schedules);
                storagePort.putTextAtomic(AUTO_DIR, SCHEDULES_FILE, json, true).join();
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Failed to persist schedules", unwrap(exception));
            }
        }
    }

    static final class JsonScheduledTaskPersistencePort implements ScheduledTaskPersistencePort {

        private final StoragePort storagePort;
        private final ObjectMapper objectMapper;

        JsonScheduledTaskPersistencePort(StoragePort storagePort, ObjectMapper objectMapper) {
            this.storagePort = storagePort;
            this.objectMapper = objectMapper;
        }

        @Override
        public List<ScheduledTask> loadScheduledTasks() {
            try {
                String json = storagePort.getText(AUTO_DIR, SCHEDULED_TASKS_FILE).join();
                if (json == null || json.isBlank()) {
                    return new ArrayList<>();
                }
                List<ScheduledTask> tasks = objectMapper.readValue(json, SCHEDULED_TASK_LIST_TYPE_REF);
                return tasks != null ? new ArrayList<>(tasks) : new ArrayList<>();
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Failed to load scheduled tasks", unwrap(exception));
            }
        }

        @Override
        public void replaceScheduledTasks(List<ScheduledTask> tasks) {
            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tasks);
                storagePort.putTextAtomic(AUTO_DIR, SCHEDULED_TASKS_FILE, json, true).join();
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Failed to persist scheduled tasks", unwrap(exception));
            }
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }
}

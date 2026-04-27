package me.golemcore.bot.adapter.outbound.storage;

import java.io.IOException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.port.outbound.SchedulePersistencePort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

@Component
public class StorageSchedulePersistenceAdapter implements SchedulePersistencePort {

    private static final String AUTO_DIR = "auto";
    private static final String SCHEDULES_FILE = "schedules.json";
    private static final TypeReference<List<ScheduleEntry>> SCHEDULE_LIST_TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public StorageSchedulePersistenceAdapter(
            StoragePort storagePort,
            ObjectMapper objectMapper) {
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
            throw new IllegalStateException("Failed to load schedules", exception);
        }
    }

    @Override
    public void saveSchedules(List<ScheduleEntry> schedules) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schedules);
            storagePort.putTextAtomic(AUTO_DIR, SCHEDULES_FILE, json, true).join();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to persist schedules", exception);
        }
    }
}

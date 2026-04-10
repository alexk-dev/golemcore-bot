package me.golemcore.bot.adapter.outbound.hive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.port.outbound.HiveSessionStatePort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StorageHiveSessionStateAdapter implements HiveSessionStatePort {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String SESSION_STATE_FILE = "hive-session.json";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<HiveSessionState> load() {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, SESSION_STATE_FILE).join();
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, HiveSessionState.class));
        } catch (Exception exception) { // NOSONAR
            log.warn("[Hive] Failed to load persisted session state: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(HiveSessionState sessionState) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sessionState);
            storagePort.putTextAtomic(PREFERENCES_DIR, SESSION_STATE_FILE, json, true).join();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist Hive session state", exception);
        }
    }

    @Override
    public void clear() {
        try {
            storagePort.deleteObject(PREFERENCES_DIR, SESSION_STATE_FILE).join();
        } catch (RuntimeException exception) { // NOSONAR
            log.warn("[Hive] Failed to delete persisted session state: {}", exception.getMessage());
        }
    }
}

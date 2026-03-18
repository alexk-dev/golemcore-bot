package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HiveControlInboxService {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String INBOX_FILE = "hive-control-inbox.json";
    private static final int MAX_BUFFERED_COMMANDS = 100;

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    private final Object lock = new Object();

    public InboxSummary recordReceived(HiveControlCommandEnvelope envelope) {
        synchronized (lock) {
            InboxState state = loadState();
            state.getCommands().add(0, envelope);
            if (state.getCommands().size() > MAX_BUFFERED_COMMANDS) {
                state.setCommands(new ArrayList<>(state.getCommands().subList(0, MAX_BUFFERED_COMMANDS)));
            }
            state.setUpdatedAt(Instant.now().toString());
            state.setReceivedCommandCount(state.getReceivedCommandCount() + 1);
            state.setLastReceivedCommandId(envelope.getCommandId());
            state.setLastReceivedAt(Instant.now().toString());
            saveState(state);
            return toSummary(state);
        }
    }

    public InboxSummary getSummary() {
        synchronized (lock) {
            return toSummary(loadState());
        }
    }

    private InboxState loadState() {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, INBOX_FILE).join();
            if (json == null || json.isBlank()) {
                return new InboxState();
            }
            return objectMapper.readValue(json, InboxState.class);
        } catch (IOException | RuntimeException exception) { // NOSONAR - startup should degrade gracefully
            log.warn("[Hive] Failed to load control inbox: {}", exception.getMessage());
            return new InboxState();
        }
    }

    private void saveState(InboxState state) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            storagePort.putTextAtomic(PREFERENCES_DIR, INBOX_FILE, json, true).join();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist Hive control inbox", exception);
        }
    }

    private InboxSummary toSummary(InboxState state) {
        return new InboxSummary(
                state.getReceivedCommandCount(),
                state.getCommands().size(),
                state.getLastReceivedCommandId(),
                state.getLastReceivedAt());
    }

    public record InboxSummary(
            int receivedCommandCount,
            int bufferedCommandCount,
            String lastReceivedCommandId,
            String lastReceivedAt) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class InboxState {

        private int schemaVersion = 1;
        private String updatedAt = Instant.now().toString();
        private int receivedCommandCount;
        private String lastReceivedCommandId;
        private String lastReceivedAt;
        private List<HiveControlCommandEnvelope> commands = new ArrayList<>();
    }
}

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
    private static final int MAX_TRACKED_COMMANDS = 100;

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    private final Object lock = new Object();
    private InboxState cachedState;
    private boolean loaded;

    public RecordResult recordReceived(HiveControlCommandEnvelope envelope) {
        validateEnvelope(envelope);
        String trackingId = resolveTrackingId(envelope);
        synchronized (lock) {
            InboxState state = getLoadedStateLocked();
            StoredCommand existing = findByTrackingId(state, trackingId);
            if (existing != null) {
                state.setLastReceivedCommandId(trackingId);
                state.setLastReceivedAt(Instant.now().toString());
                touch(state);
                saveStateLocked(state);
                return new RecordResult(true, toSummary(state));
            }

            state.getCommands().add(new StoredCommand(
                    envelope,
                    CommandStatus.RECEIVED.name(),
                    Instant.now().toString(),
                    null,
                    null,
                    0,
                    null));
            state.setUpdatedAt(Instant.now().toString());
            state.setReceivedCommandCount(state.getReceivedCommandCount() + 1);
            state.setLastReceivedCommandId(trackingId);
            state.setLastReceivedAt(Instant.now().toString());
            trimProcessedCommandsLocked(state);
            saveStateLocked(state);
            return new RecordResult(false, toSummary(state));
        }
    }

    public int drainPending(CommandHandler commandHandler) {
        if (commandHandler == null) {
            throw new IllegalArgumentException("Hive command handler is required");
        }
        int processedCount = 0;
        while (true) {
            StoredCommand command = claimNextPending();
            if (command == null) {
                return processedCount;
            }
            try {
                commandHandler.handle(command.getEnvelope());
                processedCount++;
            } catch (RuntimeException exception) {
                markFailed(resolveTrackingId(command.getEnvelope()), exception);
                return processedCount;
            }
        }
    }

    public String resolveTrackingId(HiveControlCommandEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        if (envelope.getRequestId() != null && !envelope.getRequestId().isBlank()) {
            return envelope.getRequestId().trim();
        }
        if (envelope.getCommandId() != null && !envelope.getCommandId().isBlank()) {
            return envelope.getCommandId().trim();
        }
        return null;
    }

    public InboxSummary getSummary() {
        synchronized (lock) {
            return toSummary(getLoadedStateLocked());
        }
    }

    public void markProcessed(String commandId) {
        synchronized (lock) {
            InboxState state = getLoadedStateLocked();
            StoredCommand command = findByTrackingId(state, commandId);
            if (command == null) {
                return;
            }
            command.setStatus(CommandStatus.PROCESSED.name());
            command.setProcessedAt(Instant.now().toString());
            command.setLastError(null);
            trimProcessedCommandsLocked(state);
            touch(state);
            saveStateLocked(state);
        }
    }

    public void markFailed(String commandId, Throwable failure) {
        markFailed(commandId, failure, false);
    }

    public void markFailedIfPending(String commandId, Throwable failure) {
        markFailed(commandId, failure, true);
    }

    private void markFailed(String commandId, Throwable failure, boolean onlyIfPending) {
        synchronized (lock) {
            InboxState state = getLoadedStateLocked();
            StoredCommand command = findByTrackingId(state, commandId);
            if (command == null) {
                return;
            }
            if (onlyIfPending && parseStatus(command.getStatus()) == CommandStatus.PROCESSED) {
                return;
            }
            command.setStatus(CommandStatus.FAILED.name());
            command.setLastError(failure != null ? failure.getMessage() : null);
            touch(state);
            saveStateLocked(state);
        }
    }

    public int resetInFlightCommandsForRestart() {
        synchronized (lock) {
            InboxState state = getLoadedStateLocked();
            int resetCount = 0;
            for (StoredCommand command : state.getCommands()) {
                if (parseStatus(command.getStatus()) != CommandStatus.PROCESSING) {
                    continue;
                }
                command.setStatus(CommandStatus.FAILED.name());
                command.setLastError("Bot restarted before command execution completed");
                resetCount++;
            }
            if (resetCount > 0) {
                touch(state);
                saveStateLocked(state);
            }
            return resetCount;
        }
    }

    public void clear() {
        synchronized (lock) {
            try {
                storagePort.deleteObject(PREFERENCES_DIR, INBOX_FILE).join();
            } catch (RuntimeException exception) {
                log.warn("[Hive] Failed to delete control inbox: {}", exception.getMessage());
            }
            cachedState = new InboxState();
            loaded = true;
        }
    }

    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
    private StoredCommand claimNextPending() {
        synchronized (lock) {
            InboxState state = getLoadedStateLocked();
            for (StoredCommand command : state.getCommands()) {
                if (!isDispatchable(command)) {
                    continue;
                }
                command.setStatus(CommandStatus.PROCESSING.name());
                command.setDispatchAttemptCount(command.getDispatchAttemptCount() + 1);
                command.setLastDispatchAt(Instant.now().toString());
                command.setLastError(null);
                touch(state);
                saveStateLocked(state);
                return copyCommand(command);
            }
            return null;
        }
    }

    private boolean isPending(StoredCommand command) {
        CommandStatus status = parseStatus(command.getStatus());
        return status == CommandStatus.RECEIVED
                || status == CommandStatus.FAILED
                || status == CommandStatus.PROCESSING;
    }

    private boolean isDispatchable(StoredCommand command) {
        CommandStatus status = parseStatus(command.getStatus());
        return status == CommandStatus.RECEIVED || status == CommandStatus.FAILED;
    }

    private CommandStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return CommandStatus.RECEIVED;
        }
        try {
            return CommandStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            return CommandStatus.RECEIVED;
        }
    }

    private void trimProcessedCommandsLocked(InboxState state) {
        if (state.getCommands().size() <= MAX_TRACKED_COMMANDS) {
            return;
        }
        List<StoredCommand> pending = new ArrayList<>();
        List<StoredCommand> processed = new ArrayList<>();
        for (StoredCommand command : state.getCommands()) {
            if (isPending(command)) {
                pending.add(command);
            } else {
                processed.add(command);
            }
        }
        if (pending.size() >= MAX_TRACKED_COMMANDS) {
            state.setCommands(pending);
            return;
        }
        int processedToKeep = MAX_TRACKED_COMMANDS - pending.size();
        int processedStartIndex = Math.max(processed.size() - processedToKeep, 0);
        List<StoredCommand> trimmed = new ArrayList<>(pending);
        trimmed.addAll(processed.subList(processedStartIndex, processed.size()));
        state.setCommands(trimmed);
    }

    private void validateEnvelope(HiveControlCommandEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Hive control command is required");
        }
        if (resolveTrackingId(envelope) == null) {
            throw new IllegalArgumentException("Hive control command commandId is required");
        }
    }

    private StoredCommand findByTrackingId(InboxState state, String trackingId) {
        if (trackingId == null || trackingId.isBlank()) {
            return null;
        }
        for (StoredCommand command : state.getCommands()) {
            if (command.getEnvelope() != null && trackingId.equals(resolveTrackingId(command.getEnvelope()))) {
                return command;
            }
        }
        return null;
    }

    private InboxState getLoadedStateLocked() {
        if (!loaded) {
            cachedState = loadState();
            loaded = true;
        }
        if (cachedState == null) {
            cachedState = new InboxState();
        }
        return cachedState;
    }

    private InboxState loadState() {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, INBOX_FILE).join();
            if (json == null || json.isBlank()) {
                return new InboxState();
            }
            InboxState state = objectMapper.readValue(json, InboxState.class);
            return state != null ? state : new InboxState();
        } catch (IOException | RuntimeException exception) { // NOSONAR - startup should degrade gracefully
            log.warn("[Hive] Failed to load control inbox: {}", exception.getMessage());
            return new InboxState();
        }
    }

    private void saveStateLocked(InboxState state) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            storagePort.putTextAtomic(PREFERENCES_DIR, INBOX_FILE, json, true).join();
            cachedState = state;
            loaded = true;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist Hive control inbox", exception);
        }
    }

    private void touch(InboxState state) {
        state.setUpdatedAt(Instant.now().toString());
    }

    private InboxSummary toSummary(InboxState state) {
        int bufferedCommandCount = state.getCommands().size();
        int pendingCommandCount = 0;
        for (StoredCommand command : state.getCommands()) {
            if (isPending(command)) {
                pendingCommandCount++;
            }
        }
        return new InboxSummary(
                state.getReceivedCommandCount(),
                bufferedCommandCount,
                pendingCommandCount,
                state.getLastReceivedCommandId(),
                state.getLastReceivedAt());
    }

    private StoredCommand copyCommand(StoredCommand source) {
        return new StoredCommand(
                source.getEnvelope(),
                source.getStatus(),
                source.getReceivedAt(),
                source.getProcessedAt(),
                source.getLastDispatchAt(),
                source.getDispatchAttemptCount(),
                source.getLastError());
    }

    @FunctionalInterface
    public interface CommandHandler {

        void handle(HiveControlCommandEnvelope envelope);
    }

    public record RecordResult(
            boolean duplicate,
            InboxSummary summary) {
    }

    public record InboxSummary(
            int receivedCommandCount,
            int bufferedCommandCount,
            int pendingCommandCount,
            String lastReceivedCommandId,
            String lastReceivedAt) {
    }

    private enum CommandStatus {
        RECEIVED, PROCESSING, FAILED, PROCESSED
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
        private List<StoredCommand> commands = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class StoredCommand {

        private HiveControlCommandEnvelope envelope;
        private String status = CommandStatus.RECEIVED.name();
        private String receivedAt = Instant.now().toString();
        private String processedAt;
        private String lastDispatchAt;
        private int dispatchAttemptCount;
        private String lastError;
    }
}

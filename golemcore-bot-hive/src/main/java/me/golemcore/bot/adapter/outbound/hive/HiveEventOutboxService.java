package me.golemcore.bot.adapter.outbound.hive;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class HiveEventOutboxService {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String OUTBOX_FILE = "hive-event-outbox.json";
    private static final int MAX_PENDING_BATCHES = 256;
    private static final int MAX_PENDING_EVENTS = 2048;

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    private final Object lock = new Object();
    private OutboxState cachedState;
    private boolean loaded;

    public OutboxSummary enqueue(HiveSessionState sessionState, List<HiveEventPayload> events) {
        validateSession(sessionState);
        if (events == null || events.isEmpty()) {
            return getSummary();
        }
        synchronized (lock) {
            OutboxState state = getLoadedStateLocked();
            state.getBatches().add(new OutboxBatch(
                    UUID.randomUUID().toString(),
                    sessionState.getServerUrl(),
                    sessionState.getGolemId(),
                    Instant.now().toString(),
                    null,
                    0,
                    null,
                    new ArrayList<>(events)));
            trimOverflowLocked(state);
            touch(state);
            saveStateLocked(state);
            return toSummary(state);
        }
    }

    public OutboxSummary flush(HiveSessionState sessionState, BatchSender batchSender) {
        validateSession(sessionState);
        if (batchSender == null) {
            throw new IllegalArgumentException("Hive batch sender is required");
        }

        while (true) {
            OutboxBatch batch = claimNextBatch(sessionState);
            if (batch == null) {
                return getSummary();
            }
            try {
                batchSender.send(
                        sessionState.getServerUrl(),
                        sessionState.getGolemId(),
                        sessionState.getAccessToken(),
                        batch.getEvents());
                removeBatch(batch.getBatchId());
            } catch (RuntimeException exception) {
                recordFailure(batch.getBatchId(), exception);
                log.warn("[Hive] Failed to flush outbox batch {}: {}", batch.getBatchId(), exception.getMessage());
                return getSummary();
            }
        }
    }

    public OutboxSummary getSummary() {
        synchronized (lock) {
            return toSummary(getLoadedStateLocked());
        }
    }

    public void clear() {
        synchronized (lock) {
            try {
                storagePort.deleteObject(PREFERENCES_DIR, OUTBOX_FILE).join();
            } catch (RuntimeException exception) {
                log.warn("[Hive] Failed to delete event outbox: {}", exception.getMessage());
            }
            cachedState = new OutboxState();
            loaded = true;
        }
    }

    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
    private OutboxBatch claimNextBatch(HiveSessionState sessionState) {
        synchronized (lock) {
            OutboxState state = getLoadedStateLocked();
            for (OutboxBatch batch : state.getBatches()) {
                if (!matchesTarget(batch, sessionState)) {
                    continue;
                }
                batch.setAttemptCount(batch.getAttemptCount() + 1);
                batch.setLastAttemptAt(Instant.now().toString());
                batch.setLastError(null);
                touch(state);
                saveStateLocked(state);
                return copyBatch(batch);
            }
            return null;
        }
    }

    private void removeBatch(String batchId) {
        synchronized (lock) {
            OutboxState state = getLoadedStateLocked();
            state.getBatches().removeIf(batch -> batchId.equals(batch.getBatchId()));
            touch(state);
            saveStateLocked(state);
        }
    }

    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
    private void recordFailure(String batchId, RuntimeException exception) {
        synchronized (lock) {
            OutboxState state = getLoadedStateLocked();
            for (OutboxBatch batch : state.getBatches()) {
                if (!batchId.equals(batch.getBatchId())) {
                    continue;
                }
                batch.setLastError(exception.getMessage());
                touch(state);
                saveStateLocked(state);
                return;
            }
        }
    }

    private boolean matchesTarget(OutboxBatch batch, HiveSessionState sessionState) {
        return sessionState.getServerUrl().equals(batch.getServerUrl())
                && sessionState.getGolemId().equals(batch.getGolemId());
    }

    private void validateSession(HiveSessionState sessionState) {
        if (sessionState == null) {
            throw new IllegalStateException("Hive session is not available");
        }
        if (isBlank(sessionState.getServerUrl()) || isBlank(sessionState.getGolemId())
                || isBlank(sessionState.getAccessToken())) {
            throw new IllegalStateException("Hive session is incomplete");
        }
    }

    private OutboxState getLoadedStateLocked() {
        if (!loaded) {
            cachedState = loadState();
            loaded = true;
        }
        if (cachedState == null) {
            cachedState = new OutboxState();
        }
        return cachedState;
    }

    private OutboxState loadState() {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, OUTBOX_FILE).join();
            if (json == null || json.isBlank()) {
                return new OutboxState();
            }
            OutboxState state = objectMapper.readValue(json, OutboxState.class);
            return state != null ? state : new OutboxState();
        } catch (IOException | RuntimeException exception) { // NOSONAR - startup should degrade gracefully
            log.warn("[Hive] Failed to load event outbox: {}", exception.getMessage());
            return new OutboxState();
        }
    }

    private void saveStateLocked(OutboxState state) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            storagePort.putTextAtomic(PREFERENCES_DIR, OUTBOX_FILE, json, true).join();
            cachedState = state;
            loaded = true;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist Hive event outbox", exception);
        }
    }

    private void touch(OutboxState state) {
        state.setUpdatedAt(Instant.now().toString());
    }

    private void trimOverflowLocked(OutboxState state) {
        int pendingEventCount = countEvents(state);
        while (!state.getBatches().isEmpty()
                && (state.getBatches().size() > MAX_PENDING_BATCHES || pendingEventCount > MAX_PENDING_EVENTS)) {
            OutboxBatch droppedBatch = state.getBatches().remove(0);
            int droppedEventCount = droppedBatch.getEvents() != null ? droppedBatch.getEvents().size() : 0;
            pendingEventCount -= droppedEventCount;
            log.warn("[Hive] Dropped oldest outbox batch due to capacity limit: batchId={}, droppedEvents={}",
                    droppedBatch.getBatchId(), droppedEventCount);
        }
    }

    private int countEvents(OutboxState state) {
        int pendingEventCount = 0;
        for (OutboxBatch batch : state.getBatches()) {
            pendingEventCount += batch.getEvents() != null ? batch.getEvents().size() : 0;
        }
        return pendingEventCount;
    }

    private OutboxSummary toSummary(OutboxState state) {
        int pendingBatchCount = 0;
        int pendingEventCount = 0;
        String lastError = null;
        for (OutboxBatch batch : state.getBatches()) {
            pendingBatchCount++;
            pendingEventCount += batch.getEvents() != null ? batch.getEvents().size() : 0;
            if (lastError == null && batch.getLastError() != null && !batch.getLastError().isBlank()) {
                lastError = batch.getLastError();
            }
        }
        return new OutboxSummary(pendingBatchCount, pendingEventCount, lastError);
    }

    private OutboxBatch copyBatch(OutboxBatch source) {
        return new OutboxBatch(
                source.getBatchId(),
                source.getServerUrl(),
                source.getGolemId(),
                source.getCreatedAt(),
                source.getLastAttemptAt(),
                source.getAttemptCount(),
                source.getLastError(),
                source.getEvents() != null ? new ArrayList<>(source.getEvents()) : new ArrayList<>());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @FunctionalInterface
    public interface BatchSender {

        void send(String serverUrl, String golemId, String accessToken, List<HiveEventPayload> events);
    }

    public record OutboxSummary(
            int pendingBatchCount,
            int pendingEventCount,
            String lastError) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OutboxState {

        private int schemaVersion = 1;
        private String updatedAt = Instant.now().toString();
        private List<OutboxBatch> batches = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OutboxBatch {

        private String batchId;
        private String serverUrl;
        private String golemId;
        private String createdAt;
        private String lastAttemptAt;
        private int attemptCount;
        private String lastError;
        private List<HiveEventPayload> events = new ArrayList<>();
    }
}

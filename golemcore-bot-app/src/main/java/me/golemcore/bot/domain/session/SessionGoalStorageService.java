package me.golemcore.bot.domain.session;

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

import me.golemcore.bot.domain.service.StringValueSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

/**
 * Persists session-bound goals/tasks outside the session transcript while still
 * keeping their lifecycle tied to a concrete session identifier.
 */
@Service
@Slf4j
public class SessionGoalStorageService {

    private static final String AUTO_DIR = "auto";
    private static final String GOALS_DIR = "session-goals";
    private static final String FILE_EXTENSION = ".json";
    private static final TypeReference<List<Goal>> GOAL_LIST_TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public SessionGoalStorageService(
            StoragePort storagePort,
            ObjectMapper objectMapper) {
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
    }

    public List<Goal> loadGoals(String sessionId) {
        requireSessionId(sessionId);
        try {
            String json = storagePort.getText(AUTO_DIR, buildPath(sessionId)).join();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            List<Goal> goals = objectMapper.readValue(json, GOAL_LIST_TYPE_REF);
            return goals != null ? new ArrayList<>(goals) : new ArrayList<>();
        } catch (IOException | RuntimeException exception) { // NOSONAR - fallback to empty goals
            log.debug("[SessionGoalStorage] Failed to load goals for session {}: {}", sessionId,
                    exception.getMessage());
            return new ArrayList<>();
        }
    }

    public void saveGoals(String sessionId, List<Goal> goals) {
        requireSessionId(sessionId);
        List<Goal> normalizedGoals = goals != null ? new ArrayList<>(goals) : new ArrayList<>();
        try {
            if (normalizedGoals.isEmpty()) {
                storagePort.deleteObject(AUTO_DIR, buildPath(sessionId)).join();
                return;
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalizedGoals);
            storagePort.putTextAtomic(AUTO_DIR, buildPath(sessionId), json, true).join();
        } catch (IOException | RuntimeException exception) { // NOSONAR
            throw new IllegalStateException("Failed to persist session goals for session: " + sessionId, exception);
        }
    }

    public void deleteGoals(String sessionId) {
        requireSessionId(sessionId);
        try {
            storagePort.deleteObject(AUTO_DIR, buildPath(sessionId)).join();
        } catch (RuntimeException exception) { // NOSONAR - deletion should stay best effort
            log.warn("[SessionGoalStorage] Failed to delete goals for session {}: {}", sessionId,
                    exception.getMessage());
        }
    }

    private String buildPath(String sessionId) {
        return GOALS_DIR + "/" + sessionId + FILE_EXTENSION;
    }

    private void requireSessionId(String sessionId) {
        if (StringValueSupport.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId is required");
        }
    }
}

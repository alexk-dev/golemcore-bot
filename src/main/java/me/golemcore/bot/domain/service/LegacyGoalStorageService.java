package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.port.outbound.StoragePort;

final class LegacyGoalStorageService extends SessionGoalStorageService {

    private static final String AUTO_DIR = "auto";
    private static final String GOALS_FILE = "goals.json";
    private static final TypeReference<List<Goal>> GOAL_LIST_TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private List<Goal> cachedGoals;

    LegacyGoalStorageService(StoragePort storagePort, ObjectMapper objectMapper) {
        super(storagePort, objectMapper);
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Goal> loadGoals(String sessionId) {
        if (cachedGoals != null) {
            return new ArrayList<>(cachedGoals);
        }
        try {
            String json = storagePort.getText(AUTO_DIR, GOALS_FILE).join();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            List<Goal> goals = objectMapper.readValue(json, GOAL_LIST_TYPE_REF);
            cachedGoals = goals != null ? new ArrayList<>(goals) : new ArrayList<>();
            return new ArrayList<>(cachedGoals);
        } catch (Exception exception) { // NOSONAR - legacy test adapter mirrors old empty-state fallback
            return new ArrayList<>();
        }
    }

    @Override
    public void saveGoals(String sessionId, List<Goal> goals) {
        try {
            List<Goal> normalizedGoals = goals != null ? new ArrayList<>(goals) : new ArrayList<>();
            cachedGoals = new ArrayList<>(normalizedGoals);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalizedGoals);
            storagePort.putText(AUTO_DIR, GOALS_FILE, json).join();
        } catch (Exception exception) { // NOSONAR
            throw new IllegalStateException("Failed to persist legacy goals", exception);
        }
    }

    @Override
    public void deleteGoals(String sessionId) {
        saveGoals(sessionId, List.of());
    }
}

package me.golemcore.bot.adapter.outbound.storage;

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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.port.outbound.ScheduledTaskPersistencePort;
import me.golemcore.bot.port.outbound.StoragePort;

/**
 * JSON persistence kept for legacy/test wiring. Production scheduled tasks use
 * SQLite via {@link SqliteScheduledTaskPersistenceAdapter}.
 */
public class JsonScheduledTaskPersistenceAdapter implements ScheduledTaskPersistencePort {

    private static final String AUTO_DIR = "auto";
    private static final String FILE_NAME = "scheduled-tasks.json";
    private static final TypeReference<List<ScheduledTask>> TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public JsonScheduledTaskPersistenceAdapter(StoragePort storagePort, ObjectMapper objectMapper) {
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ScheduledTask> loadScheduledTasks() {
        try {
            String json = storagePort.getText(AUTO_DIR, FILE_NAME).join();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            List<ScheduledTask> tasks = objectMapper.readValue(json, TYPE_REF);
            return tasks != null ? new ArrayList<>(tasks) : new ArrayList<>();
        } catch (IOException | RuntimeException exception) { // NOSONAR
            throw new IllegalStateException("Failed to load scheduled tasks", exception);
        }
    }

    @Override
    public void replaceScheduledTasks(List<ScheduledTask> tasks) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tasks);
            storagePort.putTextAtomic(AUTO_DIR, FILE_NAME, json, true).join();
        } catch (IOException | RuntimeException exception) { // NOSONAR
            throw new IllegalStateException("Failed to persist scheduled tasks", exception);
        }
    }
}

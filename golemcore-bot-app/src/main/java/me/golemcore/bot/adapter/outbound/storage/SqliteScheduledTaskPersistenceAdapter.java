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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.ScheduledTaskPersistencePort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

/**
 * SQLite-backed persistence for recurring scheduled tasks. Legacy JSON data is
 * imported on first load when the SQLite table is empty.
 */
@Component
@Slf4j
public class SqliteScheduledTaskPersistenceAdapter implements ScheduledTaskPersistencePort {

    private static final String AUTO_DIR = "auto";
    private static final String DB_FILE_NAME = "scheduled-tasks.sqlite";
    private static final String LEGACY_JSON_FILE = "scheduled-tasks.json";
    private static final String LEGACY_IMPORT_MARKER = "legacy_json_imported";
    private static final TypeReference<List<ScheduledTask>> LEGACY_JSON_TYPE = new TypeReference<>() {
    };

    private final BotProperties botProperties;
    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final AtomicReference<Path> databasePath = new AtomicReference<>();

    public SqliteScheduledTaskPersistenceAdapter(
            BotProperties botProperties,
            StoragePort storagePort,
            ObjectMapper objectMapper) {
        this.botProperties = botProperties;
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ScheduledTask> loadScheduledTasks() {
        ensureInitialized();
        migrateLegacyJsonIfNeeded();
        String sql = """
                SELECT
                    id,
                    title,
                    description,
                    prompt,
                    execution_mode,
                    shell_command,
                    shell_working_directory,
                    reflection_model_tier,
                    reflection_tier_priority,
                    consecutive_failure_count,
                    reflection_required,
                    last_failure_summary,
                    last_failure_fingerprint,
                    reflection_strategy,
                    last_used_skill_name,
                    legacy_source_type,
                    legacy_source_id,
                    last_failure_at,
                    last_reflection_at,
                    created_at,
                    updated_at
                FROM scheduled_task
                ORDER BY
                    CASE WHEN created_at IS NULL OR created_at = '' THEN 1 ELSE 0 END,
                    created_at,
                    id
                """;
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            List<ScheduledTask> tasks = new ArrayList<>();
            while (resultSet.next()) {
                tasks.add(mapTask(resultSet));
            }
            return tasks;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load scheduled tasks from SQLite", exception);
        }
    }

    @Override
    public void replaceScheduledTasks(List<ScheduledTask> tasks) {
        ensureInitialized();
        List<ScheduledTask> safeTasks = tasks != null ? tasks : List.of();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                deleteAll(connection);
                insertAll(connection, safeTasks);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw new IllegalStateException("Failed to persist scheduled tasks to SQLite", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to open scheduled task SQLite database", exception);
        }
    }

    Path getDatabasePath() {
        ensureInitialized();
        return databasePath.get();
    }

    private void ensureInitialized() {
        Path currentPath = databasePath.get();
        if (currentPath != null) {
            return;
        }
        synchronized (this) {
            currentPath = databasePath.get();
            if (currentPath != null) {
                return;
            }
            String configuredBasePath = botProperties.getStorage().getLocal().getBasePath();
            Path storageRoot = Paths.get(configuredBasePath.replace("${user.home}", System.getProperty("user.home")))
                    .toAbsolutePath()
                    .normalize();
            Path directory = storageRoot.resolve(AUTO_DIR);
            try {
                Files.createDirectories(directory);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to initialize scheduled task SQLite directory", exception);
            }
            Path resolvedPath = directory.resolve(DB_FILE_NAME);
            databasePath.set(resolvedPath);
            initializeSchema();
        }
    }

    private void initializeSchema() {
        String table = """
                CREATE TABLE IF NOT EXISTS scheduled_task (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    description TEXT,
                    prompt TEXT,
                    execution_mode TEXT NOT NULL,
                    shell_command TEXT,
                    shell_working_directory TEXT,
                    reflection_model_tier TEXT,
                    reflection_tier_priority INTEGER NOT NULL,
                    consecutive_failure_count INTEGER NOT NULL,
                    reflection_required INTEGER NOT NULL,
                    last_failure_summary TEXT,
                    last_failure_fingerprint TEXT,
                    reflection_strategy TEXT,
                    last_used_skill_name TEXT,
                    legacy_source_type TEXT,
                    legacy_source_id TEXT,
                    last_failure_at TEXT,
                    last_reflection_at TEXT,
                    created_at TEXT,
                    updated_at TEXT
                )
                """;
        String index = """
                CREATE INDEX IF NOT EXISTS idx_scheduled_task_legacy_source
                ON scheduled_task (legacy_source_type, legacy_source_id)
                """;
        String metadataTable = """
                CREATE TABLE IF NOT EXISTS scheduled_task_metadata (
                    key TEXT NOT NULL PRIMARY KEY,
                    value TEXT
                )
                """;
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(table);
            statement.execute(index);
            statement.execute(metadataTable);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize scheduled task SQLite schema", exception);
        }
    }

    private Connection openConnection() throws SQLException {
        Path currentPath = databasePath.get();
        if (currentPath == null) {
            throw new IllegalStateException("Scheduled task SQLite database path is not initialized");
        }
        return DriverManager.getConnection("jdbc:sqlite:" + currentPath.toAbsolutePath());
    }

    private void migrateLegacyJsonIfNeeded() {
        try (Connection connection = openConnection()) {
            if (isMigrationMarked(connection)) {
                return;
            }
            if (hasPersistedTasks(connection)) {
                markMigration(connection);
                return;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to inspect scheduled task SQLite contents", exception);
        }

        String legacyJson;
        try {
            legacyJson = storagePort.getText(AUTO_DIR, LEGACY_JSON_FILE).join();
        } catch (RuntimeException exception) {
            log.warn("[ScheduledTaskSqlite] Failed to read legacy JSON store: {}", exception.getMessage());
            return;
        }
        if (legacyJson == null || legacyJson.isBlank()) {
            markMigrationSafely();
            return;
        }

        List<ScheduledTask> legacyTasks;
        try {
            legacyTasks = objectMapper.readValue(legacyJson, LEGACY_JSON_TYPE);
        } catch (IOException exception) {
            log.warn("[ScheduledTaskSqlite] Failed to parse legacy JSON store: {}", exception.getMessage());
            return;
        }

        if (legacyTasks == null || legacyTasks.isEmpty()) {
            markMigrationSafely();
            return;
        }

        replaceScheduledTasks(legacyTasks);
        markMigrationSafely();
        log.info("[ScheduledTaskSqlite] Imported {} scheduled tasks from legacy JSON store", legacyTasks.size());
    }

    private boolean hasPersistedTasks(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM scheduled_task");
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getInt(1) > 0;
        }
    }

    private boolean isMigrationMarked(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM scheduled_task_metadata WHERE key = ?");) {
            statement.setString(1, LEGACY_IMPORT_MARKER);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void markMigration(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO scheduled_task_metadata (key, value) VALUES (?, ?)")) {
            statement.setString(1, LEGACY_IMPORT_MARKER);
            statement.setString(2, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private void markMigrationSafely() {
        try (Connection connection = openConnection()) {
            markMigration(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to store scheduled task migration marker", exception);
        }
    }

    private void deleteAll(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM scheduled_task")) {
            statement.executeUpdate();
        }
    }

    private void insertAll(Connection connection, List<ScheduledTask> tasks) throws SQLException {
        if (tasks.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO scheduled_task (
                    id,
                    title,
                    description,
                    prompt,
                    execution_mode,
                    shell_command,
                    shell_working_directory,
                    reflection_model_tier,
                    reflection_tier_priority,
                    consecutive_failure_count,
                    reflection_required,
                    last_failure_summary,
                    last_failure_fingerprint,
                    reflection_strategy,
                    last_used_skill_name,
                    legacy_source_type,
                    legacy_source_id,
                    last_failure_at,
                    last_reflection_at,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (ScheduledTask task : tasks) {
                statement.setString(1, task.getId());
                statement.setString(2, task.getTitle());
                statement.setString(3, task.getDescription());
                statement.setString(4, task.getPrompt());
                statement.setString(5, task.getExecutionModeOrDefault().name());
                statement.setString(6, task.getShellCommand());
                statement.setString(7, task.getShellWorkingDirectory());
                statement.setString(8, task.getReflectionModelTier());
                statement.setInt(9, task.isReflectionTierPriority() ? 1 : 0);
                statement.setInt(10, task.getConsecutiveFailureCount());
                statement.setInt(11, task.isReflectionRequired() ? 1 : 0);
                statement.setString(12, task.getLastFailureSummary());
                statement.setString(13, task.getLastFailureFingerprint());
                statement.setString(14, task.getReflectionStrategy());
                statement.setString(15, task.getLastUsedSkillName());
                statement.setString(16, task.getLegacySourceType());
                statement.setString(17, task.getLegacySourceId());
                statement.setString(18, toText(task.getLastFailureAt()));
                statement.setString(19, toText(task.getLastReflectionAt()));
                statement.setString(20, toText(task.getCreatedAt()));
                statement.setString(21, toText(task.getUpdatedAt()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private ScheduledTask mapTask(ResultSet resultSet) throws SQLException {
        ScheduledTask.ExecutionMode executionMode = parseExecutionMode(resultSet.getString("execution_mode"));
        return ScheduledTask.builder()
                .id(resultSet.getString("id"))
                .title(resultSet.getString("title"))
                .description(resultSet.getString("description"))
                .prompt(resultSet.getString("prompt"))
                .executionMode(executionMode)
                .shellCommand(resultSet.getString("shell_command"))
                .shellWorkingDirectory(resultSet.getString("shell_working_directory"))
                .reflectionModelTier(resultSet.getString("reflection_model_tier"))
                .reflectionTierPriority(resultSet.getInt("reflection_tier_priority") == 1)
                .consecutiveFailureCount(resultSet.getInt("consecutive_failure_count"))
                .reflectionRequired(resultSet.getInt("reflection_required") == 1)
                .lastFailureSummary(resultSet.getString("last_failure_summary"))
                .lastFailureFingerprint(resultSet.getString("last_failure_fingerprint"))
                .reflectionStrategy(resultSet.getString("reflection_strategy"))
                .lastUsedSkillName(resultSet.getString("last_used_skill_name"))
                .legacySourceType(resultSet.getString("legacy_source_type"))
                .legacySourceId(resultSet.getString("legacy_source_id"))
                .lastFailureAt(parseInstant(resultSet.getString("last_failure_at")))
                .lastReflectionAt(parseInstant(resultSet.getString("last_reflection_at")))
                .createdAt(parseInstant(resultSet.getString("created_at")))
                .updatedAt(parseInstant(resultSet.getString("updated_at")))
                .build();
    }

    private ScheduledTask.ExecutionMode parseExecutionMode(String value) {
        if (value == null || value.isBlank()) {
            return ScheduledTask.ExecutionMode.AGENT_PROMPT;
        }
        try {
            return ScheduledTask.ExecutionMode.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            log.warn("[ScheduledTaskSqlite] Unknown execution mode '{}', defaulting to AGENT_PROMPT", value);
            return ScheduledTask.ExecutionMode.AGENT_PROMPT;
        }
    }

    private static String toText(Instant value) {
        return value != null ? value.toString() : null;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}

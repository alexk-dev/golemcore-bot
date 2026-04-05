package me.golemcore.bot.domain.service;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Service;

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
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persists tactic embedding vectors in a local SQLite database under the
 * storage workspace.
 */
@Service
@Slf4j
public class TacticEmbeddingSqliteIndexStore {

    private static final String DB_FILE_NAME = "tactic-embedding-index.sqlite";
    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final TypeReference<List<Double>> VECTOR_TYPE = new TypeReference<>() {
    };

    private final BotProperties botProperties;
    private final ObjectMapper objectMapper;

    private final AtomicReference<Path> databasePath = new AtomicReference<>();

    public TacticEmbeddingSqliteIndexStore(BotProperties botProperties, ObjectMapper objectMapper) {
        this.botProperties = botProperties;
        this.objectMapper = objectMapper;
    }

    public void replaceAll(String provider, String model, Integer dimensions, List<Entry> entries) {
        ensureInitialized();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                deleteExistingEntries(connection, provider, model);
                insertEntries(connection, provider, model, dimensions, entries != null ? entries : List.of());
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw new IllegalStateException("Failed to persist tactic embedding index", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to open tactic embedding index", exception);
        }
    }

    public Map<String, Entry> loadEntries(String provider, String model) {
        ensureInitialized();
        Map<String, Entry> entries = new LinkedHashMap<>();
        String sql = """
                SELECT tactic_id, content_revision_id, dimensions, vector_json, updated_at
                FROM tactic_embedding_index
                WHERE provider = ? AND model = ?
                ORDER BY tactic_id
                """;
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, provider);
            statement.setString(2, model);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int rawDimensions = resultSet.getInt("dimensions");
                    Integer dimensions = resultSet.wasNull() ? null : rawDimensions;
                    entries.put(resultSet.getString("tactic_id"), new Entry(
                            resultSet.getString("tactic_id"),
                            resultSet.getString("content_revision_id"),
                            dimensions,
                            deserializeVector(resultSet.getString("vector_json")),
                            parseInstant(resultSet.getString("updated_at"))));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load tactic embedding index", exception);
        }
        return entries;
    }

    public boolean hasEntry(String tacticId, String provider, String model) {
        return loadEntries(provider, model).containsKey(tacticId);
    }

    public Path getDatabasePath() {
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
            Path directory = storageRoot.resolve(SELF_EVOLVING_DIR);
            try {
                Files.createDirectories(directory);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to initialize tactic embedding index directory", exception);
            }
            databasePath.set(directory.resolve(DB_FILE_NAME));
            initializeSchema();
        }
    }

    private void initializeSchema() {
        String schema = """
                CREATE TABLE IF NOT EXISTS tactic_embedding_index (
                    tactic_id TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    model TEXT NOT NULL,
                    content_revision_id TEXT,
                    dimensions INTEGER,
                    vector_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (tactic_id, provider, model)
                )
                """;
        String index = """
                CREATE INDEX IF NOT EXISTS idx_tactic_embedding_index_provider_model
                ON tactic_embedding_index (provider, model)
                """;
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(schema);
            statement.execute(index);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize tactic embedding SQLite schema", exception);
        }
    }

    private Connection openConnection() throws SQLException {
        Path currentPath = databasePath.get();
        if (currentPath == null) {
            throw new IllegalStateException("Tactic embedding index database path is not initialized");
        }
        return DriverManager.getConnection("jdbc:sqlite:" + currentPath.toAbsolutePath());
    }

    private void deleteExistingEntries(Connection connection, String provider, String model) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM tactic_embedding_index WHERE provider = ? AND model = ?")) {
            statement.setString(1, provider);
            statement.setString(2, model);
            statement.executeUpdate();
        }
    }

    private void insertEntries(
            Connection connection,
            String provider,
            String model,
            Integer dimensions,
            List<Entry> entries) throws SQLException {
        if (entries.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO tactic_embedding_index (
                    tactic_id,
                    provider,
                    model,
                    content_revision_id,
                    dimensions,
                    vector_json,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Entry entry : entries) {
                statement.setString(1, entry.tacticId());
                statement.setString(2, provider);
                statement.setString(3, model);
                statement.setString(4, entry.contentRevisionId());
                if (dimensions != null) {
                    statement.setInt(5, dimensions);
                } else {
                    statement.setNull(5, Types.INTEGER);
                }
                statement.setString(6, serializeVector(entry.vector()));
                statement.setString(7, (entry.updatedAt() != null ? entry.updatedAt() : Instant.EPOCH).toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private String serializeVector(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector != null ? vector : List.of());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize tactic embedding vector", exception);
        }
    }

    private List<Double> deserializeVector(String vectorJson) {
        try {
            return objectMapper.readValue(vectorJson, VECTOR_TYPE);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to deserialize tactic embedding vector", exception);
        }
    }

    private Instant parseInstant(String updatedAt) {
        if (updatedAt == null || updatedAt.isBlank()) {
            return Instant.EPOCH;
        }
        return Instant.parse(updatedAt);
    }

    public record Entry(
            String tacticId,
            String contentRevisionId,
            Integer dimensions,
            List<Double> vector,
            Instant updatedAt) {

        public Entry(
                String tacticId,
                String contentRevisionId,
                List<Double> vector,
                Instant updatedAt) {
            this(tacticId, contentRevisionId, null, vector, updatedAt);
        }
    }
}

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

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticEmbeddingSqliteIndexStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistAndReloadEmbeddingRowsFromSqlite() throws Exception {
        TacticEmbeddingSqliteIndexStore store = new TacticEmbeddingSqliteIndexStore(
                botProperties(tempDir),
                new ObjectMapper());

        store.replaceAll(
                "ollama",
                "bge-m3",
                1024,
                List.of(
                        new TacticEmbeddingSqliteIndexStore.Entry(
                                "planner",
                                "rev-1",
                                List.of(1.0d, 0.0d),
                                Instant.parse("2026-04-04T19:10:00Z")),
                        new TacticEmbeddingSqliteIndexStore.Entry(
                                "rollback",
                                "rev-2",
                                List.of(0.2d, 0.98d),
                                Instant.parse("2026-04-04T19:11:00Z"))));

        assertTrue(Files.exists(store.getDatabasePath()));
        assertTrue(store.hasEntry("planner", "ollama", "bge-m3"));

        Map<String, TacticEmbeddingSqliteIndexStore.Entry> entries = store.loadEntries("ollama", "bge-m3");

        assertEquals(List.of("planner", "rollback"), entries.keySet().stream().sorted().toList());
        assertEquals("rev-1", entries.get("planner").contentRevisionId());
        assertEquals(1024, entries.get("planner").dimensions());
        assertEquals(List.of(1.0d, 0.0d), entries.get("planner").vector());
        assertEquals(List.of(0.2d, 0.98d), entries.get("rollback").vector());
    }

    @Test
    void shouldReplaceExistingRowsWithEmptyEntryBatch() {
        TacticEmbeddingSqliteIndexStore store = new TacticEmbeddingSqliteIndexStore(
                botProperties(tempDir),
                new ObjectMapper());

        store.replaceAll(
                "ollama",
                "bge-m3",
                1024,
                List.of(new TacticEmbeddingSqliteIndexStore.Entry(
                        "planner",
                        "rev-1",
                        List.of(1.0d, 0.0d),
                        Instant.parse("2026-04-04T19:10:00Z"))));

        store.replaceAll("ollama", "bge-m3", 1024, List.of());

        assertTrue(store.loadEntries("ollama", "bge-m3").isEmpty());
        assertTrue(!store.hasEntry("planner", "ollama", "bge-m3"));
    }

    @Test
    void shouldPersistNullDimensionsVectorAndUpdatedAtWithSafeDefaults() {
        TacticEmbeddingSqliteIndexStore store = new TacticEmbeddingSqliteIndexStore(
                botProperties(tempDir),
                new ObjectMapper());

        store.replaceAll(
                "ollama",
                "bge-m3",
                null,
                List.of(new TacticEmbeddingSqliteIndexStore.Entry(
                        "planner",
                        "rev-1",
                        null,
                        null)));

        TacticEmbeddingSqliteIndexStore.Entry entry = store.loadEntries("ollama", "bge-m3").get("planner");

        assertNull(entry.dimensions());
        assertEquals(List.of(), entry.vector());
        assertEquals(Instant.EPOCH, entry.updatedAt());
    }

    @Test
    void shouldTreatBlankUpdatedAtAsEpochWhenLoadingRows() throws Exception {
        TacticEmbeddingSqliteIndexStore store = new TacticEmbeddingSqliteIndexStore(
                botProperties(tempDir),
                new ObjectMapper());

        Path databasePath = store.getDatabasePath();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO tactic_embedding_index (
                        tactic_id,
                        provider,
                        model,
                        content_revision_id,
                        dimensions,
                        vector_json,
                        updated_at
                    ) VALUES (
                        'planner',
                        'ollama',
                        'bge-m3',
                        'rev-1',
                        1024,
                        '[0.1, 0.2]',
                        ''
                    )
                    """);
        }

        TacticEmbeddingSqliteIndexStore.Entry entry = store.loadEntries("ollama", "bge-m3").get("planner");

        assertEquals(1024, entry.dimensions());
        assertEquals(Instant.EPOCH, entry.updatedAt());
        assertEquals(List.of(0.1d, 0.2d), entry.vector());
    }

    private BotProperties botProperties(Path basePath) {
        BotProperties properties = new BotProperties();
        properties.getStorage().getLocal().setBasePath(basePath.toString());
        return properties;
    }
}

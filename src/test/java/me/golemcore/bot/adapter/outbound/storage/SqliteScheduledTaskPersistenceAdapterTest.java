package me.golemcore.bot.adapter.outbound.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteScheduledTaskPersistenceAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistAndReloadScheduledTasksFromSqlite() {
        SqliteScheduledTaskPersistenceAdapter adapter = new SqliteScheduledTaskPersistenceAdapter(
                botProperties(tempDir),
                mockStorage(null),
                objectMapper());

        ScheduledTask promptTask = ScheduledTask.builder()
                .id("task-agent")
                .title("Refresh inbox")
                .prompt("Summarize unread items")
                .executionMode(ScheduledTask.ExecutionMode.AGENT_PROMPT)
                .createdAt(Instant.parse("2026-04-20T00:00:00Z"))
                .updatedAt(Instant.parse("2026-04-20T00:00:00Z"))
                .build();
        ScheduledTask shellTask = ScheduledTask.builder()
                .id("task-shell")
                .title("Nightly cleanup")
                .executionMode(ScheduledTask.ExecutionMode.SHELL_COMMAND)
                .shellCommand("printf 'cleanup'")
                .shellWorkingDirectory("jobs")
                .createdAt(Instant.parse("2026-04-21T00:00:00Z"))
                .updatedAt(Instant.parse("2026-04-21T00:00:00Z"))
                .build();

        adapter.replaceScheduledTasks(List.of(shellTask, promptTask));

        List<ScheduledTask> loaded = adapter.loadScheduledTasks();

        assertTrue(Files.exists(adapter.getDatabasePath()));
        assertEquals(List.of("task-agent", "task-shell"), loaded.stream().map(ScheduledTask::getId).toList());
        ScheduledTask reloadedShellTask = loaded.get(1);
        assertEquals(ScheduledTask.ExecutionMode.SHELL_COMMAND, reloadedShellTask.getExecutionModeOrDefault());
        assertEquals("printf 'cleanup'", reloadedShellTask.getShellCommand());
        assertEquals("jobs", reloadedShellTask.getShellWorkingDirectory());
    }

    @Test
    void shouldImportLegacyJsonIntoSqliteWhenDatabaseIsEmpty() throws Exception {
        ScheduledTask legacyTask = ScheduledTask.builder()
                .id("legacy-shell")
                .title("Legacy shell task")
                .executionMode(ScheduledTask.ExecutionMode.SHELL_COMMAND)
                .shellCommand("echo migrated")
                .shellWorkingDirectory("cron")
                .createdAt(Instant.parse("2026-04-19T00:00:00Z"))
                .updatedAt(Instant.parse("2026-04-19T00:00:00Z"))
                .build();
        String legacyJson = objectMapper().writeValueAsString(List.of(legacyTask));

        SqliteScheduledTaskPersistenceAdapter adapter = new SqliteScheduledTaskPersistenceAdapter(
                botProperties(tempDir),
                mockStorage(legacyJson),
                objectMapper());

        List<ScheduledTask> loaded = adapter.loadScheduledTasks();

        assertEquals(1, loaded.size());
        assertEquals("legacy-shell", loaded.getFirst().getId());
        assertEquals("echo migrated", loaded.getFirst().getShellCommand());
        assertEquals(ScheduledTask.ExecutionMode.SHELL_COMMAND, loaded.getFirst().getExecutionModeOrDefault());
    }

    @Test
    void shouldRetryLegacyJsonImportAfterTransientReadFailure() throws Exception {
        ScheduledTask legacyTask = ScheduledTask.builder()
                .id("legacy-shell")
                .title("Legacy shell task")
                .executionMode(ScheduledTask.ExecutionMode.SHELL_COMMAND)
                .shellCommand("echo migrated")
                .createdAt(Instant.parse("2026-04-19T00:00:00Z"))
                .updatedAt(Instant.parse("2026-04-19T00:00:00Z"))
                .build();
        String legacyJson = objectMapper().writeValueAsString(List.of(legacyTask));
        AtomicInteger reads = new AtomicInteger();
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.getText(eq("auto"), eq("scheduled-tasks.json"))).thenAnswer(invocation -> {
            if (reads.getAndIncrement() == 0) {
                return CompletableFuture.failedFuture(new IllegalStateException("temporary read failure"));
            }
            return CompletableFuture.completedFuture(legacyJson);
        });

        SqliteScheduledTaskPersistenceAdapter adapter = new SqliteScheduledTaskPersistenceAdapter(
                botProperties(tempDir),
                storagePort,
                objectMapper());

        assertTrue(adapter.loadScheduledTasks().isEmpty());
        List<ScheduledTask> loaded = adapter.loadScheduledTasks();

        assertEquals(1, loaded.size());
        assertEquals("legacy-shell", loaded.getFirst().getId());
    }

    private StoragePort mockStorage(String legacyJson) {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.getText(eq("auto"), eq("scheduled-tasks.json")))
                .thenReturn(CompletableFuture.completedFuture(legacyJson));
        return storagePort;
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private BotProperties botProperties(Path basePath) {
        BotProperties properties = new BotProperties();
        properties.getStorage().getLocal().setBasePath(basePath.toString());
        return properties;
    }
}

package me.golemcore.bot.domain.service;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateRuntimeCleanupServiceTest {

    @Test
    void shouldKeepOnlyCurrentAndStagedJarsAfterSuccessfulStartup(@TempDir Path tempDir) throws Exception {
        BotProperties botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-0.4.1.jar"), "old", StandardCharsets.UTF_8);
        Files.writeString(jarsDir.resolve("bot-0.4.2.jar"), "current", StandardCharsets.UTF_8);
        Files.writeString(jarsDir.resolve("bot-0.4.3.jar"), "staged", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.2.jar\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("staged.txt"), "bot-0.4.3.jar\n", StandardCharsets.UTF_8);

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(botProperties);
        service.cleanupAfterSuccessfulStartup();

        assertFalse(Files.exists(jarsDir.resolve("bot-0.4.1.jar")));
        assertTrue(Files.exists(jarsDir.resolve("bot-0.4.2.jar")));
        assertTrue(Files.exists(jarsDir.resolve("bot-0.4.3.jar")));
    }

    @Test
    void shouldDeleteRedundantStagedMarkerWhenItMatchesCurrent(@TempDir Path tempDir) throws Exception {
        BotProperties botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-0.4.2.jar"), "current", StandardCharsets.UTF_8);
        Files.writeString(jarsDir.resolve("bot-0.4.1.jar"), "old", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.2.jar\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("staged.txt"), "bot-0.4.2.jar\n", StandardCharsets.UTF_8);

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(botProperties);
        service.cleanupAfterSuccessfulStartup();

        assertFalse(Files.exists(tempDir.resolve("staged.txt")));
        assertFalse(Files.exists(jarsDir.resolve("bot-0.4.1.jar")));
        assertTrue(Files.exists(jarsDir.resolve("bot-0.4.2.jar")));
    }
}

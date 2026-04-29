package me.golemcore.bot.domain.update;

import me.golemcore.bot.support.LocalTestWorkspaceFilePort;
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

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        service.cleanupAfterSuccessfulStartup();

        assertFalse(Files.exists(jarsDir.resolve("bot-0.4.1.jar")));
        assertTrue(Files.exists(jarsDir.resolve("bot-0.4.2.jar")));
        assertTrue(Files.exists(jarsDir.resolve("bot-0.4.3.jar")));
    }

    @Test
    void shouldIgnoreCurrentMarkerOutsideJarsDirWhenCleaningUp(@TempDir Path tempDir) throws Exception {
        BotProperties botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(tempDir.resolve("bot-0.4.1.jar"), "outside", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "../bot-0.4.1.jar\n", StandardCharsets.UTF_8);

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        service.cleanupAfterSuccessfulStartup("0.4.3");

        assertFalse(Files.exists(tempDir.resolve("current.txt")));
        assertTrue(Files.exists(tempDir.resolve("bot-0.4.1.jar")));
    }

    @Test
    void shouldIgnoreStagedMarkerOutsideJarsDirWhenCleaningUp(@TempDir Path tempDir) throws Exception {
        BotProperties botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(tempDir.resolve("bot-0.4.1.jar"), "outside", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("staged.txt"), "../bot-0.4.1.jar\n", StandardCharsets.UTF_8);

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        service.cleanupAfterSuccessfulStartup("0.4.3");

        assertFalse(Files.exists(tempDir.resolve("staged.txt")));
        assertTrue(Files.exists(tempDir.resolve("bot-0.4.1.jar")));
    }

    @Test
    void shouldDeleteStaleCurrentMarkerAndJarWhenRunningImageIsNewer(@TempDir Path tempDir) throws Exception {
        BotProperties botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-0.4.1.jar"), "old-current", StandardCharsets.UTF_8);
        Files.writeString(jarsDir.resolve("bot-0.4.3.jar"), "staged", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.1.jar\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("staged.txt"), "bot-0.4.3.jar\n", StandardCharsets.UTF_8);

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        service.cleanupAfterSuccessfulStartup("0.4.2");

        assertFalse(Files.exists(tempDir.resolve("current.txt")));
        assertFalse(Files.exists(jarsDir.resolve("bot-0.4.1.jar")));
        assertTrue(Files.exists(tempDir.resolve("staged.txt")));
        assertTrue(Files.exists(jarsDir.resolve("bot-0.4.3.jar")));
    }

    @Test
    void shouldDeleteStaleStagedMarkerWhenRunningImageIsNewer(@TempDir Path tempDir) throws Exception {
        BotProperties botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-0.4.1.jar"), "stale-staged", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("staged.txt"), "bot-0.4.1.jar\n", StandardCharsets.UTF_8);

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        service.cleanupAfterSuccessfulStartup("0.4.2");

        assertFalse(Files.exists(tempDir.resolve("staged.txt")));
        assertFalse(Files.exists(jarsDir.resolve("bot-0.4.1.jar")));
    }

    @Test
    void shouldKeepCurrentMarkerWhenVersionsAreEqual(@TempDir Path tempDir) throws Exception {
        BotProperties botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-0.4.2.jar"), "current", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.2.jar\n", StandardCharsets.UTF_8);

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        service.cleanupAfterSuccessfulStartup("0.4.2");

        assertTrue(Files.exists(tempDir.resolve("current.txt")));
        assertTrue(Files.exists(jarsDir.resolve("bot-0.4.2.jar")));
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

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        service.cleanupAfterSuccessfulStartup();

        assertFalse(Files.exists(tempDir.resolve("staged.txt")));
        assertFalse(Files.exists(jarsDir.resolve("bot-0.4.1.jar")));
        assertTrue(Files.exists(jarsDir.resolve("bot-0.4.2.jar")));
    }

    @Test
    void shouldSkipCleanupWhenUpdateFeatureIsDisabled(@TempDir Path tempDir) throws Exception {
        BotProperties botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(false);
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-0.4.2.jar"), "current", StandardCharsets.UTF_8);

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        service.cleanupAfterSuccessfulStartup();

        assertTrue(Files.exists(jarsDir.resolve("bot-0.4.2.jar")));
    }

    @Test
    void shouldReturnWhenJarsDirectoryIsMissing(@TempDir Path tempDir) {
        BotProperties botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());

        service.cleanupAfterSuccessfulStartup();

        assertFalse(Files.exists(tempDir.resolve("jars")));
    }

    @Test
    void shouldIgnoreUnreadableMarkerDirectories(@TempDir Path tempDir) throws Exception {
        BotProperties botProperties = new BotProperties();
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-0.4.1.jar"), "old", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("current.txt"));
        Files.createDirectories(tempDir.resolve("staged.txt"));

        UpdateRuntimeCleanupService service = new UpdateRuntimeCleanupService(
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                new LocalTestWorkspaceFilePort());
        service.cleanupAfterSuccessfulStartup();

        assertFalse(Files.exists(jarsDir.resolve("bot-0.4.1.jar")));
    }
}

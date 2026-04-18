package me.golemcore.bot.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers launcher restart behavior, staged update selection, bundled runtime
 * discovery, and picocli-based native launcher argument parsing.
 */
class RuntimeLauncherTest {

    @Test
    void shouldLaunchBundledRuntimeWhenCurrentMarkerIsMissing(@TempDir Path tempDir) {
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, null, null, Map.of());

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-cp",
                "@/app/jib-classpath-file",
                "me.golemcore.bot.BotApplication"), processStarter.commands().getFirst());
    }

    @Test
    void shouldLaunchBundledRuntimeJarWhenResolverProvidesOne(@TempDir Path tempDir) throws Exception {
        Path bundledJar = tempDir.resolve("bot-bundled.jar");
        Files.writeString(bundledJar, "payload", StandardCharsets.UTF_8);
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, null, bundledJar, Map.of());

        int exitCode = launcher.run(new String[] { "--spring.profiles.active=prod" });

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-jar",
                bundledJar.toAbsolutePath().normalize().toString(),
                "--spring.profiles.active=prod"), processStarter.commands().getFirst());
    }

    @Test
    void shouldForwardServerPortSystemPropertyToBundledRuntime(@TempDir Path tempDir) throws Exception {
        Path bundledJar = tempDir.resolve("bot-bundled.jar");
        Files.writeString(bundledJar, "payload", StandardCharsets.UTF_8);
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(
                Map.of("UPDATE_PATH", tempDir.toString()),
                processStarter,
                new NoOpLauncherOutput(),
                null,
                bundledJar,
                Map.of("server.port", "9090"));

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-Dserver.port=9090",
                "-jar",
                bundledJar.toAbsolutePath().normalize().toString()), processStarter.commands().getFirst());
    }

    @Test
    void shouldForwardSystemPropertyArgumentsToBundledRuntime(@TempDir Path tempDir) throws Exception {
        Path bundledJar = tempDir.resolve("bot-bundled.jar");
        Files.writeString(bundledJar, "payload", StandardCharsets.UTF_8);
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(
                Map.of("UPDATE_PATH", tempDir.toString()),
                processStarter,
                new NoOpLauncherOutput(),
                null,
                bundledJar,
                Map.of());

        int exitCode = launcher.run(new String[] {
                "-Dserver.port=9191",
                "-Dspring.profiles.active=prod",
                "--spring.main.web-application-type=none"
        });

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-Dserver.port=9191",
                "-Dspring.profiles.active=prod",
                "-jar",
                bundledJar.toAbsolutePath().normalize().toString(),
                "--spring.main.web-application-type=none"), processStarter.commands().getFirst());
    }

    @Test
    void shouldForwardPicocliJavaOptionsAndLauncherServerPort(@TempDir Path tempDir) throws Exception {
        Path bundledJar = tempDir.resolve("bot-bundled.jar");
        Files.writeString(bundledJar, "payload", StandardCharsets.UTF_8);
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(
                Map.of("UPDATE_PATH", tempDir.toString()),
                processStarter,
                new NoOpLauncherOutput(),
                null,
                bundledJar,
                Map.of());

        int exitCode = launcher.run(new String[] {
                "-J=-Xmx512m",
                "--server-port=9191",
                "--spring.main.web-application-type=none"
        });

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-Xmx512m",
                "-Dserver.port=9191",
                "-jar",
                bundledJar.toAbsolutePath().normalize().toString(),
                "--spring.main.web-application-type=none"), processStarter.commands().getFirst());
    }

    @Test
    void shouldForwardLauncherStorageAndUpdatesOptionsAsSystemProperties(@TempDir Path tempDir) throws Exception {
        Path bundledJar = tempDir.resolve("bot-bundled.jar");
        Path storagePath = tempDir.resolve("workspace");
        Path updatesPath = tempDir.resolve("updates");
        Files.writeString(bundledJar, "payload", StandardCharsets.UTF_8);
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(
                Map.of(),
                processStarter,
                new NoOpLauncherOutput(),
                null,
                bundledJar,
                Map.of());

        int exitCode = launcher.run(new String[] {
                "--storage-path=" + storagePath,
                "--updates-path=" + updatesPath
        });

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-Dbot.storage.local.base-path=" + storagePath.toAbsolutePath().normalize(),
                "-Dbot.update.updates-path=" + updatesPath.toAbsolutePath().normalize(),
                "-jar",
                bundledJar.toAbsolutePath().normalize().toString()), processStarter.commands().getFirst());
    }

    @Test
    void shouldNotDuplicateForwardedServerPortWhenPassedAsApplicationArgument(@TempDir Path tempDir) throws Exception {
        Path bundledJar = tempDir.resolve("bot-bundled.jar");
        Files.writeString(bundledJar, "payload", StandardCharsets.UTF_8);
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(
                Map.of("UPDATE_PATH", tempDir.toString()),
                processStarter,
                new NoOpLauncherOutput(),
                null,
                bundledJar,
                Map.of("server.port", "9090"));

        int exitCode = launcher.run(new String[] { "--server.port=9191" });

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-Dserver.port=9191",
                "-jar",
                bundledJar.toAbsolutePath().normalize().toString()), processStarter.commands().getFirst());
    }

    @Test
    void shouldUseExplicitBundledJarOption(@TempDir Path tempDir) throws Exception {
        Path bundledJar = tempDir.resolve("configured.jar");
        Files.writeString(bundledJar, "payload", StandardCharsets.UTF_8);
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(
                Map.of("UPDATE_PATH", tempDir.toString()),
                processStarter,
                new NoOpLauncherOutput(),
                null,
                null,
                Map.of());

        int exitCode = launcher.run(new String[] { "--bundled-jar=" + bundledJar });

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-jar",
                bundledJar.toAbsolutePath().normalize().toString()), processStarter.commands().getFirst());
    }

    @Test
    void shouldResolveSiblingBundledRuntimeJarWhenResolverPointsToAppImageDirectory(@TempDir Path tempDir)
            throws Exception {
        Path appDir = tempDir.resolve("app");
        Files.createDirectories(appDir);
        Path bundledJar = appDir.resolve("bot-0.4.2.jar");
        Files.writeString(bundledJar, "payload", StandardCharsets.UTF_8);
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, null, appDir, Map.of());

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-jar",
                bundledJar.toAbsolutePath().normalize().toString()), processStarter.commands().getFirst());
    }

    @Test
    void shouldResolveBundledRuntimeJarFromAppImageLibRuntimeDirectory(@TempDir Path tempDir)
            throws Exception {
        Path appDir = tempDir.resolve("app-image");
        Path libRuntimeDir = appDir.resolve("lib").resolve("runtime");
        Files.createDirectories(libRuntimeDir);
        Path bundledJar = libRuntimeDir.resolve("bot-0.4.2.jar");
        Files.writeString(bundledJar, "payload", StandardCharsets.UTF_8);
        Path launcherJar = appDir.resolve("lib").resolve("app").resolve("golemcore-bot-launcher.jar");
        Files.createDirectories(launcherJar.getParent());
        Files.writeString(launcherJar, "launcher", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, null, launcherJar, Map.of());

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-jar",
                bundledJar.toAbsolutePath().normalize().toString()), processStarter.commands().getFirst());
    }

    @Test
    void shouldPreferBundledRuntimeJarFromEnvironment(@TempDir Path tempDir) throws Exception {
        Path bundledJar = tempDir.resolve("from-env.jar");
        Files.writeString(bundledJar, "payload", StandardCharsets.UTF_8);
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(
                Map.of(
                        "UPDATE_PATH", tempDir.toString(),
                        "GOLEMCORE_BUNDLED_JAR", bundledJar.toString()),
                processStarter,
                new NoOpLauncherOutput(),
                null,
                null,
                Map.of());

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-jar",
                bundledJar.toAbsolutePath().normalize().toString()), processStarter.commands().getFirst());
    }

    @Test
    void shouldIgnoreNonJarBundledRuntimeAndFallbackToClasspath(@TempDir Path tempDir) throws Exception {
        Path bundledFile = tempDir.resolve("runtime.txt");
        Files.writeString(bundledFile, "payload", StandardCharsets.UTF_8);
        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, null, bundledFile, Map.of());

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-cp",
                "@/app/jib-classpath-file",
                "me.golemcore.bot.BotApplication"), processStarter.commands().getFirst());
    }

    @Test
    void shouldLaunchBundledRuntimeWhenImageVersionIsNewerThanCurrentMarker(@TempDir Path tempDir) throws Exception {
        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Path jarPath = jarsDir.resolve("bot-0.4.1.jar");
        Files.writeString(jarPath, "payload", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.1.jar\n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, "0.4.2");

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-cp",
                "@/app/jib-classpath-file",
                "me.golemcore.bot.BotApplication"), processStarter.commands().getFirst());
    }

    @Test
    void shouldLaunchUpdatedJarWhenVersionsAreEqual(@TempDir Path tempDir) throws Exception {
        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Path jarPath = jarsDir.resolve("bot-0.4.2.jar");
        Files.writeString(jarPath, "payload", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.2.jar\n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, "0.4.2");

        int exitCode = launcher.run(new String[] { "--spring.profiles.active=prod" });

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-jar",
                jarPath.toAbsolutePath().normalize().toString(),
                "--spring.profiles.active=prod"), processStarter.commands().getFirst());
    }

    @Test
    void shouldLaunchUpdatedJarWhenCurrentMarkerVersionIsNewerThanImageVersion(@TempDir Path tempDir) throws Exception {
        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Path jarPath = jarsDir.resolve("bot-0.4.3.jar");
        Files.writeString(jarPath, "payload", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.3.jar\n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, "0.4.2");

        int exitCode = launcher.run(new String[] { "--spring.profiles.active=prod" });

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-jar",
                jarPath.toAbsolutePath().normalize().toString(),
                "--spring.profiles.active=prod"), processStarter.commands().getFirst());
    }

    @Test
    void shouldLaunchBundledRuntimeWhenCurrentMarkerIsBlank(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("current.txt"), "   \n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter);

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-cp",
                "@/app/jib-classpath-file",
                "me.golemcore.bot.BotApplication"), processStarter.commands().getFirst());
    }

    @Test
    void shouldFallbackToBundledRuntimeWhenCurrentMarkerUsesBackslashes(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("current.txt"), "subdir\\bot-0.4.2.jar\n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        CapturingLauncherOutput output = new CapturingLauncherOutput();
        RuntimeLauncher launcher = createLauncher(Map.of("UPDATE_PATH", tempDir.toString()), processStarter, output);

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertTrue(
                output.errorMessages().contains("Ignoring invalid current marker asset name: subdir\\bot-0.4.2.jar"));
    }

    @Test
    void shouldKeepCurrentJarWhenBundledVersionIsNotSemantic(@TempDir Path tempDir) throws Exception {
        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Path jarPath = jarsDir.resolve("bot-0.4.2.jar");
        Files.writeString(jarPath, "payload", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.2.jar\n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, "dev");

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-jar",
                jarPath.toAbsolutePath().normalize().toString()), processStarter.commands().getFirst());
    }

    @Test
    void shouldKeepCurrentJarWhenCurrentMarkerVersionIsNotSemantic(@TempDir Path tempDir) throws Exception {
        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Path jarPath = jarsDir.resolve("bot-latest.jar");
        Files.writeString(jarPath, "payload", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-latest.jar\n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, "0.4.2");

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-jar",
                jarPath.toAbsolutePath().normalize().toString()), processStarter.commands().getFirst());
    }

    @Test
    void shouldPreferSystemPropertyUpdatePathWhenArgumentIsMissing(@TempDir Path tempDir) {
        Path systemUpdatesDir = tempDir.resolve("system-updates");
        RuntimeLauncher launcher = createLauncher(
                Map.of("UPDATE_PATH", tempDir.resolve("env-updates").toString()),
                new RecordingProcessStarter(List.of(0)),
                new NoOpLauncherOutput(),
                null,
                null,
                Map.of(RuntimeLauncher.UPDATE_PATH_PROPERTY, systemUpdatesDir.toString()));

        Path updatesDir = launcher.resolveUpdatesDir(new String[0]);

        assertEquals(systemUpdatesDir.toAbsolutePath().normalize(), updatesDir);
    }

    @Test
    void shouldPreferSystemPropertyStoragePathWhenUpdatePathIsMissing(@TempDir Path tempDir) {
        Path storagePath = tempDir.resolve("system-workspace");
        RuntimeLauncher launcher = createLauncher(
                Map.of(),
                new RecordingProcessStarter(List.of(0)),
                new NoOpLauncherOutput(),
                null,
                null,
                Map.of(RuntimeLauncher.STORAGE_PATH_PROPERTY, storagePath.toString()));

        Path updatesDir = launcher.resolveUpdatesDir(new String[0]);

        assertEquals(storagePath.resolve("updates").toAbsolutePath().normalize(), updatesDir);
    }

    @Test
    void shouldExpandTildeInUpdatePathArgument(@TempDir Path tempDir) {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            RuntimeLauncher launcher = createLauncher(
                    Map.of(),
                    new RecordingProcessStarter(List.of(0)),
                    new NoOpLauncherOutput());

            Path updatesDir = launcher.resolveUpdatesDir(new String[] { "--bot.update.updates-path=~/custom-updates" });

            assertEquals(tempDir.resolve("custom-updates").toAbsolutePath().normalize(), updatesDir);
        } finally {
            restoreSystemProperty("user.home", previousHome);
        }
    }

    @Test
    void shouldExpandUserHomePlaceholderInStoragePathEnvironment(@TempDir Path tempDir) {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            RuntimeLauncher launcher = createLauncher(
                    Map.of("STORAGE_PATH", "${user.home}/workspace-root"),
                    new RecordingProcessStarter(List.of(0)),
                    new NoOpLauncherOutput());

            Path updatesDir = launcher.resolveUpdatesDir(new String[0]);

            assertEquals(tempDir.resolve("workspace-root").resolve("updates").toAbsolutePath().normalize(), updatesDir);
        } finally {
            restoreSystemProperty("user.home", previousHome);
        }
    }

    @Test
    void shouldRestartIntoUpdatedJarAfterExitCode42(@TempDir Path tempDir) {
        RestartingProcessStarter processStarter = new RestartingProcessStarter(tempDir);
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, null, null, Map.of());

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(2, processStarter.commands().size());
        assertEquals(List.of(
                "java",
                "-cp",
                "@/app/jib-classpath-file",
                "me.golemcore.bot.BotApplication"), processStarter.commands().get(0));
        assertEquals(List.of(
                "java",
                "-jar",
                tempDir.resolve("jars").resolve("bot-0.4.2.jar").toAbsolutePath().normalize().toString()),
                processStarter.commands().get(1));
    }

    @Test
    void shouldFallbackToBundledRuntimeWhenCurrentMarkerPointsOutsideUpdatesDir(@TempDir Path tempDir)
            throws Exception {
        Files.writeString(tempDir.resolve("current.txt"), "../escape.jar\n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        CapturingLauncherOutput output = new CapturingLauncherOutput();
        RuntimeLauncher launcher = createLauncher(Map.of("UPDATE_PATH", tempDir.toString()), processStarter, output,
                null, null, Map.of());

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-cp",
                "@/app/jib-classpath-file",
                "me.golemcore.bot.BotApplication"), processStarter.commands().getFirst());
        assertTrue(output.errorMessages().contains("Ignoring invalid current marker asset name: ../escape.jar"));
    }

    @Test
    void shouldFallbackToBundledRuntimeWhenCurrentJarIsMissing(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.2.jar\n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        CapturingLauncherOutput output = new CapturingLauncherOutput();
        RuntimeLauncher launcher = createLauncher(Map.of("UPDATE_PATH", tempDir.toString()), processStarter, output,
                null, null, Map.of());

        int exitCode = launcher.run(new String[0]);

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-cp",
                "@/app/jib-classpath-file",
                "me.golemcore.bot.BotApplication"), processStarter.commands().getFirst());
        assertTrue(output.errorMessages().stream()
                .anyMatch(message -> message.contains("Current marker points to a missing jar")));
    }

    @Test
    void shouldPreferExplicitUpdatePathArgumentOverEnvironment(@TempDir Path tempDir) {
        Path argUpdatesDir = tempDir.resolve("arg-updates");
        Path envUpdatesDir = tempDir.resolve("env-updates");
        RuntimeLauncher launcher = createLauncher(
                Map.of(
                        "UPDATE_PATH", envUpdatesDir.toString(),
                        "STORAGE_PATH", tempDir.resolve("workspace").toString()),
                new RecordingProcessStarter(List.of(0)),
                new NoOpLauncherOutput(),
                null,
                null,
                Map.of());

        Path updatesDir = launcher.resolveUpdatesDir(new String[] {
                "--updates-path=" + argUpdatesDir
        });

        assertEquals(argUpdatesDir.toAbsolutePath().normalize(), updatesDir);
    }

    @Test
    void shouldDeriveUpdatesDirFromStoragePathWhenUpdatePathIsMissing(@TempDir Path tempDir) {
        Path storagePath = tempDir.resolve("workspace");
        RuntimeLauncher launcher = createLauncher(
                Map.of("STORAGE_PATH", storagePath.toString()),
                new RecordingProcessStarter(List.of(0)),
                new NoOpLauncherOutput(),
                null,
                null,
                Map.of());

        Path updatesDir = launcher.resolveUpdatesDir(new String[0]);

        assertEquals(storagePath.resolve("updates").toAbsolutePath().normalize(), updatesDir);
    }

    @Test
    void shouldParsePicocliLauncherArguments() {
        RuntimeLauncher launcher = createLauncher(
                Map.of(),
                new RecordingProcessStarter(List.of(0)),
                new NoOpLauncherOutput(),
                null,
                null,
                Map.of());

        ParseOutcome parseOutcome = launcher.parseArguments(new String[] {
                "--storage-path=/srv/workspace",
                "--updates-path=/srv/updates",
                "--bundled-jar=/opt/bot.jar",
                "--server-port=9090",
                "-J=-Xmx512m",
                "-Dspring.profiles.active=prod",
                "--spring.main.banner-mode=off"
        });

        assertFalse(parseOutcome.shouldExit());
        LauncherArguments launcherArguments = parseOutcome.launcherArguments();
        assertNotNull(launcherArguments);
        assertEquals("/srv/workspace", launcherArguments.storagePath());
        assertEquals("/srv/updates", launcherArguments.updatesPath());
        assertEquals("/opt/bot.jar", launcherArguments.bundledJar());
        assertEquals("9090", launcherArguments.serverPort());
        assertIterableEquals(List.of("-Xmx512m", "-Dspring.profiles.active=prod"),
                launcherArguments.explicitJavaOptions());
        assertIterableEquals(List.of("--spring.main.banner-mode=off"), launcherArguments.applicationArguments());
    }

    @Test
    void shouldCaptureInfoOutputWhenVersionRequested() {
        CapturingLauncherOutput output = new CapturingLauncherOutput();
        RuntimeLauncher launcher = createLauncher(
                Map.of(),
                new RecordingProcessStarter(List.of(0)),
                output,
                null,
                null,
                Map.of());

        ParseOutcome parseOutcome = launcher.parseArguments(new String[] { "--version" });

        assertTrue(parseOutcome.shouldExit());
        assertEquals(0, parseOutcome.exitCode());
        assertTrue(output.infoMessages().stream()
                .anyMatch(message -> message.contains("golemcore-bot native launcher")));
    }

    @Test
    void shouldReturnZeroWhenUsageHelpRequested() {
        RuntimeLauncher launcher = createLauncher(
                Map.of(),
                new RecordingProcessStarter(List.of(0)),
                new NoOpLauncherOutput(),
                null,
                null,
                Map.of());

        ParseOutcome parseOutcome = launcher.parseArguments(new String[] { "--help" });

        assertTrue(parseOutcome.shouldExit());
        assertEquals(0, parseOutcome.exitCode());
    }

    @Test
    void shouldPassThroughUnknownArgumentsToSpringRuntime() {
        RuntimeLauncher launcher = createLauncher(
                Map.of(),
                new RecordingProcessStarter(List.of(0)),
                new NoOpLauncherOutput(),
                null,
                null,
                Map.of());

        ParseOutcome parseOutcome = launcher.parseArguments(new String[] {
                "--unknown-launcher-flag",
                "value"
        });

        assertFalse(parseOutcome.shouldExit());
        assertIterableEquals(List.of("--unknown-launcher-flag", "value"),
                parseOutcome.launcherArguments().applicationArguments());
    }

    @Test
    void shouldReturnFailureWhenChildProcessCannotStart(@TempDir Path tempDir) {
        CapturingLauncherOutput output = new CapturingLauncherOutput();
        RuntimeLauncher launcher = createLauncher(
                Map.of("UPDATE_PATH", tempDir.toString()),
                new FailingProcessStarter(new IOException("boom")),
                output,
                null,
                null,
                Map.of());

        int exitCode = launcher.run(new String[0]);

        assertEquals(1, exitCode);
        assertTrue(output.errorMessages().contains("Failed to start runtime: boom"));
    }

    @Test
    void shouldDestroyChildAndReturnFailureWhenLauncherThreadIsInterrupted(@TempDir Path tempDir) {
        InterruptingChildProcess childProcess = new InterruptingChildProcess();
        CapturingLauncherOutput output = new CapturingLauncherOutput();
        RuntimeLauncher launcher = createLauncher(
                Map.of("UPDATE_PATH", tempDir.toString()),
                new SingleChildProcessStarter(childProcess),
                output,
                null,
                null,
                Map.of());

        try {
            int exitCode = launcher.run(new String[0]);

            assertEquals(1, exitCode);
            assertTrue(childProcess.isDestroyed());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(output.errorMessages().contains("Runtime launcher interrupted while waiting for child process"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldReadRuntimeVersionFromContextClassLoaderWhenResourceExists() {
        RuntimeVersionReader reader = new ClasspathRuntimeVersionReader();
        ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new SingleResourceClassLoader("build.version=1.2.3\n"));
        try {
            assertEquals("1.2.3", reader.currentVersion());
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
    }

    @Test
    void shouldReturnBundledBuildVersionWhenContextClassLoaderDoesNotProvideBuildInfo() {
        RuntimeVersionReader reader = new ClasspathRuntimeVersionReader();
        ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new EmptyResourceClassLoader());
        try {
            assertEquals(expectedSystemBuildVersion(), reader.currentVersion());
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
    }

    @Test
    void shouldReturnDevWhenBuildInfoContainsBlankVersion() {
        RuntimeVersionReader reader = new ClasspathRuntimeVersionReader();
        ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new SingleResourceClassLoader("build.version=   \n"));
        try {
            assertEquals("dev", reader.currentVersion());
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void shouldWriteLauncherDiagnosticsToConsoleStreams() {
        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;

        try (PrintStream out = new PrintStream(standardOutput, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(errorOutput, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);

            LauncherOutput output = new ConsoleLauncherOutput();
            output.info("ready");
            output.error("failed");

            assertEquals("[launcher] ready" + System.lineSeparator(), standardOutput.toString(StandardCharsets.UTF_8));
            assertEquals("[launcher] failed" + System.lineSeparator(), errorOutput.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }

    @Test
    void shouldReadCurrentProcessPropertiesAndEnvironment() {
        String propertyName = "golemcore.launcher.test.property";
        String previousValue = System.getProperty(propertyName);
        try {
            System.setProperty(propertyName, "configured");

            assertEquals("configured", new SystemPropertyReader().get(propertyName));
            assertEquals(
                    System.getenv("GOLEMCORE_LAUNCHER_TEST_ENV_THAT_SHOULD_NOT_EXIST"),
                    new SystemEnvironmentReader().get("GOLEMCORE_LAUNCHER_TEST_ENV_THAT_SHOULD_NOT_EXIST"));
        } finally {
            restoreSystemProperty(propertyName, previousValue);
        }
    }

    @Test
    void shouldPreferConfiguredJavaCommandProperty(@TempDir Path tempDir) {
        Path javaCommand = tempDir.resolve("runtime").resolve("bin").resolve("java");

        String resolvedCommand = RuntimeLauncher.resolveJavaCommand(
                new MapEnvironmentReader(Map.of(RuntimeLauncher.JAVA_COMMAND_ENV, "java-from-env")),
                new MapPropertyReader(Map.of(RuntimeLauncher.JAVA_COMMAND_PROPERTY, javaCommand.toString())));

        assertEquals(javaCommand.toAbsolutePath().normalize().toString(), resolvedCommand);
    }

    @Test
    void shouldUseConfiguredJavaCommandEnvironmentWhenPropertyIsMissing() {
        String resolvedCommand = RuntimeLauncher.resolveJavaCommand(
                new MapEnvironmentReader(Map.of(RuntimeLauncher.JAVA_COMMAND_ENV, "custom-java")),
                new MapPropertyReader(Map.of()));

        assertEquals("custom-java", resolvedCommand);
    }

    @Test
    void shouldResolveDefaultBundledRuntimeCodeSource() {
        Path resolvedLocation = new DefaultBundledRuntimeResolver().resolve();

        assertNotNull(resolvedLocation);
        assertTrue(resolvedLocation.isAbsolute());
    }

    @Test
    void shouldWrapJvmProcessAsChildProcess() throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "-version").start();
        ChildProcess childProcess = new ProcessChildProcess(process);

        assertEquals(0, childProcess.waitFor());
        childProcess.destroy();
    }

    private static String expectedSystemBuildVersion() {
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream(RuntimeLauncher.BUILD_INFO_RESOURCE)) {
            if (inputStream == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(inputStream);
            String version = properties.getProperty("build.version");
            return version == null || version.isBlank() ? "dev" : version;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read expected system build version", exception);
        }
    }

    private static String buildInfoResourceName() {
        return RuntimeLauncher.BUILD_INFO_RESOURCE;
    }

    private static String javaExecutable() {
        String executableName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", executableName);
        return Files.isRegularFile(candidate) ? candidate.toAbsolutePath().normalize().toString() : executableName;
    }

    private RuntimeLauncher createLauncher(Path updatesDir, RecordingProcessStarter processStarter) {
        return createLauncher(updatesDir, processStarter, "0.4.2");
    }

    private RuntimeLauncher createLauncher(Path updatesDir, RecordingProcessStarter processStarter,
            String runtimeVersion) {
        return createLauncher(updatesDir, processStarter, null, null, Map.of(), runtimeVersion);
    }

    /**
     * Creates a launcher that uses an isolated updates directory with optional
     * bundled runtime overrides.
     */
    private RuntimeLauncher createLauncher(
            Path updatesDir,
            RecordingProcessStarter processStarter,
            Path configuredBundledJar,
            Path resolvedBundledJar,
            Map<String, String> properties) {
        return createLauncher(updatesDir, processStarter, configuredBundledJar, resolvedBundledJar, properties,
                "0.4.2");
    }

    private RuntimeLauncher createLauncher(
            Path updatesDir,
            RecordingProcessStarter processStarter,
            Path configuredBundledJar,
            Path resolvedBundledJar,
            Map<String, String> properties,
            String runtimeVersion) {
        return createLauncher(
                Map.of("UPDATE_PATH", updatesDir.toString()),
                processStarter,
                new NoOpLauncherOutput(),
                configuredBundledJar,
                resolvedBundledJar,
                properties,
                runtimeVersion);
    }

    /**
     * Creates a launcher wired to deterministic test doubles for environment,
     * system properties, process startup, and bundled runtime discovery.
     */
    private RuntimeLauncher createLauncher(
            Map<String, String> environment,
            ProcessStarter processStarter,
            LauncherOutput output) {
        return createLauncher(environment, processStarter, output, null, null, Map.of(), "0.4.2");
    }

    private RuntimeLauncher createLauncher(
            Map<String, String> environment,
            ProcessStarter processStarter,
            LauncherOutput output,
            Path configuredBundledJar,
            Path resolvedBundledJar,
            Map<String, String> properties) {
        return createLauncher(environment, processStarter, output, configuredBundledJar, resolvedBundledJar, properties,
                "0.4.2");
    }

    private RuntimeLauncher createLauncher(
            Map<String, String> environment,
            ProcessStarter processStarter,
            LauncherOutput output,
            Path configuredBundledJar,
            Path resolvedBundledJar,
            Map<String, String> properties,
            String runtimeVersion) {
        Map<String, String> effectiveEnvironment = new HashMap<>(environment);
        if (configuredBundledJar != null) {
            effectiveEnvironment.put("GOLEMCORE_BUNDLED_JAR", configuredBundledJar.toString());
        }
        return new RuntimeLauncher(
                "java",
                processStarter,
                new MapEnvironmentReader(effectiveEnvironment),
                new MapPropertyReader(properties),
                output,
                new FixedBundledRuntimeResolver(resolvedBundledJar),
                new FixedRuntimeVersionReader(runtimeVersion));
    }

    private static void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }

    /**
     * Records every command sent to the child process and replays configured exit
     * codes in sequence.
     */
    private static class RecordingProcessStarter implements ProcessStarter {

        private final List<Integer> exitCodes;
        private final List<List<String>> recordedCommands = new ArrayList<>();
        private int cursor;

        private RecordingProcessStarter(List<Integer> exitCodes) {
            this.exitCodes = new ArrayList<>(exitCodes);
        }

        @Override
        public ChildProcess start(List<String> command) throws IOException {
            recordedCommands.add(List.copyOf(command));
            int exitCode = exitCodes.get(cursor);
            cursor++;
            return new FixedExitChildProcess(exitCode);
        }

        protected List<List<String>> commands() {
            return recordedCommands;
        }
    }

    /**
     * Simulates a self-update by writing the staged jar marker after the first
     * launcher cycle returns the restart exit code.
     */
    private static final class RestartingProcessStarter extends RecordingProcessStarter {

        private final Path updatesDir;

        private RestartingProcessStarter(Path updatesDir) {
            super(List.of(RuntimeLauncher.RESTART_EXIT_CODE, 0));
            this.updatesDir = updatesDir;
        }

        @Override
        public ChildProcess start(List<String> command) throws IOException {
            ChildProcess childProcess = super.start(command);
            if (commands().size() == 1) {
                Path jarsDir = updatesDir.resolve("jars");
                Files.createDirectories(jarsDir);
                Files.writeString(jarsDir.resolve("bot-0.4.2.jar"), "payload", StandardCharsets.UTF_8);
                Files.writeString(updatesDir.resolve("current.txt"), "bot-0.4.2.jar\n", StandardCharsets.UTF_8);
            }
            return childProcess;
        }
    }

    /**
     * Returns a fixed exit code without spawning a real process.
     */
    private static final class FixedExitChildProcess implements ChildProcess {

        private final int exitCode;

        private FixedExitChildProcess(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public void destroy() {
            // Nothing to destroy in tests because no external process exists.
        }
    }

    /**
     * Always returns the same child process instance.
     */
    private static final class SingleChildProcessStarter implements ProcessStarter {

        private final ChildProcess childProcess;

        private SingleChildProcessStarter(ChildProcess childProcess) {
            this.childProcess = childProcess;
        }

        @Override
        public ChildProcess start(List<String> command) {
            return childProcess;
        }
    }

    /**
     * Fails immediately when the launcher tries to spawn a child process.
     */
    private static final class FailingProcessStarter implements ProcessStarter {

        private final IOException failure;

        private FailingProcessStarter(IOException failure) {
            this.failure = failure;
        }

        @Override
        public ChildProcess start(List<String> command) throws IOException {
            throw failure;
        }
    }

    /**
     * Simulates an interrupted wait so the launcher can prove it destroys the child
     * process and preserves the interrupt flag.
     */
    private static final class InterruptingChildProcess implements ChildProcess {

        private boolean destroyed;

        @Override
        public int waitFor() throws InterruptedException {
            throw new InterruptedException("simulated interruption");
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        private boolean isDestroyed() {
            return destroyed;
        }
    }

    /**
     * In-memory environment source for deterministic tests.
     */
    private static final class MapEnvironmentReader implements EnvironmentReader {

        private final Map<String, String> values;

        private MapEnvironmentReader(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public String get(String name) {
            return values.get(name);
        }
    }

    /**
     * In-memory system property source for deterministic tests.
     */
    private static final class MapPropertyReader implements PropertyReader {

        private final Map<String, String> values;

        private MapPropertyReader(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public String get(String name) {
            return values.get(name);
        }
    }

    private static final class FixedRuntimeVersionReader implements RuntimeVersionReader {

        private final String version;

        private FixedRuntimeVersionReader(String version) {
            this.version = version;
        }

        @Override
        public String currentVersion() {
            return version;
        }
    }

    /**
     * Discards launcher logs when the test does not assert on them.
     */
    private static final class NoOpLauncherOutput implements LauncherOutput {

        @Override
        public void info(String message) {
            // Intentionally ignored to keep successful test output quiet.
        }

        @Override
        public void error(String message) {
            // Intentionally ignored to keep successful test output quiet.
        }
    }

    /**
     * Collects launcher output for assertion-friendly verification.
     */
    private static final class CapturingLauncherOutput implements LauncherOutput {

        private final List<String> capturedInfoMessages = new ArrayList<>();
        private final List<String> capturedErrorMessages = new ArrayList<>();

        @Override
        public void info(String message) {
            capturedInfoMessages.add(message);
        }

        @Override
        public void error(String message) {
            capturedErrorMessages.add(message);
        }

        private List<String> infoMessages() {
            return capturedInfoMessages;
        }

        private List<String> errorMessages() {
            return capturedErrorMessages;
        }
    }

    /**
     * Returns a fixed code-source location so bundled runtime discovery can be
     * tested without relying on the real test JVM layout.
     */
    private static final class FixedBundledRuntimeResolver implements BundledRuntimeResolver {

        private final Path bundledJar;

        private FixedBundledRuntimeResolver(Path bundledJar) {
            this.bundledJar = bundledJar;
        }

        @Override
        public Path resolve() {
            return bundledJar;
        }
    }

    private static final class SingleResourceClassLoader extends ClassLoader {

        private final byte[] content;

        private SingleResourceClassLoader(String content) {
            super(null);
            this.content = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (buildInfoResourceName().equals(name)) {
                return new java.io.ByteArrayInputStream(content);
            }
            return null;
        }
    }

    private static final class EmptyResourceClassLoader extends ClassLoader {

        private EmptyResourceClassLoader() {
            super(null);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return null;
        }
    }
}

package me.golemcore.bot.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                Map.of(),
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
    void shouldLaunchUpdatedJarWhenCurrentMarkerPointsToExistingJar(@TempDir Path tempDir) throws Exception {
        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Path jarPath = jarsDir.resolve("bot-0.4.2.jar");
        Files.writeString(jarPath, "payload", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.2.jar\n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter, null, null, Map.of());

        int exitCode = launcher.run(new String[] { "--spring.profiles.active=prod" });

        assertEquals(0, exitCode);
        assertEquals(List.of(
                "java",
                "-jar",
                jarPath.toAbsolutePath().normalize().toString(),
                "--spring.profiles.active=prod"), processStarter.commands().getFirst());
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

        RuntimeLauncher.ParseOutcome parseOutcome = launcher.parseArguments(new String[] {
                "--storage-path=/srv/workspace",
                "--updates-path=/srv/updates",
                "--bundled-jar=/opt/bot.jar",
                "--server-port=9090",
                "-J=-Xmx512m",
                "-Dspring.profiles.active=prod",
                "--spring.main.banner-mode=off"
        });

        assertFalse(parseOutcome.shouldExit());
        RuntimeLauncher.LauncherArguments launcherArguments = parseOutcome.launcherArguments();
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
    void shouldReturnZeroWhenUsageHelpRequested() {
        RuntimeLauncher launcher = createLauncher(
                Map.of(),
                new RecordingProcessStarter(List.of(0)),
                new NoOpLauncherOutput(),
                null,
                null,
                Map.of());

        RuntimeLauncher.ParseOutcome parseOutcome = launcher.parseArguments(new String[] { "--help" });

        assertTrue(parseOutcome.shouldExit());
        assertEquals(0, parseOutcome.exitCode());
    }

    @Test
    void shouldReturnCliErrorWhenUnknownLauncherOptionLooksLikeLauncherFlag() {
        CapturingLauncherOutput output = new CapturingLauncherOutput();
        RuntimeLauncher launcher = createLauncher(
                Map.of(),
                new RecordingProcessStarter(List.of(0)),
                output,
                null,
                null,
                Map.of());

        RuntimeLauncher.ParseOutcome parseOutcome = launcher.parseArguments(new String[] {
                "--unknown-launcher-flag"
        });

        assertTrue(parseOutcome.shouldExit());
        assertEquals(RuntimeLauncher.CLI_ERROR_EXIT_CODE, parseOutcome.exitCode());
        assertTrue(output.errorMessages().stream()
                .anyMatch(message -> message
                        .contains("Missing required parameter for option '--unknown-launcher-flag'")));
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
        return createLauncher(
                Map.of("UPDATE_PATH", updatesDir.toString()),
                processStarter,
                new NoOpLauncherOutput(),
                configuredBundledJar,
                resolvedBundledJar,
                properties);
    }

    /**
     * Creates a launcher wired to deterministic test doubles for environment,
     * system properties, process startup, and bundled runtime discovery.
     */
    private RuntimeLauncher createLauncher(
            Map<String, String> environment,
            RuntimeLauncher.ProcessStarter processStarter,
            RuntimeLauncher.LauncherOutput output,
            Path configuredBundledJar,
            Path resolvedBundledJar,
            Map<String, String> properties) {
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
                new FixedBundledRuntimeResolver(resolvedBundledJar));
    }

    /**
     * Records every command sent to the child process and replays configured exit
     * codes in sequence.
     */
    private static class RecordingProcessStarter implements RuntimeLauncher.ProcessStarter {

        private final List<Integer> exitCodes;
        private final List<List<String>> recordedCommands = new ArrayList<>();
        private int cursor;

        private RecordingProcessStarter(List<Integer> exitCodes) {
            this.exitCodes = new ArrayList<>(exitCodes);
        }

        @Override
        public RuntimeLauncher.ChildProcess start(List<String> command) throws IOException {
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
        public RuntimeLauncher.ChildProcess start(List<String> command) throws IOException {
            RuntimeLauncher.ChildProcess childProcess = super.start(command);
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
    private static final class FixedExitChildProcess implements RuntimeLauncher.ChildProcess {

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
    private static final class SingleChildProcessStarter implements RuntimeLauncher.ProcessStarter {

        private final RuntimeLauncher.ChildProcess childProcess;

        private SingleChildProcessStarter(RuntimeLauncher.ChildProcess childProcess) {
            this.childProcess = childProcess;
        }

        @Override
        public RuntimeLauncher.ChildProcess start(List<String> command) {
            return childProcess;
        }
    }

    /**
     * Fails immediately when the launcher tries to spawn a child process.
     */
    private static final class FailingProcessStarter implements RuntimeLauncher.ProcessStarter {

        private final IOException failure;

        private FailingProcessStarter(IOException failure) {
            this.failure = failure;
        }

        @Override
        public RuntimeLauncher.ChildProcess start(List<String> command) throws IOException {
            throw failure;
        }
    }

    /**
     * Simulates an interrupted wait so the launcher can prove it destroys the child
     * process and preserves the interrupt flag.
     */
    private static final class InterruptingChildProcess implements RuntimeLauncher.ChildProcess {

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
    private static final class MapEnvironmentReader implements RuntimeLauncher.EnvironmentReader {

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
    private static final class MapPropertyReader implements RuntimeLauncher.PropertyReader {

        private final Map<String, String> values;

        private MapPropertyReader(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public String get(String name) {
            return values.get(name);
        }
    }

    /**
     * Discards launcher logs when the test does not assert on them.
     */
    private static final class NoOpLauncherOutput implements RuntimeLauncher.LauncherOutput {

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
    private static final class CapturingLauncherOutput implements RuntimeLauncher.LauncherOutput {

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
    private static final class FixedBundledRuntimeResolver implements RuntimeLauncher.BundledRuntimeResolver {

        private final Path bundledJar;

        private FixedBundledRuntimeResolver(Path bundledJar) {
            this.bundledJar = bundledJar;
        }

        @Override
        public Path resolve() {
            return bundledJar;
        }
    }
}

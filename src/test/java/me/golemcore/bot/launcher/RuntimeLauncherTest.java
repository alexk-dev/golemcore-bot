package me.golemcore.bot.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLauncherTest {

    @Test
    void shouldLaunchBundledRuntimeWhenCurrentMarkerIsMissing(@TempDir Path tempDir) {
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
    void shouldRestartIntoUpdatedJarAfterExitCode42(@TempDir Path tempDir) {
        RestartingProcessStarter processStarter = new RestartingProcessStarter(tempDir);
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter);

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
        RuntimeLauncher launcher = createLauncher(Map.of("UPDATE_PATH", tempDir.toString()), processStarter, output);

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
        RuntimeLauncher launcher = createLauncher(Map.of("UPDATE_PATH", tempDir.toString()), processStarter, output);

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
                new NoOpLauncherOutput());

        Path updatesDir = launcher.resolveUpdatesDir(new String[] {
                "--bot.update.updates-path=" + argUpdatesDir
        });

        assertEquals(argUpdatesDir.toAbsolutePath().normalize(), updatesDir);
    }

    @Test
    void shouldDeriveUpdatesDirFromStoragePathWhenUpdatePathIsMissing(@TempDir Path tempDir) {
        Path storagePath = tempDir.resolve("workspace");
        RuntimeLauncher launcher = createLauncher(
                Map.of("STORAGE_PATH", storagePath.toString()),
                new RecordingProcessStarter(List.of(0)),
                new NoOpLauncherOutput());

        Path updatesDir = launcher.resolveUpdatesDir(new String[0]);

        assertEquals(storagePath.resolve("updates").toAbsolutePath().normalize(), updatesDir);
    }

    @Test
    void shouldReturnFailureWhenChildProcessCannotStart(@TempDir Path tempDir) {
        CapturingLauncherOutput output = new CapturingLauncherOutput();
        RuntimeLauncher launcher = createLauncher(
                Map.of("UPDATE_PATH", tempDir.toString()),
                new FailingProcessStarter(new IOException("boom")),
                output);

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
                output);

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

    private RuntimeLauncher createLauncher(Path updatesDir, RecordingProcessStarter processStarter) {
        return createLauncher(updatesDir, processStarter, "0.4.2");
    }

    private RuntimeLauncher createLauncher(Path updatesDir, RecordingProcessStarter processStarter,
            String runtimeVersion) {
        return createLauncher(
                Map.of("UPDATE_PATH", updatesDir.toString()),
                processStarter,
                new NoOpLauncherOutput(),
                runtimeVersion);
    }

    private RuntimeLauncher createLauncher(
            Map<String, String> environment,
            RuntimeLauncher.ProcessStarter processStarter,
            RuntimeLauncher.LauncherOutput output) {
        return createLauncher(environment, processStarter, output, "0.4.2");
    }

    private RuntimeLauncher createLauncher(
            Map<String, String> environment,
            RuntimeLauncher.ProcessStarter processStarter,
            RuntimeLauncher.LauncherOutput output,
            String runtimeVersion) {
        return new RuntimeLauncher(
                "java",
                processStarter,
                new MapEnvironmentReader(environment),
                output,
                new FixedRuntimeVersionReader(runtimeVersion));
    }

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
            // nothing to destroy in tests
        }
    }

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

    private static final class FixedRuntimeVersionReader implements RuntimeLauncher.RuntimeVersionReader {

        private final String version;

        private FixedRuntimeVersionReader(String version) {
            this.version = version;
        }

        @Override
        public String currentVersion() {
            return version;
        }
    }

    private static final class NoOpLauncherOutput implements RuntimeLauncher.LauncherOutput {

        @Override
        public void info(String message) {
            // ignore launcher logs in unit tests
        }

        @Override
        public void error(String message) {
            // ignore launcher logs in unit tests
        }
    }

    private static final class CapturingLauncherOutput implements RuntimeLauncher.LauncherOutput {

        private final List<String> capturedErrorMessages = new ArrayList<>();

        @Override
        public void info(String message) {
            // ignore info-level launcher logs in assertions
        }

        @Override
        public void error(String message) {
            capturedErrorMessages.add(message);
        }

        private List<String> errorMessages() {
            return capturedErrorMessages;
        }
    }
}

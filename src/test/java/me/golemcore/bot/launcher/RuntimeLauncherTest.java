package me.golemcore.bot.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

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
        String previousValue = System.getProperty(RuntimeLauncher.UPDATE_PATH_PROPERTY);
        Path systemUpdatesDir = tempDir.resolve("system-updates");
        System.setProperty(RuntimeLauncher.UPDATE_PATH_PROPERTY, systemUpdatesDir.toString());
        try {
            RuntimeLauncher launcher = createLauncher(
                    Map.of("UPDATE_PATH", tempDir.resolve("env-updates").toString()),
                    new RecordingProcessStarter(List.of(0)),
                    new NoOpLauncherOutput());

            Path updatesDir = launcher.resolveUpdatesDir(new String[0]);

            assertEquals(systemUpdatesDir.toAbsolutePath().normalize(), updatesDir);
        } finally {
            restoreSystemProperty(RuntimeLauncher.UPDATE_PATH_PROPERTY, previousValue);
        }
    }

    @Test
    void shouldPreferSystemPropertyStoragePathWhenUpdatePathIsMissing(@TempDir Path tempDir) {
        String previousValue = System.getProperty(RuntimeLauncher.STORAGE_PATH_PROPERTY);
        Path storagePath = tempDir.resolve("system-workspace");
        System.setProperty(RuntimeLauncher.STORAGE_PATH_PROPERTY, storagePath.toString());
        try {
            RuntimeLauncher launcher = createLauncher(
                    Map.of(),
                    new RecordingProcessStarter(List.of(0)),
                    new NoOpLauncherOutput());

            Path updatesDir = launcher.resolveUpdatesDir(new String[0]);

            assertEquals(storagePath.resolve("updates").toAbsolutePath().normalize(), updatesDir);
        } finally {
            restoreSystemProperty(RuntimeLauncher.STORAGE_PATH_PROPERTY, previousValue);
        }
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

    @Test
    void shouldReadRuntimeVersionFromContextClassLoaderWhenResourceExists() {
        RuntimeLauncher.RuntimeVersionReader reader = new RuntimeLauncher.ClasspathRuntimeVersionReader();
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
        RuntimeLauncher.RuntimeVersionReader reader = new RuntimeLauncher.ClasspathRuntimeVersionReader();
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
        RuntimeLauncher.RuntimeVersionReader reader = new RuntimeLauncher.ClasspathRuntimeVersionReader();
        ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new SingleResourceClassLoader("build.version=   \n"));
        try {
            assertEquals("dev", reader.currentVersion());
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
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

    private static void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
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

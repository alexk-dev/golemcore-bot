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
    void shouldLaunchUpdatedJarWhenCurrentMarkerPointsToExistingJar(@TempDir Path tempDir) throws Exception {
        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Path jarPath = jarsDir.resolve("bot-0.4.2.jar");
        Files.writeString(jarPath, "payload", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("current.txt"), "bot-0.4.2.jar\n", StandardCharsets.UTF_8);

        RecordingProcessStarter processStarter = new RecordingProcessStarter(List.of(0));
        RuntimeLauncher launcher = createLauncher(tempDir, processStarter);

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

    private RuntimeLauncher createLauncher(Path updatesDir, RecordingProcessStarter processStarter) {
        return new RuntimeLauncher(
                "java",
                processStarter,
                new MapEnvironmentReader(Map.of("UPDATE_PATH", updatesDir.toString())),
                new NoOpLauncherOutput());
    }

    private static class RecordingProcessStarter implements RuntimeLauncher.ProcessStarter {

        private final List<Integer> exitCodes;
        private final List<List<String>> commands = new ArrayList<>();
        private int cursor;

        private RecordingProcessStarter(List<Integer> exitCodes) {
            this.exitCodes = new ArrayList<>(exitCodes);
        }

        @Override
        public RuntimeLauncher.ChildProcess start(List<String> command) throws IOException {
            commands.add(List.copyOf(command));
            int exitCode = exitCodes.get(cursor);
            cursor++;
            return new FixedExitChildProcess(exitCode);
        }

        protected List<List<String>> commands() {
            return commands;
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
}

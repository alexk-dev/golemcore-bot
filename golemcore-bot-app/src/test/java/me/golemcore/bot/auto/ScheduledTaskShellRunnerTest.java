package me.golemcore.bot.auto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Map;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.security.InjectionGuard;
import me.golemcore.bot.tools.ShellTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduledTaskShellRunnerTest {

    @TempDir
    Path tempDir;

    private ShellTool shellTool;

    @AfterEach
    void tearDown() {
        if (shellTool != null) {
            shellTool.shutdown();
        }
    }

    @Test
    void shouldRunShellScheduledTaskThroughShellTool() throws Exception {
        RuntimeConfigService runtimeConfigService = runtimeConfigService();
        shellTool = new ShellTool(botProperties(tempDir), runtimeConfigService,
                new InjectionGuard(runtimeConfigService));
        ScheduledTaskShellRunner runner = new ScheduledTaskShellRunner(shellTool);

        ScheduledTaskShellRunner.ShellRunResult result = runner.run(
                ScheduledTask.builder()
                        .id("shell-task")
                        .title("Echo")
                        .executionMode(ScheduledTask.ExecutionMode.SHELL_COMMAND)
                        .shellCommand("printf 'hello'")
                        .build(),
                1);

        assertTrue(result.success());
        assertEquals("hello", result.summary().trim());
        assertEquals("hello", result.reportBody().trim());
    }

    @Test
    void shouldReturnStructuredFailureForShellExitCode() throws Exception {
        RuntimeConfigService runtimeConfigService = runtimeConfigService();
        shellTool = new ShellTool(botProperties(tempDir), runtimeConfigService,
                new InjectionGuard(runtimeConfigService));
        ScheduledTaskShellRunner runner = new ScheduledTaskShellRunner(shellTool);

        ScheduledTaskShellRunner.ShellRunResult result = runner.run(
                ScheduledTask.builder()
                        .id("shell-task")
                        .title("Fail")
                        .executionMode(ScheduledTask.ExecutionMode.SHELL_COMMAND)
                        .shellCommand("exit 2")
                        .build(),
                1);

        assertFalse(result.success());
        assertEquals("Command failed with exit code 2", result.summary());
        assertEquals("shell_exit_2", result.fingerprint());
    }

    private RuntimeConfigService runtimeConfigService() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isShellEnabled()).thenReturn(true);
        when(runtimeConfigService.getShellEnvironmentVariables()).thenReturn(Map.of());
        when(runtimeConfigService.isCommandInjectionDetectionEnabled()).thenReturn(false);
        return runtimeConfigService;
    }

    private BotProperties botProperties(Path workspace) {
        BotProperties properties = new BotProperties();
        properties.getTools().getShell().setWorkspace(workspace.toString());
        return properties;
    }
}

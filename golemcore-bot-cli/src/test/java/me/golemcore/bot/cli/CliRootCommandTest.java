package me.golemcore.bot.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import me.golemcore.bot.cli.adapter.in.picocli.CliGlobalOptions;
import me.golemcore.bot.cli.adapter.in.picocli.CliRootCommand;
import me.golemcore.bot.cli.config.CliAttachMode;
import me.golemcore.bot.cli.domain.CliExitCodes;
import me.golemcore.bot.cli.domain.CliCommandInvocation;
import me.golemcore.bot.cli.domain.CliCommandOptions;
import me.golemcore.bot.domain.cli.CliOutputFormat;
import me.golemcore.bot.domain.cli.CliPermissionMode;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CliRootCommandTest {

    @Test
    void shouldRegisterInitialCommandSurface() {
        CommandLine commandLine = commandLine(new StringWriter(), new StringWriter());

        assertIterableEquals(CliCommandFactory.subcommandNames(), commandLine.getSubcommands().keySet().stream()
                .filter(commandName -> !"help".equals(commandName))
                .toList());
        assertEquals(Set.of(
                "help",
                "run",
                "serve",
                "attach",
                "acp",
                "session",
                "agent",
                "auth",
                "providers",
                "models",
                "tier",
                "mcp",
                "skill",
                "plugin",
                "tool",
                "permissions",
                "project",
                "config",
                "memory",
                "rag",
                "auto",
                "lsp",
                "terminal",
                "git",
                "patch",
                "github",
                "trace",
                "stats",
                "doctor",
                "export",
                "import",
                "completion",
                "upgrade",
                "uninstall"), commandLine.getSubcommands().keySet());
    }

    @Test
    void shouldCreateDefaultCommandLineFromFactory() {
        CommandLine commandLine = CliCommandFactory.create();

        assertEquals(Set.of(
                "help",
                "run",
                "serve",
                "attach",
                "acp",
                "session",
                "agent",
                "auth",
                "providers",
                "models",
                "tier",
                "mcp",
                "skill",
                "plugin",
                "tool",
                "permissions",
                "project",
                "config",
                "memory",
                "rag",
                "auto",
                "lsp",
                "terminal",
                "git",
                "patch",
                "github",
                "trace",
                "stats",
                "doctor",
                "export",
                "import",
                "completion",
                "upgrade",
                "uninstall"), commandLine.getSubcommands().keySet());
    }

    @Test
    void shouldRenderRootHelp() {
        StringWriter out = new StringWriter();
        int exitCode = commandLine(out, new StringWriter()).execute("--help");

        assertEquals(0, exitCode);
        String help = out.toString();
        assertTrue(help.contains("Usage: cli"));
        assertTrue(help.contains("run"));
        assertTrue(help.contains("doctor"));
    }

    @Test
    void shouldRunCliApplicationEntrypoint() {
        StringWriter out = new StringWriter();
        int exitCode = CliApplication.run(new String[] { "doctor", "--json" },
                new PrintWriter(out, true),
                new PrintWriter(new StringWriter(), true));

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("\"status\":\"ok\""));
        assertTrue(out.toString().contains("\"name\":\"cli.command_surface\""));
        assertTrue(out.toString().contains("\"name\":\"java.runtime\""));
        assertTrue(out.toString().contains("\"name\":\"cli.package_boundaries\""));
    }

    @Test
    void shouldReportPendingTuiWhenNoSubcommandIsProvided() {
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true));
        int exitCode = CliApplication.commandLine(rootCommand).execute();

        assertEquals(CliExitCodes.SESSION_RUNTIME_UNAVAILABLE, exitCode);
        assertTrue(err.toString().contains("TUI runtime is not implemented in this CLI slice"));
        assertEquals("cli", rootCommand.invocation().commandName());
    }

    @Test
    void shouldReturnDeterministicNotImplementedForStubCommand() {
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true));
        int exitCode = CliApplication.commandLine(rootCommand).execute("run");

        assertEquals(CliExitCodes.SESSION_RUNTIME_UNAVAILABLE, exitCode);
        assertTrue(err.toString().contains("run is not implemented in this CLI slice"));
        assertEquals("run", rootCommand.invocation().commandName());
    }

    @Test
    void shouldReturnDeterministicNotImplementedForEveryTopLevelStubCommand() {
        for (String commandName : CliCommandFactory.subcommandNames()) {
            if ("doctor".equals(commandName)) {
                continue;
            }
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(out, true), new PrintWriter(err, true));

            int exitCode = CliApplication.commandLine(rootCommand).execute(commandName);

            assertEquals(CliExitCodes.NOT_IMPLEMENTED, exitCode, commandName);
            assertEquals("", out.toString(), commandName);
            assertTrue(err.toString().contains(commandName + " is not implemented in this CLI slice"), commandName);
            assertEquals(commandName, rootCommand.invocation().commandName(), commandName);
        }
    }

    @Test
    void shouldReturnDeterministicNotImplementedForNestedStubCommands() {
        List<List<String>> commands = List.of(
                List.of("session", "list"),
                List.of("session", "ls"),
                List.of("agent", "list"),
                List.of("agent", "ls"),
                List.of("mcp", "list"),
                List.of("mcp", "ls"));
        for (List<String> command : commands) {
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(out, true), new PrintWriter(err, true));

            int exitCode = CliApplication.commandLine(rootCommand).execute(command.toArray(String[]::new));

            String expectedCommandName = command.get(0) + " list";
            assertEquals(CliExitCodes.NOT_IMPLEMENTED, exitCode, expectedCommandName);
            assertEquals("", out.toString(), expectedCommandName);
            assertTrue(err.toString().contains(expectedCommandName + " is not implemented in this CLI slice"),
                    expectedCommandName);
            assertEquals(expectedCommandName, rootCommand.invocation().commandName(), expectedCommandName);
        }
    }

    @Test
    void shouldParseGlobalOptionsBeforeSubcommand() {
        StringWriter out = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(out, true),
                new PrintWriter(new StringWriter()));
        CommandLine commandLine = CliApplication.commandLine(rootCommand);

        int exitCode = commandLine.execute(
                "--cwd",
                "/repo",
                "--project",
                "/repo/project",
                "--workspace",
                "/workspace",
                "--config",
                "/config/runtime.json",
                "--config-dir",
                "/config",
                "--profile",
                "dev",
                "--env-file",
                ".env.local",
                "--model",
                "openai/gpt-5.2",
                "--tier",
                "coding",
                "--agent",
                "reviewer",
                "--session",
                "ses_1",
                "--continue",
                "--fork",
                "ses_0",
                "--format",
                "ndjson",
                "--json",
                "--no-color",
                "--color",
                "never",
                "--quiet",
                "--verbose",
                "--log-level",
                "debug",
                "--trace",
                "--trace-export",
                "/tmp/trace.ndjson",
                "--no-memory",
                "--no-rag",
                "--no-mcp",
                "--no-skills",
                "--permission-mode",
                "read-only",
                "--yes",
                "--no-input",
                "--timeout",
                "PT5M",
                "--max-llm-calls",
                "4",
                "--max-tool-executions",
                "8",
                "--attach",
                "required",
                "--port",
                "4096",
                "--hostname",
                "127.0.0.1",
                "-J=-Xmx1g",
                "--java-option",
                "-Dgolemcore.test=true",
                "doctor",
                "--json");

        CliGlobalOptions options = rootCommand.globalOptions();
        assertEquals(0, exitCode);
        assertEquals("/repo", options.cwd());
        assertEquals("/repo/project", options.project());
        assertEquals("/workspace", options.workspace());
        assertEquals("/config/runtime.json", options.config());
        assertEquals("/config", options.configDir());
        assertEquals("dev", options.profile());
        assertEquals(".env.local", options.envFile());
        assertEquals("openai/gpt-5.2", options.model());
        assertEquals("coding", options.tier());
        assertEquals("reviewer", options.agent());
        assertEquals("ses_1", options.session());
        assertTrue(options.continueLatest());
        assertEquals("ses_0", options.fork());
        assertEquals(CliOutputFormat.JSON, options.effectiveFormat());
        assertTrue(options.noColor());
        assertEquals("never", options.color());
        assertTrue(options.quiet());
        assertTrue(options.verbose());
        assertEquals("debug", options.logLevel());
        assertTrue(options.trace());
        assertEquals("/tmp/trace.ndjson", options.traceExport());
        assertTrue(options.noMemory());
        assertTrue(options.noRag());
        assertTrue(options.noMcp());
        assertTrue(options.noSkills());
        assertEquals(CliPermissionMode.READ_ONLY, options.permissionMode());
        assertTrue(options.yes());
        assertTrue(options.noInput());
        assertEquals("PT5M", options.timeout());
        assertEquals(4, options.maxLlmCalls());
        assertEquals(8, options.maxToolExecutions());
        assertEquals(CliAttachMode.REQUIRED, options.attach());
        assertEquals(4096, options.port());
        assertEquals("127.0.0.1", options.hostname());
        assertIterableEquals(List.of("-Xmx1g", "-Dgolemcore.test=true"), options.javaOptions());

        CliCommandInvocation invocation = rootCommand.invocation();
        assertNotNull(invocation);
        assertEquals("doctor", invocation.commandName());
        CliCommandOptions invocationOptions = invocation.options();
        assertEquals(Path.of("/repo"), invocationOptions.cwd());
        assertEquals(Path.of("/repo/project"), invocationOptions.project());
        assertEquals(Path.of("/workspace"), invocationOptions.workspace());
        assertEquals(Path.of("/config/runtime.json"), invocationOptions.config());
        assertEquals(Path.of("/config"), invocationOptions.configDir());
        assertEquals("dev", invocationOptions.profile());
        assertEquals(Path.of(".env.local"), invocationOptions.envFile());
        assertEquals("openai/gpt-5.2", invocationOptions.model());
        assertEquals("coding", invocationOptions.tier());
        assertEquals("reviewer", invocationOptions.agent());
        assertEquals("ses_1", invocationOptions.session());
        assertTrue(invocationOptions.continueSession());
        assertEquals("ses_0", invocationOptions.fork());
        assertEquals("json", invocationOptions.format());
        assertTrue(invocationOptions.json());
        assertTrue(invocationOptions.noColor());
        assertEquals("never", invocationOptions.color());
        assertTrue(invocationOptions.quiet());
        assertTrue(invocationOptions.verbose());
        assertEquals("debug", invocationOptions.logLevel());
        assertTrue(invocationOptions.trace());
        assertEquals(Path.of("/tmp/trace.ndjson"), invocationOptions.traceExport());
        assertTrue(invocationOptions.noMemory());
        assertTrue(invocationOptions.noRag());
        assertTrue(invocationOptions.noMcp());
        assertTrue(invocationOptions.noSkills());
        assertEquals("read-only", invocationOptions.permissionMode());
        assertTrue(invocationOptions.yes());
        assertTrue(invocationOptions.noInput());
        assertEquals("PT5M", invocationOptions.timeout());
        assertEquals(4, invocationOptions.maxLlmCalls());
        assertEquals(8, invocationOptions.maxToolExecutions());
        assertEquals("required", invocationOptions.attach());
        assertEquals(4096, invocationOptions.port());
        assertEquals("127.0.0.1", invocationOptions.hostname());
        assertIterableEquals(List.of("-Xmx1g", "-Dgolemcore.test=true"), invocationOptions.javaOptions());
    }

    @Test
    void shouldAcceptRunPromptAndGlobalOptionsAfterSubcommand() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(out, true), new PrintWriter(err, true));

        int exitCode = CliApplication.commandLine(rootCommand).execute(
                "run",
                "--format",
                "json",
                "--permission-mode",
                "read-only",
                "review",
                "this",
                "change");

        assertEquals(CliExitCodes.NOT_IMPLEMENTED, exitCode);
        assertEquals("", out.toString());
        assertTrue(err.toString().contains("run is not implemented in this CLI slice"));
        assertFalse(err.toString().contains("Unknown option"));
        assertFalse(err.toString().contains("Unmatched argument"));
        assertEquals("run", rootCommand.invocation().commandName());
        assertEquals("json", rootCommand.invocation().options().format());
        assertEquals("read-only", rootCommand.invocation().options().permissionMode());
    }

    @Test
    void shouldAcceptAttachFlagWithoutConsumingRunPrompt() {
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true));

        int exitCode = CliApplication.commandLine(rootCommand).execute(
                "run",
                "--attach",
                "fix failing tests");

        assertEquals(CliExitCodes.NOT_IMPLEMENTED, exitCode);
        assertFalse(err.toString().contains("Invalid value"));
        assertFalse(err.toString().contains("Unmatched argument"));
        assertEquals("run", rootCommand.invocation().commandName());
        assertEquals("required", rootCommand.invocation().options().attach());
    }

    @Test
    void shouldAcceptServeOptionsAfterSubcommand() {
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true));

        int exitCode = CliApplication.commandLine(rootCommand).execute(
                "serve",
                "--port",
                "4096",
                "--hostname",
                "0.0.0.0");

        assertEquals(CliExitCodes.NOT_IMPLEMENTED, exitCode);
        assertFalse(err.toString().contains("Unknown option"));
        assertEquals("serve", rootCommand.invocation().commandName());
        assertEquals(4096, rootCommand.invocation().options().port());
        assertEquals("0.0.0.0", rootCommand.invocation().options().hostname());
    }

    @Test
    void shouldAcceptAttachTargetAndOptionsAfterSubcommand() {
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true));

        int exitCode = CliApplication.commandLine(rootCommand).execute(
                "attach",
                "--session",
                "ses_123",
                "http://127.0.0.1:4096");

        assertEquals(CliExitCodes.NOT_IMPLEMENTED, exitCode);
        assertFalse(err.toString().contains("Unknown option"));
        assertFalse(err.toString().contains("Unmatched argument"));
        assertEquals("attach", rootCommand.invocation().commandName());
        assertEquals("ses_123", rootCommand.invocation().options().session());
    }

    @Test
    void shouldAcceptPlannedManagementCommandArgumentsBeforeRealHandlersExist() {
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true));

        int exitCode = CliApplication.commandLine(rootCommand).execute(
                "session",
                "show",
                "ses_123",
                "--json");

        assertEquals(CliExitCodes.NOT_IMPLEMENTED, exitCode);
        assertFalse(err.toString().contains("Unknown option"));
        assertFalse(err.toString().contains("Unmatched argument"));
        assertEquals("session", rootCommand.invocation().commandName());
        assertEquals("json", rootCommand.invocation().options().format());
    }

    @Test
    void shouldRenderDeterministicDoctorJson() {
        StringWriter out = new StringWriter();
        int exitCode = commandLine(out, new StringWriter()).execute("doctor", "--json");

        assertEquals(0, exitCode);
        String json = out.toString();
        assertTrue(json.contains("\"status\":\"ok\""));
        assertTrue(json.contains("\"checks\":["));
        assertTrue(json.contains("\"name\":\"cli.command_surface\""));
        assertTrue(json.contains("\"name\":\"java.runtime\""));
        assertTrue(json.contains("\"name\":\"cli.package_boundaries\""));
    }

    @Test
    void shouldRenderDeterministicDoctorText() {
        StringWriter out = new StringWriter();
        int exitCode = commandLine(out, new StringWriter()).execute("doctor");

        assertEquals(0, exitCode);
        String text = out.toString();
        assertTrue(text.contains("cli.command_surface: ok"));
        assertTrue(text.contains("java.runtime: ok"));
        assertTrue(text.contains("cli.package_boundaries: ok"));
    }

    @Test
    void shouldRenderCliVersion() {
        StringWriter out = new StringWriter();
        int exitCode = commandLine(out, new StringWriter()).execute("--version");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("golemcore-bot cli"));
        assertTrue(out.toString().contains("java "));
    }

    @Test
    void shouldExposePlanExitCodeSpec() {
        assertEquals(0, CliExitCodes.SUCCESS);
        assertEquals(1, CliExitCodes.GENERAL_FAILURE);
        assertEquals(2, CliExitCodes.INVALID_USAGE);
        assertEquals(3, CliExitCodes.CONFIG_ERROR);
        assertEquals(4, CliExitCodes.AUTHENTICATION_OR_PROVIDER_ERROR);
        assertEquals(5, CliExitCodes.PERMISSION_DENIED);
        assertEquals(6, CliExitCodes.TOOL_EXECUTION_FAILURE);
        assertEquals(7, CliExitCodes.MODEL_OR_LLM_FAILURE);
        assertEquals(8, CliExitCodes.TIMEOUT_OR_BUDGET_EXCEEDED);
        assertEquals(9, CliExitCodes.SESSION_RUNTIME_UNAVAILABLE);
        assertEquals(10, CliExitCodes.PROJECT_UNTRUSTED_OR_RESTRICTED);
        assertEquals(11, CliExitCodes.PATCH_CONFLICT);
        assertEquals(12, CliExitCodes.NETWORK_OR_MCP_FAILURE);
        assertEquals(13, CliExitCodes.CHECK_COMMAND_FAILED);
        assertEquals(CliExitCodes.SESSION_RUNTIME_UNAVAILABLE, CliExitCodes.NOT_IMPLEMENTED);
    }

    @Test
    void shouldReturnUsageErrorForUnknownArguments() {
        StringWriter err = new StringWriter();
        int exitCode = commandLine(new StringWriter(), err).execute("--does-not-exist");

        assertEquals(2, exitCode);
        assertTrue(err.toString().contains("Unknown option"));
        assertTrue(err.toString().contains("Usage: cli"));
    }

    @Test
    void shouldExposeManagementAliases() {
        CommandLine commandLine = commandLine(new StringWriter(), new StringWriter());

        assertArrayEquals(new String[] { "ls" },
                commandLine.getSubcommands().get("session").getSubcommands().get("list").getCommandSpec().aliases());
        assertArrayEquals(new String[] { "ls" },
                commandLine.getSubcommands().get("agent").getSubcommands().get("list").getCommandSpec().aliases());
        assertArrayEquals(new String[] { "ls" },
                commandLine.getSubcommands().get("mcp").getSubcommands().get("list").getCommandSpec().aliases());
    }

    private CommandLine commandLine(StringWriter out, StringWriter err) {
        return CliApplication.commandLine(
                new CliRootCommand(new PrintWriter(out, true), new PrintWriter(err, true)));
    }
}

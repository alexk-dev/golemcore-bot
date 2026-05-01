package me.golemcore.bot.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import me.golemcore.bot.cli.adapter.in.picocli.CliGlobalOptions;
import me.golemcore.bot.cli.adapter.in.picocli.CliRootCommand;
import me.golemcore.bot.cli.config.CliAttachMode;
import me.golemcore.bot.cli.domain.CliExitCodes;
import me.golemcore.bot.cli.domain.CliCommandInvocation;
import me.golemcore.bot.cli.domain.CliCommandOptions;
import me.golemcore.bot.cli.domain.DoctorCheck;
import me.golemcore.bot.cli.domain.DoctorCheckStatus;
import me.golemcore.bot.cli.domain.DoctorReport;
import me.golemcore.bot.cli.domain.CommandExecutionResult;
import me.golemcore.bot.cli.presentation.CommandResultPresenter;
import me.golemcore.bot.cli.presentation.DoctorPresenter;
import me.golemcore.bot.domain.cli.CliExitCode;
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
    void shouldCreateRootCommandWithInjectedDependenciesObject() {
        StringWriter out = new StringWriter();
        CliDependencies dependencies = new CliDependencies(
                invocation -> CommandExecutionResult.stdout(0, "started"),
                invocation -> CommandExecutionResult.stderr(14, "stubbed " + invocation.commandName()),
                options -> new DoctorReport("ok", List.of()),
                new CommandResultPresenter(),
                new DoctorPresenter());
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(out, true),
                new PrintWriter(new StringWriter(), true),
                dependencies);

        int exitCode = CliApplication.commandLine(rootCommand).execute();

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("started"));
        assertEquals("cli", rootCommand.invocation().commandName());
    }

    @Test
    void shouldRunApplicationWithInjectedDependenciesObject() {
        StringWriter out = new StringWriter();
        CliDependencies dependencies = new CliDependencies(
                invocation -> CommandExecutionResult.stdout(0, "started through app"),
                invocation -> CommandExecutionResult.stderr(14, "stubbed " + invocation.commandName()),
                options -> new DoctorReport("ok", List.of()),
                new CommandResultPresenter(),
                new DoctorPresenter());

        int exitCode = CliApplication.run(new String[] {}, new PrintWriter(out, true),
                new PrintWriter(new StringWriter(), true),
                dependencies);

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("started through app"));
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
    void shouldMatchGoldenHelpSnapshots() throws IOException {
        assertGoldenStdout("golden/cli/help-root.txt", "--help");
        assertGoldenStdout("golden/cli/help-run.txt", "run", "--help");
        assertGoldenStdout("golden/cli/help-session.txt", "session", "--help");
    }

    @Test
    void shouldMatchGoldenInvalidUsageStderr() throws IOException {
        assertGoldenStderr("golden/cli/error-unknown-option.txt", "--does-not-exist");
        assertGoldenStderr("golden/cli/error-conflicting-output-format.txt", "--json", "--format", "ndjson", "run");
        assertGoldenStderr("golden/cli/error-no-input-ask.txt", "--no-input", "--permission-mode", "ask", "run");
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

        assertEquals(CliExitCode.FEATURE_UNAVAILABLE.processCode(), exitCode);
        assertTrue(err.toString().contains("TUI runtime is not implemented in this CLI slice"));
        assertEquals("cli", rootCommand.invocation().commandName());
    }

    @Test
    void shouldReturnDeterministicNotImplementedForStubCommand() {
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true));
        int exitCode = CliApplication.commandLine(rootCommand).execute("run");

        assertEquals(CliExitCode.FEATURE_UNAVAILABLE.processCode(), exitCode);
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
                "--format",
                "json",
                "--json",
                "--no-color",
                "--color",
                "never",
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
        assertFalse(options.continueLatest());
        assertEquals(null, options.fork());
        assertEquals(CliOutputFormat.JSON, options.effectiveFormat());
        assertTrue(options.noColor());
        assertEquals("never", options.color());
        assertFalse(options.quiet());
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
        assertFalse(invocationOptions.continueSession());
        assertEquals(null, invocationOptions.fork());
        assertEquals("json", invocationOptions.format());
        assertTrue(invocationOptions.json());
        assertTrue(invocationOptions.noColor());
        assertEquals("never", invocationOptions.color());
        assertFalse(invocationOptions.quiet());
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
    }

    @Test
    void shouldExposeGroupedCommandOptions() {
        StringWriter out = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(out, true),
                new PrintWriter(new StringWriter()));
        CommandLine commandLine = CliApplication.commandLine(rootCommand);

        int exitCode = commandLine.execute(
                "--cwd",
                "/repo",
                "--model",
                "openai/gpt-5.2",
                "--format",
                "json",
                "--trace",
                "--no-memory",
                "--permission-mode",
                "read-only",
                "--timeout",
                "PT5M",
                "--attach",
                "required",
                "doctor",
                "--json");

        assertEquals(0, exitCode);
        CliCommandOptions options = rootCommand.invocation().options();
        assertEquals(Path.of("/repo"), options.projectOptions().cwd());
        assertEquals("openai/gpt-5.2", options.runtimeSelection().model());
        assertEquals("json", options.output().format());
        assertTrue(options.traceOptions().trace());
        assertTrue(options.capabilities().noMemory());
        assertEquals("read-only", options.permissions().permissionMode());
        assertEquals("PT5M", options.budget().timeout());
        assertEquals("required", options.attachOptions().attach());
    }

    @Test
    void shouldRejectRuntimeJavaOptionsInInternalCliParser() {
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true));

        int exitCode = CliApplication.commandLine(rootCommand).execute(
                "-J=-Xmx1g",
                "doctor",
                "--json");

        assertEquals(CliExitCodes.INVALID_USAGE, exitCode);
        assertTrue(err.toString().contains("Unknown option"));
        assertTrue(err.toString().contains("-J=-Xmx1g"));
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
        assertEquals("session show", rootCommand.invocation().commandName());
        assertEquals("json", rootCommand.invocation().options().format());
        assertEquals(List.of("ses_123"), rootCommand.invocation().rawArguments());
    }

    @Test
    void shouldRejectMistypedPlannedManagementSubcommand() {
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true));

        int exitCode = CliApplication.commandLine(rootCommand).execute(
                "session",
                "shwo",
                "ses_123");

        assertEquals(CliExitCodes.INVALID_USAGE, exitCode);
        assertTrue(err.toString().contains("Unmatched argument"));
    }

    @Test
    void shouldRejectConflictingGlobalOptions() {
        assertInvalidUsage("--json", "--format", "ndjson", "doctor");
        assertInvalidUsage("--quiet", "--verbose", "doctor");
        assertInvalidUsage("--no-color", "--color", "always", "doctor");
        assertInvalidUsage("--session", "ses_1", "--continue", "doctor");
        assertInvalidUsage("--session", "ses_1", "--fork", "ses_2", "doctor");
        assertInvalidUsage("--continue", "--fork", "ses_2", "doctor");
        assertInvalidUsage("--no-input", "--permission-mode", "ask", "doctor");
        assertInvalidUsage("--port", "-1", "doctor");
        assertInvalidUsage("--max-llm-calls", "-5", "doctor");
        assertInvalidUsage("--max-tool-executions", "-5", "doctor");
        assertInvalidUsage("--timeout", "soon", "doctor");
    }

    @Test
    void shouldHandleAttachOptionalValueEdgeCases() {
        CliCommandInvocation bareAttach = runInvocation("run", "--attach", "--port", "4096", "fix");
        assertEquals("required", bareAttach.options().attach());
        assertEquals(4096, bareAttach.options().port());
        assertEquals(List.of("fix"), bareAttach.rawArguments());

        CliCommandInvocation requiredAttach = runInvocation("run", "--attach=required", "fix");
        assertEquals("required", requiredAttach.options().attach());
        assertEquals(List.of("fix"), requiredAttach.rawArguments());

        CliCommandInvocation autoAttach = runInvocation("run", "--attach", "auto", "fix");
        assertEquals("auto", autoAttach.options().attach());
        assertEquals(List.of("fix"), autoAttach.rawArguments());

        CliCommandInvocation promptBeginningWithModeWords = runInvocation("run", "auto", "required", "never", "fix");
        assertEquals("auto", promptBeginningWithModeWords.options().attach());
        assertEquals(List.of("auto", "required", "never", "fix"), promptBeginningWithModeWords.rawArguments());
    }

    @Test
    void shouldRenderDeterministicDoctorJson() throws Exception {
        StringWriter out = new StringWriter();
        int exitCode = commandLine(out, new StringWriter()).execute("doctor", "--json");

        assertEquals(0, exitCode);
        JsonNode json = new ObjectMapper().readTree(out.toString());
        assertEquals("warn", json.get("status").asText());
        assertTrue(json.get("checks").isArray());
        assertEquals("cli.command_surface", json.get("checks").get(0).get("name").asText());
        assertEquals("ok", json.get("checks").get(0).get("status").asText());
        assertTrue(out.toString().contains("\"name\":\"java.runtime\""));
        assertTrue(out.toString().contains("\"name\":\"cli.package_boundaries\""));
    }

    @Test
    void shouldRenderDoctorJsonWithJacksonEscaping() throws Exception {
        DoctorReport report = new DoctorReport("warn", List.of(new DoctorCheck(
                "control",
                DoctorCheckStatus.WARN,
                "line\u0001\nbreak")));
        StringWriter out = new StringWriter();

        new DoctorPresenter().renderJson(report, new PrintWriter(out, true));

        JsonNode json = new ObjectMapper().readTree(out.toString());
        assertEquals("warn", json.get("status").asText());
        assertEquals("line\u0001\nbreak", json.get("checks").get(0).get("message").asText());
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
        assertEquals(CliExitCode.SUCCESS.processCode(), CliExitCodes.SUCCESS);
        assertEquals(CliExitCode.GENERAL_ERROR.processCode(), CliExitCodes.GENERAL_FAILURE);
        assertEquals(CliExitCode.INVALID_USAGE.processCode(), CliExitCodes.INVALID_USAGE);
        assertEquals(CliExitCode.CONFIG_ERROR.processCode(), CliExitCodes.CONFIG_ERROR);
        assertEquals(CliExitCode.AUTHENTICATION_ERROR.processCode(), CliExitCodes.AUTHENTICATION_OR_PROVIDER_ERROR);
        assertEquals(CliExitCode.PERMISSION_DENIED.processCode(), CliExitCodes.PERMISSION_DENIED);
        assertEquals(CliExitCode.TOOL_EXECUTION_FAILURE.processCode(), CliExitCodes.TOOL_EXECUTION_FAILURE);
        assertEquals(CliExitCode.MODEL_FAILURE.processCode(), CliExitCodes.MODEL_OR_LLM_FAILURE);
        assertEquals(CliExitCode.TIMEOUT.processCode(), CliExitCodes.TIMEOUT_OR_BUDGET_EXCEEDED);
        assertEquals(CliExitCode.RUNTIME_UNAVAILABLE.processCode(), CliExitCodes.SESSION_RUNTIME_UNAVAILABLE);
        assertEquals(CliExitCode.PROJECT_UNTRUSTED.processCode(), CliExitCodes.PROJECT_UNTRUSTED_OR_RESTRICTED);
        assertEquals(CliExitCode.PATCH_CONFLICT.processCode(), CliExitCodes.PATCH_CONFLICT);
        assertEquals(CliExitCode.NETWORK_OR_MCP_FAILURE.processCode(), CliExitCodes.NETWORK_OR_MCP_FAILURE);
        assertEquals(CliExitCode.CHECK_FAILED.processCode(), CliExitCodes.CHECK_COMMAND_FAILED);
        assertEquals(CliExitCode.FEATURE_UNAVAILABLE.processCode(), CliExitCodes.NOT_IMPLEMENTED);
        assertFalse(CliExitCodes.NOT_IMPLEMENTED == CliExitCodes.SESSION_RUNTIME_UNAVAILABLE);
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

    @Test
    void shouldRegisterDocumentedPlannedCommandTree() {
        CommandLine commandLine = commandLine(new StringWriter(), new StringWriter());

        assertSubcommands(commandLine, "session", "list", "show", "new", "continue", "fork", "rename", "delete",
                "compact", "export", "import", "share", "unshare", "stats", "trace", "snapshot", "restore", "prune");
        assertSubcommands(commandLine, "agent", "list", "show", "create", "edit", "validate", "enable", "disable",
                "remove", "import", "export", "run", "permissions", "skills", "mcp");
        assertSubcommands(commandLine, "auth", "login", "list", "show", "logout", "status", "doctor", "import",
                "export");
        assertSubcommands(commandLine, "providers", "list", "show", "add", "set", "remove", "refresh", "doctor",
                "import", "export");
        assertSubcommands(commandLine, "models", "list", "show", "refresh", "set", "reset", "route", "doctor");
        assertSubcommands(commandLine, "tier", "get", "set", "reset", "explain", "doctor");
        assertSubcommands(commandLine, "mcp", "list", "add", "show", "remove", "enable", "disable", "auth",
                "logout", "debug", "test", "import", "export", "logs");
        assertSubcommands(commandLine, "skill", "list", "show", "create", "edit", "install", "remove", "enable",
                "disable", "validate", "reload", "marketplace", "update");
        assertSubcommands(commandLine, "plugin", "list", "show", "install", "remove", "enable", "disable", "config",
                "doctor", "reload", "marketplace", "update");
        assertSubcommands(commandLine, "tool", "list", "show", "enable", "disable", "run", "permissions",
                "history");
        assertSubcommands(commandLine, "permissions", "list", "preset", "set", "allow", "deny", "reset", "explain",
                "approve", "reject");
        assertSubcommands(commandLine, "project", "init", "status", "doctor", "trust", "untrust", "rules", "index",
                "ignore", "env", "reset");
        assertSubcommands(commandLine, "config", "get", "set", "unset", "list", "edit", "validate", "path",
                "import", "export", "reset");
        assertSubcommands(commandLine, "memory", "status", "search", "list", "show", "pin", "unpin", "forget",
                "compact", "export", "import", "stats", "doctor");
        assertSubcommands(commandLine, "rag", "status", "query", "index", "reindex", "clear", "config", "doctor");
        assertSubcommands(commandLine, "auto", "status", "goal", "task", "schedule", "diary", "run", "stop");
        assertSubcommands(commandLine, "lsp", "list", "status", "install", "start", "stop", "restart",
                "diagnostics", "symbols", "references", "hover", "doctor");
        assertSubcommands(commandLine, "terminal", "list", "open", "attach", "send", "kill", "logs");
        assertSubcommands(commandLine, "git", "status", "diff", "checkpoint", "restore", "commit", "branch",
                "worktree");
        assertSubcommands(commandLine, "patch", "list", "show", "accept", "reject", "apply", "revert", "export",
                "split");
        assertSubcommands(commandLine, "github", "auth", "doctor", "install", "run", "pr", "issue");
        assertSubcommands(commandLine, "trace", "list", "show", "export", "replay", "waterfall", "prune");
        assertSubcommands(commandLine, "stats", "usage", "models", "tools", "agents", "costs");

        assertArrayEquals(new String[] { "rm" },
                commandLine.getSubcommands().get("session").getSubcommands().get("delete").getCommandSpec()
                        .aliases());
        assertArrayEquals(new String[] { "rm" },
                commandLine.getSubcommands().get("agent").getSubcommands().get("remove").getCommandSpec()
                        .aliases());
    }

    private CommandLine commandLine(StringWriter out, StringWriter err) {
        return CliApplication.commandLine(
                new CliRootCommand(new PrintWriter(out, true), new PrintWriter(err, true)));
    }

    private void assertGoldenStdout(String resourcePath, String... args) throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = commandLine(out, err).execute(args);

        assertEquals(CliExitCodes.SUCCESS, exitCode, String.join(" ", args));
        assertEquals("", err.toString(), String.join(" ", args));
        assertEquals(golden(resourcePath), normalize(out.toString()), resourcePath);
    }

    private void assertGoldenStderr(String resourcePath, String... args) throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = commandLine(out, err).execute(args);

        assertEquals(CliExitCodes.INVALID_USAGE, exitCode, String.join(" ", args));
        assertEquals("", out.toString(), String.join(" ", args));
        assertEquals(golden(resourcePath), normalize(err.toString()), resourcePath);
    }

    private static String golden(String resourcePath) throws IOException {
        try (InputStream input = CliRootCommandTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(input, "Missing golden fixture " + resourcePath);
            return normalize(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n");
    }

    private void assertInvalidUsage(String... args) {
        StringWriter err = new StringWriter();
        int exitCode = commandLine(new StringWriter(), err).execute(args);

        assertEquals(CliExitCodes.INVALID_USAGE, exitCode, String.join(" ", args));
        assertFalse(err.toString().isBlank(), String.join(" ", args));
    }

    private CliCommandInvocation runInvocation(String... args) {
        StringWriter err = new StringWriter();
        CliRootCommand rootCommand = new CliRootCommand(new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true));
        int exitCode = CliApplication.commandLine(rootCommand).execute(args);

        assertEquals(CliExitCodes.NOT_IMPLEMENTED, exitCode, String.join(" ", args));
        assertFalse(err.toString().contains("Invalid value"), String.join(" ", args));
        assertFalse(err.toString().contains("Unmatched argument"), String.join(" ", args));
        return rootCommand.invocation();
    }

    private static void assertSubcommands(CommandLine commandLine, String commandName, String... subcommands) {
        CommandLine command = commandLine.getSubcommands().get(commandName);
        assertNotNull(command, commandName);
        assertTrue(command.getSubcommands().keySet().containsAll(Set.of(subcommands)),
                () -> commandName + " missing subcommands from " + command.getSubcommands().keySet());
    }
}

package me.golemcore.bot.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CliRootCommandTest {

    @Test
    void shouldRegisterInitialCommandSurface() {
        CommandLine commandLine = commandLine(new StringWriter(), new StringWriter());

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

        CliInvocation invocation = rootCommand.invocation();
        assertNotNull(invocation);
        assertEquals("doctor", invocation.commandName());
        CliOptions invocationOptions = invocation.options();
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
    void shouldRenderDeterministicDoctorJson() {
        StringWriter out = new StringWriter();
        int exitCode = commandLine(out, new StringWriter()).execute("doctor", "--json");

        assertEquals(0, exitCode);
        assertEquals(
                """
                        {"status":"ok","checks":[{"name":"cli","status":"ok","message":"CLI adapter slice is available"}]}
                        """,
                out.toString());
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

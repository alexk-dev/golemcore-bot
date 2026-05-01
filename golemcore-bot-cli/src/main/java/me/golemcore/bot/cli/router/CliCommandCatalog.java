package me.golemcore.bot.cli.router;

import java.util.List;
import me.golemcore.bot.cli.adapter.in.picocli.DoctorCommand;
import me.golemcore.bot.cli.adapter.in.picocli.PlannedCommands;

public final class CliCommandCatalog {

    private static final List<CliCommandDescriptor> DESCRIPTORS = List.of(
            descriptor("run", "Run agent non-interactively.", PlannedCommands.RunCommand.class),
            descriptor("serve", "Start headless runtime server.", PlannedCommands.ServeCommand.class),
            descriptor("attach", "Attach TUI to runtime.", PlannedCommands.AttachCommand.class),
            descriptor("acp", "Start IDE/ACP stdio server.", PlannedCommands.AcpCommand.class),
            descriptor("session", "Manage sessions.", PlannedCommands.SessionCommand.class),
            descriptor("agent", "Manage agent profiles.", PlannedCommands.AgentCommand.class),
            descriptor("auth", "Manage credentials.", PlannedCommands.AuthCommand.class),
            descriptor("providers", "Manage provider definitions.", PlannedCommands.ProvidersCommand.class),
            descriptor("models", "Manage/discover models.", PlannedCommands.ModelsCommand.class),
            descriptor("tier", "Manage model tier preference.", PlannedCommands.TierCommand.class),
            descriptor("mcp", "Manage MCP servers.", PlannedCommands.McpCommand.class),
            descriptor("skill", "Manage skills.", PlannedCommands.SkillCommand.class),
            descriptor("plugin", "Manage plugins.", PlannedCommands.PluginCommand.class),
            descriptor("tool", "Inspect/run tools.", PlannedCommands.ToolCommand.class),
            descriptor("permissions", "Manage permission policy.", PlannedCommands.PermissionsCommand.class),
            descriptor("project", "Project config/trust/index.", PlannedCommands.ProjectCommand.class),
            descriptor("config", "Runtime/project config.", PlannedCommands.ConfigCommand.class),
            descriptor("memory", "Inspect/manage Memory V2.", PlannedCommands.MemoryCommand.class),
            descriptor("rag", "Inspect/manage RAG.", PlannedCommands.RagCommand.class),
            descriptor("auto", "Manage Auto Mode goals/tasks/schedules.", PlannedCommands.AutoCommand.class),
            descriptor("lsp", "LSP diagnostics/symbols.", PlannedCommands.LspCommand.class),
            descriptor("terminal", "PTY terminal sessions.", PlannedCommands.TerminalCommand.class),
            descriptor("git", "Git/checkpoint helpers.", PlannedCommands.GitCommand.class),
            descriptor("patch", "Agent patch approval/application.", PlannedCommands.PatchCommand.class),
            descriptor("github", "GitHub integration.", PlannedCommands.GithubCommand.class),
            descriptor("trace", "Trace inspect/export/replay.", PlannedCommands.TraceCommand.class),
            descriptor("stats", "Usage/cost/tool stats.", PlannedCommands.StatsCommand.class),
            descriptor("doctor", "Environment diagnostics.", DoctorCommand.class),
            descriptor("export", "Export sessions/config/etc.", PlannedCommands.ExportCommand.class),
            descriptor("import", "Import bundle.", PlannedCommands.ImportCommand.class),
            descriptor("completion", "Shell completions.", PlannedCommands.CompletionCommand.class),
            descriptor("upgrade", "Upgrade runtime/launcher.", PlannedCommands.UpgradeCommand.class),
            descriptor("uninstall", "Uninstall with data/config options.", PlannedCommands.UninstallCommand.class));

    private CliCommandCatalog() {
    }

    public static List<CliCommandDescriptor> descriptors() {
        return DESCRIPTORS;
    }

    public static List<String> subcommandNames() {
        return DESCRIPTORS.stream()
                .map(CliCommandDescriptor::name)
                .toList();
    }

    private static CliCommandDescriptor descriptor(String name, String description, Class<?> commandClass) {
        return new CliCommandDescriptor(name, description, commandClass);
    }
}

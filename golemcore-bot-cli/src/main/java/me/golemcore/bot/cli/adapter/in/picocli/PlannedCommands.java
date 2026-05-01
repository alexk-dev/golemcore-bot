package me.golemcore.bot.cli.adapter.in.picocli;

import picocli.CommandLine.Command;

@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class PlannedCommands {

    private PlannedCommands() {
    }

    @Command(name = "run", mixinStandardHelpOptions = true, description = "Run agent non-interactively.")
    public static final class RunCommand extends PlannedStubCommand {
    }

    @Command(name = "serve", mixinStandardHelpOptions = true, description = "Start headless runtime server.")
    public static final class ServeCommand extends PlannedStubCommand {
    }

    @Command(name = "attach", mixinStandardHelpOptions = true, description = "Attach TUI to runtime.")
    public static final class AttachCommand extends PlannedStubCommand {
    }

    @Command(name = "acp", mixinStandardHelpOptions = true, description = "Start IDE/ACP stdio server.")
    public static final class AcpCommand extends PlannedStubCommand {
    }

    @Command(name = "session", mixinStandardHelpOptions = true, description = "Manage sessions.", subcommands = {
            ListCommand.class,
            ShowCommand.class,
            NewCommand.class,
            ContinueCommand.class,
            ForkCommand.class,
            RenameCommand.class,
            DeleteCommand.class,
            CompactCommand.class,
            ExportCommand.class,
            ImportCommand.class,
            ShareCommand.class,
            UnshareCommand.class,
            StatsActionCommand.class,
            TraceActionCommand.class,
            SnapshotCommand.class,
            RestoreCommand.class,
            PruneCommand.class
    })
    public static final class SessionCommand extends PlannedStubCommand {
    }

    @Command(name = "agent", mixinStandardHelpOptions = true, description = "Manage agent profiles.", subcommands = {
            ListCommand.class,
            ShowCommand.class,
            CreateCommand.class,
            EditCommand.class,
            ValidateCommand.class,
            EnableCommand.class,
            DisableCommand.class,
            RemoveCommand.class,
            ImportCommand.class,
            ExportCommand.class,
            RunCommand.class,
            PermissionsActionCommand.class,
            SkillsCommand.class,
            McpActionCommand.class
    })
    public static final class AgentCommand extends PlannedStubCommand {
    }

    @Command(name = "auth", mixinStandardHelpOptions = true, description = "Manage credentials.", subcommands = {
            LoginCommand.class,
            ListCommand.class,
            ShowCommand.class,
            LogoutCommand.class,
            StatusCommand.class,
            DoctorActionCommand.class,
            ImportCommand.class,
            ExportCommand.class
    })
    public static final class AuthCommand extends PlannedStubCommand {
    }

    @Command(name = "providers", mixinStandardHelpOptions = true, description = "Manage provider definitions.", subcommands = {
            ListCommand.class,
            ShowCommand.class,
            AddCommand.class,
            SetCommand.class,
            RemoveCommand.class,
            RefreshCommand.class,
            DoctorActionCommand.class,
            ImportCommand.class,
            ExportCommand.class
    })
    public static final class ProvidersCommand extends PlannedStubCommand {
    }

    @Command(name = "models", mixinStandardHelpOptions = true, description = "Manage/discover models.", subcommands = {
            ListCommand.class,
            ShowCommand.class,
            RefreshCommand.class,
            SetCommand.class,
            ResetCommand.class,
            RouteCommand.class,
            DoctorActionCommand.class
    })
    public static final class ModelsCommand extends PlannedStubCommand {
    }

    @Command(name = "tier", mixinStandardHelpOptions = true, description = "Manage model tier preference.", subcommands = {
            GetCommand.class,
            SetCommand.class,
            ResetCommand.class,
            ExplainCommand.class,
            DoctorActionCommand.class
    })
    public static final class TierCommand extends PlannedStubCommand {
    }

    @Command(name = "mcp", mixinStandardHelpOptions = true, description = "Manage MCP servers.", subcommands = {
            ListCommand.class,
            AddCommand.class,
            ShowCommand.class,
            RemoveCommand.class,
            EnableCommand.class,
            DisableCommand.class,
            AuthActionCommand.class,
            LogoutCommand.class,
            DebugCommand.class,
            ProbeCommand.class,
            ImportCommand.class,
            ExportCommand.class,
            LogsCommand.class
    })
    public static final class McpCommand extends PlannedStubCommand {
    }

    @Command(name = "skill", mixinStandardHelpOptions = true, description = "Manage skills.", subcommands = {
            ListCommand.class,
            ShowCommand.class,
            CreateCommand.class,
            EditCommand.class,
            InstallCommand.class,
            RemoveCommand.class,
            EnableCommand.class,
            DisableCommand.class,
            ValidateCommand.class,
            ReloadCommand.class,
            MarketplaceCommand.class,
            UpdateCommand.class
    })
    public static final class SkillCommand extends PlannedStubCommand {
    }

    @Command(name = "plugin", mixinStandardHelpOptions = true, description = "Manage plugins.", subcommands = {
            ListCommand.class,
            ShowCommand.class,
            InstallCommand.class,
            RemoveCommand.class,
            EnableCommand.class,
            DisableCommand.class,
            ConfigActionCommand.class,
            DoctorActionCommand.class,
            ReloadCommand.class,
            MarketplaceCommand.class,
            UpdateCommand.class
    })
    public static final class PluginCommand extends PlannedStubCommand {
    }

    @Command(name = "tool", mixinStandardHelpOptions = true, description = "Inspect/run tools.", subcommands = {
            ListCommand.class,
            ShowCommand.class,
            EnableCommand.class,
            DisableCommand.class,
            RunCommand.class,
            PermissionsActionCommand.class,
            HistoryCommand.class
    })
    public static final class ToolCommand extends PlannedStubCommand {
    }

    @Command(name = "permissions", mixinStandardHelpOptions = true, description = "Manage permission policy.", subcommands = {
            ListCommand.class,
            PresetCommand.class,
            SetCommand.class,
            AllowCommand.class,
            DenyCommand.class,
            ResetCommand.class,
            ExplainCommand.class,
            ApproveCommand.class,
            RejectCommand.class
    })
    public static final class PermissionsCommand extends PlannedStubCommand {
    }

    @Command(name = "project", mixinStandardHelpOptions = true, description = "Project config/trust/index.", subcommands = {
            InitCommand.class,
            StatusCommand.class,
            DoctorActionCommand.class,
            TrustCommand.class,
            UntrustCommand.class,
            RulesCommand.class,
            IndexCommand.class,
            IgnoreCommand.class,
            EnvCommand.class,
            ResetCommand.class
    })
    public static final class ProjectCommand extends PlannedStubCommand {
    }

    @Command(name = "config", mixinStandardHelpOptions = true, description = "Runtime/project config.", subcommands = {
            GetCommand.class,
            SetCommand.class,
            UnsetCommand.class,
            ListCommand.class,
            EditCommand.class,
            ValidateCommand.class,
            PathCommand.class,
            ImportCommand.class,
            ExportCommand.class,
            ResetCommand.class
    })
    public static final class ConfigCommand extends PlannedStubCommand {
    }

    @Command(name = "memory", mixinStandardHelpOptions = true, description = "Inspect/manage Memory V2.", subcommands = {
            StatusCommand.class,
            SearchCommand.class,
            ListCommand.class,
            ShowCommand.class,
            PinCommand.class,
            UnpinCommand.class,
            ForgetCommand.class,
            CompactCommand.class,
            ExportCommand.class,
            ImportCommand.class,
            StatsActionCommand.class,
            DoctorActionCommand.class
    })
    public static final class MemoryCommand extends PlannedStubCommand {
    }

    @Command(name = "rag", mixinStandardHelpOptions = true, description = "Inspect/manage RAG.", subcommands = {
            StatusCommand.class,
            QueryCommand.class,
            IndexCommand.class,
            ReindexCommand.class,
            ClearCommand.class,
            ConfigActionCommand.class,
            DoctorActionCommand.class
    })
    public static final class RagCommand extends PlannedStubCommand {
    }

    @Command(name = "auto", mixinStandardHelpOptions = true, description = "Manage Auto Mode.", subcommands = {
            StatusCommand.class,
            GoalCommand.class,
            TaskCommand.class,
            ScheduleCommand.class,
            DiaryCommand.class,
            RunCommand.class,
            StopCommand.class
    })
    public static final class AutoCommand extends PlannedStubCommand {
    }

    @Command(name = "lsp", mixinStandardHelpOptions = true, description = "LSP diagnostics/symbols.", subcommands = {
            ListCommand.class,
            StatusCommand.class,
            InstallCommand.class,
            StartCommand.class,
            StopCommand.class,
            RestartCommand.class,
            DiagnosticsCommand.class,
            SymbolsCommand.class,
            ReferencesCommand.class,
            HoverCommand.class,
            DoctorActionCommand.class
    })
    public static final class LspCommand extends PlannedStubCommand {
    }

    @Command(name = "terminal", mixinStandardHelpOptions = true, description = "PTY terminal sessions.", subcommands = {
            ListCommand.class,
            OpenCommand.class,
            AttachCommand.class,
            SendCommand.class,
            KillCommand.class,
            LogsCommand.class
    })
    public static final class TerminalCommand extends PlannedStubCommand {
    }

    @Command(name = "git", mixinStandardHelpOptions = true, description = "Git/checkpoint helpers.", subcommands = {
            StatusCommand.class,
            DiffCommand.class,
            CheckpointCommand.class,
            RestoreCommand.class,
            CommitCommand.class,
            BranchCommand.class,
            WorktreeCommand.class
    })
    public static final class GitCommand extends PlannedStubCommand {
    }

    @Command(name = "patch", mixinStandardHelpOptions = true, description = "Agent patch approval/application.", subcommands = {
            ListCommand.class,
            ShowCommand.class,
            AcceptCommand.class,
            RejectCommand.class,
            ApplyCommand.class,
            RevertCommand.class,
            ExportCommand.class,
            SplitCommand.class
    })
    public static final class PatchCommand extends PlannedStubCommand {
    }

    @Command(name = "github", mixinStandardHelpOptions = true, description = "GitHub integration.", subcommands = {
            AuthActionCommand.class,
            DoctorActionCommand.class,
            InstallCommand.class,
            RunCommand.class,
            PrCommand.class,
            IssueCommand.class
    })
    public static final class GithubCommand extends PlannedStubCommand {
    }

    @Command(name = "trace", mixinStandardHelpOptions = true, description = "Trace inspect/export/replay.", subcommands = {
            ListCommand.class,
            ShowCommand.class,
            ExportCommand.class,
            ReplayCommand.class,
            WaterfallCommand.class,
            PruneCommand.class
    })
    public static final class TraceCommand extends PlannedStubCommand {
    }

    @Command(name = "stats", mixinStandardHelpOptions = true, description = "Usage/cost/tool stats.", subcommands = {
            UsageCommand.class,
            ModelsActionCommand.class,
            ToolsActionCommand.class,
            AgentsActionCommand.class,
            CostsCommand.class
    })
    public static final class StatsCommand extends PlannedStubCommand {
    }

    @Command(name = "export", mixinStandardHelpOptions = true, description = "Export sessions/config/etc.")
    public static final class ExportCommand extends PlannedStubCommand {
    }

    @Command(name = "import", mixinStandardHelpOptions = true, description = "Import bundle.")
    public static final class ImportCommand extends PlannedStubCommand {
    }

    @Command(name = "completion", mixinStandardHelpOptions = true, description = "Shell completions.")
    public static final class CompletionCommand extends PlannedStubCommand {
    }

    @Command(name = "upgrade", mixinStandardHelpOptions = true, description = "Upgrade runtime/launcher.")
    public static final class UpgradeCommand extends PlannedStubCommand {
    }

    @Command(name = "uninstall", mixinStandardHelpOptions = true, description = "Uninstall with data/config options.")
    public static final class UninstallCommand extends PlannedStubCommand {
    }

    @Command(name = "accept", mixinStandardHelpOptions = true, description = "Accept resource.")
    public static final class AcceptCommand extends PlannedStubCommand {
    }

    @Command(name = "add", mixinStandardHelpOptions = true, description = "Add resource.")
    public static final class AddCommand extends PlannedStubCommand {
    }

    @Command(name = "agents", mixinStandardHelpOptions = true, description = "Group by agents.")
    public static final class AgentsActionCommand extends PlannedStubCommand {
    }

    @Command(name = "allow", mixinStandardHelpOptions = true, description = "Allow resource.")
    public static final class AllowCommand extends PlannedStubCommand {
    }

    @Command(name = "apply", mixinStandardHelpOptions = true, description = "Apply resource.")
    public static final class ApplyCommand extends PlannedStubCommand {
    }

    @Command(name = "approve", mixinStandardHelpOptions = true, description = "Approve request.")
    public static final class ApproveCommand extends PlannedStubCommand {
    }

    @Command(name = "auth", mixinStandardHelpOptions = true, description = "Authenticate resource.")
    public static final class AuthActionCommand extends PlannedStubCommand {
    }

    @Command(name = "branch", mixinStandardHelpOptions = true, description = "Manage branches.")
    public static final class BranchCommand extends PlannedStubCommand {
    }

    @Command(name = "checkpoint", mixinStandardHelpOptions = true, description = "Create checkpoint.")
    public static final class CheckpointCommand extends PlannedStubCommand {
    }

    @Command(name = "clear", mixinStandardHelpOptions = true, description = "Clear resource.")
    public static final class ClearCommand extends PlannedStubCommand {
    }

    @Command(name = "commit", mixinStandardHelpOptions = true, description = "Commit changes.")
    public static final class CommitCommand extends PlannedStubCommand {
    }

    @Command(name = "compact", mixinStandardHelpOptions = true, description = "Compact resource.")
    public static final class CompactCommand extends PlannedStubCommand {
    }

    @Command(name = "config", mixinStandardHelpOptions = true, description = "Configure resource.")
    public static final class ConfigActionCommand extends PlannedStubCommand {
    }

    @Command(name = "continue", mixinStandardHelpOptions = true, description = "Continue resource.")
    public static final class ContinueCommand extends PlannedStubCommand {
    }

    @Command(name = "costs", mixinStandardHelpOptions = true, description = "Show costs.")
    public static final class CostsCommand extends PlannedStubCommand {
    }

    @Command(name = "create", mixinStandardHelpOptions = true, description = "Create resource.")
    public static final class CreateCommand extends PlannedStubCommand {
    }

    @Command(name = "debug", mixinStandardHelpOptions = true, description = "Debug resource.")
    public static final class DebugCommand extends PlannedStubCommand {
    }

    @Command(name = "delete", aliases = "rm", mixinStandardHelpOptions = true, description = "Delete resource.")
    public static final class DeleteCommand extends PlannedStubCommand {
    }

    @Command(name = "deny", mixinStandardHelpOptions = true, description = "Deny resource.")
    public static final class DenyCommand extends PlannedStubCommand {
    }

    @Command(name = "diagnostics", mixinStandardHelpOptions = true, description = "Show diagnostics.")
    public static final class DiagnosticsCommand extends PlannedStubCommand {
    }

    @Command(name = "diary", mixinStandardHelpOptions = true, description = "Show diary.")
    public static final class DiaryCommand extends PlannedStubCommand {
    }

    @Command(name = "diff", mixinStandardHelpOptions = true, description = "Show diff.")
    public static final class DiffCommand extends PlannedStubCommand {
    }

    @Command(name = "disable", mixinStandardHelpOptions = true, description = "Disable resource.")
    public static final class DisableCommand extends PlannedStubCommand {
    }

    @Command(name = "doctor", mixinStandardHelpOptions = true, description = "Diagnose resource.")
    public static final class DoctorActionCommand extends PlannedStubCommand {
    }

    @Command(name = "edit", mixinStandardHelpOptions = true, description = "Edit resource.")
    public static final class EditCommand extends PlannedStubCommand {
    }

    @Command(name = "enable", mixinStandardHelpOptions = true, description = "Enable resource.")
    public static final class EnableCommand extends PlannedStubCommand {
    }

    @Command(name = "env", mixinStandardHelpOptions = true, description = "Show environment resolution.")
    public static final class EnvCommand extends PlannedStubCommand {
    }

    @Command(name = "explain", mixinStandardHelpOptions = true, description = "Explain resource.")
    public static final class ExplainCommand extends PlannedStubCommand {
    }

    @Command(name = "forget", mixinStandardHelpOptions = true, description = "Forget resource.")
    public static final class ForgetCommand extends PlannedStubCommand {
    }

    @Command(name = "fork", mixinStandardHelpOptions = true, description = "Fork resource.")
    public static final class ForkCommand extends PlannedStubCommand {
    }

    @Command(name = "get", mixinStandardHelpOptions = true, description = "Get value.")
    public static final class GetCommand extends PlannedStubCommand {
    }

    @Command(name = "goal", mixinStandardHelpOptions = true, description = "Manage goals.")
    public static final class GoalCommand extends PlannedStubCommand {
    }

    @Command(name = "history", mixinStandardHelpOptions = true, description = "Show history.")
    public static final class HistoryCommand extends PlannedStubCommand {
    }

    @Command(name = "hover", mixinStandardHelpOptions = true, description = "Show hover info.")
    public static final class HoverCommand extends PlannedStubCommand {
    }

    @Command(name = "ignore", mixinStandardHelpOptions = true, description = "Manage ignore rules.")
    public static final class IgnoreCommand extends PlannedStubCommand {
    }

    @Command(name = "index", mixinStandardHelpOptions = true, description = "Index resource.")
    public static final class IndexCommand extends PlannedStubCommand {
    }

    @Command(name = "init", mixinStandardHelpOptions = true, description = "Initialize resource.")
    public static final class InitCommand extends PlannedStubCommand {
    }

    @Command(name = "install", mixinStandardHelpOptions = true, description = "Install resource.")
    public static final class InstallCommand extends PlannedStubCommand {
    }

    @Command(name = "issue", mixinStandardHelpOptions = true, description = "Manage issues.")
    public static final class IssueCommand extends PlannedStubCommand {
    }

    @Command(name = "kill", mixinStandardHelpOptions = true, description = "Kill resource.")
    public static final class KillCommand extends PlannedStubCommand {
    }

    @Command(name = "list", aliases = "ls", mixinStandardHelpOptions = true, description = "List resources.")
    public static final class ListCommand extends PlannedStubCommand {
    }

    @Command(name = "login", mixinStandardHelpOptions = true, description = "Log in.")
    public static final class LoginCommand extends PlannedStubCommand {
    }

    @Command(name = "logout", mixinStandardHelpOptions = true, description = "Log out.")
    public static final class LogoutCommand extends PlannedStubCommand {
    }

    @Command(name = "logs", mixinStandardHelpOptions = true, description = "Show logs.")
    public static final class LogsCommand extends PlannedStubCommand {
    }

    @Command(name = "marketplace", mixinStandardHelpOptions = true, description = "Marketplace operations.")
    public static final class MarketplaceCommand extends PlannedStubCommand {
    }

    @Command(name = "mcp", mixinStandardHelpOptions = true, description = "Manage MCP links.")
    public static final class McpActionCommand extends PlannedStubCommand {
    }

    @Command(name = "models", mixinStandardHelpOptions = true, description = "Group by models.")
    public static final class ModelsActionCommand extends PlannedStubCommand {
    }

    @Command(name = "new", mixinStandardHelpOptions = true, description = "Create new resource.")
    public static final class NewCommand extends PlannedStubCommand {
    }

    @Command(name = "open", mixinStandardHelpOptions = true, description = "Open resource.")
    public static final class OpenCommand extends PlannedStubCommand {
    }

    @Command(name = "path", mixinStandardHelpOptions = true, description = "Show path.")
    public static final class PathCommand extends PlannedStubCommand {
    }

    @Command(name = "permissions", mixinStandardHelpOptions = true, description = "Manage permissions.")
    public static final class PermissionsActionCommand extends PlannedStubCommand {
    }

    @Command(name = "pin", mixinStandardHelpOptions = true, description = "Pin resource.")
    public static final class PinCommand extends PlannedStubCommand {
    }

    @Command(name = "pr", mixinStandardHelpOptions = true, description = "Manage pull requests.")
    public static final class PrCommand extends PlannedStubCommand {
    }

    @Command(name = "preset", mixinStandardHelpOptions = true, description = "Apply preset.")
    public static final class PresetCommand extends PlannedStubCommand {
    }

    @Command(name = "prune", mixinStandardHelpOptions = true, description = "Prune resources.")
    public static final class PruneCommand extends PlannedStubCommand {
    }

    @Command(name = "query", mixinStandardHelpOptions = true, description = "Query resource.")
    public static final class QueryCommand extends PlannedStubCommand {
    }

    @Command(name = "references", mixinStandardHelpOptions = true, description = "Find references.")
    public static final class ReferencesCommand extends PlannedStubCommand {
    }

    @Command(name = "refresh", mixinStandardHelpOptions = true, description = "Refresh resource.")
    public static final class RefreshCommand extends PlannedStubCommand {
    }

    @Command(name = "reindex", mixinStandardHelpOptions = true, description = "Reindex resource.")
    public static final class ReindexCommand extends PlannedStubCommand {
    }

    @Command(name = "reject", mixinStandardHelpOptions = true, description = "Reject resource.")
    public static final class RejectCommand extends PlannedStubCommand {
    }

    @Command(name = "reload", mixinStandardHelpOptions = true, description = "Reload resource.")
    public static final class ReloadCommand extends PlannedStubCommand {
    }

    @Command(name = "remove", aliases = "rm", mixinStandardHelpOptions = true, description = "Remove resource.")
    public static final class RemoveCommand extends PlannedStubCommand {
    }

    @Command(name = "rename", mixinStandardHelpOptions = true, description = "Rename resource.")
    public static final class RenameCommand extends PlannedStubCommand {
    }

    @Command(name = "replay", mixinStandardHelpOptions = true, description = "Replay resource.")
    public static final class ReplayCommand extends PlannedStubCommand {
    }

    @Command(name = "reset", mixinStandardHelpOptions = true, description = "Reset resource.")
    public static final class ResetCommand extends PlannedStubCommand {
    }

    @Command(name = "restart", mixinStandardHelpOptions = true, description = "Restart resource.")
    public static final class RestartCommand extends PlannedStubCommand {
    }

    @Command(name = "restore", mixinStandardHelpOptions = true, description = "Restore resource.")
    public static final class RestoreCommand extends PlannedStubCommand {
    }

    @Command(name = "revert", mixinStandardHelpOptions = true, description = "Revert resource.")
    public static final class RevertCommand extends PlannedStubCommand {
    }

    @Command(name = "route", mixinStandardHelpOptions = true, description = "Explain route.")
    public static final class RouteCommand extends PlannedStubCommand {
    }

    @Command(name = "rules", mixinStandardHelpOptions = true, description = "Manage rules.")
    public static final class RulesCommand extends PlannedStubCommand {
    }

    @Command(name = "schedule", mixinStandardHelpOptions = true, description = "Manage schedules.")
    public static final class ScheduleCommand extends PlannedStubCommand {
    }

    @Command(name = "search", mixinStandardHelpOptions = true, description = "Search resources.")
    public static final class SearchCommand extends PlannedStubCommand {
    }

    @Command(name = "send", mixinStandardHelpOptions = true, description = "Send command.")
    public static final class SendCommand extends PlannedStubCommand {
    }

    @Command(name = "set", mixinStandardHelpOptions = true, description = "Set value.")
    public static final class SetCommand extends PlannedStubCommand {
    }

    @Command(name = "share", mixinStandardHelpOptions = true, description = "Share resource.")
    public static final class ShareCommand extends PlannedStubCommand {
    }

    @Command(name = "show", mixinStandardHelpOptions = true, description = "Show resource details.")
    public static final class ShowCommand extends PlannedStubCommand {
    }

    @Command(name = "skills", mixinStandardHelpOptions = true, description = "Manage skills.")
    public static final class SkillsCommand extends PlannedStubCommand {
    }

    @Command(name = "snapshot", mixinStandardHelpOptions = true, description = "Create snapshot.")
    public static final class SnapshotCommand extends PlannedStubCommand {
    }

    @Command(name = "split", mixinStandardHelpOptions = true, description = "Split resource.")
    public static final class SplitCommand extends PlannedStubCommand {
    }

    @Command(name = "start", mixinStandardHelpOptions = true, description = "Start resource.")
    public static final class StartCommand extends PlannedStubCommand {
    }

    @Command(name = "stats", mixinStandardHelpOptions = true, description = "Show stats.")
    public static final class StatsActionCommand extends PlannedStubCommand {
    }

    @Command(name = "status", mixinStandardHelpOptions = true, description = "Show status.")
    public static final class StatusCommand extends PlannedStubCommand {
    }

    @Command(name = "stop", mixinStandardHelpOptions = true, description = "Stop resource.")
    public static final class StopCommand extends PlannedStubCommand {
    }

    @Command(name = "symbols", mixinStandardHelpOptions = true, description = "Show symbols.")
    public static final class SymbolsCommand extends PlannedStubCommand {
    }

    @Command(name = "task", mixinStandardHelpOptions = true, description = "Manage tasks.")
    public static final class TaskCommand extends PlannedStubCommand {
    }

    @Command(name = "test", mixinStandardHelpOptions = true, description = "Test resource.")
    public static final class ProbeCommand extends PlannedStubCommand {
    }

    @Command(name = "tools", mixinStandardHelpOptions = true, description = "Group by tools.")
    public static final class ToolsActionCommand extends PlannedStubCommand {
    }

    @Command(name = "trace", mixinStandardHelpOptions = true, description = "Show trace.")
    public static final class TraceActionCommand extends PlannedStubCommand {
    }

    @Command(name = "trust", mixinStandardHelpOptions = true, description = "Trust project.")
    public static final class TrustCommand extends PlannedStubCommand {
    }

    @Command(name = "unpin", mixinStandardHelpOptions = true, description = "Unpin resource.")
    public static final class UnpinCommand extends PlannedStubCommand {
    }

    @Command(name = "unshare", mixinStandardHelpOptions = true, description = "Unshare resource.")
    public static final class UnshareCommand extends PlannedStubCommand {
    }

    @Command(name = "unset", mixinStandardHelpOptions = true, description = "Unset value.")
    public static final class UnsetCommand extends PlannedStubCommand {
    }

    @Command(name = "untrust", mixinStandardHelpOptions = true, description = "Untrust project.")
    public static final class UntrustCommand extends PlannedStubCommand {
    }

    @Command(name = "update", mixinStandardHelpOptions = true, description = "Update resource.")
    public static final class UpdateCommand extends PlannedStubCommand {
    }

    @Command(name = "usage", mixinStandardHelpOptions = true, description = "Show usage.")
    public static final class UsageCommand extends PlannedStubCommand {
    }

    @Command(name = "validate", mixinStandardHelpOptions = true, description = "Validate resource.")
    public static final class ValidateCommand extends PlannedStubCommand {
    }

    @Command(name = "waterfall", mixinStandardHelpOptions = true, description = "Show waterfall.")
    public static final class WaterfallCommand extends PlannedStubCommand {
    }

    @Command(name = "worktree", mixinStandardHelpOptions = true, description = "Manage worktrees.")
    public static final class WorktreeCommand extends PlannedStubCommand {
    }

}

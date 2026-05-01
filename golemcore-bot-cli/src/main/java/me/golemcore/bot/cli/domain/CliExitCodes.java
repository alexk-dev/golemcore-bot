package me.golemcore.bot.cli.domain;

import me.golemcore.bot.domain.cli.CliExitCode;

public final class CliExitCodes {

    public static final int SUCCESS = CliExitCode.SUCCESS.processCode();
    public static final int GENERAL_FAILURE = CliExitCode.GENERAL_ERROR.processCode();
    public static final int INVALID_USAGE = CliExitCode.INVALID_USAGE.processCode();
    public static final int CONFIG_ERROR = CliExitCode.CONFIG_ERROR.processCode();
    public static final int AUTHENTICATION_OR_PROVIDER_ERROR = CliExitCode.AUTHENTICATION_ERROR.processCode();
    public static final int PERMISSION_DENIED = CliExitCode.PERMISSION_DENIED.processCode();
    public static final int TOOL_EXECUTION_FAILURE = CliExitCode.TOOL_EXECUTION_FAILURE.processCode();
    public static final int MODEL_OR_LLM_FAILURE = CliExitCode.MODEL_FAILURE.processCode();
    public static final int TIMEOUT_OR_BUDGET_EXCEEDED = CliExitCode.TIMEOUT.processCode();
    public static final int SESSION_RUNTIME_UNAVAILABLE = CliExitCode.RUNTIME_UNAVAILABLE.processCode();
    public static final int PROJECT_UNTRUSTED_OR_RESTRICTED = CliExitCode.PROJECT_UNTRUSTED.processCode();
    public static final int PATCH_CONFLICT = CliExitCode.PATCH_CONFLICT.processCode();
    public static final int NETWORK_OR_MCP_FAILURE = CliExitCode.NETWORK_OR_MCP_FAILURE.processCode();
    public static final int CHECK_COMMAND_FAILED = CliExitCode.CHECK_FAILED.processCode();

    public static final int NOT_IMPLEMENTED = CliExitCode.FEATURE_UNAVAILABLE.processCode();

    private CliExitCodes() {
    }
}

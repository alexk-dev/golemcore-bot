package me.golemcore.bot.cli.domain;

public final class CliExitCodes {

    public static final int SUCCESS = 0;
    public static final int GENERAL_FAILURE = 1;
    public static final int INVALID_USAGE = 2;
    public static final int CONFIG_ERROR = 3;
    public static final int AUTHENTICATION_OR_PROVIDER_ERROR = 4;
    public static final int PERMISSION_DENIED = 5;
    public static final int TOOL_EXECUTION_FAILURE = 6;
    public static final int MODEL_OR_LLM_FAILURE = 7;
    public static final int TIMEOUT_OR_BUDGET_EXCEEDED = 8;
    public static final int SESSION_RUNTIME_UNAVAILABLE = 9;
    public static final int PROJECT_UNTRUSTED_OR_RESTRICTED = 10;
    public static final int PATCH_CONFLICT = 11;
    public static final int NETWORK_OR_MCP_FAILURE = 12;
    public static final int CHECK_COMMAND_FAILED = 13;

    public static final int NOT_IMPLEMENTED = SESSION_RUNTIME_UNAVAILABLE;

    private CliExitCodes() {
    }
}

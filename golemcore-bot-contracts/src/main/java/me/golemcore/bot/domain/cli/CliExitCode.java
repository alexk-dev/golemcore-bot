package me.golemcore.bot.domain.cli;

/**
 * Process exit codes exposed by CLI adapters.
 */
public enum CliExitCode {
    SUCCESS(0), GENERAL_ERROR(1), INVALID_USAGE(2), CONFIG_ERROR(3), AUTHENTICATION_ERROR(4), PERMISSION_DENIED(
            5), TOOL_EXECUTION_FAILURE(6), MODEL_FAILURE(7), TIMEOUT(8), RUNTIME_UNAVAILABLE(9), PROJECT_UNTRUSTED(
                    10), PATCH_CONFLICT(11), NETWORK_OR_MCP_FAILURE(12), CHECK_FAILED(13), CANCELLED(130);

    private final int numericCode;

    CliExitCode(int numericCode) {
        this.numericCode = numericCode;
    }

    public int processCode() {
        return numericCode;
    }

    public static CliExitCode fromProcessCode(int processCode) {
        for (CliExitCode exitCode : values()) {
            if (exitCode.numericCode == processCode) {
                return exitCode;
            }
        }
        return GENERAL_ERROR;
    }
}

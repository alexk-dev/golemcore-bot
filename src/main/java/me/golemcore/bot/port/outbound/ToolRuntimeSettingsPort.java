package me.golemcore.bot.port.outbound;

import java.time.Duration;

/**
 * Domain-facing access to tool runtime and loop execution settings.
 */
public interface ToolRuntimeSettingsPort {

    ToolExecutionSettings toolExecution();

    TurnSettings turn();

    ToolLoopSettings toolLoop();

    static TurnSettings defaultTurnSettings() {
        return new TurnSettings(200, 500, Duration.ofHours(1));
    }

    static ToolLoopSettings defaultToolLoopSettings() {
        return new ToolLoopSettings(false, true, false);
    }

    record ToolExecutionSettings(int maxToolResultChars) {
    }

    record TurnSettings(int maxLlmCalls, int maxToolExecutions, Duration deadline) {
        public TurnSettings {
            deadline = deadline != null ? deadline : Duration.ofHours(1);
        }
    }

    record ToolLoopSettings(
            boolean stopOnToolFailure,
            boolean stopOnConfirmationDenied,
            boolean stopOnToolPolicyDenied) {
    }
}

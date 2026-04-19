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
        return new ToolLoopSettings(20, 80, false, true, false);
    }

    record ToolExecutionSettings(int maxToolResultChars) {
    }

    record TurnSettings(int maxLlmCalls, int maxToolExecutions, Duration deadline) {
        public TurnSettings {
            deadline = deadline != null ? deadline : Duration.ofHours(1);
        }
    }

    record ToolLoopSettings(
            int maxLlmCalls,
            int maxToolExecutions,
            boolean stopOnToolFailure,
            boolean stopOnConfirmationDenied,
            boolean stopOnToolPolicyDenied) {

        public ToolLoopSettings(boolean stopOnToolFailure, boolean stopOnConfirmationDenied,
                boolean stopOnToolPolicyDenied) {
            this(20, 80, stopOnToolFailure, stopOnConfirmationDenied, stopOnToolPolicyDenied);
        }
    }
}

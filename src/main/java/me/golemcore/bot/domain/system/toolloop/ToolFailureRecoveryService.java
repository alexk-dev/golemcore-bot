package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolFailureRecoverability;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Classifies tool failures and decides whether the loop should stop or first
 * inject a bounded recovery hint for the model.
 */
@Component
public class ToolFailureRecoveryService {

    private static final int SHELL_RECOVERY_MAX_ATTEMPTS = 2;
    private static final int MAX_COMMAND_FINGERPRINT_LENGTH = 120;

    public ToolFailureRecoveryDecision evaluate(
            Message.ToolCall toolCall,
            ToolExecutionOutcome outcome,
            Map<String, Integer> recoveryCounts) {
        if (outcome == null || outcome.toolResult() == null || outcome.toolResult().isSuccess()) {
            return new ToolFailureRecoveryDecision(false, false, null, null, null);
        }

        String fingerprint = buildFingerprint(toolCall, outcome);
        ToolFailureRecoverability recoverability = classifyRecoverability(toolCall, outcome);
        if (!isShellTool(toolCall, outcome)) {
            return new ToolFailureRecoveryDecision(true, false, null, fingerprint, recoverability);
        }
        if (recoverability == ToolFailureRecoverability.FATAL
                || recoverability == ToolFailureRecoverability.USER_ACTION_REQUIRED) {
            return new ToolFailureRecoveryDecision(true, false, null, fingerprint, recoverability);
        }

        int attempts = recoveryCounts.merge(fingerprint, 1, Integer::sum);
        if (attempts <= SHELL_RECOVERY_MAX_ATTEMPTS) {
            return new ToolFailureRecoveryDecision(
                    false,
                    true,
                    buildRecoveryHint(toolCall, outcome, recoverability, attempts, SHELL_RECOVERY_MAX_ATTEMPTS),
                    fingerprint,
                    recoverability);
        }

        return new ToolFailureRecoveryDecision(true, false, null, fingerprint, recoverability);
    }

    public String buildFingerprint(Message.ToolCall toolCall, ToolExecutionOutcome outcome) {
        String toolName = resolveToolName(toolCall, outcome);
        String failureKind = outcome != null && outcome.toolResult() != null
                && outcome.toolResult().getFailureKind() != null
                        ? outcome.toolResult().getFailureKind().name()
                        : ToolFailureKind.EXECUTION_FAILED.name();
        if (isShellTool(toolCall, outcome)) {
            String command = normalizeShellCommand(toolCall);
            String errorBucket = classifyShellErrorBucket(outcome);
            return toolName + ":" + failureKind + ":cmd=" + command + ":bucket=" + errorBucket;
        }

        String error = outcome != null && outcome.toolResult() != null && outcome.toolResult().getError() != null
                ? outcome.toolResult().getError()
                : outcome != null ? outcome.messageContent() : null;
        String normalized = normalizeFreeText(error);
        return toolName + ":" + failureKind + ":" + normalized;
    }

    private ToolFailureRecoverability classifyRecoverability(Message.ToolCall toolCall, ToolExecutionOutcome outcome) {
        if (!isShellTool(toolCall, outcome)) {
            return ToolFailureRecoverability.FATAL;
        }
        if (outcome == null || outcome.toolResult() == null) {
            return ToolFailureRecoverability.SELF_CORRECTABLE;
        }
        ToolFailureKind failureKind = outcome.toolResult().getFailureKind();
        if (failureKind == ToolFailureKind.POLICY_DENIED) {
            return ToolFailureRecoverability.FATAL;
        }
        if (failureKind == ToolFailureKind.CONFIRMATION_DENIED) {
            return ToolFailureRecoverability.USER_ACTION_REQUIRED;
        }

        String normalizedError = normalizeFreeText(outcome.toolResult().getError() != null
                ? outcome.toolResult().getError()
                : outcome.messageContent());
        if (normalizedError.contains("command injection detected")
                || normalizedError.contains("command blocked for security reasons")
                || normalizedError.contains("tool is disabled")
                || normalizedError.contains("working directory must be within workspace")) {
            return ToolFailureRecoverability.FATAL;
        }
        if (normalizedError.contains("timed out")
                || normalizedError.contains("interrupted")) {
            return ToolFailureRecoverability.RETRYABLE;
        }
        return ToolFailureRecoverability.SELF_CORRECTABLE;
    }

    private String buildRecoveryHint(
            Message.ToolCall toolCall,
            ToolExecutionOutcome outcome,
            ToolFailureRecoverability recoverability,
            int attempt,
            int maxAttempts) {
        String command = extractShellCommand(toolCall);
        StringBuilder hint = new StringBuilder();
        hint.append("Shell recovery note:\n")
                .append("The previous shell command failed. ")
                .append("Do not repeat the same command unchanged.\n")
                .append("Recovery attempt ")
                .append(attempt)
                .append(" of ")
                .append(maxAttempts)
                .append(".\n");
        if (command != null && !command.isBlank()) {
            hint.append("Last command: `")
                    .append(command.trim())
                    .append("`\n");
        }
        String errorBucket = classifyShellErrorBucket(outcome);
        if ("workdir_missing".equals(errorBucket) || "path_not_found".equals(errorBucket)) {
            hint.append(
                    "First verify the current directory and locate the target path with simple diagnostics such as pwd, ls, or find.\n");
        } else if ("command_not_found".equals(errorBucket)) {
            hint.append("First verify which commands are available in the workspace environment before retrying.\n");
        } else if (recoverability == ToolFailureRecoverability.RETRYABLE) {
            hint.append("Wait briefly or simplify the command before retrying.\n");
        } else {
            hint.append("First inspect the environment with simple diagnostics such as pwd, ls, or find.\n");
        }
        hint.append("Then correct the workdir, path, or command before retrying. ")
                .append("If shell still fails after the remaining recovery budget, explain the blocker and switch strategy.");
        return hint.toString();
    }

    private boolean isShellTool(Message.ToolCall toolCall, ToolExecutionOutcome outcome) {
        return "shell".equals(resolveToolName(toolCall, outcome));
    }

    private String resolveToolName(Message.ToolCall toolCall, ToolExecutionOutcome outcome) {
        if (toolCall != null && toolCall.getName() != null && !toolCall.getName().isBlank()) {
            return toolCall.getName();
        }
        if (outcome != null && outcome.toolName() != null && !outcome.toolName().isBlank()) {
            return outcome.toolName();
        }
        return "unknown";
    }

    private String extractShellCommand(Message.ToolCall toolCall) {
        if (toolCall == null || toolCall.getArguments() == null) {
            return null;
        }
        Object commandObject = toolCall.getArguments().get("command");
        if (!(commandObject instanceof String command)) {
            return null;
        }
        return command;
    }

    private String normalizeShellCommand(Message.ToolCall toolCall) {
        String command = extractShellCommand(toolCall);
        if (command == null || command.isBlank()) {
            return "unknown";
        }
        String normalized = command.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_COMMAND_FINGERPRINT_LENGTH) {
            return normalized.substring(0, MAX_COMMAND_FINGERPRINT_LENGTH);
        }
        return normalized;
    }

    private String classifyShellErrorBucket(ToolExecutionOutcome outcome) {
        String normalizedError = normalizeFreeText(outcome != null && outcome.toolResult() != null
                && outcome.toolResult().getError() != null
                        ? outcome.toolResult().getError()
                        : outcome != null ? outcome.messageContent() : null);
        if (normalizedError.contains("working directory does not exist")) {
            return "workdir_missing";
        }
        if (normalizedError.contains("no such file or directory")) {
            return "path_not_found";
        }
        if (normalizedError.contains("command not found")) {
            return "command_not_found";
        }
        if (normalizedError.contains("timed out")) {
            return "timeout";
        }
        if (normalizedError.contains("command injection detected")
                || normalizedError.contains("command blocked for security reasons")
                || normalizedError.contains("working directory must be within workspace")) {
            return "security_blocked";
        }
        if (normalizedError.contains("exit code 1")) {
            return "exit_code_1";
        }
        if (normalizedError.contains("exit code 2")) {
            return "exit_code_2";
        }
        return "execution_failed";
    }

    private String normalizeFreeText(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}

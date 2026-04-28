package me.golemcore.bot.domain.system.toolloop.repeat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import me.golemcore.bot.domain.system.toolloop.TurnState;

/**
 * Decides whether a tool call should execute, warn, or be blocked as a repeat.
 */
public class ToolRepeatGuard {

    private final ToolUseFingerprintService fingerprintService;
    private final Supplier<ToolRepeatGuardSettings> settingsSupplier;
    private final Clock clock;

    public ToolRepeatGuard(
            ToolUseFingerprintService fingerprintService,
            ToolRepeatGuardSettings settings,
            Clock clock) {
        this(fingerprintService, () -> settings, clock);
    }

    public ToolRepeatGuard(
            ToolUseFingerprintService fingerprintService,
            Supplier<ToolRepeatGuardSettings> settingsSupplier,
            Clock clock) {
        this.fingerprintService = Objects.requireNonNull(fingerprintService);
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier);
        this.clock = Objects.requireNonNull(clock);
    }

    public static ToolRepeatGuard noop() {
        return new ToolRepeatGuard(
                new ToolUseFingerprintService(), ToolRepeatGuardSettings.disabled(), Clock.systemUTC());
    }

    public ToolRepeatDecision beforeExecute(TurnState turnState, Message.ToolCall toolCall) {
        ToolRepeatGuardSettings settings = settings();
        ToolUseFingerprint fingerprint = fingerprintService.fingerprint(toolCall);
        if (!settings.enabled()) {
            return new ToolRepeatDecision.Allow(fingerprint);
        }
        ToolUseLedger ledger = turnState.getToolUseLedger();
        if (ledger.getBlockedRepeatCount() >= settings.maxBlockedRepeatsPerTurn()) {
            return new ToolRepeatDecision.StopTurn(
                    "stopped after repeated blocked tool calls; no progress detected");
        }
        if (fingerprint.category() == ToolUseCategory.CONTROL) {
            return new ToolRepeatDecision.Allow(fingerprint);
        }

        return switch (fingerprint.category()) {
        case OBSERVE -> decideCountedRepeat(ledger, fingerprint, settings.maxSameObservePerTurn(), settings);
        case EXECUTE_UNKNOWN -> decideCountedRepeat(ledger, fingerprint, settings.maxSameUnknownPerTurn(), settings);
        case POLL -> decidePolling(ledger, fingerprint, settings);
        case MUTATE_IDEMPOTENT, MUTATE_NON_IDEMPOTENT -> decideMutation(ledger, fingerprint, settings);
        case CONTROL -> new ToolRepeatDecision.Allow(fingerprint);
        };
    }

    public void afterOutcome(TurnState turnState, Message.ToolCall toolCall, ToolExecutionOutcome outcome) {
        if (turnState == null || toolCall == null || outcome == null || outcome.toolResult() == null) {
            return;
        }
        ToolUseFingerprint fingerprint = fingerprintService.fingerprint(toolCall);
        ToolResult toolResult = outcome.toolResult();
        boolean guardBlocked = !toolResult.isSuccess()
                && toolResult.getFailureKind() != null
                && "REPEATED_TOOL_USE_BLOCKED".equals(toolResult.getFailureKind().name());
        Instant now = clock.instant();
        turnState.getToolUseLedger().record(new ToolUseRecord(
                fingerprint,
                now,
                now,
                toolResult.isSuccess(),
                toolResult.getFailureKind() != null ? toolResult.getFailureKind().name() : null,
                outputDigest(outcome),
                turnState.getToolUseLedger().getEnvironmentVersion(),
                guardBlocked,
                outcome.messageContent()));
    }

    public String repeatHint(ToolUseFingerprint fingerprint, int count) {
        String toolName = fingerprint != null ? fingerprint.toolName() : "unknown";
        return "Repeated tool call blocked by repeat guard. Tool: " + toolName
                + ". Reason: same fingerprint was already executed " + count
                + " times with no state change. Do not call this tool with the same arguments again in this turn. "
                + "Use the previous result already present in conversation/tool history, change the arguments, "
                + "perform a state-changing next step, write a diary/checkpoint, schedule a later check, "
                + "or provide the final answer.";
    }

    private ToolRepeatDecision decideCountedRepeat(
            ToolUseLedger ledger,
            ToolUseFingerprint fingerprint,
            int maxAllowed,
            ToolRepeatGuardSettings settings) {
        int count = ledger.repeatCountInCurrentEnvironment(fingerprint);
        if (count <= 0) {
            return new ToolRepeatDecision.Allow(fingerprint);
        }
        if (count < maxAllowed) {
            ledger.incrementWarnedRepeatCount();
            return new ToolRepeatDecision.WarnAndAllow(fingerprint, repeatHint(fingerprint, count));
        }
        return blockOrShadow(ledger, fingerprint, repeatHint(fingerprint, count), settings);
    }

    private ToolRepeatDecision decidePolling(
            ToolUseLedger ledger,
            ToolUseFingerprint fingerprint,
            ToolRepeatGuardSettings settings) {
        Instant now = clock.instant();
        boolean tooSoon = ledger.recordsForCurrentEnvironment(fingerprint).stream()
                .map(ToolUseRecord::finishedAt)
                .filter(Objects::nonNull)
                .anyMatch(finished -> finished.plus(settings.minPollInterval()).isAfter(now));
        if (!tooSoon) {
            return new ToolRepeatDecision.Allow(fingerprint);
        }
        String hint = "Polling before the configured backoff interval was blocked. "
                + "Do not poll again immediately. Schedule/resume later or continue with another independent step.";
        return blockOrShadow(ledger, fingerprint, hint, settings);
    }

    private ToolRepeatDecision decideMutation(
            ToolUseLedger ledger,
            ToolUseFingerprint fingerprint,
            ToolRepeatGuardSettings settings) {
        int count = ledger.repeatCountInCurrentEnvironment(fingerprint);
        if (count <= 0) {
            return new ToolRepeatDecision.Allow(fingerprint);
        }
        return blockOrShadow(ledger, fingerprint,
                "Repeated exact mutation blocked by repeat guard. Tool: " + fingerprint.toolName()
                        + ". Confirm a new intent or change arguments before retrying.",
                settings);
    }

    private ToolRepeatDecision blockOrShadow(
            ToolUseLedger ledger,
            ToolUseFingerprint fingerprint,
            String hint,
            ToolRepeatGuardSettings settings) {
        ledger.incrementBlockedRepeatCount();
        if (settings.shadowMode()) {
            ledger.incrementWarnedRepeatCount();
            return new ToolRepeatDecision.WarnAndAllow(fingerprint, hint, true);
        }
        return new ToolRepeatDecision.BlockAndHint(fingerprint, hint);
    }

    private ToolRepeatGuardSettings settings() {
        ToolRepeatGuardSettings settings = settingsSupplier.get();
        return settings != null ? settings : ToolRepeatGuardSettings.defaults();
    }

    private String outputDigest(ToolExecutionOutcome outcome) {
        String value = outcome.messageContent() != null ? outcome.messageContent() : "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}

package me.golemcore.bot.domain.system.toolloop.repeat;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import me.golemcore.bot.domain.system.toolloop.TurnState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a tool call should execute, warn, or be blocked as a repeat.
 */
public class ToolRepeatGuard {

    public static final String STOP_TURN_REASON = "stopped after repeated blocked tool calls; no progress detected";
    private static final Logger log = LoggerFactory.getLogger(ToolRepeatGuard.class);
    private static final Set<String> SECRET_FIELD_FRAGMENTS = Set.of(
            "token", "password", "secret", "apikey", "api_key", "authorization");

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
        ToolUseFingerprint fingerprint = safeFingerprint(toolCall);
        if (!settings.enabled()) {
            return new ToolRepeatDecision.Allow(fingerprint);
        }
        ToolUseLedger ledger = turnState.getToolUseLedger();
        if (fingerprint.category() == ToolUseCategory.CONTROL) {
            return new ToolRepeatDecision.Allow(fingerprint);
        }

        boolean durableLedgerActive = durableLedgerActive(turnState);
        return switch (fingerprint.category()) {
        case OBSERVE -> decideCountedRepeat(ledger, fingerprint, settings.maxSameObservePerTurn(), settings,
                durableLedgerActive);
        case EXECUTE_UNKNOWN -> decideCountedRepeat(
                ledger, fingerprint, settings.maxSameUnknownPerTurn(), settings, durableLedgerActive);
        case POLL -> decidePolling(ledger, fingerprint, settings);
        case MUTATE_IDEMPOTENT, MUTATE_NON_IDEMPOTENT -> decideMutation(ledger, fingerprint, settings,
                durableLedgerActive);
        case CONTROL -> new ToolRepeatDecision.Allow(fingerprint);
        };
    }

    public void afterOutcome(TurnState turnState, Message.ToolCall toolCall, ToolExecutionOutcome outcome) {
        if (!settings().enabled()) {
            return;
        }
        if (turnState == null || toolCall == null || outcome == null || outcome.toolResult() == null) {
            return;
        }
        ToolUseFingerprint fingerprint = safeFingerprint(toolCall);
        ToolResult toolResult = outcome.toolResult();
        boolean guardBlocked = !toolResult.isSuccess()
                && toolResult.getFailureKind() != null
                && ("REPEATED_TOOL_USE_BLOCKED".equals(toolResult.getFailureKind().name())
                        || "REPEAT_GUARD_STOP_TURN".equals(toolResult.getFailureKind().name()));
        Instant now = clock.instant();
        turnState.getToolUseLedger().recordUse(new ToolUseRecord(
                fingerprint,
                now,
                now,
                toolResult.isSuccess(),
                toolResult.getFailureKind() != null ? toolResult.getFailureKind().name() : null,
                outputDigest(outcome),
                turnState.getToolUseLedger().getEnvironmentVersion(),
                guardBlocked,
                guardBlocked ? outcome.messageContent() : null));
    }

    public String repeatHint(ToolUseFingerprint fingerprint, int count) {
        return blockedHint(fingerprint, count, false);
    }

    public String repeatHint(ToolUseFingerprint fingerprint, int count, boolean durableLedgerActive) {
        return blockedHint(fingerprint, count, durableLedgerActive);
    }

    private String warningHint(ToolUseFingerprint fingerprint, int count, boolean durableLedgerActive) {
        String toolName = fingerprint != null ? fingerprint.toolName() : "unknown";
        return "Repeated tool call allowed this time by repeat guard. Tool: " + toolName
                + ". Reason: same fingerprint was already executed " + count
                + " " + timeWord(count) + " with no verified state change. The next identical call may be blocked "
                + scope(durableLedgerActive) + ". "
                + "Prefer the previous result already present in conversation/tool history, change the arguments, "
                + "perform a state-changing next step, write a diary/checkpoint, schedule a later check, "
                + "or provide the final answer.";
    }

    private String blockedHint(ToolUseFingerprint fingerprint, int count, boolean durableLedgerActive) {
        String toolName = fingerprint != null ? fingerprint.toolName() : "unknown";
        return "Repeated tool call blocked by repeat guard. Tool: " + toolName
                + ". Reason: same fingerprint was already executed " + count
                + " " + timeWord(count)
                + " with no verified state change. Do not call this tool with the same arguments again "
                + scope(durableLedgerActive) + ". "
                + "Use the previous result already present in conversation/tool history, change the arguments, "
                + "perform a state-changing next step, write a diary/checkpoint, schedule a later check, "
                + "or provide the final answer.";
    }

    private String scope(boolean durableLedgerActive) {
        return durableLedgerActive
                ? "for this autonomous task until state changes, arguments change, or the repeat ledger TTL expires"
                : "in this turn";
    }

    private ToolRepeatDecision decideCountedRepeat(
            ToolUseLedger ledger,
            ToolUseFingerprint fingerprint,
            int maxAllowed,
            ToolRepeatGuardSettings settings,
            boolean durableLedgerActive) {
        int count = ledger.successfulRepeatCountInCurrentEnvironment(fingerprint);
        if (count <= 0) {
            return new ToolRepeatDecision.Allow(fingerprint);
        }
        if (count < maxAllowed) {
            ledger.incrementWarnedRepeatCount();
            return new ToolRepeatDecision.WarnAndAllow(fingerprint, warningHint(fingerprint, count,
                    durableLedgerActive));
        }
        return blockOrShadow(ledger, fingerprint, blockedHint(fingerprint, count, durableLedgerActive), settings);
    }

    private ToolRepeatDecision decidePolling(
            ToolUseLedger ledger,
            ToolUseFingerprint fingerprint,
            ToolRepeatGuardSettings settings) {
        Instant now = clock.instant();
        boolean tooSoon = ledger.lastActualAttemptAt(fingerprint)
                .map(finished -> finished.plus(settings.minPollInterval()).isAfter(now))
                .orElse(false);
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
            ToolRepeatGuardSettings settings,
            boolean durableLedgerActive) {
        int count = ledger.successfulRepeatCountInCurrentEnvironment(fingerprint);
        if (count <= 0) {
            return new ToolRepeatDecision.Allow(fingerprint);
        }
        return blockOrShadow(ledger, fingerprint,
                "Repeated exact mutation blocked by repeat guard. Tool: " + fingerprint.toolName()
                        + ". Confirm a new intent or change arguments before retrying.",
                settings);
    }

    private String timeWord(int count) {
        return count == 1 ? "time" : "times";
    }

    private ToolRepeatDecision blockOrShadow(
            ToolUseLedger ledger,
            ToolUseFingerprint fingerprint,
            String hint,
            ToolRepeatGuardSettings settings) {
        if (settings.shadowMode()) {
            ledger.incrementWarnedRepeatCount();
            return new ToolRepeatDecision.WarnAndAllow(fingerprint, hint, true);
        }
        if (ledger.getBlockedRepeatCount() >= settings.maxBlockedRepeatsPerTurn()) {
            return new ToolRepeatDecision.StopTurn(STOP_TURN_REASON, fingerprint);
        }
        ledger.incrementBlockedRepeatCount();
        return new ToolRepeatDecision.BlockAndHint(fingerprint, hint);
    }

    private ToolUseFingerprint safeFingerprint(Message.ToolCall toolCall) {
        try {
            return fingerprintService.fingerprint(toolCall);
        } catch (RuntimeException e) {
            String toolName = toolCall != null && toolCall.getName() != null
                    ? toolCall.getName().trim().toLowerCase(Locale.ROOT)
                    : "";
            String fallbackHash = sha256(redactedArgumentSnapshot(toolCall != null ? toolCall.getArguments() : null));
            String stableKey = toolName + ":EXECUTE_UNKNOWN:fingerprint-failed:" + fallbackHash;
            log.warn("Repeat guard fingerprinting failed for tool '{}' with fallback hash {}: {}",
                    toolName, fallbackHash, e.getClass().getName());
            return new ToolUseFingerprint(
                    toolName,
                    ToolUseCategory.EXECUTE_UNKNOWN,
                    "sha256:" + fallbackHash,
                    stableKey,
                    null,
                    Set.of(ToolStateDomain.UNKNOWN),
                    Set.of(),
                    false);
        }
    }

    private ToolRepeatGuardSettings settings() {
        ToolRepeatGuardSettings settings = settingsSupplier.get();
        return settings != null ? settings : ToolRepeatGuardSettings.defaults();
    }

    private boolean durableLedgerActive(TurnState turnState) {
        AgentContext context = turnState != null ? turnState.getContext() : null;
        return context != null && AutonomyWorkKey.fromMetadata(context.getAttributes()).isPresent();
    }

    private String outputDigest(ToolExecutionOutcome outcome) {
        String semanticDigest = semanticOutputDigest(outcome);
        if (semanticDigest != null) {
            return semanticDigest;
        }
        String value = outcome.messageContent() != null ? outcome.messageContent() : "";
        return "sha256:" + sha256(value);
    }

    private String semanticOutputDigest(ToolExecutionOutcome outcome) {
        if (outcome == null
                || outcome.toolResult() == null
                || !(outcome.toolResult().getData() instanceof Map<?, ?> data)) {
            return null;
        }
        Object value = data.get("semanticOutputDigest");
        if (!(value instanceof String primaryValue) || primaryValue.isBlank()) {
            value = data.get("semanticDigest");
        }
        if (!(value instanceof String digestValue) || digestValue.isBlank()) {
            return null;
        }
        String trimmed = digestValue.trim();
        return trimmed.startsWith("sha256:") ? trimmed : "sha256:" + sha256(trimmed);
    }

    private String redactedArgumentSnapshot(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, String> sorted = new TreeMap<>();
            map.forEach((key, item) -> {
                String field = String.valueOf(key);
                sorted.put(field, isSecretField(field) ? "<redacted>" : redactedArgumentSnapshot(item));
            });
            return sorted.toString();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(this::redactedArgumentSnapshot)
                    .toList()
                    .toString();
        }
        if (value.getClass().isArray()) {
            Map<Integer, String> values = new LinkedHashMap<>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                values.put(index, redactedArgumentSnapshot(Array.get(value, index)));
            }
            return values.values().toString();
        }
        return String.valueOf(value);
    }

    private boolean isSecretField(String field) {
        String normalized = field == null ? "" : field.toLowerCase(Locale.ROOT);
        for (String fragment : SECRET_FIELD_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}

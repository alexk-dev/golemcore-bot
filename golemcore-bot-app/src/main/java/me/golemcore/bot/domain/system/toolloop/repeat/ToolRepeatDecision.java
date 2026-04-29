package me.golemcore.bot.domain.system.toolloop.repeat;

/**
 * Pre-execution repeat guard decision.
 */
// @formatter:off
public sealed interface ToolRepeatDecision {

    record Allow(ToolUseFingerprint fingerprint) implements ToolRepeatDecision {
    }

    record WarnAndAllow(
            ToolUseFingerprint fingerprint,
            String hint,
            boolean wouldBlock) implements ToolRepeatDecision {

        public WarnAndAllow(ToolUseFingerprint fingerprint, String hint) {
            this(fingerprint, hint, false);
        }
    }

    record BlockAndHint(ToolUseFingerprint fingerprint, String hint) implements ToolRepeatDecision {
    }

    record StopTurn(String reason, ToolUseFingerprint fingerprint) implements ToolRepeatDecision {

        public StopTurn(String reason) {
            this(reason, null);
        }
    }
}
// @formatter:on

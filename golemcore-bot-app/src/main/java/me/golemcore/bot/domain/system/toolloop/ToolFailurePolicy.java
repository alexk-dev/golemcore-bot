package me.golemcore.bot.domain.system.toolloop;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolRepeatGuard;

import java.time.Clock;

/**
 * Unified policy for all tool failure handling within the tool loop.
 *
 * <p>
 * Evaluates three layers of policy in order:
 * <ol>
 * <li><b>Immediate stop conditions</b> — confirmation denied, policy
 * denied</li>
 * <li><b>Repeated failure recovery</b> — delegates to
 * {@link ToolFailureRecoveryService} for fingerprint-based tracking and bounded
 * shell recovery</li>
 * <li><b>Generic stop-on-failure</b> — configurable flag from tool loop
 * settings</li>
 * </ol>
 *
 * @see ToolFailureRecoveryService
 * @see ToolFailureRecoveryDecision
 */
public class ToolFailurePolicy {

    private final ToolFailureRecoveryService recoveryService;
    private final Clock clock;

    /**
     * @param recoveryService
     *            service for classifying and recovering from shell failures
     * @param clock
     *            clock for timestamps in failure events
     */
    public ToolFailurePolicy(ToolFailureRecoveryService recoveryService, Clock clock) {
        this.recoveryService = recoveryService;
        this.clock = clock;
    }

    /**
     * Result of evaluating the failure policy for a single tool execution.
     */
    public sealed

    interface Verdict {

        /** Continue normally — no policy triggered. */
        record Ok() implements Verdict {
        }

        /**
         * Stop the turn immediately with the given reason.
         *
         * @param reason
         *            user-safe stop reason
         */
        record StopTurn(String reason) implements Verdict {
        }

        /**
         * Inject a recovery hint and break out of the current tool batch.
         *
         * @param hint
         *            guidance injected into the next LLM step
         * @param fingerprint
         *            stable failure fingerprint
         * @param recoverabilityName
         *            recovery classification name
         */
        record RecoveryHint(String hint, String fingerprint, String recoverabilityName) implements Verdict {
        }
    }

    /**
     * Evaluates the failure policy for a tool execution outcome.
     *
     * <p>
     * Returns {@link Verdict.Ok} if no policy triggers, {@link Verdict.StopTurn}
     * if the turn should stop, or {@link Verdict.RecoveryHint} if a recovery
     * hint should be injected.
     *
     * @param turnState the current turn state with counters and config
     * @param toolCall  the tool call that failed
     * @param outcome   the execution outcome
     * @return the verdict for this failure
     */
    public Verdict evaluate(TurnState turnState, Message.ToolCall toolCall, ToolExecutionOutcome outcome) {
        if (outcome == null || outcome.toolResult() == null || outcome.toolResult().isSuccess()) {
            return new Verdict.Ok();
        }

        ToolFailureKind kind = outcome.toolResult().getFailureKind();

        if (kind == ToolFailureKind.REPEATED_TOOL_USE_BLOCKED) {
            if (ToolRepeatGuard.STOP_TURN_REASON.equals(outcome.toolResult().getError())) {
                return new Verdict.StopTurn(ToolRepeatGuard.STOP_TURN_REASON);
            }
            return new Verdict.RecoveryHint(repeatGuardHint(outcome), repeatGuardFingerprint(toolCall),
                    "REPEAT_GUARD");
        }

        // Layer 1: Immediate stop conditions
        if (turnState.isStopOnConfirmationDenied() && kind == ToolFailureKind.CONFIRMATION_DENIED) {
            return new Verdict.StopTurn("confirmation denied");
        }
        if (turnState.isStopOnToolPolicyDenied() && kind == ToolFailureKind.POLICY_DENIED) {
            return new Verdict.StopTurn("tool denied by policy");
        }

        // Layer 2: Repeated failure recovery
        ToolFailureRecoveryDecision recoveryDecision = evaluateRecovery(turnState, toolCall, outcome);
        if (recoveryDecision instanceof ToolFailureRecoveryDecision.InjectHint hint) {
            return new Verdict.RecoveryHint(hint.hint(), hint.fingerprint(), hint.recoverability().name());
        }
        if (recoveryDecision instanceof ToolFailureRecoveryDecision.Stop) {
            return new Verdict.StopTurn("repeated tool failure (" + outcome.toolName() + ")");
        }

        // Layer 3: Generic stop-on-failure
        if (turnState.isStopOnToolFailure()) {
            return new Verdict.StopTurn("tool failure (" + outcome.toolName() + ")");
        }

        return new Verdict.Ok();
    }

        private String repeatGuardHint(ToolExecutionOutcome outcome) {
            String error = outcome.toolResult().getError();
            if (error == null || error.isBlank()) {
                return "Repeated tool call blocked by repeat guard. Use the previous result, change arguments, "
                        + "perform a state-changing step, checkpoint progress, or finish the turn.";
            }
            return error;
        }

        private String repeatGuardFingerprint(Message.ToolCall toolCall) {
            if (toolCall == null) {
                return "repeat-guard:unknown";
            }
            return "repeat-guard:" + toolCall.getName() + ":" + toolCall.getId();
        }

        private ToolFailureRecoveryDecision evaluateRecovery(TurnState turnState, Message.ToolCall toolCall,
                ToolExecutionOutcome outcome) {
            String fingerprint = recoveryService.buildFingerprint(toolCall, outcome);
            int attempts = turnState.getToolFailureCounts().merge(fingerprint, 1, Integer::sum);
            if (attempts < 2) {
                return new ToolFailureRecoveryDecision.Continue(fingerprint);
            }

            ToolFailureRecoveryDecision decision = recoveryService.evaluate(toolCall, outcome,
                    turnState.getToolRecoveryCounts());

            FailureKind failureKind = (decision instanceof ToolFailureRecoveryDecision.InjectHint)
                    ? FailureKind.VALIDATION
                    : FailureKind.EXCEPTION;
            String message = (decision instanceof ToolFailureRecoveryDecision.InjectHint)
                    ? "Tool recovery requested: " + fingerprint
                    : "Repeated tool failure: " + fingerprint;

            turnState.getContext().addFailure(new FailureEvent(
                    FailureSource.TOOL, "DefaultToolLoopSystem", failureKind, message, clock.instant()));

            return decision;
        }
}

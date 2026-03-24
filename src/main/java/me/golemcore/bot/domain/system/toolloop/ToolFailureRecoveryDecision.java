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

import me.golemcore.bot.domain.model.ToolFailureRecoverability;

/**
 * Decision returned by {@link ToolFailureRecoveryService} for a single failed
 * tool execution.
 *
 * <p>
 * Modeled as a sealed interface with three mutually exclusive outcomes:
 * <ul>
 *   <li>{@link Continue} — first occurrence, no action needed yet</li>
 *   <li>{@link InjectHint} — inject a recovery hint and let the model retry</li>
 *   <li>{@link Stop} — stop the tool loop for this failure</li>
 * </ul>
 */
public sealed

interface ToolFailureRecoveryDecision {

    /** The failure fingerprint that triggered this decision. */
    String fingerprint();

    /**
     * First occurrence of this failure — the loop continues normally without
     * any recovery action.
     *
     * @param fingerprint failure fingerprint for tracking
     */
    record Continue(String fingerprint) implements ToolFailureRecoveryDecision {
    }

    /**
     * Inject a recovery hint into the conversation and give the model another
     * chance to self-correct.
     *
     * @param hint           the recovery guidance text to inject
     * @param fingerprint    failure fingerprint for tracking
     * @param recoverability classification of the failure
     */
    record InjectHint(String hint, String fingerprint, ToolFailureRecoverability recoverability)
            implements ToolFailureRecoveryDecision {
    }

    /**
     * Stop the tool loop — the failure is either fatal, exhausted the recovery
     * budget, or applies to a non-shell tool.
     *
     * @param fingerprint    failure fingerprint for tracking
     * @param recoverability classification of the failure (may be null for
     *                       non-recoverable scenarios)
     */
    record Stop(String fingerprint, ToolFailureRecoverability recoverability) implements ToolFailureRecoveryDecision {
    }
}

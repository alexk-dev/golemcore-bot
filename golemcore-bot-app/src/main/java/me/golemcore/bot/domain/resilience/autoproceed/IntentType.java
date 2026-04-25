package me.golemcore.bot.domain.resilience.autoproceed;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

/**
 * Classifier intent taxonomy for the Auto-Proceed layer.
 *
 * <p>
 * Only {@link #RHETORICAL_CONFIRM} triggers an auto-affirmation. Every other
 * intent type is treated as a case where a human must reply (fail-closed).
 */
public enum IntentType {
    /**
     * Assistant ended with a rhetorical confirmation question that implies a single
     * obvious forward path ("Ready to continue?", "Shall I proceed?").
     */
    RHETORICAL_CONFIRM,

    /**
     * Assistant offered branching options or alternatives and is waiting for the
     * user to pick one.
     */
    CHOICE_REQUEST,

    /**
     * Assistant asked a clarifying or information-seeking question that the user
     * must answer.
     */
    OPEN_QUESTION,

    /** Assistant reported completion or made a statement — nothing to confirm. */
    STATEMENT,

    /** Classifier could not decide; treated as non-actionable. */
    UNKNOWN
}

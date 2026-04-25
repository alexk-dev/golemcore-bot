package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

/**
 * Classifier intent taxonomy for the assistant's final reply.
 *
 * <p>
 * Only {@link #COMMITMENT} can trigger a follow-through nudge. Other intent
 * types — even when the text superficially resembles a promise — are treated as
 * non-actionable (user must reply / nothing to follow up on).
 */
public enum IntentType {
    /** Assistant unambiguously committed to execute the next step itself. */
    COMMITMENT,

    /** Assistant offered options or alternatives and is waiting for user choice. */
    OPTIONS_OFFERED,

    /** Assistant asked the user a clarifying question. */
    QUESTION,

    /** Assistant reported that work is finished. */
    COMPLETION,

    /**
     * Assistant acknowledged input without committing to a concrete next action.
     */
    ACKNOWLEDGEMENT,

    /** Classifier could not decide; treated as non-commitment. */
    UNKNOWN
}

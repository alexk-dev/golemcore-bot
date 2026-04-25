package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

/**
 * Coarse server-side category for the assistant commitment.
 */
public enum CommitmentCategory {
    READ_FILES, RUN_TESTS, SUMMARIZE, UNKNOWN
}

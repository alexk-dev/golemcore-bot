package me.golemcore.bot.domain.resilience.autoproceed;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

/**
 * Risk level emitted by the classifier.
 */
public enum RiskLevel {
    LOW, MEDIUM, HIGH;

    public boolean isHighRisk() {
        return this == HIGH;
    }
}

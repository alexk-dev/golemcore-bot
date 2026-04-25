package me.golemcore.bot.domain.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

/**
 * Conservative risk level assigned by resilience classifiers.
 */
public enum RiskLevel {
    LOW, MEDIUM, HIGH;

    public boolean isHighRisk() {
        return this == HIGH;
    }
}

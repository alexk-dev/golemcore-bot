package me.golemcore.bot.domain.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

/**
 * Minimal prompt-builder contract shared by resilience classifiers.
 */
public interface ClassifierPromptBuilder {

    String systemPrompt();

    String userPrompt(ClassifierRequest request);
}

package me.golemcore.bot.domain.resilience.autoproceed;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.resilience.AbstractClassifierService;
import me.golemcore.bot.domain.resilience.ClassifierPromptBuilder;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Domain service that runs the Auto-Proceed classifier LLM call.
 *
 * <p>
 * The classifier is intentionally fail-closed: any error, timeout, empty
 * response, or malformed JSON collapses to an {@link IntentType#UNKNOWN}
 * non-actionable verdict so that a classifier hiccup never triggers a
 * false-positive affirmation.
 */
@Service
@Slf4j
public class AutoProceedClassifier extends AbstractClassifierService<ClassifierVerdict> {

    private static final String CALLER_TAG = "auto_proceed";
    private static final String LOG_PREFIX = "AutoProceed";

    private final AutoProceedPromptBuilder classifierPromptBuilder;
    private final AutoProceedVerdictParser verdictParser;

    public AutoProceedClassifier(LlmPort llmPort, ModelSelectionService modelSelectionService,
            AutoProceedPromptBuilder classifierPromptBuilder, AutoProceedVerdictParser verdictParser) {
        super(llmPort, modelSelectionService);
        this.classifierPromptBuilder = classifierPromptBuilder;
        this.verdictParser = verdictParser;
    }

    @Override
    protected Logger logger() {
        return log;
    }

    @Override
    protected String logPrefix() {
        return LOG_PREFIX;
    }

    @Override
    protected String callerTag() {
        return CALLER_TAG;
    }

    @Override
    protected ClassifierPromptBuilder promptBuilder() {
        return classifierPromptBuilder;
    }

    @Override
    protected ClassifierVerdict parseResponse(String rawResponse) {
        return verdictParser.parse(rawResponse);
    }

    @Override
    protected ClassifierVerdict failureVerdict(String reason) {
        return ClassifierVerdict.nonActionable(IntentType.UNKNOWN, reason);
    }
}

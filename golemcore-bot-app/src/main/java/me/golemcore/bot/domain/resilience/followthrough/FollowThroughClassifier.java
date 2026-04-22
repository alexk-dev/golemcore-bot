package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.resilience.AbstractClassifierService;
import me.golemcore.bot.domain.resilience.ClassifierPromptBuilder;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Domain service that runs the follow-through classifier LLM call.
 *
 * <p>
 * The classifier is intentionally fail-closed: any error, timeout, empty
 * response, or malformed JSON collapses to an {@link IntentType#UNKNOWN}
 * non-commitment verdict so that a classifier hiccup never triggers a
 * false-positive nudge.
 */
@Service
@Slf4j
public class FollowThroughClassifier extends AbstractClassifierService<ClassifierVerdict> {

    private static final String CALLER_TAG = "follow_through";
    private static final String LOG_PREFIX = "FollowThrough";

    private final FollowThroughPromptBuilder classifierPromptBuilder;
    private final FollowThroughVerdictParser verdictParser;

    public FollowThroughClassifier(LlmPort llmPort, ModelSelectionService modelSelectionService,
            FollowThroughPromptBuilder classifierPromptBuilder, FollowThroughVerdictParser verdictParser) {
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
        return ClassifierVerdict.nonCommitment(IntentType.UNKNOWN, reason);
    }
}

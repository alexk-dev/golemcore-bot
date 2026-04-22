package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowThroughVerdictParserTest {

    private FollowThroughVerdictParser parser;

    @BeforeEach
    void setUp() {
        parser = new FollowThroughVerdictParser(new FakeCodec());
    }

    @Test
    void shouldParseCommitmentVerdictFromClassifierJson() {
        String json = """
                {
                  "intent_type": "commitment",
                  "has_unfulfilled_commitment": true,
                  "commitment_category": "read_files",
                  "risk_level": "low",
                  "commitment_text": "gather the three files",
                  "continuation_prompt": "Proceed with gathering the three files you committed to.",
                  "reason": "assistant said 'I'll now gather' without invoking any tool"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.COMMITMENT, verdict.intentType());
        assertTrue(verdict.hasUnfulfilledCommitment());
        assertEquals("gather the three files", verdict.commitmentText());
        assertEquals(CommitmentCategory.READ_FILES, verdict.commitmentCategory());
        assertEquals(RiskLevel.LOW, verdict.riskLevel());
        assertNotNull(verdict.reason());
    }

    @Test
    void shouldForceFalseWhenIntentIsOptionsOfferedEvenIfModelSaysUnfulfilled() {
        String json = """
                {
                  "intent_type": "options_offered",
                  "has_unfulfilled_commitment": true,
                  "commitment_category": "run_tests",
                  "risk_level": "low",
                  "commitment_text": "any of the three",
                  "continuation_prompt": "Pick option A.",
                  "reason": "offered choices"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.OPTIONS_OFFERED, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment(),
                "options_offered must never produce an unfulfilled commitment nudge");
    }

    @Test
    void shouldForceFalseWhenIntentIsQuestion() {
        String json = """
                {
                  "intent_type": "question",
                  "has_unfulfilled_commitment": true,
                  "reason": "clarifying question"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.QUESTION, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }

    @Test
    void shouldReturnUnknownAndNoCommitmentForInvalidJson() {
        ClassifierVerdict verdict = parser.parse("not json at all");

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
        assertNotNull(verdict.reason());
    }

    @Test
    void shouldReturnUnknownAndNoCommitmentForNullInput() {
        ClassifierVerdict verdict = parser.parse(null);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }

    @Test
    void shouldKeepStructuredCommitmentWhenPromptFieldIsMissing() {
        String json = """
                {
                  "intent_type": "commitment",
                  "has_unfulfilled_commitment": true,
                  "commitment_category": "summarize",
                  "risk_level": "medium",
                  "commitment_text": "summarise the result",
                  "reason": "missing prompt is fine because server builds it"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.COMMITMENT, verdict.intentType());
        assertTrue(verdict.hasUnfulfilledCommitment(),
                "missing continuation_prompt must stay actionable because the server authors the safe prompt");
        assertEquals(CommitmentCategory.SUMMARIZE, verdict.commitmentCategory());
        assertEquals(RiskLevel.MEDIUM, verdict.riskLevel());
    }

    @Test
    void shouldExtractJsonFromResponseThatWrapsItInMarkdownFence() {
        String json = """
                Here is the analysis:
                ```json
                {
                  "intent_type": "completion",
                  "has_unfulfilled_commitment": false,
                  "reason": "assistant reported done"
                }
                ```
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.COMPLETION, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }

    @Test
    void shouldParseUnknownIntentTypeValueAsUnknown() {
        String json = """
                {
                  "intent_type": "gibberish",
                  "has_unfulfilled_commitment": true,
                  "reason": "unknown label"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }

    @Test
    void shouldExtractFirstJsonObjectWhenResponseIsPrecededByJsonLikeProse() {
        String raw = """
                Reasoning: consider the shape { brace } then produce the verdict below.
                {"intent_type":"commitment","has_unfulfilled_commitment":true,\
                "commitment_text":"gather the three files",\
                "continuation_prompt":"Gather the three files now.",\
                "reason":"committed but no tool invoked"}
                """;

        ClassifierVerdict verdict = parser.parse(raw);

        assertEquals(IntentType.COMMITMENT, verdict.intentType());
        assertTrue(verdict.hasUnfulfilledCommitment(),
                "greedy regex would absorb the leading '{ brace }' + prose and fail JSON decode → UNKNOWN/false");
        assertEquals("committed but no tool invoked", verdict.reason());
        assertEquals("Gather the three files now.", verdict.continuationPrompt());
    }

    @Test
    void shouldExtractJsonEvenWhenStringValuesContainEscapedBraces() {
        String raw = """
                {"intent_type":"commitment","has_unfulfilled_commitment":true,\
                "commitment_text":"say \\"go {ahead}\\" now",\
                "continuation_prompt":"Say \\"go {ahead}\\" now.",\
                "reason":"escaped braces inside strings must not confuse the scanner"}
                """;

        ClassifierVerdict verdict = parser.parse(raw);

        assertEquals(IntentType.COMMITMENT, verdict.intentType());
        assertTrue(verdict.hasUnfulfilledCommitment());
        assertEquals("say \"go {ahead}\" now", verdict.commitmentText());
    }

    @Test
    void shouldReturnUnknownWhenBraceIsUnbalanced() {
        ClassifierVerdict verdict = parser.parse("{\"intent_type\":\"commitment\"");

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
        assertNotNull(verdict.reason());
    }

    @Test
    void shouldReturnUnknownWhenFencedBlockContainsInvalidJson() {
        String raw = """
                ```json
                {not valid json}
                ```
                """;

        ClassifierVerdict verdict = parser.parse(raw);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
        assertTrue(verdict.reason().contains("not parseable as JSON"));
    }

    @Test
    void shouldDefaultToUnknownWhenIntentTypeFieldIsMissing() {
        String json = """
                {
                  "has_unfulfilled_commitment": false,
                  "reason": "no intent_type provided"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }

    @Test
    void shouldDefaultToUnknownWhenIntentTypeIsEmptyString() {
        String json = """
                {
                  "intent_type": "   ",
                  "reason": "blank intent_type"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }

    @Test
    void shouldTreatCommitmentWithRawFlagFalseAsFulfilled() {
        String json = """
                {
                  "intent_type": "commitment",
                  "has_unfulfilled_commitment": false,
                  "commitment_category": "read_files",
                  "risk_level": "low",
                  "commitment_text": "already done",
                  "reason": "carried out"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.COMMITMENT, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
        assertEquals("already done", verdict.commitmentText());
    }

    @Test
    void shouldTrimCommitmentTextAndContinuationPrompt() {
        String json = """
                {
                  "intent_type": "commitment",
                  "has_unfulfilled_commitment": true,
                  "commitment_category": "   read_files  ",
                  "risk_level": "  low  ",
                  "commitment_text": "   read the file  ",
                  "continuation_prompt": "  Read the file now.  ",
                  "reason": "ok"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertTrue(verdict.hasUnfulfilledCommitment());
        assertEquals("read the file", verdict.commitmentText());
        assertEquals(CommitmentCategory.READ_FILES, verdict.commitmentCategory());
        assertEquals(RiskLevel.LOW, verdict.riskLevel());
    }

    @Test
    void shouldDefaultStructuredFieldsWhenClassifierOmitsCategoryAndRisk() {
        String json = """
                {
                  "intent_type": "commitment",
                  "has_unfulfilled_commitment": true,
                  "commitment_text": "run the checks",
                  "reason": "unfulfilled"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertTrue(verdict.hasUnfulfilledCommitment());
        assertEquals(CommitmentCategory.UNKNOWN, verdict.commitmentCategory());
        assertEquals(RiskLevel.HIGH, verdict.riskLevel(),
                "missing risk must fail closed to high so the system refuses to nudge");
    }

    @Test
    void shouldTreatHighRiskCommitmentAsStructuredButUnsafe() {
        String json = """
                {
                  "intent_type": "commitment",
                  "has_unfulfilled_commitment": true,
                  "commitment_category": "run_tests",
                  "risk_level": "high",
                  "commitment_text": "deploy to production",
                  "reason": "destructive or production action"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertTrue(verdict.hasUnfulfilledCommitment());
        assertEquals(CommitmentCategory.RUN_TESTS, verdict.commitmentCategory());
        assertEquals(RiskLevel.HIGH, verdict.riskLevel());
    }
}

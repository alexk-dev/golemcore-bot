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
import static org.junit.jupiter.api.Assertions.assertNull;
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
                  "commitment_text": "gather the three files",
                  "continuation_prompt": "Proceed with gathering the three files you committed to.",
                  "reason": "assistant said 'I'll now gather' without invoking any tool"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.COMMITMENT, verdict.intentType());
        assertTrue(verdict.hasUnfulfilledCommitment());
        assertEquals("gather the three files", verdict.commitmentText());
        assertEquals("Proceed with gathering the three files you committed to.",
                verdict.continuationPrompt());
        assertNotNull(verdict.reason());
    }

    @Test
    void shouldForceFalseWhenIntentIsOptionsOfferedEvenIfModelSaysUnfulfilled() {
        String json = """
                {
                  "intent_type": "options_offered",
                  "has_unfulfilled_commitment": true,
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
    void shouldTreatCommitmentWithoutContinuationPromptAsFulfilled() {
        String json = """
                {
                  "intent_type": "commitment",
                  "has_unfulfilled_commitment": true,
                  "commitment_text": "summarise the result",
                  "reason": "missing continuation prompt"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.COMMITMENT, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment(),
                "missing continuation_prompt must downgrade to non-actionable to avoid nudging with empty text");
        assertNull(verdict.continuationPrompt());
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
    void shouldTrimCommitmentTextAndContinuationPrompt() {
        String json = """
                {
                  "intent_type": "commitment",
                  "has_unfulfilled_commitment": true,
                  "commitment_text": "   read the file  ",
                  "continuation_prompt": "  Read the file now.  ",
                  "reason": "ok"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertTrue(verdict.hasUnfulfilledCommitment());
        assertEquals("read the file", verdict.commitmentText());
        assertEquals("Read the file now.", verdict.continuationPrompt());
    }
}

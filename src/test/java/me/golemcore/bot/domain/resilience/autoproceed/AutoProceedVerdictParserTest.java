package me.golemcore.bot.domain.resilience.autoproceed;

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

class AutoProceedVerdictParserTest {

    private AutoProceedVerdictParser parser;

    @BeforeEach
    void setUp() {
        parser = new AutoProceedVerdictParser(new FakeCodec());
    }

    @Test
    void shouldParseRhetoricalConfirmVerdictFromClassifierJson() {
        String json = """
                {
                  "intent_type": "rhetorical_confirm",
                  "should_auto_affirm": true,
                  "question_text": "shall I run the tests",
                  "affirmation_prompt": "Yes, please proceed.",
                  "reason": "single forward path, no alternatives"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.RHETORICAL_CONFIRM, verdict.intentType());
        assertTrue(verdict.shouldAutoAffirm());
        assertEquals("shall I run the tests", verdict.questionText());
        assertEquals("Yes, please proceed.", verdict.affirmationPrompt());
        assertNotNull(verdict.reason());
    }

    @Test
    void shouldForceFalseWhenIntentIsChoiceRequestEvenIfModelSaysAffirm() {
        String json = """
                {
                  "intent_type": "choice_request",
                  "should_auto_affirm": true,
                  "question_text": "A or B",
                  "affirmation_prompt": "A.",
                  "reason": "offered choices"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.CHOICE_REQUEST, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm(),
                "choice_request must never produce an auto-affirmation");
        assertNull(verdict.affirmationPrompt());
    }

    @Test
    void shouldForceFalseWhenIntentIsOpenQuestion() {
        String json = """
                {
                  "intent_type": "open_question",
                  "should_auto_affirm": true,
                  "reason": "clarifying question"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.OPEN_QUESTION, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm());
    }

    @Test
    void shouldForceFalseWhenIntentIsStatement() {
        String json = """
                {
                  "intent_type": "statement",
                  "should_auto_affirm": false,
                  "reason": "no question"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.STATEMENT, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm());
    }

    @Test
    void shouldReturnUnknownAndNoAffirmForInvalidJson() {
        ClassifierVerdict verdict = parser.parse("not json at all");

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm());
        assertNotNull(verdict.reason());
    }

    @Test
    void shouldReturnUnknownAndNoAffirmForNullInput() {
        ClassifierVerdict verdict = parser.parse(null);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm());
    }

    @Test
    void shouldTreatRhetoricalConfirmWithoutAffirmationPromptAsNonActionable() {
        String json = """
                {
                  "intent_type": "rhetorical_confirm",
                  "should_auto_affirm": true,
                  "question_text": "ready?",
                  "reason": "missing affirmation prompt"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.RHETORICAL_CONFIRM, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm(),
                "missing affirmation_prompt must downgrade to non-actionable");
        assertNull(verdict.affirmationPrompt());
    }

    @Test
    void shouldExtractJsonFromResponseThatWrapsItInMarkdownFence() {
        String json = """
                Here is the analysis:
                ```json
                {
                  "intent_type": "statement",
                  "should_auto_affirm": false,
                  "reason": "no question in reply"
                }
                ```
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.STATEMENT, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm());
    }

    @Test
    void shouldParseUnknownIntentTypeValueAsUnknown() {
        String json = """
                {
                  "intent_type": "gibberish",
                  "should_auto_affirm": true,
                  "reason": "unknown label"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm());
    }

    @Test
    void shouldReturnUnknownWhenBraceIsUnbalanced() {
        ClassifierVerdict verdict = parser.parse("{\"intent_type\":\"rhetorical_confirm\"");

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm());
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
        assertFalse(verdict.shouldAutoAffirm());
        assertTrue(verdict.reason().contains("not parseable as JSON"));
    }

    @Test
    void shouldDefaultToUnknownWhenIntentTypeFieldIsMissing() {
        String json = """
                {
                  "should_auto_affirm": false,
                  "reason": "no intent_type provided"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm());
    }

    @Test
    void shouldTreatRhetoricalConfirmWithRawFlagFalseAsNonActionable() {
        String json = """
                {
                  "intent_type": "rhetorical_confirm",
                  "should_auto_affirm": false,
                  "question_text": "ok to keep going",
                  "reason": "model said no, keep human in loop"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertEquals(IntentType.RHETORICAL_CONFIRM, verdict.intentType());
        assertFalse(verdict.shouldAutoAffirm());
        assertNull(verdict.affirmationPrompt());
    }

    @Test
    void shouldTrimQuestionTextAndAffirmationPrompt() {
        String json = """
                {
                  "intent_type": "rhetorical_confirm",
                  "should_auto_affirm": true,
                  "question_text": "   ready?  ",
                  "affirmation_prompt": "  Yes, proceed.  ",
                  "reason": "ok"
                }
                """;

        ClassifierVerdict verdict = parser.parse(json);

        assertTrue(verdict.shouldAutoAffirm());
        assertEquals("ready?", verdict.questionText());
        assertEquals("Yes, proceed.", verdict.affirmationPrompt());
    }
}

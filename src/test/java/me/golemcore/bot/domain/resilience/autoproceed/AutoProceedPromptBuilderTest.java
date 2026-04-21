package me.golemcore.bot.domain.resilience.autoproceed;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoProceedPromptBuilderTest {

    private AutoProceedPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AutoProceedPromptBuilder();
    }

    @Test
    void systemPromptShouldEnumerateAllIntentTypes() {
        String systemPrompt = builder.systemPrompt().toLowerCase(Locale.ROOT);

        assertTrue(systemPrompt.contains("rhetorical_confirm"));
        assertTrue(systemPrompt.contains("choice_request"));
        assertTrue(systemPrompt.contains("open_question"));
        assertTrue(systemPrompt.contains("statement"));
    }

    @Test
    void systemPromptShouldRequireStructuredVerdictWithoutSyntheticAffirmationField() {
        String systemPrompt = builder.systemPrompt();

        assertTrue(systemPrompt.toLowerCase(Locale.ROOT).contains("json"));
        assertTrue(systemPrompt.contains("intent_type"));
        assertTrue(systemPrompt.contains("should_auto_affirm"));
        assertTrue(systemPrompt.contains("risk_level"));
        assertFalse(systemPrompt.contains("affirmation_prompt"),
                "classifier must no longer author the internal user affirmation");
    }

    @Test
    void systemPromptShouldExplicitlySayServerBuildsSafeAffirmation() {
        String systemPrompt = builder.systemPrompt().toLowerCase(Locale.ROOT);

        assertTrue(systemPrompt.contains("server") && systemPrompt.contains("fixed safe internal affirmation"),
                "prompt must make it explicit that the classifier cannot write the follow-up text");
    }

    @Test
    void systemPromptShouldCallOutHighRiskActionsAsNonActionable() {
        String systemPrompt = builder.systemPrompt().toLowerCase(Locale.ROOT);

        assertTrue(systemPrompt.contains("destructive") && systemPrompt.contains("production")
                && systemPrompt.contains("credentials"),
                "high-risk classes must be called out explicitly in the classifier instructions");
    }

    @Test
    void userPromptShouldIncludeAssistantReplyAndUserMessage() {
        ClassifierRequest request = new ClassifierRequest(
                "please continue",
                "I'm ready to run the tests. Shall I proceed?",
                List.of());

        String userPrompt = builder.userPrompt(request);

        assertTrue(userPrompt.contains("please continue"));
        assertTrue(userPrompt.contains("Shall I proceed?"));
    }

    @Test
    void userPromptShouldListExecutedTools() {
        ClassifierRequest request = new ClassifierRequest("continue", "Ready?", List.of("shell", "read_file"));

        String userPrompt = builder.userPrompt(request);

        assertTrue(userPrompt.contains("shell"));
        assertTrue(userPrompt.contains("read_file"));
    }

    @Test
    void userPromptShouldCapLongAssistantReplyToProtectTokenBudget() {
        String huge = "x".repeat(20_000);
        ClassifierRequest request = new ClassifierRequest("continue", huge, List.of());

        String userPrompt = builder.userPrompt(request);

        assertTrue(userPrompt.length() < huge.length());
        assertTrue(userPrompt.contains("truncated") || userPrompt.contains("?"));
    }

    @Test
    void userPromptShouldHandleNullUserMessageWithoutNpe() {
        ClassifierRequest request = new ClassifierRequest(null, "Ready to continue?", List.of());

        String userPrompt = builder.userPrompt(request);

        assertNotNull(userPrompt);
        assertFalse(userPrompt.contains("null"));
    }
}

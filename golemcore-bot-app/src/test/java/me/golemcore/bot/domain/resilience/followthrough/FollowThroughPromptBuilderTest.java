package me.golemcore.bot.domain.resilience.followthrough;

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
import me.golemcore.bot.domain.resilience.ClassifierRequest;

class FollowThroughPromptBuilderTest {

    private FollowThroughPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new FollowThroughPromptBuilder();
    }

    @Test
    void systemPromptShouldEnumerateAllIntentTypes() {
        String systemPrompt = builder.systemPrompt().toLowerCase(Locale.ROOT);

        assertTrue(systemPrompt.contains("commitment"));
        assertTrue(systemPrompt.contains("options_offered"));
        assertTrue(systemPrompt.contains("question"));
        assertTrue(systemPrompt.contains("completion"));
        assertTrue(systemPrompt.contains("acknowledgement"));
    }

    @Test
    void systemPromptShouldRequireStructuredVerdictWithoutSyntheticUserPromptField() {
        String systemPrompt = builder.systemPrompt();

        assertTrue(systemPrompt.toLowerCase(Locale.ROOT).contains("json"),
                "system prompt must mention JSON output");
        assertTrue(systemPrompt.contains("intent_type"));
        assertTrue(systemPrompt.contains("has_unfulfilled_commitment"));
        assertTrue(systemPrompt.contains("commitment_category"));
        assertTrue(systemPrompt.contains("risk_level"));
        assertFalse(systemPrompt.contains("continuation_prompt"),
                "classifier must no longer author the internal user prompt");
    }

    @Test
    void systemPromptShouldExplicitlySayServerBuildsSafeContinuationPrompt() {
        String systemPrompt = builder.systemPrompt().toLowerCase(Locale.ROOT);

        assertTrue(systemPrompt.contains("server") && systemPrompt.contains("fixed safe internal continuation"),
                "prompt must make it explicit that the classifier cannot write the follow-up text");
    }

    @Test
    void systemPromptShouldDiscourageNudgingOptionsOrQuestions() {
        String systemPrompt = builder.systemPrompt().toLowerCase(Locale.ROOT);

        assertTrue(systemPrompt.contains("waiting") || systemPrompt.contains("user choice")
                || systemPrompt.contains("user input"),
                "system prompt must explicitly say options/questions wait for the user");
    }

    @Test
    void userPromptShouldIncludeAssistantReplyVerbatim() {
        String assistant = "I'll now gather the three files and summarise them.";
        ClassifierRequest request = new ClassifierRequest("please work on the files", assistant, List.of());

        String userPrompt = builder.userPrompt(request);

        assertTrue(userPrompt.contains(assistant));
    }

    @Test
    void userPromptShouldIncludeUserMessage() {
        ClassifierRequest request = new ClassifierRequest("the unique user request xyz123", "ack", List.of());

        String userPrompt = builder.userPrompt(request);

        assertTrue(userPrompt.contains("xyz123"));
    }

    @Test
    void userPromptShouldListExecutedTools() {
        ClassifierRequest request = new ClassifierRequest("do it", "done", List.of("file_read", "shell_exec"));

        String userPrompt = builder.userPrompt(request);

        assertTrue(userPrompt.contains("file_read"));
        assertTrue(userPrompt.contains("shell_exec"));
    }

    @Test
    void userPromptShouldIndicateNoToolsWhenListEmpty() {
        ClassifierRequest request = new ClassifierRequest("do it", "I'll continue", List.of());

        String userPrompt = builder.userPrompt(request).toLowerCase(Locale.ROOT);

        assertTrue(userPrompt.contains("(none)") || userPrompt.contains("no tools"),
                "empty tool list must be explicitly rendered so the model can reason about it");
    }

    @Test
    void userPromptShouldCapExtremelyLongAssistantReplyToProtectTokenBudget() {
        String huge = "x".repeat(20_000);
        ClassifierRequest request = new ClassifierRequest("do it", huge, List.of());

        String userPrompt = builder.userPrompt(request);

        assertTrue(userPrompt.length() < huge.length(),
                "user prompt must truncate very long assistant replies");
        assertTrue(userPrompt.contains("truncated") || userPrompt.contains("?"),
                "truncation must be marked so the classifier knows content was cut");
    }

    @Test
    void userPromptShouldHandleNullUserMessageWithoutNpe() {
        ClassifierRequest request = new ClassifierRequest(null, "I'll now do it", List.of());

        String userPrompt = builder.userPrompt(request);

        assertNotNull(userPrompt);
        assertFalse(userPrompt.contains("null"),
                "null user message must not leak the literal string 'null'");
    }
}

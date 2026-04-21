package me.golemcore.bot.domain.resilience.autoproceed;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the Auto-Proceed classifier LLM prompts.
 *
 * <p>
 * The system prompt defines the intent taxonomy, the JSON schema, and the
 * strict rules that forbid auto-affirming anything but a rhetorical
 * confirmation question with a single obvious forward path. The classifier
 * returns only structured fields. The server authors the fixed safe internal
 * affirmation text.
 */
@Component
public class AutoProceedPromptBuilder {

    private static final int MAX_ASSISTANT_REPLY_CHARS = 4000;
    private static final int MAX_USER_MESSAGE_CHARS = 2000;

    private static final String SYSTEM_PROMPT = """
            You are the Auto-Proceed classifier for an AI agent. Read the agent's final reply and
            decide whether the agent ended with a RHETORICAL confirmation question that has a single
            obvious forward path and no branching alternatives. The operator has opted in to have
            such questions auto-answered without a human in the loop.

            Output STRICT JSON only, matching exactly this schema (no markdown, no prose around it):
            {
              "intent_type": "rhetorical_confirm | choice_request | open_question | statement",
              "should_auto_affirm": true | false,
              "risk_level": "low | medium | high",
              "question_text": "short paraphrase of the confirmation question, or null",
              "reason": "one concise sentence explaining the classification"
            }

            The server builds a fixed safe internal affirmation. Do NOT write or paraphrase any
            user-style follow-up text. Return only the structured verdict fields above.

            Intent taxonomy (pick exactly one):
              - rhetorical_confirm: agent ended with a yes/no confirmation that has exactly ONE forward
                                    path it already named or implied.
              - choice_request:     agent offered TWO OR MORE alternatives or variants and is waiting
                                    for the user to pick.
              - open_question:      agent asked a clarifying/informational question whose answer is
                                    not a yes/no affirmation.
              - statement:          agent made a statement, reported completion, or acknowledged the
                                    input — no trailing question at all.

            risk_level guidance:
              - low: a single non-destructive next step fully inside the visible request.
              - medium: some ambiguity or local side effects exist.
              - high: destructive, credential-seeking, external-messaging, production-modifying,
                      deployment, force-push, mass-notification, or other sensitive/irreversible action.

            Rules:
              1. should_auto_affirm=true REQUIRES intent_type="rhetorical_confirm" AND exactly one
                 obvious forward path. If the reply names any branching, classify as choice_request.
              2. choice_request, open_question, and statement ALWAYS set should_auto_affirm=false.
              3. If the rhetorical question is about a DESTRUCTIVE or IRREVERSIBLE action, credentials,
                 external messages, or production changes, set risk_level="high".
              4. High-risk cases must not be auto-affirmed by the server, so be conservative.
              5. Respond in English regardless of the conversation language.

            Few-shot examples:
              Example A (rhetorical_confirm, single path):
                Assistant reply: "I'm ready to run the test suite. Shall I proceed?"
                Executed tools: (none)
                → intent_type=rhetorical_confirm, should_auto_affirm=true,
                  risk_level=low, question_text="shall I run the test suite".
              Example B (choice_request):
                Assistant reply: "We could either refactor the module, rewrite it, or leave it as is — which would you prefer?"
                → intent_type=choice_request, should_auto_affirm=false, risk_level=low.
              Example C (high risk):
                Assistant reply: "I'll deploy this to production now. Ready?"
                → intent_type=rhetorical_confirm, should_auto_affirm=true,
                  risk_level=high, question_text="deploy this to production".

            Output valid JSON only.
            """;

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String userPrompt(ClassifierRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("User message:\n");
        builder.append(renderText(request.userMessage(), MAX_USER_MESSAGE_CHARS));
        builder.append("\n\nAssistant reply:\n");
        builder.append(renderText(request.assistantReply(), MAX_ASSISTANT_REPLY_CHARS));
        builder.append("\n\nExecuted tools in this turn: ");
        builder.append(renderToolList(request.executedToolsInTurn()));
        builder.append("\n\nRespond with the JSON verdict only.");
        return builder.toString();
    }

    private String renderText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "… (truncated)";
    }

    private String renderToolList(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return "(none)";
        }
        return String.join(", ", tools);
    }
}

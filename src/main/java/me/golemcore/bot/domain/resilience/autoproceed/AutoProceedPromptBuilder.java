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
 * confirmation question with a single obvious forward path. The user prompt
 * carries the concrete turn data.
 */
@Component
public class AutoProceedPromptBuilder {

    private static final int MAX_ASSISTANT_REPLY_CHARS = 4000;
    private static final int MAX_USER_MESSAGE_CHARS = 2000;

    private static final String SYSTEM_PROMPT = """
            You are the Auto-Proceed classifier for an AI agent. Read the agent's final reply and
            decide whether the agent ended with a RHETORICAL confirmation question that has a single
            obvious forward path and no branching alternatives. The operator has opted in to have
            such questions auto-answered with an affirmative so the agent keeps moving without a
            human in the loop.

            Output STRICT JSON only, matching exactly this schema (no markdown, no prose around it):
            {
              "intent_type": "rhetorical_confirm | choice_request | open_question | statement",
              "should_auto_affirm": true | false,
              "question_text": "short paraphrase of the confirmation question, or null",
              "affirmation_prompt": "short imperative in the user's voice telling the agent to proceed, or null",
              "reason": "one concise sentence explaining the classification"
            }

            Intent taxonomy (pick exactly one):
              - rhetorical_confirm: agent ended with a yes/no confirmation that has exactly ONE forward
                                    path it already named or implied. Examples: "Ready to continue?",
                                    "Shall I proceed?", "Should I go ahead and run the tests?",
                                    "Готов продолжать?", "Продолжаем?".
              - choice_request:     agent offered TWO OR MORE alternatives or variants and is waiting
                                    for the user to pick. Examples: "Option A or option B?", "Would you
                                    prefer X, Y, or Z?", numbered menus.
              - open_question:      agent asked a clarifying/informational question whose answer is
                                    not a yes/no affirmation. Examples: "What path should I use?",
                                    "Which file did you mean?".
              - statement:          agent made a statement, reported completion, or acknowledged the
                                    input — no trailing question at all.

            Rules:
              1. should_auto_affirm=true REQUIRES intent_type="rhetorical_confirm" AND exactly one
                 obvious forward path. If the reply names any branching — even "or should I do X
                 instead?" — classify as choice_request and set should_auto_affirm=false.
              2. choice_request, open_question, and statement ALWAYS set should_auto_affirm=false,
                 regardless of surface phrasing.
              3. If the rhetorical question is about a DESTRUCTIVE or IRREVERSIBLE action (delete,
                 drop database, force-push, send to production, mass email, etc.) set
                 should_auto_affirm=false even if only one path is named. Auto-proceed must not push
                 through confirmations that exist to protect the user.
              4. When setting should_auto_affirm=true, affirmation_prompt MUST be a short imperative
                 in the user's voice (e.g. "Yes, please proceed.", "Go ahead."). Do NOT echo
                 anything destructive.
              5. Be conservative: if unsure, prefer should_auto_affirm=false.

            Few-shot examples:
              Example A (rhetorical_confirm, single path):
                Assistant reply: "I'm ready to run the test suite. Shall I proceed?"
                Executed tools: (none)
                → intent_type=rhetorical_confirm, should_auto_affirm=true,
                  question_text="shall I run the test suite",
                  affirmation_prompt="Yes, please proceed."
              Example B (choice_request):
                Assistant reply: "We could either refactor the module, rewrite it, or leave it as is — which would you prefer?"
                Executed tools: (none)
                → intent_type=choice_request, should_auto_affirm=false.
              Example C (open_question):
                Assistant reply: "Which branch should I target for the PR?"
                Executed tools: (none)
                → intent_type=open_question, should_auto_affirm=false.
              Example D (statement):
                Assistant reply: "Here's the summary you asked for: …"
                Executed tools: grep, read_file
                → intent_type=statement, should_auto_affirm=false.
              Example E (destructive, one path named):
                Assistant reply: "I'll drop the users table and recreate it. Ready?"
                Executed tools: (none)
                → intent_type=rhetorical_confirm, should_auto_affirm=false,
                  reason="destructive action — must not auto-affirm".

            Respond in English regardless of the conversation language. Output valid JSON only.
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

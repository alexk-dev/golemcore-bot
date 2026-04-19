package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the classifier LLM prompts (system + user) used by
 * {@link FollowThroughClassifier}.
 *
 * <p>
 * The system prompt is a static instruction that defines the intent taxonomy,
 * the strict JSON output schema, and the semantic rules that forbid nudging
 * when the assistant is waiting on user input. The user prompt is built per
 * request and carries the concrete turn data.
 */
@Component
public class FollowThroughPromptBuilder {

    private static final int MAX_ASSISTANT_REPLY_CHARS = 4000;
    private static final int MAX_USER_MESSAGE_CHARS = 2000;

    private static final String SYSTEM_PROMPT = """
            You are the follow-through classifier for an AI agent. Read the agent's final reply and
            decide whether the agent committed to a concrete next action that the agent itself should
            execute WITHOUT waiting for further user input, but failed to actually trigger that action
            in the current turn.

            Output STRICT JSON only, matching exactly this schema (no markdown, no prose around it):
            {
              "intent_type": "commitment | options_offered | question | completion | acknowledgement",
              "has_unfulfilled_commitment": true | false,
              "commitment_text": "short paraphrase of what the agent committed to, or null",
              "continuation_prompt": "one short user-style imperative that tells the agent to actually do it, or null",
              "reason": "one concise sentence explaining the classification"
            }

            Intent taxonomy (pick exactly one):
              - commitment:       agent unambiguously says IT WILL do the next step itself right now
                                  ("I'll now X", "let me run Y", "next I will Z", "going to do X now").
              - options_offered:  agent offered alternatives/choices and is waiting for user to pick
                                  ("we could do A, B, or C", "would you prefer X or Y?").
              - question:         agent asked a clarifying question and is waiting for user reply.
              - completion:       agent reported that the requested work is finished.
              - acknowledgement:  agent acknowledged the input but did not commit to any concrete
                                  next action.

            Rules:
              1. options_offered and question ALWAYS wait for user input — set
                 has_unfulfilled_commitment=false regardless of surface phrasing.
              2. completion and acknowledgement likewise set has_unfulfilled_commitment=false.
              3. Only set has_unfulfilled_commitment=true when intent_type="commitment" AND the
                 executed tools in this turn do NOT already fulfil the committed action.
              4. When setting has_unfulfilled_commitment=true, continuation_prompt MUST be a short
                 imperative in the user's voice that will unblock the agent (e.g. "Read the three
                 files and produce the summary you promised.").
              5. Be conservative: if unsure, prefer has_unfulfilled_commitment=false.

            Few-shot examples:
              Example A (commitment, unfulfilled):
                Assistant reply: "I'll now gather the three files and summarise them."
                Executed tools: (none)
                → intent_type=commitment, has_unfulfilled_commitment=true,
                  commitment_text="gather three files and summarise",
                  continuation_prompt="Gather the three files and produce the summary you promised."
              Example B (options_offered):
                Assistant reply: "We could either refactor the module, rewrite it, or leave it as is — which would you prefer?"
                Executed tools: (none)
                → intent_type=options_offered, has_unfulfilled_commitment=false.
              Example C (commitment, fulfilled):
                Assistant reply: "Here's the summary you asked for: …"
                Executed tools: file_read, file_read, file_read
                → intent_type=completion, has_unfulfilled_commitment=false.

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

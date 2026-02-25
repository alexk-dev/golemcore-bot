package me.golemcore.bot.domain.system;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.service.UserPreferencesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Feedback guarantee system.
 *
 * <p>
 * Goal: ensure that for a user-facing inbound message we always produce a
 * minimal {@link OutgoingResponse} (even if upstream systems failed to produce
 * a response).
 * </p>
 *
 * <p>
 * This system is intentionally <b>transport-agnostic</b>: it only writes
 * {@link ContextAttributes#OUTGOING_RESPONSE}. The transport is handled by
 * {@link ResponseRoutingSystem}.
 * </p>
 *
 * <p>
 * Important: this system must NOT mutate raw history. Raw history ownership
 * belongs to domain executors (ToolLoop, plan executors, etc.).
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeedbackGuaranteeSystem implements AgentSystem {

    private final UserPreferencesService preferencesService;

    @Override
    public String getName() {
        return "FeedbackGuaranteeSystem";
    }

    @Override
    public int getOrder() {
        // Run right before ResponseRoutingSystem (60), after preparation (58).
        return 59;
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        if (context.getAttribute(ContextAttributes.OUTGOING_RESPONSE) != null) {
            return false;
        }
        return !isAutoModeContext(context);
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (context.getAttribute(ContextAttributes.OUTGOING_RESPONSE) != null) {
            return context;
        }
        if (isAutoModeContext(context)) {
            return context;
        }

        String message = preferencesService.getMessage("system.error.generic.feedback");
        String reasonCode = classifyFallbackReason(context);
        log.warn("[FeedbackGuarantee] Producing fallback OutgoingResponse (reason={}, failures={})",
                reasonCode, context.getFailures().size());
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(message));
        return context;
    }

    private boolean isAutoModeContext(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get("auto.mode"));
    }

    private String classifyFallbackReason(AgentContext context) {
        String llmErrorCode = context.getAttribute(ContextAttributes.LLM_ERROR_CODE);
        if (llmErrorCode != null && !llmErrorCode.isBlank()) {
            return llmErrorCode;
        }

        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null && !llmError.isBlank()) {
            return LlmErrorClassifier.classifyFromDiagnostic(llmError);
        }

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (response != null) {
            if (response.hasToolCalls()) {
                return "tool_calls_without_final_answer";
            }
            if (response.getContent() == null || response.getContent().isBlank()) {
                return LlmErrorClassifier.classifyEmptyFinalResponse(response);
            }
            return "llm_response_present_but_not_routed";
        }

        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.FINAL_ANSWER_READY))) {
            return LlmErrorClassifier.NO_ASSISTANT_MESSAGE;
        }
        if (!context.getFailures().isEmpty()) {
            return "pipeline_failures_without_outgoing_response";
        }
        return "missing_outgoing_response";
    }
}

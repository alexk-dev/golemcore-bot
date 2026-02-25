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
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.TurnLimitReason;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prepares {@link OutgoingResponse} from LLM results before transport routing.
 * Converts {@code LLM_RESPONSE} and {@code LLM_ERROR} context attributes into a
 * single {@link OutgoingResponse}, handling voice prefix detection and
 * auto-voice policy.
 *
 * <p>
 * This system replaces the former {@code AgentLoop.prepareOutgoingResponse()}
 * method, eliminating string-based pipeline coupling.
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutgoingResponsePreparationSystem implements AgentSystem {

    static final String VOICE_PREFIX = "\uD83D\uDD0A";

    private final UserPreferencesService preferencesService;
    private final ModelSelectionService modelSelectionService;
    private final RuntimeConfigService runtimeConfigService;

    @Override
    public String getName() {
        return "OutgoingResponsePreparationSystem";
    }

    @Override
    public int getOrder() {
        // Must be before FeedbackGuaranteeSystem and ResponseRoutingSystem.
        return 58;
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        // Cross-system contract: OUTGOING_RESPONSE is stored in ContextAttributes.
        if (context.getAttribute(ContextAttributes.OUTGOING_RESPONSE) != null) {
            return false;
        }

        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null) {
            return true;
        }

        LlmResponse llmResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (llmResponse != null) {
            return true;
        }
        return Boolean.TRUE.equals(context.getAttribute(ContextAttributes.FINAL_ANSWER_READY));
    }

    @Override
    public AgentContext process(AgentContext context) {
        // Defensive: if OUTGOING_RESPONSE already present, noop.
        if (context.getAttribute(ContextAttributes.OUTGOING_RESPONSE) != null) {
            return context;
        }

        // Error path: convert LLM_ERROR into a user-facing OutgoingResponse.
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null) {
            String errorMessage = preferencesService.getMessage("system.error.llm");
            setOutgoingResponse(context, OutgoingResponse.textOnly(errorMessage));
            return context;
        }

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        boolean finalAnswerReady = Boolean.TRUE.equals(context.getAttribute(ContextAttributes.FINAL_ANSWER_READY));
        if (response == null) {
            if (finalAnswerReady) {
                return classifyEmptyFinalResponse(context, null);
            }
            return context;
        }
        String text = response.getContent();

        // Tool loop limit: provide specific, user-friendly reason by limit type.
        Boolean toolLoopLimitReached = context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED);
        if (Boolean.TRUE.equals(toolLoopLimitReached)) {
            TurnLimitReason reason = context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REASON);
            text = buildToolLoopLimitMessage(reason);
        }

        // Voice intent is typed (backward-incompatible: no ContextAttributes.* for
        // voice).

        boolean voiceRequested = context.isVoiceRequested();

        String voiceText = context.getVoiceText();

        boolean hasText = text != null && !text.isBlank();
        boolean hasVoice = voiceRequested || (voiceText != null && !voiceText.isBlank());

        // A response containing tool calls is not user-facing output — unless
        // FINAL_ANSWER_READY is set (e.g. tool loop was stopped by internal limits).
        Boolean finalReady = context.getAttribute(ContextAttributes.FINAL_ANSWER_READY);
        if (response.hasToolCalls() && !Boolean.TRUE.equals(finalReady)) {
            return context;
        }

        // Voice prefix detection: strip prefix and promote to voice request.
        if (hasText && hasVoicePrefix(text)) {
            String stripped = stripVoicePrefix(text);
            if (!stripped.isBlank()) {
                // Prefer tool-provided voiceText; otherwise use stripped prefix text.
                String effectiveVoiceText = (voiceText != null && !voiceText.isBlank()) ? voiceText : stripped;
                OutgoingResponse outgoing = OutgoingResponse.builder()
                        .text(stripped)
                        .voiceRequested(true)
                        .voiceText(effectiveVoiceText)
                        .build();
                setOutgoingResponse(context, outgoing);
                return context;
            }
            // Prefix with blank content after stripping — nothing to send.
            return context;
        }

        // Auto-respond with voice when incoming message was voice and config enabled.
        if (hasText && !hasVoice) {
            hasVoice = shouldAutoVoiceRespond(context);
        }

        if (!hasText && !hasVoice) {
            if (finalAnswerReady) {
                return classifyEmptyFinalResponse(context, response);
            }
            return context;
        }

        OutgoingResponse outgoing = OutgoingResponse.builder()
                .text(hasText ? text : null)
                .voiceRequested(hasVoice)
                .voiceText(voiceText)
                .build();

        setOutgoingResponse(context, outgoing);
        return context;
    }

    private AgentContext classifyEmptyFinalResponse(AgentContext context, LlmResponse response) {
        String model = context.getAttribute(ContextAttributes.LLM_MODEL);
        String finishReason = response != null ? response.getFinishReason() : null;
        String diagnostic = String.format(
                "empty final LLM response (model=%s, finishReason=%s)",
                model != null ? model : "unknown",
                finishReason != null ? finishReason : "unknown");

        log.warn("[ResponsePrep] {}", diagnostic);
        context.setAttribute(ContextAttributes.LLM_ERROR, diagnostic);
        context.addFailure(new FailureEvent(
                FailureSource.LLM,
                getName(),
                FailureKind.VALIDATION,
                diagnostic,
                Instant.now()));

        String errorMessage = preferencesService.getMessage("system.error.llm");
        setOutgoingResponse(context, OutgoingResponse.textOnly(errorMessage));
        return context;
    }

    private void setOutgoingResponse(AgentContext context, OutgoingResponse outgoing) {
        // Attach turn metadata hints for web channel context panel.
        Map<String, Object> hints = buildHints(context);
        OutgoingResponse withHints = OutgoingResponse.builder()
                .text(outgoing.getText())
                .voiceRequested(outgoing.isVoiceRequested())
                .voiceText(outgoing.getVoiceText())
                .attachments(outgoing.getAttachments())
                .skipAssistantHistory(outgoing.isSkipAssistantHistory())
                .hints(hints)
                .build();
        // Canonical contract.
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, withHints);
        // Typed mirror (ergonomics) - keep consistent.
        context.setOutgoingResponse(withHints);
    }

    private Map<String, Object> buildHints(AgentContext context) {
        Map<String, Object> hints = new LinkedHashMap<>();

        String model = context.getAttribute(ContextAttributes.LLM_MODEL);
        if (model != null) {
            hints.put("model", model);
        }

        String reasoning = context.getAttribute(ContextAttributes.LLM_REASONING);
        if (reasoning != null && !"none".equals(reasoning)) {
            hints.put("reasoning", reasoning);
        }

        String tier = context.getModelTier();
        hints.put("tier", tier != null ? tier : "balanced");

        LlmResponse llmResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (llmResponse != null && llmResponse.getUsage() != null) {
            LlmUsage usage = llmResponse.getUsage();
            hints.put("inputTokens", usage.getInputTokens());
            hints.put("outputTokens", usage.getOutputTokens());
            hints.put("totalTokens", usage.getTotalTokens());
            if (usage.getLatency() != null) {
                hints.put("latencyMs", usage.getLatency().toMillis());
            }
        }

        String effectiveTier = tier != null ? tier : "balanced";
        int maxContextTokens = modelSelectionService.resolveMaxInputTokens(effectiveTier);
        hints.put("maxContextTokens", maxContextTokens);

        return hints;
    }

    private boolean hasVoicePrefix(String content) {
        return content != null && content.trim().startsWith(VOICE_PREFIX);
    }

    private String stripVoicePrefix(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith(VOICE_PREFIX)) {
            return trimmed.substring(VOICE_PREFIX.length()).trim();
        }
        return trimmed;
    }

    private String buildToolLoopLimitMessage(TurnLimitReason reason) {
        if (reason == null) {
            return preferencesService.getMessage("system.toolloop.limit.unknown");
        }

        return switch (reason) {
        case MAX_LLM_CALLS -> preferencesService.getMessage("system.toolloop.limit.maxLlmCalls",
                runtimeConfigService.getTurnMaxLlmCalls());
        case MAX_TOOL_EXECUTIONS -> preferencesService.getMessage("system.toolloop.limit.maxToolExecutions",
                runtimeConfigService.getTurnMaxToolExecutions());
        case DEADLINE -> preferencesService.getMessage("system.toolloop.limit.deadline",
                runtimeConfigService.getTurnDeadline().toMinutes());
        case UNKNOWN -> preferencesService.getMessage("system.toolloop.limit.unknown");
        };
    }

    private boolean shouldAutoVoiceRespond(AgentContext context) {
        if (!runtimeConfigService.isTelegramRespondWithVoiceEnabled()) {
            return false;
        }
        return hasIncomingVoice(context);
    }

    private boolean hasIncomingVoice(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getMessages().get(i);
            if (msg.isUserMessage()) {
                return msg.hasVoice();
            }
        }
        return false;
    }
}

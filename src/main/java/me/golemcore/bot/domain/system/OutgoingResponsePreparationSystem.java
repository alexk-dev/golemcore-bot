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
import me.golemcore.bot.infrastructure.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    private final BotProperties properties;

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
        return llmResponse != null;
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
        if (response == null) {
            return context;
        }
        String text = response.getContent();

        // Voice intent is typed (backward-incompatible: no ContextAttributes.* for
        // voice).

        boolean voiceRequested = context.isVoiceRequested();

        String voiceText = context.getVoiceText();

        boolean hasText = text != null && !text.isBlank();
        boolean hasVoice = voiceRequested || (voiceText != null && !voiceText.isBlank());

        // A response containing tool calls is not user-facing output.
        // It will be handled by ToolLoop/other systems.
        if (response.hasToolCalls()) {
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

    private void setOutgoingResponse(AgentContext context, OutgoingResponse outgoing) {
        // Canonical contract.
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, outgoing);
        // Typed mirror (ergonomics) - keep consistent.
        context.setOutgoingResponse(outgoing);
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

    private boolean shouldAutoVoiceRespond(AgentContext context) {
        BotProperties.VoiceProperties voice = properties.getVoice();
        if (!voice.getTelegram().isRespondWithVoice()) {
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

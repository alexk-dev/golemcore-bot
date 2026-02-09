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

package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool that lets the LLM request voice output for its response.
 *
 * <p>
 * When the LLM calls this tool, the text is queued for voice synthesis and
 * {@link me.golemcore.bot.domain.system.ResponseRoutingSystem} will synthesize
 * and send the voice message after the text response.
 *
 * <p>
 * Uses {@link AgentContextHolder} to access the current AgentContext (same
 * pattern as {@link SkillTransitionTool}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceResponseTool implements ToolComponent {

    private final BotProperties properties;

    @Override
    public String getToolName() {
        return "send_voice";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("send_voice")
                .description("Send a voice message to the user. " +
                        "Primary method: start your response with \uD83D\uDD0A (speaker emoji). " +
                        "This tool is a secondary mechanism for models that support tool calling. " +
                        "Provide the text to speak.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "text", Map.of(
                                        "type", "string",
                                        "description",
                                        "The text content to speak.")),
                        "required", List.of("text")))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        log.debug("[VoiceResponse] Execute called with parameters: {}", parameters);

        Object rawText = parameters.get("text");
        String text = rawText != null ? String.valueOf(rawText) : null;

        AgentContext context = AgentContextHolder.get();
        if (context == null) {
            log.warn("[VoiceResponse] No agent context available — cannot queue voice response");
            return CompletableFuture.completedFuture(ToolResult.failure("No agent context available"));
        }

        context.setAttribute(ContextAttributes.VOICE_REQUESTED, true);
        context.setAttribute(ContextAttributes.LOOP_COMPLETE, true);
        if (text != null && !text.isBlank()) {
            context.setAttribute(ContextAttributes.VOICE_TEXT, text);
            log.info("[VoiceResponse] Voice response queued: {} chars of custom text", text.length());
        } else {
            log.info("[VoiceResponse] Voice response queued: will use full LLM response");
        }

        return CompletableFuture.completedFuture(ToolResult.success("Voice response queued"));
    }

    @Override
    public boolean isEnabled() {
        return properties.getVoice().isEnabled();
    }
}

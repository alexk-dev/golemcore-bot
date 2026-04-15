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

package me.golemcore.bot.adapter.outbound.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.domain.model.Message;

@RequiredArgsConstructor
class Langchain4jResponseMapper {

    private static final String GEMINI_THINKING_SIGNATURE_KEY = "thinking_signature";

    private final ObjectMapper objectMapper;

    LlmResponse convertResponse(ChatResponse response, String currentModel, boolean compatibilityFlatteningApplied,
            boolean geminiApiType) {
        AiMessage aiMessage = response.aiMessage();

        List<Message.ToolCall> toolCalls = null;
        if (aiMessage.hasToolExecutionRequests()) {
            toolCalls = aiMessage.toolExecutionRequests().stream()
                    .map(ter -> Message.ToolCall.builder()
                            .id(ter.id())
                            .name(ter.name())
                            .arguments(Langchain4jToolArgumentJson.parse(ter.arguments(), objectMapper))
                            .build())
                    .toList();
        }

        LlmUsage usage = toLlmUsage(response.tokenUsage());
        Map<String, Object> providerMetadata = extractProviderMetadata(aiMessage, geminiApiType);

        return LlmResponse.builder()
                .content(aiMessage.text())
                .toolCalls(toolCalls)
                .usage(usage)
                .model(currentModel)
                .finishReason(response.finishReason() != null ? response.finishReason().name() : "stop")
                .providerMetadata(providerMetadata.isEmpty() ? null : providerMetadata)
                .compatibilityFlatteningApplied(compatibilityFlatteningApplied)
                .build();
    }

    static LlmResponse withProviderMetadata(LlmResponse response, Map<String, Object> extraMetadata) {
        if (response == null || extraMetadata == null || extraMetadata.isEmpty()) {
            return response;
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        if (response.getProviderMetadata() != null) {
            merged.putAll(response.getProviderMetadata());
        }
        merged.putAll(extraMetadata);

        return LlmResponse.builder()
                .content(response.getContent())
                .toolCalls(response.getToolCalls())
                .usage(response.getUsage())
                .model(response.getModel())
                .finishReason(response.getFinishReason())
                .providerMetadata(merged)
                .compatibilityFlatteningApplied(response.isCompatibilityFlatteningApplied())
                .build();
    }

    private LlmUsage toLlmUsage(TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return null;
        }

        Integer inputTokens = tokenUsage.inputTokenCount();
        Integer outputTokens = tokenUsage.outputTokenCount();
        Integer totalTokens = tokenUsage.totalTokenCount();
        if (inputTokens == null && outputTokens == null && totalTokens == null) {
            return null;
        }

        int safeInputTokens = safeTokenCount(inputTokens);
        int safeOutputTokens = safeTokenCount(outputTokens);
        int safeTotalTokens = totalTokens != null ? totalTokens : safeInputTokens + safeOutputTokens;

        return LlmUsage.builder()
                .inputTokens(safeInputTokens)
                .outputTokens(safeOutputTokens)
                .totalTokens(safeTotalTokens)
                .build();
    }

    private int safeTokenCount(Integer value) {
        return value != null ? value : 0;
    }

    private Map<String, Object> extractProviderMetadata(AiMessage aiMessage, boolean geminiApiType) {
        if (!geminiApiType || aiMessage == null || !aiMessage.hasToolExecutionRequests()) {
            return Collections.emptyMap();
        }
        String thinkingSignature = aiMessage.attribute(GEMINI_THINKING_SIGNATURE_KEY, String.class);
        if (thinkingSignature == null || thinkingSignature.isBlank()) {
            return Collections.emptyMap();
        }
        return Map.of(GEMINI_THINKING_SIGNATURE_KEY, thinkingSignature);
    }
}

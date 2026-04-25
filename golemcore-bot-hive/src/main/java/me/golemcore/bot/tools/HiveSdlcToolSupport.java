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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;

final class HiveSdlcToolSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private HiveSdlcToolSupport() {
    }

    static AgentContext requireHiveContext() {
        AgentContext context = AgentContextHolder.get();
        if (context == null || context.getSession() == null
                || !ChannelTypes.HIVE.equalsIgnoreCase(context.getSession().getChannelType())) {
            throw new IllegalStateException("Hive SDLC tools are only available in Hive sessions");
        }
        return context;
    }

    static CompletableFuture<ToolResult> failedFuture(String message) {
        return CompletableFuture.completedFuture(ToolResult.failure(ToolFailureKind.POLICY_DENIED, message));
    }

    static CompletableFuture<ToolResult> executionFailedFuture(String message) {
        return CompletableFuture.completedFuture(ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, message));
    }

    static ToolResult visibleSuccess(String summary, Object data) {
        if (data == null) {
            return ToolResult.success(summary);
        }
        return ToolResult.success(summary + "\n\n" + renderJson(data), data);
    }

    static String renderJson(Object data) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            return String.valueOf(data);
        }
    }

    static String stringParam(Map<String, Object> parameters, String name) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(name);
        if (!(value instanceof String stringValue)) {
            return null;
        }
        String trimmed = stringValue.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static boolean booleanParam(Map<String, Object> parameters, String name) {
        if (parameters == null) {
            return false;
        }
        Object value = parameters.get(name);
        return value instanceof Boolean booleanValue && booleanValue;
    }

    static Integer integerParam(Map<String, Object> parameters, String name) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(name);
        if (value instanceof Integer integerValue) {
            return integerValue;
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        return null;
    }

    static List<String> stringListParam(Map<String, Object> parameters, String name) {
        if (parameters == null) {
            return List.of();
        }
        Object value = parameters.get(name);
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    static String contextAttribute(AgentContext context, String key) {
        if (context == null || key == null) {
            return null;
        }
        String value = context.getAttribute(key);
        return value != null && !value.isBlank() ? value : null;
    }

    static String resolveCardId(AgentContext context, Map<String, Object> parameters) {
        String cardId = stringParam(parameters, "card_id");
        return cardId != null ? cardId : contextAttribute(context, ContextAttributes.HIVE_CARD_ID);
    }

    static String resolveThreadId(AgentContext context, Map<String, Object> parameters) {
        String threadId = stringParam(parameters, "thread_id");
        return threadId != null ? threadId : contextAttribute(context, ContextAttributes.HIVE_THREAD_ID);
    }

    static Map<String, Object> currentContextData(AgentContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        putIfPresent(data, "threadId", contextAttribute(context, ContextAttributes.HIVE_THREAD_ID));
        putIfPresent(data, "cardId", contextAttribute(context, ContextAttributes.HIVE_CARD_ID));
        putIfPresent(data, "commandId", contextAttribute(context, ContextAttributes.HIVE_COMMAND_ID));
        putIfPresent(data, "runId", contextAttribute(context, ContextAttributes.HIVE_RUN_ID));
        putIfPresent(data, "golemId", contextAttribute(context, ContextAttributes.HIVE_GOLEM_ID));
        if (context != null && context.getSession() != null) {
            putIfPresent(data, "chatId", context.getSession().getChatId());
            putIfPresent(data, "channelType", context.getSession().getChannelType());
        }
        return data;
    }

    static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}

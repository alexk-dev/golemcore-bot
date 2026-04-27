package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit tool execution boundary passed from runtime orchestration into tool execution.
 */
public record ToolExecutionContext(AgentContext agentContext, String sessionId, String channelType,
        Map<String, Object> runtimeAttributes) {

    public ToolExecutionContext {
        Objects.requireNonNull(agentContext, "agentContext must not be null");
        sessionId = normalize(sessionId);
        channelType = normalize(channelType);
        runtimeAttributes = immutableCopy(runtimeAttributes);
    }

    public static ToolExecutionContext from(AgentContext agentContext) {
        Objects.requireNonNull(agentContext, "agentContext must not be null");
        AgentSession session = agentContext.getSession();
        return new ToolExecutionContext(agentContext, session != null ? session.getId() : null,
                session != null ? session.getChannelType() : null, agentContext.getAttributes());
    }

    public String transportChatId() {
        Object transportChatId = runtimeAttributes.get(ContextAttributes.TRANSPORT_CHAT_ID);
        if (transportChatId instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return SessionIdentitySupport.resolveTransportChatId(agentContext.getSession());
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> runtimeAttributes) {
        if (runtimeAttributes == null || runtimeAttributes.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(runtimeAttributes));
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}

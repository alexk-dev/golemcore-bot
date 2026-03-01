package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.SessionIdentity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits and stores runtime events for the current turn.
 */
@Service
public class RuntimeEventService {

    private final Clock clock;

    public RuntimeEventService(Clock clock) {
        this.clock = clock;
    }

    public RuntimeEvent emit(AgentContext context, RuntimeEventType type, Map<String, Object> payload) {
        List<RuntimeEvent> events = getOrCreateEvents(context);

        AgentSession session = context != null ? context.getSession() : null;
        SessionIdentity identity = SessionIdentitySupport.resolveSessionIdentity(session);
        String channelType = identity != null ? identity.channelType()
                : (session != null ? session.getChannelType() : null);
        String chatId = identity != null ? identity.conversationKey() : (session != null ? session.getChatId() : null);
        Map<String, Object> safePayload = payload != null ? new LinkedHashMap<>(payload) : Map.of();

        RuntimeEvent event = RuntimeEvent.builder()
                .type(type)
                .timestamp(Instant.now(clock))
                .sessionId(session != null ? session.getId() : null)
                .channelType(channelType)
                .chatId(chatId)
                .payload(safePayload)
                .build();

        events.add(event);
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, events);
        return event;
    }

    @SuppressWarnings("unchecked")
    public List<RuntimeEvent> getOrCreateEvents(AgentContext context) {
        Object value = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        if (value instanceof List<?>) {
            return (List<RuntimeEvent>) value;
        }

        List<RuntimeEvent> events = new ArrayList<>();
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, events);
        return events;
    }
}

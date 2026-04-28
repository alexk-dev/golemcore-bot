package me.golemcore.bot.domain.events;

import me.golemcore.bot.domain.service.SessionIdentitySupport;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Objects;

/**
 * Emits and stores runtime events for the current turn.
 */
@Service
@Slf4j
public class RuntimeEventService {

    private final Clock clock;

    public RuntimeEventService(Clock clock) {
        this.clock = clock;
    }

    public RuntimeEvent emit(AgentContext context, RuntimeEventType type, Map<String, Object> payload) {
        AgentContext safeContext = Objects.requireNonNull(context, "context must not be null");
        List<RuntimeEvent> events = getOrCreateEvents(safeContext);
        RuntimeEvent event = buildEvent(safeContext.getSession(), type, payload);

        events.add(event);
        safeContext.setAttribute(ContextAttributes.RUNTIME_EVENTS, events);
        return event;
    }

    public RuntimeEvent emitForSession(AgentSession session, RuntimeEventType type, Map<String, Object> payload) {
        return buildEvent(session, type, payload);
    }

    private RuntimeEvent buildEvent(AgentSession session, RuntimeEventType type, Map<String, Object> payload) {
        SessionIdentity identity = SessionIdentitySupport.resolveSessionIdentity(session);
        String channelType = identity != null ? identity.channelType()
                : (session != null ? session.getChannelType() : null);
        String chatId = identity != null ? identity.conversationKey() : (session != null ? session.getChatId() : null);
        Map<String, Object> safePayload = payload != null ? new LinkedHashMap<>(payload) : Map.of();

        return RuntimeEvent.builder().type(type).timestamp(Instant.now(clock))
                .sessionId(session != null ? session.getId() : null).channelType(channelType).chatId(chatId)
                .payload(safePayload).build();
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

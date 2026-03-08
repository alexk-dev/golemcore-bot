package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emits and stores runtime events for the current turn.
 */
@Service
public class RuntimeEventService {

    private final Clock clock;
    private final Map<String, ChannelPort> channelRegistry = new ConcurrentHashMap<>();

    public RuntimeEventService(Clock clock, List<ChannelPort> channelPorts) {
        this.clock = clock;
        if (channelPorts != null) {
            for (ChannelPort channelPort : channelPorts) {
                if (channelPort != null) {
                    channelRegistry.put(channelPort.getChannelType(), channelPort);
                }
            }
        }
    }

    public RuntimeEvent emit(AgentContext context, RuntimeEventType type, Map<String, Object> payload) {
        AgentContext safeContext = Objects.requireNonNull(context, "context must not be null");
        List<RuntimeEvent> events = getOrCreateEvents(safeContext);
        RuntimeEvent event = buildEvent(safeContext.getSession(), type, payload);

        events.add(event);
        safeContext.setAttribute(ContextAttributes.RUNTIME_EVENTS, events);
        forwardEventToChannel(safeContext.getSession(), event);
        return event;
    }

    public RuntimeEvent emitForSession(AgentSession session, RuntimeEventType type, Map<String, Object> payload) {
        RuntimeEvent event = buildEvent(session, type, payload);
        forwardEventToChannel(session, event);
        return event;
    }

    private RuntimeEvent buildEvent(AgentSession session, RuntimeEventType type, Map<String, Object> payload) {
        SessionIdentity identity = SessionIdentitySupport.resolveSessionIdentity(session);
        String channelType = identity != null ? identity.channelType()
                : (session != null ? session.getChannelType() : null);
        String chatId = identity != null ? identity.conversationKey() : (session != null ? session.getChatId() : null);
        Map<String, Object> safePayload = payload != null ? new LinkedHashMap<>(payload) : Map.of();

        return RuntimeEvent.builder()
                .type(type)
                .timestamp(Instant.now(clock))
                .sessionId(session != null ? session.getId() : null)
                .channelType(channelType)
                .chatId(chatId)
                .payload(safePayload)
                .build();
    }

    private void forwardEventToChannel(AgentSession session, RuntimeEvent event) {
        if (session == null || event == null) {
            return;
        }

        String channelType = session.getChannelType();
        if (channelType == null || channelType.isBlank()) {
            return;
        }

        ChannelPort channelPort = channelRegistry.get(channelType);
        if (channelPort == null) {
            return;
        }

        String chatId = SessionIdentitySupport.resolveTransportChatId(session);
        if (chatId == null || chatId.isBlank()) {
            return;
        }

        try {
            channelPort.sendRuntimeEvent(chatId, event);
        } catch (Exception e) { // NOSONAR - runtime event streaming must be best effort
            // Ignore channel transport errors, event remains in context list for
            // observability.
        }
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

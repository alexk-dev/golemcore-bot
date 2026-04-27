package me.golemcore.bot.domain.events;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEventServiceTest {

    @Test
    void shouldEmitRuntimeEventAndStoreItInContext() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);
        RuntimeEventService service = new RuntimeEventService(clock);

        AgentSession session = AgentSession.builder()
                .id("session-1")
                .channelType("web")
                .chatId("chat-1")
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();

        Map<String, Object> payload = new HashMap<>();
        payload.put("attempt", 1);

        RuntimeEvent event = service.emit(context, RuntimeEventType.TURN_STARTED, payload);

        assertNotNull(event);
        assertEquals(RuntimeEventType.TURN_STARTED, event.type());
        assertEquals(Instant.parse("2026-03-01T12:00:00Z"), event.timestamp());
        assertEquals("session-1", event.sessionId());
        assertEquals("web", event.channelType());
        assertEquals("chat-1", event.chatId());
        assertEquals(Map.of("attempt", 1), event.payload());

        payload.put("attempt", 2);
        assertEquals(Map.of("attempt", 1), event.payload());

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertNotNull(events);
        assertEquals(1, events.size());
    }

    @Test
    void shouldEmitForSessionUsingConversationIdentity() {
        RuntimeEventService service = new RuntimeEventService(Clock.systemUTC());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ContextAttributes.CONVERSATION_KEY, "conv-42");
        metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, "transport-42");

        AgentSession session = AgentSession.builder()
                .id("telegram:legacy")
                .channelType("telegram")
                .chatId("legacy-chat")
                .metadata(metadata)
                .build();

        RuntimeEvent event = service.emitForSession(session, RuntimeEventType.TOOL_FINISHED, Map.of("tool", "fs"));

        assertEquals("telegram", event.channelType());
        assertEquals("conv-42", event.chatId());
    }

    @Test
    void shouldCreateEventForSessionWithoutRequiringAChannel() {
        RuntimeEventService service = new RuntimeEventService(Clock.systemUTC());
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("unknown")
                .chatId("chat-1")
                .build();

        RuntimeEvent event = service.emitForSession(session, RuntimeEventType.LLM_STARTED, Map.of());

        assertNotNull(event);
    }

    @Test
    void shouldBuildEventsEvenWhenChannelTypeOrChatIdIsBlank() {
        RuntimeEventService service = new RuntimeEventService(Clock.systemUTC());
        AgentSession noChannel = AgentSession.builder()
                .id("s1")
                .channelType("  ")
                .chatId("chat-1")
                .build();
        AgentSession noChat = AgentSession.builder()
                .id("s2")
                .channelType("web")
                .chatId(" ")
                .build();

        service.emitForSession(noChannel, RuntimeEventType.LLM_STARTED, Map.of());
        service.emitForSession(noChat, RuntimeEventType.LLM_STARTED, Map.of());
    }

    @Test
    void shouldIgnoreNullPayloadAndStillStoreEvent() {
        RuntimeEventService service = new RuntimeEventService(Clock.systemUTC());
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("web")
                .chatId("chat-1")
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();

        assertDoesNotThrow(() -> service.emit(context, RuntimeEventType.TURN_FINISHED, null));
        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertNotNull(events);
        assertEquals(1, events.size());
        assertTrue(events.get(0).payload().isEmpty());
    }

    @Test
    void shouldReturnExistingRuntimeEventsListFromContext() {
        RuntimeEventService service = new RuntimeEventService(Clock.systemUTC());
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("s1").build())
                .messages(new ArrayList<>())
                .build();

        List<RuntimeEvent> existing = new ArrayList<>();
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, existing);

        List<RuntimeEvent> returned = service.getOrCreateEvents(context);

        assertSame(existing, returned);
    }

    @Test
    void shouldAppendNewRuntimeEventToExistingContextList() {
        RuntimeEventService service = new RuntimeEventService(Clock.systemUTC());
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("web")
                .chatId("chat-1")
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();

        List<RuntimeEvent> existing = new ArrayList<>();
        existing.add(RuntimeEvent.builder()
                .type(RuntimeEventType.TURN_STARTED)
                .timestamp(Instant.parse("2026-03-01T00:00:00Z"))
                .sessionId("s1")
                .channelType("web")
                .chatId("chat-1")
                .payload(Map.of("step", "before"))
                .build());
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, existing);

        service.emit(context, RuntimeEventType.TURN_FINISHED, Map.of("step", "after"));

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertSame(existing, events);
        assertEquals(2, events.size());
        assertEquals(RuntimeEventType.TURN_STARTED, events.get(0).type());
        assertEquals(RuntimeEventType.TURN_FINISHED, events.get(1).type());
    }

    @Test
    void shouldCreateRuntimeEventsListWhenAttributeHasUnexpectedType() {
        RuntimeEventService service = new RuntimeEventService(Clock.systemUTC());
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("s1").build())
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, "invalid");

        List<RuntimeEvent> returned = service.getOrCreateEvents(context);

        assertNotNull(returned);
        assertTrue(returned.isEmpty());
        List<RuntimeEvent> fromContext = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertSame(returned, fromContext);
    }
}

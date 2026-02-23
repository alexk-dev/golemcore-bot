package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramSessionServiceTest {

    private SessionPort sessionPort;
    private ActiveSessionPointerService pointerService;
    private TelegramSessionService service;

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        pointerService = mock(ActiveSessionPointerService.class);
        service = new TelegramSessionService(sessionPort, pointerService);
    }

    @Test
    void shouldResolveActiveConversationFromPointer() {
        when(pointerService.buildTelegramPointerKey("100")).thenReturn("telegram|100");
        when(pointerService.getActiveConversationKey("telegram|100")).thenReturn(Optional.of("conv-1"));

        AgentSession session = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .build();
        when(sessionPort.get("telegram:conv-1")).thenReturn(Optional.of(session));
        when(sessionPort.getOrCreate("telegram", "conv-1")).thenReturn(session);

        String conversation = service.resolveActiveConversationKey("100");

        assertEquals("conv-1", conversation);
        assertEquals("100", session.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        verify(sessionPort).save(session);
    }

    @Test
    void shouldCreateNewConversationWhenPointerMissingAndNoHistoryExists() {
        when(pointerService.buildTelegramPointerKey("100")).thenReturn("telegram|100");
        when(pointerService.getActiveConversationKey("telegram|100")).thenReturn(Optional.empty());
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "100")).thenReturn(List.of());

        when(sessionPort.getOrCreate(anyString(), anyString())).thenAnswer(invocation -> AgentSession.builder()
                .id(invocation.getArgument(0) + ":" + invocation.getArgument(1))
                .channelType(invocation.getArgument(0))
                .chatId(invocation.getArgument(1))
                .metadata(new HashMap<>())
                .build());

        String conversation = service.resolveActiveConversationKey("100");

        assertTrue(conversation.length() >= 8);
        verify(pointerService).setActiveConversationKey("telegram|100", conversation);
        verify(sessionPort).save(any(AgentSession.class));
    }

    @Test
    void shouldActivateConversationAndBindSession() {
        when(pointerService.buildTelegramPointerKey("200")).thenReturn("telegram|200");

        AgentSession session = AgentSession.builder()
                .id("telegram:conv-200")
                .channelType("telegram")
                .chatId("conv-200")
                .metadata(new HashMap<>())
                .build();
        when(sessionPort.getOrCreate("telegram", "conv-200")).thenReturn(session);

        service.activateConversation("200", "conv-200");

        verify(pointerService).setActiveConversationKey("telegram|200", "conv-200");
        verify(sessionPort).save(session);
        assertEquals("200", session.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertEquals("conv-200", session.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
    }

    @Test
    void shouldListRecentSessionsOnlyForTransport() {
        AgentSession s1 = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .metadata(metadata("100", "conv-1"))
                .updatedAt(Instant.now())
                .build();
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "100")).thenReturn(List.of(s1));

        List<AgentSession> result = service.listRecentSessions("100", 5);

        assertEquals(1, result.size());
        assertEquals("telegram:conv-1", result.get(0).getId());
    }

    @Test
    void shouldCreateAndActivateConversation() {
        when(pointerService.buildTelegramPointerKey("300")).thenReturn("telegram|300");
        when(sessionPort.getOrCreate(anyString(), anyString())).thenAnswer(invocation -> AgentSession.builder()
                .id(invocation.getArgument(0) + ":" + invocation.getArgument(1))
                .channelType(invocation.getArgument(0))
                .chatId(invocation.getArgument(1))
                .metadata(new HashMap<>())
                .build());

        String conversation = service.createAndActivateConversation("300");

        assertTrue(conversation.length() >= 8);
        verify(pointerService).setActiveConversationKey("telegram|300", conversation);
    }

    @Test
    void shouldFallbackToLatestConversationForTransportWhenPointerMissing() {
        when(pointerService.buildTelegramPointerKey("100")).thenReturn("telegram|100");
        when(pointerService.getActiveConversationKey("telegram|100")).thenReturn(Optional.empty());

        AgentSession oldSession = AgentSession.builder()
                .id("telegram:conv-old")
                .channelType("telegram")
                .chatId("conv-old")
                .metadata(metadata("100", "conv-old"))
                .updatedAt(Instant.now())
                .build();
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "100")).thenReturn(List.of(oldSession));
        when(sessionPort.getOrCreate("telegram", "conv-old")).thenReturn(oldSession);

        String conversation = service.resolveActiveConversationKey("100");

        assertEquals("conv-old", conversation);
        verify(pointerService).setActiveConversationKey("telegram|100", "conv-old");
    }

    @Test
    void shouldThrowWhenTransportChatIdBlank() {
        assertThrows(IllegalArgumentException.class, () -> service.resolveActiveConversationKey(" "));
        assertThrows(IllegalArgumentException.class, () -> service.listRecentSessions("", 5));
        assertThrows(IllegalArgumentException.class, () -> service.activateConversation(null, "conv-1"));
    }

    @Test
    void shouldThrowWhenConversationKeyInvalid() {
        assertThrows(IllegalArgumentException.class, () -> service.activateConversation("100", "bad:key"));
        assertThrows(IllegalArgumentException.class, () -> service.activateConversation("100", " "));
        assertThrows(IllegalArgumentException.class, () -> service.activateConversation("100", "a".repeat(65)));

        verify(pointerService, never()).setActiveConversationKey(anyString(), anyString());
    }

    @Test
    void shouldClampRecentLimitToMaxTwenty() {
        List<AgentSession> sessions = List.of(
                buildTransportSession("conv-1", "100", Instant.now().plusSeconds(1)),
                buildTransportSession("conv-2", "100", Instant.now().plusSeconds(2)),
                buildTransportSession("conv-3", "100", Instant.now().plusSeconds(3)),
                buildTransportSession("conv-4", "100", Instant.now().plusSeconds(4)),
                buildTransportSession("conv-5", "100", Instant.now().plusSeconds(5)),
                buildTransportSession("conv-6", "100", Instant.now().plusSeconds(6)),
                buildTransportSession("conv-7", "100", Instant.now().plusSeconds(7)),
                buildTransportSession("conv-8", "100", Instant.now().plusSeconds(8)),
                buildTransportSession("conv-9", "100", Instant.now().plusSeconds(9)),
                buildTransportSession("conv-10", "100", Instant.now().plusSeconds(10)),
                buildTransportSession("conv-11", "100", Instant.now().plusSeconds(11)),
                buildTransportSession("conv-12", "100", Instant.now().plusSeconds(12)),
                buildTransportSession("conv-13", "100", Instant.now().plusSeconds(13)),
                buildTransportSession("conv-14", "100", Instant.now().plusSeconds(14)),
                buildTransportSession("conv-15", "100", Instant.now().plusSeconds(15)),
                buildTransportSession("conv-16", "100", Instant.now().plusSeconds(16)),
                buildTransportSession("conv-17", "100", Instant.now().plusSeconds(17)),
                buildTransportSession("conv-18", "100", Instant.now().plusSeconds(18)),
                buildTransportSession("conv-19", "100", Instant.now().plusSeconds(19)),
                buildTransportSession("conv-20", "100", Instant.now().plusSeconds(20)),
                buildTransportSession("conv-21", "100", Instant.now().plusSeconds(21)),
                buildTransportSession("conv-22", "100", Instant.now().plusSeconds(22)));
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "100")).thenReturn(sessions);

        List<AgentSession> result = service.listRecentSessions("100", 100);

        assertEquals(20, result.size());
        assertEquals("telegram:conv-22", result.get(0).getId());
    }

    @Test
    void shouldClampRecentLimitToMinimumOne() {
        AgentSession older = buildTransportSession("conv-1", "100", Instant.now().plusSeconds(1));
        AgentSession newer = buildTransportSession("conv-2", "100", Instant.now().plusSeconds(2));
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "100")).thenReturn(List.of(older, newer));

        List<AgentSession> result = service.listRecentSessions("100", 0);

        assertEquals(1, result.size());
        assertEquals("telegram:conv-2", result.get(0).getId());
    }

    @Test
    void shouldSortByCreatedAtWhenUpdatedAtMissing() {
        AgentSession older = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .metadata(metadata("100", "conv-1"))
                .createdAt(Instant.parse("2026-02-20T10:00:00Z"))
                .build();
        AgentSession newer = AgentSession.builder()
                .id("telegram:conv-2")
                .channelType("telegram")
                .chatId("conv-2")
                .metadata(metadata("100", "conv-2"))
                .createdAt(Instant.parse("2026-02-20T10:05:00Z"))
                .build();
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "100")).thenReturn(List.of(older, newer));

        List<AgentSession> result = service.listRecentSessions("100", 5);

        assertEquals(2, result.size());
        assertEquals("telegram:conv-2", result.get(0).getId());
    }

    @Test
    void shouldIncludeLegacyTransportSessionsByChatIdFallback() {
        AgentSession legacy = AgentSession.builder()
                .id("telegram:100")
                .channelType("telegram")
                .chatId("100")
                .updatedAt(Instant.now())
                .build();
        AgentSession unrelated = AgentSession.builder()
                .id("telegram:conv-2")
                .channelType("telegram")
                .chatId("conv-2")
                .metadata(metadata("200", "conv-2"))
                .updatedAt(Instant.now().plusSeconds(1))
                .build();
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "100")).thenReturn(List.of(legacy, unrelated));

        List<AgentSession> result = service.listRecentSessions("100", 5);

        assertEquals(1, result.size());
        assertEquals("telegram:100", result.get(0).getId());
    }

    @Test
    void shouldRejectConversationKeyWithUnsupportedCharacters() {
        assertThrows(IllegalArgumentException.class, () -> service.activateConversation("100", "bad key"));
        verify(pointerService, never()).setActiveConversationKey(anyString(), anyString());
    }

    @Test
    void shouldAllowLegacyConversationKeyWhenSessionAlreadyExists() {
        when(pointerService.buildTelegramPointerKey("100")).thenReturn("telegram|100");
        when(sessionPort.get("telegram:legacy7")).thenReturn(Optional.of(AgentSession.builder()
                .id("telegram:legacy7")
                .channelType("telegram")
                .chatId("legacy7")
                .metadata(new HashMap<>())
                .build()));
        AgentSession session = AgentSession.builder()
                .id("telegram:legacy7")
                .channelType("telegram")
                .chatId("legacy7")
                .metadata(new HashMap<>())
                .build();
        when(sessionPort.getOrCreate("telegram", "legacy7")).thenReturn(session);

        service.activateConversation("100", "legacy7");

        verify(pointerService).setActiveConversationKey("telegram|100", "legacy7");
        verify(sessionPort).save(session);
    }

    @Test
    void shouldRejectUnknownLegacyConversationKey() {
        when(sessionPort.get("telegram:legacy7")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.activateConversation("100", "legacy7"));
        verify(pointerService, never()).setActiveConversationKey(anyString(), anyString());
    }

    @Test
    void shouldRepairPointerWhenConversationBoundToDifferentTransport() {
        when(pointerService.buildTelegramPointerKey("100")).thenReturn("telegram|100");
        when(pointerService.getActiveConversationKey("telegram|100")).thenReturn(Optional.of("conv-other"));

        AgentSession otherTransportSession = AgentSession.builder()
                .id("telegram:conv-other")
                .channelType("telegram")
                .chatId("conv-other")
                .metadata(metadata("200", "conv-other"))
                .updatedAt(Instant.now().plusSeconds(10))
                .build();
        AgentSession fallbackSession = buildTransportSession("conv-own", "100", Instant.now());

        when(sessionPort.get("telegram:conv-other")).thenReturn(Optional.of(otherTransportSession));
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "100"))
                .thenReturn(List.of(otherTransportSession, fallbackSession));
        when(sessionPort.getOrCreate("telegram", "conv-own")).thenReturn(fallbackSession);

        String conversation = service.resolveActiveConversationKey("100");

        assertEquals("conv-own", conversation);
        verify(pointerService).setActiveConversationKey("telegram|100", "conv-own");
    }

    private AgentSession buildTransportSession(String conversationKey, String transportChatId, Instant updatedAt) {
        return AgentSession.builder()
                .id("telegram:" + conversationKey)
                .channelType("telegram")
                .chatId(conversationKey)
                .metadata(metadata(transportChatId, conversationKey))
                .updatedAt(updatedAt)
                .build();
    }

    private Map<String, Object> metadata(String transportChatId, String conversationKey) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, transportChatId);
        metadata.put(ContextAttributes.CONVERSATION_KEY, conversationKey);
        return metadata;
    }
}

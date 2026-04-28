package me.golemcore.bot.domain.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.identity.SessionIdentitySupport;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.view.ActiveSessionSelectionView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionSelectionServiceTest {

    private SessionPort sessionPort;
    private ActiveSessionPointerService pointerService;
    private SessionSelectionService service;

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        pointerService = mock(ActiveSessionPointerService.class);
        service = new SessionSelectionService(sessionPort, pointerService);
    }

    @Test
    void shouldListRecentSessionsWithActiveConversationAndLimit() {
        AgentSession newer = AgentSession.builder().id("web:conv-2").channelType("web").chatId("legacy-2")
                .metadata(new HashMap<>()).updatedAt(Instant.parse("2026-03-20T10:05:00Z")).messages(List.of()).build();
        AgentSession older = AgentSession.builder().id("web:conv-1").channelType("web").chatId("legacy-1")
                .metadata(new HashMap<>()).updatedAt(Instant.parse("2026-03-20T10:00:00Z")).messages(List.of()).build();
        newer.getMetadata().put(ContextAttributes.CONVERSATION_KEY, "conv-2");
        older.getMetadata().put(ContextAttributes.CONVERSATION_KEY, "conv-1");
        SessionIdentitySupport.bindWebClientInstance(newer, "client-1");
        SessionIdentitySupport.bindWebClientInstance(older, "client-1");
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(older, newer));
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(pointerService.getActiveConversationKey("web|admin|client-1")).thenReturn(Optional.of("conv-2"));

        List<SessionSummaryView> recent = service.listRecentSessions("web", "client-1", null, "admin", 1);

        assertEquals(1, recent.size());
        assertEquals("web:conv-2", recent.get(0).getId());
        assertTrue(recent.get(0).isActive());
    }

    @Test
    void shouldRepairPointerWhenActiveConversationDoesNotExist() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(pointerService.getActiveConversationKey("web|admin|client-1")).thenReturn(Optional.of("missing-session"));
        when(sessionPort.get("web:missing-session")).thenReturn(Optional.empty());

        AgentSession fallback = AgentSession.builder().id("web:valid-session-123").channelType("web")
                .chatId("valid-session-123").metadata(new HashMap<>()).updatedAt(Instant.parse("2026-02-22T10:00:00Z"))
                .messages(List.of()).build();
        SessionIdentitySupport.bindWebClientInstance(fallback, "client-1");
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(fallback));

        ActiveSessionSelectionView selection = service.getActiveSession("web", "client-1", null, "admin");

        assertEquals("valid-session-123", selection.getConversationKey());
        assertEquals("repaired", selection.getSource());
        verify(pointerService).setActiveConversationKey("web|admin|client-1", "valid-session-123");
    }

    @Test
    void shouldCreateFreshOwnedSessionInsteadOfReusingAnotherClientsWebSession() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(pointerService.getActiveConversationKey("web|admin|client-1")).thenReturn(Optional.of("missing-session"));
        when(sessionPort.get("web:missing-session")).thenReturn(Optional.empty());

        AgentSession foreignSession = AgentSession.builder().id("web:foreign-session").channelType("web")
                .chatId("foreign-session").metadata(new HashMap<>()).updatedAt(Instant.parse("2026-02-22T10:00:00Z"))
                .messages(List.of()).build();
        SessionIdentitySupport.bindWebClientInstance(foreignSession, "client-2");
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(foreignSession));
        when(sessionPort.getOrCreate(eq("web"), anyString())).thenAnswer(invocation -> AgentSession.builder()
                .id("web:" + invocation.getArgument(1, String.class)).channelType("web")
                .chatId(invocation.getArgument(1, String.class)).messages(List.of()).build());

        ActiveSessionSelectionView selection = service.getActiveSession("web", "client-1", null, "admin");

        assertNotEquals("foreign-session", selection.getConversationKey());
        assertEquals("repaired", selection.getSource());
        ArgumentCaptor<String> conversationCaptor = ArgumentCaptor.forClass(String.class);
        verify(pointerService).setActiveConversationKey(eq("web|admin|client-1"), conversationCaptor.capture());
        assertEquals(conversationCaptor.getValue(), selection.getConversationKey());
        ArgumentCaptor<AgentSession> sessionCaptor = ArgumentCaptor.forClass(AgentSession.class);
        verify(sessionPort).save(sessionCaptor.capture());
        assertEquals("client-1", SessionIdentitySupport.resolveWebClientInstanceId(sessionCaptor.getValue()));
    }

    @Test
    void shouldCreateSessionAndActivateByDefault() {
        AgentSession session = AgentSession.builder().id("web:new-session").channelType("web").chatId("new-session")
                .createdAt(Instant.now()).messages(List.of()).build();

        when(sessionPort.getOrCreate("web", "new-session")).thenReturn(session);
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");

        SessionSummaryView created = service.createSession("web", "client-1", "admin", "new-session", null);

        assertEquals("new-session", created.getConversationKey());
        assertTrue(created.isActive());
        verify(sessionPort).save(session);
        verify(pointerService).setActiveConversationKey("web|admin|client-1", "new-session");
        assertEquals("client-1", SessionIdentitySupport.resolveWebClientInstanceId(session));
    }

    @Test
    void shouldRejectActivatingSessionOwnedByAnotherClient() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");

        AgentSession foreignSession = AgentSession.builder().id("web:session-1234").channelType("web")
                .chatId("session-1234").metadata(new HashMap<>()).messages(List.of()).build();
        SessionIdentitySupport.bindWebClientInstance(foreignSession, "client-2");
        when(sessionPort.get("web:session-1234")).thenReturn(Optional.of(foreignSession));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.setActiveSession("web", "client-1", null, "admin", "session-1234"));

        assertEquals("conversationKey belongs to another web client", error.getMessage());
        verify(pointerService, never()).setActiveConversationKey("web|admin|client-1", "session-1234");
    }

    @Test
    void shouldSetActiveTelegramSessionAndPersistTransportBinding() {
        AgentSession telegramSession = AgentSession.builder().id("telegram:conv-1").channelType("telegram")
                .chatId("conv-1").metadata(new HashMap<>()).messages(List.of()).build();
        when(pointerService.buildTelegramPointerKey("100")).thenReturn("telegram|100");
        when(sessionPort.get("telegram:conv-1")).thenReturn(Optional.of(telegramSession));
        when(sessionPort.getOrCreate("telegram", "conv-1")).thenReturn(telegramSession);

        ActiveSessionSelectionView selection = service.setActiveSession("telegram", null, "100", null, "conv-1");

        assertEquals("telegram", selection.getChannelType());
        assertEquals("100", selection.getTransportChatId());
        verify(pointerService).setActiveConversationKey("telegram|100", "conv-1");
        verify(sessionPort).save(telegramSession);
        assertEquals("100", telegramSession.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertEquals("conv-1", telegramSession.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
    }

    @Test
    void shouldRejectCreateSessionWithoutClientInstanceIdBeforePersisting() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createSession("web", null, "admin", "new-session", true));

        assertEquals("clientInstanceId is required for web", error.getMessage());
        verify(sessionPort, never()).getOrCreate(anyString(), anyString());
        verify(sessionPort, never()).save(any());
    }
}

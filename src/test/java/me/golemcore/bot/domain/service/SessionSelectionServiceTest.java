package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.view.ActiveSessionSelectionView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        AgentSession newer = AgentSession.builder()
                .id("web:conv-2")
                .channelType("web")
                .chatId("legacy-2")
                .metadata(java.util.Map.of(ContextAttributes.CONVERSATION_KEY, "conv-2"))
                .updatedAt(Instant.parse("2026-03-20T10:05:00Z"))
                .messages(List.of())
                .build();
        AgentSession older = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("legacy-1")
                .metadata(java.util.Map.of(ContextAttributes.CONVERSATION_KEY, "conv-1"))
                .updatedAt(Instant.parse("2026-03-20T10:00:00Z"))
                .messages(List.of())
                .build();
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
        when(pointerService.getActiveConversationKey("web|admin|client-1"))
                .thenReturn(Optional.of("missing-session"));
        when(sessionPort.get("web:missing-session")).thenReturn(Optional.empty());

        AgentSession fallback = AgentSession.builder()
                .id("web:valid-session-123")
                .channelType("web")
                .chatId("valid-session-123")
                .updatedAt(Instant.parse("2026-02-22T10:00:00Z"))
                .messages(List.of())
                .build();
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(fallback));

        ActiveSessionSelectionView selection = service.getActiveSession("web", "client-1", null, "admin");

        assertEquals("valid-session-123", selection.getConversationKey());
        assertEquals("repaired", selection.getSource());
        verify(pointerService).setActiveConversationKey("web|admin|client-1", "valid-session-123");
    }

    @Test
    void shouldCreateSessionAndActivateByDefault() {
        AgentSession session = AgentSession.builder()
                .id("web:new-session")
                .channelType("web")
                .chatId("new-session")
                .createdAt(Instant.now())
                .messages(List.of())
                .build();

        when(sessionPort.getOrCreate("web", "new-session")).thenReturn(session);
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");

        SessionSummaryView created = service.createSession("web", "client-1", "admin", "new-session", null);

        assertEquals("new-session", created.getConversationKey());
        assertTrue(created.isActive());
        verify(sessionPort).save(session);
        verify(pointerService).setActiveConversationKey("web|admin|client-1", "new-session");
    }
}

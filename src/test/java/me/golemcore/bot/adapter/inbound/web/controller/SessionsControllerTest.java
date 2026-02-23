package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.ActiveSessionRequest;
import me.golemcore.bot.adapter.inbound.web.dto.ActiveSessionResponse;
import me.golemcore.bot.adapter.inbound.web.dto.CreateSessionRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SessionDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ActiveSessionPointerService;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import reactor.test.StepVerifier;
import org.mockito.ArgumentCaptor;

class SessionsControllerTest {

    private SessionPort sessionPort;
    private ActiveSessionPointerService pointerService;
    private SessionsController controller;

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        pointerService = mock(ActiveSessionPointerService.class);
        controller = new SessionsController(sessionPort, pointerService);
    }

    @Test
    void shouldListSessions() {
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("123")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .messages(List.of())
                .build();
        when(sessionPort.listAll()).thenReturn(List.of(session));

        StepVerifier.create(controller.listSessions(null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<SessionSummaryDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("s1", body.get(0).getId());
                })
                .verifyComplete();
    }

    @Test
    void shouldFilterSessionsByChannel() {
        AgentSession tgSession = AgentSession.builder()
                .id("s1").channelType("telegram").chatId("123")
                .createdAt(Instant.now()).messages(List.of()).build();
        AgentSession webSession = AgentSession.builder()
                .id("s2").channelType("web").chatId("456")
                .createdAt(Instant.now()).messages(List.of()).build();
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(webSession));

        StepVerifier.create(controller.listSessions("web"))
                .assertNext(response -> {
                    assertEquals(1, response.getBody().size());
                    assertEquals("s2", response.getBody().get(0).getId());
                })
                .verifyComplete();
    }

    @Test
    void shouldGetSessionById() {
        Message msg = Message.builder()
                .id("m1").role("user").content("hello")
                .timestamp(Instant.now()).build();
        AgentSession session = AgentSession.builder()
                .id("s1").channelType("telegram").chatId("123")
                .createdAt(Instant.now()).messages(List.of(msg)).build();
        when(sessionPort.get("s1")).thenReturn(Optional.of(session));

        StepVerifier.create(controller.getSession("s1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SessionDetailDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("s1", body.getId());
                    assertEquals(1, body.getMessages().size());
                    assertEquals("hello", body.getMessages().get(0).getContent());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturn404ForMissingSession() {
        when(sessionPort.get("unknown")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSession("unknown"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Session not found", ex.getReason());
    }

    @Test
    void shouldDeleteSession() {
        StepVerifier.create(controller.deleteSession("s1"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();
        verify(sessionPort).delete("s1");
    }

    @Test
    void shouldCompactSession() {
        when(sessionPort.compactMessages("s1", 20)).thenReturn(5);

        StepVerifier.create(controller.compactSession("s1", 20))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(5, response.getBody().get("removed"));
                })
                .verifyComplete();
    }

    @Test
    void shouldClearSession() {
        StepVerifier.create(controller.clearSession("s1"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();
        verify(sessionPort).clearMessages("s1");
    }

    @Test
    void shouldReturnRecentSessionsWithActiveFlag() {
        AgentSession session = AgentSession.builder()
                .id("web:abc-session")
                .channelType("web")
                .chatId("abc-session")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .messages(List.of())
                .build();
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(session));
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(pointerService.getActiveConversationKey("web|admin|client-1"))
                .thenReturn(Optional.of("abc-session"));

        Principal principal = () -> "admin";
        StepVerifier.create(controller.listRecentSessions("web", 5, "client-1", null, principal))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<SessionSummaryDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("abc-session", body.get(0).getConversationKey());
                    assertTrue(body.get(0).isActive());
                })
                .verifyComplete();
    }

    @Test
    void shouldFilterTelegramRecentSessionsByTransportChatId() {
        AgentSession first = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .metadata(Map.of(
                        "session.transport.chat.id", "100",
                        "session.conversation.key", "conv-1"))
                .updatedAt(Instant.now())
                .messages(List.of())
                .build();
        AgentSession second = AgentSession.builder()
                .id("telegram:conv-2")
                .channelType("telegram")
                .chatId("conv-2")
                .metadata(Map.of(
                        "session.transport.chat.id", "200",
                        "session.conversation.key", "conv-2"))
                .updatedAt(Instant.now().plusSeconds(10))
                .messages(List.of())
                .build();
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "100")).thenReturn(List.of(first));
        when(pointerService.buildTelegramPointerKey("100")).thenReturn("telegram|100");
        when(pointerService.getActiveConversationKey("telegram|100")).thenReturn(Optional.of("conv-1"));

        StepVerifier.create(controller.listRecentSessions("telegram", 5, null, "100", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<SessionSummaryDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("conv-1", body.get(0).getConversationKey());
                    assertEquals("100", body.get(0).getTransportChatId());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnActiveSessionFromPointer() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(pointerService.getActiveConversationKey("web|admin|client-1"))
                .thenReturn(Optional.of("active-session"));
        AgentSession existing = AgentSession.builder()
                .id("web:active-session")
                .channelType("web")
                .chatId("active-session")
                .messages(List.of())
                .build();
        when(sessionPort.get("web:active-session")).thenReturn(Optional.of(existing));

        Principal principal = () -> "admin";
        StepVerifier.create(controller.getActiveSession("web", "client-1", null, principal))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    ActiveSessionResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("active-session", body.getConversationKey());
                })
                .verifyComplete();
    }

    @Test
    void shouldSetActiveSessionForWeb() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");

        ActiveSessionRequest request = ActiveSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("session-1234")
                .build();
        Principal principal = () -> "admin";

        StepVerifier.create(controller.setActiveSession(request, principal))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    ActiveSessionResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("session-1234", body.getConversationKey());
                })
                .verifyComplete();

        verify(pointerService).setActiveConversationKey("web|admin|client-1", "session-1234");
    }

    @Test
    void shouldCreateSessionAndActivate() {
        AgentSession session = AgentSession.builder()
                .id("web:new-session")
                .channelType("web")
                .chatId("new-session")
                .createdAt(Instant.now())
                .messages(List.of())
                .build();

        when(sessionPort.getOrCreate("web", "new-session")).thenReturn(session);
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");

        CreateSessionRequest request = CreateSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("new-session")
                .activate(true)
                .build();
        Principal principal = () -> "admin";

        StepVerifier.create(controller.createSession(request, principal))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatusCode());
                    SessionSummaryDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("new-session", body.getConversationKey());
                    assertTrue(body.isActive());
                })
                .verifyComplete();

        verify(sessionPort).save(session);
        verify(pointerService).setActiveConversationKey("web|admin|client-1", "new-session");
    }

    @Test
    void shouldRejectSetActiveWhenConversationKeyMissing() {
        ActiveSessionRequest request = ActiveSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey(" ")
                .build();
        Principal principal = () -> "admin";

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.setActiveSession(request, principal));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldRejectSetActiveWhenConversationKeyInvalid() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        ActiveSessionRequest request = ActiveSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("bad:key")
                .build();
        Principal principal = () -> "admin";

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.setActiveSession(request, principal));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldRejectRecentSessionsWithoutWebClientInstanceId() {
        Principal principal = () -> "admin";

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.listRecentSessions("web", 5, null, null, principal));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldRejectActiveSessionWithoutTelegramTransportChatId() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.getActiveSession("telegram", null, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldRejectCreateSessionForNonWebChannel() {
        CreateSessionRequest request = CreateSessionRequest.builder()
                .channelType("telegram")
                .conversationKey("conv-1")
                .build();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.createSession(request, () -> "admin"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldCreateSessionWithoutActivationWhenRequested() {
        AgentSession session = AgentSession.builder()
                .id("web:new-passive")
                .channelType("web")
                .chatId("new-passive")
                .createdAt(Instant.now())
                .messages(List.of())
                .build();

        when(sessionPort.getOrCreate("web", "new-passive")).thenReturn(session);

        CreateSessionRequest request = CreateSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("new-passive")
                .activate(false)
                .build();

        StepVerifier.create(controller.createSession(request, () -> "admin"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatusCode());
                    SessionSummaryDto body = response.getBody();
                    assertNotNull(body);
                    assertFalse(body.isActive());
                })
                .verifyComplete();

        verify(pointerService, never()).setActiveConversationKey("web|admin|client-1", "new-passive");
    }

    @Test
    void shouldRejectActiveSessionRequestWhenPrincipalMissing() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.getActiveSession("web", "client-1", null, null));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
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

        StepVerifier.create(controller.getActiveSession("web", "client-1", null, () -> "admin"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    ActiveSessionResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("valid-session-123", body.getConversationKey());
                })
                .verifyComplete();

        verify(pointerService).setActiveConversationKey("web|admin|client-1", "valid-session-123");
    }

    @Test
    void shouldCreateAndActivateFreshConversationWhenPointerIsStaleAndNoFallbackExists() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(pointerService.getActiveConversationKey("web|admin|client-1"))
                .thenReturn(Optional.of("missing-session"));
        when(sessionPort.get("web:missing-session")).thenReturn(Optional.empty());
        when(sessionPort.listByChannelType("web")).thenReturn(List.of());
        when(sessionPort.getOrCreate(eq("web"), any(String.class))).thenAnswer(invocation -> AgentSession.builder()
                .id("web:" + invocation.getArgument(1))
                .channelType("web")
                .chatId(invocation.getArgument(1))
                .messages(List.of())
                .build());

        StepVerifier.create(controller.getActiveSession("web", "client-1", null, () -> "admin"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    ActiveSessionResponse body = response.getBody();
                    assertNotNull(body);
                    assertTrue(body.getConversationKey().length() >= 8);
                })
                .verifyComplete();

        ArgumentCaptor<String> conversationCaptor = ArgumentCaptor.forClass(String.class);
        verify(pointerService).setActiveConversationKey(eq("web|admin|client-1"), conversationCaptor.capture());
        verify(sessionPort).save(any(AgentSession.class));
        assertTrue(conversationCaptor.getValue().length() >= 8);
    }

    @Test
    void shouldRepointMatchingPointersWhenDeletingSession() {
        AgentSession deleted = AgentSession.builder()
                .id("web:to-delete")
                .channelType("web")
                .chatId("to-delete")
                .messages(List.of())
                .build();
        AgentSession fallback = AgentSession.builder()
                .id("web:latest-session")
                .channelType("web")
                .chatId("latest-session")
                .updatedAt(Instant.parse("2026-02-22T10:00:00Z"))
                .messages(List.of())
                .build();

        when(sessionPort.get("web:to-delete")).thenReturn(Optional.of(deleted));
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(fallback));
        when(pointerService.getPointersSnapshot()).thenReturn(Map.of(
                "web|admin|client-1", "to-delete",
                "web|other|client-2", "other-session"));

        StepVerifier.create(controller.deleteSession("web:to-delete"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();

        verify(pointerService).setActiveConversationKey("web|admin|client-1", "latest-session");
        verify(pointerService, never()).setActiveConversationKey("web|other|client-2", "latest-session");
    }

    @Test
    void shouldRejectCreateSessionWithTooShortConversationKey() {
        CreateSessionRequest request = CreateSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("short_7")
                .build();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.createSession(request, () -> "admin"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldAllowSetActiveForExistingLegacyConversationKey() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        AgentSession legacy = AgentSession.builder()
                .id("web:legacy7")
                .channelType("web")
                .chatId("legacy7")
                .messages(List.of())
                .build();
        when(sessionPort.get("web:legacy7")).thenReturn(Optional.of(legacy));

        ActiveSessionRequest request = ActiveSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("legacy7")
                .build();

        StepVerifier.create(controller.setActiveSession(request, () -> "admin"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("legacy7", response.getBody().getConversationKey());
                })
                .verifyComplete();

        verify(pointerService).setActiveConversationKey("web|admin|client-1", "legacy7");
    }

    @Test
    void shouldRejectSetActiveForUnknownLegacyConversationKey() {
        when(pointerService.buildWebPointerKey("admin", "client-1")).thenReturn("web|admin|client-1");
        when(sessionPort.get("web:legacy7")).thenReturn(Optional.empty());

        ActiveSessionRequest request = ActiveSessionRequest.builder()
                .channelType("web")
                .clientInstanceId("client-1")
                .conversationKey("legacy7")
                .build();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.setActiveSession(request, () -> "admin"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }
}

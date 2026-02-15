package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.SessionDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import reactor.test.StepVerifier;

class SessionsControllerTest {

    private SessionPort sessionPort;
    private SessionsController controller;

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        controller = new SessionsController(sessionPort);
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
        when(sessionPort.listAll()).thenReturn(List.of(tgSession, webSession));

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

        StepVerifier.create(controller.getSession("unknown"))
                .assertNext(response -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()))
                .verifyComplete();
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
}

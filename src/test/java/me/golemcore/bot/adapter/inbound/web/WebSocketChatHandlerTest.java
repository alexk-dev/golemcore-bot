package me.golemcore.bot.adapter.inbound.web;

import me.golemcore.bot.adapter.inbound.command.WebCommandPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.adapter.inbound.web.security.JwtTokenProvider;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.service.ActiveSessionPointerService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.CommandPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.web.reactive.socket.WebSocketMessage;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketChatHandlerTest {

    private JwtTokenProvider tokenProvider;
    private WebChannelAdapter webChannelAdapter;
    private ObjectMapper objectMapper;
    private ApplicationEventPublisher eventPublisher;
    private ActiveSessionPointerService pointerService;
    @SuppressWarnings("unchecked")
    private ObjectProvider<WebCommandPort> commandRouterProvider = mock(ObjectProvider.class);
    private WebSocketChatHandler handler;

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties();
        props.getDashboard().setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        tokenProvider = new JwtTokenProvider(props);
        tokenProvider.init();

        objectMapper = new ObjectMapper();
        eventPublisher = mock(ApplicationEventPublisher.class);
        webChannelAdapter = new WebChannelAdapter(objectMapper, eventPublisher);
        pointerService = mock(ActiveSessionPointerService.class);
        when(pointerService.buildWebPointerKey(any(String.class), any(String.class)))
                .thenAnswer(invocation -> "web|" + invocation.getArgument(0) + "|" + invocation.getArgument(1));
        when(commandRouterProvider.getIfAvailable()).thenReturn(null);
        handler = new WebSocketChatHandler(tokenProvider, webChannelAdapter, objectMapper, commandRouterProvider,
                pointerService);
    }

    @Test
    void shouldRejectConnectionWithoutToken() {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat"));
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
        verify(session).close();
    }

    @Test
    void shouldRejectConnectionWithInvalidToken() {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=invalid"));
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
        verify(session).close();
    }

    @Test
    void shouldAcceptConnectionWithValidToken() {
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-1");
        when(session.receive()).thenReturn(Flux.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
    }

    @Test
    void shouldHandleIncomingMessage() {
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-1");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"hello\",\"sessionId\":\"chat-1\"}");

        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(eventPublisher).publishEvent(any(AgentLoop.InboundMessageEvent.class));
    }

    @Test
    void shouldBindWebActivePointerFromSocketPayload() {
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-pointer");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText())
                .thenReturn("{\"text\":\"hello\",\"sessionId\":\"chat-1\",\"clientInstanceId\":\"client-1\"}");
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(pointerService).setActiveConversationKey("web|admin|client-1", "chat-1");
    }

    @Test
    void shouldIgnoreBlankMessages() {
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-2");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"\"}");

        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
    }

    @Test
    void shouldHandleMalformedJson() {
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-3");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("not json");

        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
    }

    @Test
    void shouldHandleImageAttachmentWithoutText() {
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-4");

        String imagePayload = "{\"text\":\"\",\"sessionId\":\"chat-4\",\"attachments\":[{\"type\":\"image\",\"name\":\"sample.png\",\"mimeType\":\"image/png\",\"dataBase64\":\"iVBORw0KGgo=\"}]}";
        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn(imagePayload);
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        AgentLoop.InboundMessageEvent event = captor.getValue();
        assertNotNull(event);
        assertNotNull(event.message().getMetadata());
        Object attachments = event.message().getMetadata().get("attachments");
        assertNotNull(attachments);
        assertEquals("", event.message().getContent());
    }

    @Test
    void shouldRejectRefreshTokenForWebSocket() {
        String refreshToken = tokenProvider.generateRefreshToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + refreshToken));
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();
        verify(session).close();
    }

    // ===== Slash command routing =====

    @Test
    void shouldRouteSlashCommandToCommandRouter() {
        WebCommandPort commandRouter = mock(WebCommandPort.class);
        when(commandRouterProvider.getIfAvailable()).thenReturn(commandRouter);
        when(commandRouter.hasCommand("status", "web")).thenReturn(true);
        when(commandRouter.execute(eq("status"), anyList(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(
                        CommandPort.CommandResult.success("Bot status: OK")));

        handler = new WebSocketChatHandler(tokenProvider, webChannelAdapter, objectMapper, commandRouterProvider,
                pointerService);
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-cmd");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"/status\",\"sessionId\":\"chat-cmd\"}");
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(commandRouter).execute(eq("status"), eq(List.of()), anyMap());
        verify(eventPublisher, never()).publishEvent(any(AgentLoop.InboundMessageEvent.class));
    }

    @Test
    void shouldBindSessionForFirstCommandAndRouteResponseToSameChat() {
        WebCommandPort commandRouter = mock(WebCommandPort.class);
        when(commandRouterProvider.getIfAvailable()).thenReturn(commandRouter);
        when(commandRouter.hasCommand("status", "web")).thenReturn(true);
        when(commandRouter.execute(eq("status"), anyList(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(
                        CommandPort.CommandResult.success("Bot status: OK")));

        handler = new WebSocketChatHandler(tokenProvider, webChannelAdapter, objectMapper, commandRouterProvider,
                pointerService);
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-cmd-bind");
        when(session.isOpen()).thenReturn(true);
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.textMessage(any(String.class)))
                .thenReturn(mock(WebSocketMessage.class));

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"/status\",\"sessionId\":\"chat-first\"}");
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(session).textMessage(payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"sessionId\":\"chat-first\""));
        assertTrue(payload.contains("\"type\":\"assistant_chunk\""));
    }

    @Test
    void shouldPassConsistentSessionConversationContextForCommandExecution() {
        WebCommandPort commandRouter = mock(WebCommandPort.class);
        when(commandRouterProvider.getIfAvailable()).thenReturn(commandRouter);
        when(commandRouter.hasCommand("status", "web")).thenReturn(true);
        when(commandRouter.execute(eq("status"), anyList(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(
                        CommandPort.CommandResult.success("Bot status: OK")));

        handler = new WebSocketChatHandler(tokenProvider, webChannelAdapter, objectMapper, commandRouterProvider,
                pointerService);
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-cmd-ctx");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"/status\",\"sessionId\":\"chat-cmd-ctx\"}");
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(commandRouter).execute(eq("status"), eq(List.of()), contextCaptor.capture());

        Map<String, Object> context = contextCaptor.getValue();
        assertEquals("web:chat-cmd-ctx", context.get("sessionId"));
        assertEquals("chat-cmd-ctx", context.get("chatId"));
        assertEquals("chat-cmd-ctx", context.get("sessionChatId"));
        assertEquals("chat-cmd-ctx", context.get("transportChatId"));
        assertEquals("chat-cmd-ctx", context.get("conversationKey"));
        assertEquals("web", context.get("channelType"));
    }

    @Test
    void shouldPassCommandArgumentsCorrectly() {
        WebCommandPort commandRouter = mock(WebCommandPort.class);
        when(commandRouterProvider.getIfAvailable()).thenReturn(commandRouter);
        when(commandRouter.hasCommand("tier", "web")).thenReturn(true);
        when(commandRouter.execute(eq("tier"), anyList(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(
                        CommandPort.CommandResult.success("Tier set to smart")));

        handler = new WebSocketChatHandler(tokenProvider, webChannelAdapter, objectMapper, commandRouterProvider,
                pointerService);
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-args");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"/tier smart force\",\"sessionId\":\"chat-args\"}");
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(commandRouter).execute(eq("tier"), eq(List.of("smart", "force")), anyMap());
    }

    @Test
    void shouldFallThroughWhenCommandNotRegistered() {
        WebCommandPort commandRouter = mock(WebCommandPort.class);
        when(commandRouterProvider.getIfAvailable()).thenReturn(commandRouter);
        when(commandRouter.hasCommand("unknown", "web")).thenReturn(false);

        handler = new WebSocketChatHandler(tokenProvider, webChannelAdapter, objectMapper, commandRouterProvider,
                pointerService);
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-unk");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"/unknown arg\",\"sessionId\":\"chat-unk\"}");
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(eventPublisher).publishEvent(any(AgentLoop.InboundMessageEvent.class));
    }

    @Test
    void shouldHandleCommandExecutionFailure() {
        WebCommandPort commandRouter = mock(WebCommandPort.class);
        when(commandRouterProvider.getIfAvailable()).thenReturn(commandRouter);
        when(commandRouter.hasCommand("broken", "web")).thenReturn(true);
        when(commandRouter.execute(eq("broken"), anyList(), anyMap()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        handler = new WebSocketChatHandler(tokenProvider, webChannelAdapter, objectMapper, commandRouterProvider,
                pointerService);
        String token = tokenProvider.generateAccessToken("admin");

        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-err");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"/broken\",\"sessionId\":\"chat-err\"}");
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(eventPublisher, never()).publishEvent(any(AgentLoop.InboundMessageEvent.class));
    }

    // ===== Image attachment validation =====

    @Test
    void shouldRejectNonImageAttachmentType() {
        String token = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-5");

        String payload = "{\"text\":\"\",\"sessionId\":\"chat-5\",\"attachments\":[{\"type\":\"file\",\"mimeType\":\"application/pdf\",\"dataBase64\":\"AAAA\"}]}";
        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn(payload);
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(eventPublisher, never()).publishEvent(any(AgentLoop.InboundMessageEvent.class));
    }

    @Test
    void shouldRejectInvalidBase64Attachment() {
        String token = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-6");

        String payload = "{\"text\":\"\",\"sessionId\":\"chat-6\",\"attachments\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"dataBase64\":\"!!!invalid!!!\"}]}";
        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn(payload);
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(eventPublisher, never()).publishEvent(any(AgentLoop.InboundMessageEvent.class));
    }

    @Test
    void shouldLimitAttachmentsToMax() {
        String token = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-7");

        String validBase64 = Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3, 4 });
        StringBuilder attachments = new StringBuilder("[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                attachments.append(",");
            }
            attachments.append("{\"type\":\"image\",\"mimeType\":\"image/png\",\"dataBase64\":\"")
                    .append(validBase64).append("\",\"name\":\"img").append(i).append(".png\"}");
        }
        attachments.append("]");

        String payload = "{\"text\":\"Look at these\",\"sessionId\":\"chat-7\",\"attachments\":" + attachments + "}";
        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn(payload);
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultAttachments = (List<Map<String, Object>>) captor.getValue()
                .message().getMetadata().get("attachments");
        assertEquals(6, resultAttachments.size());
    }

    @Test
    void shouldDefaultAttachmentNameWhenMissing() {
        String token = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-8");

        String validBase64 = Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 });
        String payload = "{\"text\":\"image\",\"sessionId\":\"chat-8\",\"attachments\":[{\"type\":\"image\",\"mimeType\":\"image/jpeg\",\"dataBase64\":\""
                + validBase64 + "\"}]}";
        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn(payload);
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultAttachments = (List<Map<String, Object>>) captor.getValue()
                .message().getMetadata().get("attachments");
        assertEquals("image", resultAttachments.get(0).get("name"));
    }

    @Test
    void shouldNotAttachMetadataWhenNoAttachments() {
        String token = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?token=" + token));
        when(session.getId()).thenReturn("session-9");

        WebSocketMessage wsMessage = mock(WebSocketMessage.class);
        when(wsMessage.getPayloadAsText()).thenReturn("{\"text\":\"plain text\",\"sessionId\":\"chat-9\"}");
        when(session.receive()).thenReturn(Flux.just(wsMessage));

        StepVerifier.create(handler.handle(session)).verifyComplete();

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertNull(captor.getValue().message().getMetadata());
    }
}

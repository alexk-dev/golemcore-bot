package me.golemcore.bot.adapter.inbound.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.adapter.inbound.webhook.dto.AgentRequest;
import me.golemcore.bot.adapter.inbound.webhook.dto.WakeRequest;
import me.golemcore.bot.adapter.inbound.webhook.dto.WebhookResponse;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.security.InputSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookControllerTest {

    private static final String TOKEN = "test-token-value";
    private static final String SAMPLE_TEXT = "sample-input";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UserPreferencesService preferencesService;
    private WebhookAuthenticator authenticator;
    private WebhookChannelAdapter channelAdapter;
    private WebhookPayloadTransformer transformer;
    private WebhookDeliveryTracker deliveryTracker;
    private ApplicationEventPublisher eventPublisher;
    private InputSanitizer inputSanitizer;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        authenticator = mock(WebhookAuthenticator.class);
        channelAdapter = mock(WebhookChannelAdapter.class);
        transformer = mock(WebhookPayloadTransformer.class);
        deliveryTracker = mock(WebhookDeliveryTracker.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        inputSanitizer = new InputSanitizer();

        controller = new WebhookController(
                preferencesService, authenticator, channelAdapter,
                transformer, deliveryTracker, eventPublisher, inputSanitizer);

        when(preferencesService.getPreferences()).thenReturn(buildEnabledPrefs());
        when(authenticator.authenticateBearer(any())).thenReturn(true);
    }

    // ==================== /wake ====================

    @Test
    void wakeShouldAcceptValidRequest() {
        WakeRequest request = WakeRequest.builder()
                .text("CI build failed")
                .chatId("webhook:ci")
                .build();

        ResponseEntity<WebhookResponse> response = controller.wake(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("accepted", response.getBody().getStatus());
        assertEquals("webhook:ci", response.getBody().getChatId());

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertTrue(captor.getValue().message().getContent().contains("CI build failed"));
    }

    @Test
    void wakeShouldReturnNotFoundWhenDisabled() {
        UserPreferences prefs = UserPreferences.builder()
                .webhooks(UserPreferences.WebhookConfig.builder()
                        .enabled(false)
                        .build())
                .build();
        when(preferencesService.getPreferences()).thenReturn(prefs);

        WakeRequest request = WakeRequest.builder().text(SAMPLE_TEXT).build();

        ResponseEntity<WebhookResponse> response = controller.wake(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void wakeShouldReturnUnauthorizedOnBadToken() {
        when(authenticator.authenticateBearer(any())).thenReturn(false);

        WakeRequest request = WakeRequest.builder().text(SAMPLE_TEXT).build();

        ResponseEntity<WebhookResponse> response = controller.wake(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void wakeShouldReturnUnauthorizedBeforeMalformedJsonWhenTokenIsBad() {
        when(authenticator.authenticateBearer(any())).thenReturn(false);

        ResponseEntity<WebhookResponse> response = controller.wake("{".getBytes(), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void wakeShouldReturnBadRequestWhenTextMissing() {
        WakeRequest request = WakeRequest.builder().text(null).build();

        ResponseEntity<WebhookResponse> response = controller.wake(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void wakeShouldWrapPayloadWithSafetyMarkers() {
        WakeRequest request = WakeRequest.builder().text("External event").build();

        controller.wake(toJsonBytes(request), new HttpHeaders()).block();

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        String content = captor.getValue().message().getContent();
        assertTrue(content.startsWith("[EXTERNAL WEBHOOK DATA"));
        assertTrue(content.endsWith("[END EXTERNAL DATA]"));
    }

    @Test
    void wakeShouldAttachTraceMetadata() {
        WakeRequest request = WakeRequest.builder().text("External event").build();

        controller.wake(toJsonBytes(request), new HttpHeaders()).block();

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals("INGRESS", captor.getValue().message().getMetadata().get("trace.root.kind"));
        assertEquals("webhook.wake", captor.getValue().message().getMetadata().get("trace.name"));
        assertNotNull(captor.getValue().message().getMetadata().get("trace.id"));
        assertNotNull(captor.getValue().message().getMetadata().get("trace.span.id"));
        assertNull(captor.getValue().message().getMetadata().get("trace.parent.span.id"));
    }

    // ==================== /agent ====================

    @Test
    void agentShouldReturn202WithRunId() {
        AgentRequest request = AgentRequest.builder()
                .message("Summarize issues")
                .name("Daily Digest")
                .model("smart")
                .build();

        ResponseEntity<WebhookResponse> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody().getRunId());
        assertNotNull(response.getBody().getChatId());
        assertEquals("accepted", response.getBody().getStatus());
    }

    @Test
    void agentShouldUseProvidedChatId() {
        AgentRequest request = AgentRequest.builder()
                .message("Test msg")
                .chatId("my-session")
                .build();

        ResponseEntity<WebhookResponse> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals("my-session", response.getBody().getChatId());
    }

    @Test
    void agentShouldReturnBadRequestWhenMessageMissing() {
        AgentRequest request = AgentRequest.builder().message(null).build();

        ResponseEntity<WebhookResponse> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void agentShouldRegisterPendingRun() {
        AgentRequest request = AgentRequest.builder()
                .message("Test msg")
                .callbackUrl("https://example.com/callback")
                .model("coding")
                .build();
        when(deliveryTracker.registerPendingDelivery(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("delivery-1");

        controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        verify(deliveryTracker).validateCallbackUrl("https://example.com/callback");
        verify(deliveryTracker).registerPendingDelivery(anyString(), anyString(),
                eq("https://example.com/callback"), eq("coding"));
        verify(channelAdapter).registerPendingRun(anyString(), anyString(),
                eq("https://example.com/callback"), eq("coding"), eq("delivery-1"));
    }

    @Test
    void agentShouldAttachTraceMetadata() {
        AgentRequest request = AgentRequest.builder()
                .message("Summarize issues")
                .name("Daily Digest")
                .build();

        controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals("INGRESS", captor.getValue().message().getMetadata().get("trace.root.kind"));
        assertEquals("webhook.agent", captor.getValue().message().getMetadata().get("trace.name"));
        assertNotNull(captor.getValue().message().getMetadata().get("trace.id"));
        assertNotNull(captor.getValue().message().getMetadata().get("trace.span.id"));
        assertNull(captor.getValue().message().getMetadata().get("trace.parent.span.id"));
    }

    @Test
    void agentShouldAttachDeliveryMetadataUsingCanonicalKeys() {
        AgentRequest request = AgentRequest.builder()
                .message("Summarize issues")
                .deliver(true)
                .channel("telegram")
                .to("tg-user-42")
                .metadata(Map.of("source", "ci"))
                .build();

        controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        Message message = captor.getValue().message();
        assertEquals("ci", message.getMetadata().get("source"));
        assertEquals(true, message.getMetadata().get(ContextAttributes.WEBHOOK_DELIVER));
        assertEquals("telegram", message.getMetadata().get(ContextAttributes.WEBHOOK_DELIVER_CHANNEL));
        assertEquals("tg-user-42", message.getMetadata().get(ContextAttributes.WEBHOOK_DELIVER_TO));
    }

    @Test
    void agentShouldAcceptSpecialTier() {
        AgentRequest request = AgentRequest.builder()
                .message("Test msg")
                .callbackUrl("https://example.com/callback")
                .model("special4")
                .build();
        when(deliveryTracker.registerPendingDelivery(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("delivery-special");

        ResponseEntity<WebhookResponse> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(channelAdapter).registerPendingRun(anyString(), anyString(),
                eq("https://example.com/callback"), eq("special4"), eq("delivery-special"));
    }

    @Test
    void agentShouldRejectUnknownTier() {
        AgentRequest request = AgentRequest.builder()
                .message("Test msg")
                .model("turbo")
                .build();

        ResponseEntity<WebhookResponse> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("'model' must be a known tier id", response.getBody().getErrorMessage());
        verify(channelAdapter, never()).registerPendingRun(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void agentShouldReturnBadRequestWhenCallbackUrlIsInvalid() {
        AgentRequest request = AgentRequest.builder()
                .message("Test msg")
                .callbackUrl("ftp://example.com/callback")
                .build();
        doThrow(new IllegalArgumentException("callbackUrl must be a valid http(s) URL"))
                .when(deliveryTracker).validateCallbackUrl("ftp://example.com/callback");

        ResponseEntity<WebhookResponse> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("error", response.getBody().getStatus());
        assertEquals("callbackUrl must be a valid http(s) URL", response.getBody().getErrorMessage());

        verify(channelAdapter, never()).registerPendingRun(anyString(), anyString(), anyString(), anyString(),
                anyString());
    }

    // ==================== /{name} (custom mapping) ====================

    @Test
    void customHookShouldResolveMapping() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("github-push")
                .action("wake")
                .messageTemplate("Push to {repository.name}")
                .build();
        when(preferencesService.getPreferences()).thenReturn(buildPrefsWithMapping(mapping));
        when(authenticator.authenticate(any(), any(), any())).thenReturn(true);
        when(transformer.transform(eq("Push to {repository.name}"), any()))
                .thenReturn("Push to myapp");

        byte[] body = "{}".getBytes();
        ResponseEntity<WebhookResponse> response = controller.customHook("github-push", body, new HttpHeaders())
                .block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void customHookShouldReturn404ForUnknownMapping() {
        byte[] body = "{}".getBytes();
        ResponseEntity<WebhookResponse> response = controller.customHook("unknown", body, new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void customHookShouldReturn401OnAuthFailure() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("auth-fail")
                .authMode("hmac")
                .hmacHeader("x-sig")
                .hmacSecret(Secret.of("hmac-key"))
                .build();
        when(preferencesService.getPreferences()).thenReturn(buildPrefsWithMapping(mapping));
        when(authenticator.authenticate(any(), any(), any())).thenReturn(false);

        byte[] body = "{}".getBytes();
        ResponseEntity<WebhookResponse> response = controller.customHook("auth-fail", body, new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void customHookShouldReturn413WhenPayloadTooLarge() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("big")
                .action("wake")
                .messageTemplate("payload-test")
                .build();
        UserPreferences prefs = UserPreferences.builder()
                .webhooks(UserPreferences.WebhookConfig.builder()
                        .enabled(true)
                        .token(Secret.of(TOKEN))
                        .maxPayloadSize(10)
                        .mappings(List.of(mapping))
                        .build())
                .build();
        when(preferencesService.getPreferences()).thenReturn(prefs);
        when(authenticator.authenticate(any(), any(), any())).thenReturn(true);

        byte[] body = "this payload is way too large for the limit".getBytes();
        ResponseEntity<WebhookResponse> response = controller.customHook("big", body, new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatusCode.valueOf(413), response.getStatusCode());
    }

    @Test
    void customHookAgentActionShouldReturn202() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("agent-hook")
                .action("agent")
                .messageTemplate("Process: {event}")
                .model("smart")
                .build();
        when(preferencesService.getPreferences()).thenReturn(buildPrefsWithMapping(mapping));
        when(authenticator.authenticate(any(), any(), any())).thenReturn(true);
        when(transformer.transform(any(), any())).thenReturn("Process: deploy");

        byte[] body = "{\"event\":\"deploy\"}".getBytes();
        ResponseEntity<WebhookResponse> response = controller.customHook("agent-hook", body, new HttpHeaders())
                .block();

        assertNotNull(response);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody().getRunId());
    }

    @Test
    void customHookAgentActionShouldAttachDeliveryMetadataUsingCanonicalKeys() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("agent-hook")
                .action("agent")
                .messageTemplate("Process: {event}")
                .deliver(true)
                .channel("telegram")
                .to("tg-user-42")
                .build();
        when(preferencesService.getPreferences()).thenReturn(buildPrefsWithMapping(mapping));
        when(authenticator.authenticate(any(), any(), any())).thenReturn(true);
        when(transformer.transform(any(), any())).thenReturn("Process: deploy");

        byte[] body = "{\"event\":\"deploy\"}".getBytes();
        controller.customHook("agent-hook", body, new HttpHeaders()).block();

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        Message message = captor.getValue().message();
        assertEquals(true, message.getMetadata().get(ContextAttributes.WEBHOOK_DELIVER));
        assertEquals("telegram", message.getMetadata().get(ContextAttributes.WEBHOOK_DELIVER_CHANNEL));
        assertEquals("tg-user-42", message.getMetadata().get(ContextAttributes.WEBHOOK_DELIVER_TO));
    }

    @Test
    void customHookAgentActionShouldRejectUnknownTier() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("agent-hook")
                .action("agent")
                .messageTemplate("Process: {event}")
                .model("turbo")
                .build();
        when(preferencesService.getPreferences()).thenReturn(buildPrefsWithMapping(mapping));
        when(authenticator.authenticate(any(), any(), any())).thenReturn(true);
        when(transformer.transform(any(), any())).thenReturn("Process: deploy");

        byte[] body = "{\"event\":\"deploy\"}".getBytes();
        ResponseEntity<WebhookResponse> response = controller.customHook("agent-hook", body, new HttpHeaders())
                .block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Webhook mapping model must be a known tier id", response.getBody().getErrorMessage());
    }

    private UserPreferences buildEnabledPrefs() {
        return UserPreferences.builder()
                .webhooks(UserPreferences.WebhookConfig.builder()
                        .enabled(true)
                        .token(Secret.of(TOKEN))
                        .build())
                .build();
    }

    private UserPreferences buildPrefsWithMapping(UserPreferences.HookMapping mapping) {
        return UserPreferences.builder()
                .webhooks(UserPreferences.WebhookConfig.builder()
                        .enabled(true)
                        .token(Secret.of(TOKEN))
                        .mappings(List.of(mapping))
                        .build())
                .build();
    }

    private byte[] toJsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize test payload", e);
        }
    }
}

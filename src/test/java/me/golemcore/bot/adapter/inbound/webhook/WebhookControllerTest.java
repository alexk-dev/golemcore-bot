package me.golemcore.bot.adapter.inbound.webhook;

import me.golemcore.bot.adapter.inbound.webhook.dto.AgentRequest;
import me.golemcore.bot.adapter.inbound.webhook.dto.WakeRequest;
import me.golemcore.bot.adapter.inbound.webhook.dto.WebhookResponse;
import me.golemcore.bot.domain.loop.AgentLoop;
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
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookControllerTest {

    private static final String TOKEN = "test-token-value";
    private static final String SAMPLE_TEXT = "sample-input";

    private UserPreferencesService preferencesService;
    private WebhookAuthenticator authenticator;
    private WebhookChannelAdapter channelAdapter;
    private WebhookPayloadTransformer transformer;
    private ApplicationEventPublisher eventPublisher;
    private InputSanitizer inputSanitizer;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        authenticator = mock(WebhookAuthenticator.class);
        channelAdapter = mock(WebhookChannelAdapter.class);
        transformer = mock(WebhookPayloadTransformer.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        inputSanitizer = new InputSanitizer();

        controller = new WebhookController(
                preferencesService, authenticator, channelAdapter,
                transformer, eventPublisher, inputSanitizer);

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

        ResponseEntity<WebhookResponse> response = controller.wake(request, new HttpHeaders()).block();

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

        ResponseEntity<WebhookResponse> response = controller.wake(request, new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void wakeShouldReturnUnauthorizedOnBadToken() {
        when(authenticator.authenticateBearer(any())).thenReturn(false);

        WakeRequest request = WakeRequest.builder().text(SAMPLE_TEXT).build();

        ResponseEntity<WebhookResponse> response = controller.wake(request, new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void wakeShouldReturnBadRequestWhenTextMissing() {
        WakeRequest request = WakeRequest.builder().text(null).build();

        ResponseEntity<WebhookResponse> response = controller.wake(request, new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void wakeShouldWrapPayloadWithSafetyMarkers() {
        WakeRequest request = WakeRequest.builder().text("External event").build();

        controller.wake(request, new HttpHeaders()).block();

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        String content = captor.getValue().message().getContent();
        assertTrue(content.startsWith("[EXTERNAL WEBHOOK DATA"));
        assertTrue(content.endsWith("[END EXTERNAL DATA]"));
    }

    // ==================== /agent ====================

    @Test
    void agentShouldReturn202WithRunId() {
        AgentRequest request = AgentRequest.builder()
                .message("Summarize issues")
                .name("Daily Digest")
                .model("smart")
                .build();

        ResponseEntity<WebhookResponse> response = controller.agent(request, new HttpHeaders()).block();

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

        ResponseEntity<WebhookResponse> response = controller.agent(request, new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals("my-session", response.getBody().getChatId());
    }

    @Test
    void agentShouldReturnBadRequestWhenMessageMissing() {
        AgentRequest request = AgentRequest.builder().message(null).build();

        ResponseEntity<WebhookResponse> response = controller.agent(request, new HttpHeaders()).block();

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

        controller.agent(request, new HttpHeaders()).block();

        verify(channelAdapter).registerPendingRun(anyString(), anyString(),
                eq("https://example.com/callback"), eq("coding"));
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
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
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
}

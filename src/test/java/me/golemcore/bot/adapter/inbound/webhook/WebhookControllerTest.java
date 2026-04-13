package me.golemcore.bot.adapter.inbound.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.adapter.inbound.webhook.dto.AgentRequest;
import me.golemcore.bot.adapter.inbound.webhook.dto.WakeRequest;
import me.golemcore.bot.adapter.inbound.webhook.dto.WebhookResponse;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.security.InputSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
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
    private WebhookResponseSchemaService responseSchemaService;
    private ApplicationEventPublisher eventPublisher;
    private SessionPort sessionPort;
    private TraceService traceService;
    private InputSanitizer inputSanitizer;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        authenticator = mock(WebhookAuthenticator.class);
        channelAdapter = mock(WebhookChannelAdapter.class);
        transformer = mock(WebhookPayloadTransformer.class);
        deliveryTracker = mock(WebhookDeliveryTracker.class);
        responseSchemaService = mock(WebhookResponseSchemaService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        sessionPort = mock(SessionPort.class);
        traceService = mock(TraceService.class);
        inputSanitizer = new InputSanitizer();

        controller = new WebhookController(
                preferencesService, authenticator, channelAdapter,
                transformer, deliveryTracker, responseSchemaService, eventPublisher,
                sessionPort, traceService, inputSanitizer);

        when(preferencesService.getPreferences()).thenReturn(buildEnabledPrefs());
        when(authenticator.authenticateBearer(any())).thenReturn(true);
        when(sessionPort.get(anyString())).thenReturn(Optional.empty());
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

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(webhookBody(response).getRunId());
        assertNotNull(webhookBody(response).getChatId());
        assertEquals("accepted", webhookBody(response).getStatus());
    }

    @Test
    void agentShouldUseProvidedChatId() {
        AgentRequest request = AgentRequest.builder()
                .message("Test msg")
                .chatId("my-session")
                .build();

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals("my-session", webhookBody(response).getChatId());
    }

    @Test
    void agentShouldReturnBadRequestWhenMessageMissing() {
        AgentRequest request = AgentRequest.builder().message(null).build();

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

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
    void agentShouldReturnSynchronousResponseWhenRequested() {
        AgentRequest request = AgentRequest.builder()
                .message("Answer directly")
                .syncResponse(true)
                .build();
        when(channelAdapter.registerPendingRun(anyString(), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("Direct answer"));

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("completed", webhookBody(response).getStatus());
        assertEquals("Direct answer", webhookBody(response).getResponse());
    }

    @Test
    void agentShouldReturnSchemaPayloadForSynchronousJsonSchema() {
        Map<String, Object> schema = aliceResponseSchema();
        Map<String, Object> payload = Map.of(
                "version", "1.0",
                "response", Map.of("text", "Ready", "tts", "Ready", "end_session", true));
        AgentRequest request = AgentRequest.builder()
                .message("Answer in Alice format")
                .model("coding")
                .syncResponse(true)
                .responseJsonSchema(schema)
                .responseValidationModelTier("smart")
                .build();
        when(channelAdapter.registerPendingRun(anyString(), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("Ready"));
        when(responseSchemaService.renderSchema(schema)).thenReturn("{\"type\":\"object\"}");
        when(responseSchemaService.validateAndRepair(eq("Ready"), eq(schema), eq("smart"), eq("coding"), any()))
                .thenReturn(new WebhookResponseSchemaService.SchemaResult(payload, 1));

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(payload, response.getBody());
        assertEquals(List.of("1"), response.getHeaders().get("X-Golemcore-Schema-Repair-Attempts"));
        verify(responseSchemaService).validateSchemaDefinition(schema);
        ArgumentCaptor<Duration> repairBudgetCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(responseSchemaService).validateAndRepair(eq("Ready"), eq(schema), eq("smart"), eq("coding"),
                repairBudgetCaptor.capture());
        assertTrue(repairBudgetCaptor.getValue().compareTo(Duration.ZERO) > 0);
        assertTrue(repairBudgetCaptor.getValue().compareTo(Duration.ofSeconds(300)) <= 0);

        ArgumentCaptor<AgentLoop.InboundMessageEvent> captor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        Message message = captor.getValue().message();
        assertEquals(schema, message.getMetadata().get(ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA));
        assertEquals("{\"type\":\"object\"}",
                message.getMetadata().get(ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT));
        assertEquals("smart", message.getMetadata().get(ContextAttributes.WEBHOOK_RESPONSE_VALIDATION_MODEL_TIER));
        assertEquals("coding", message.getMetadata().get(ContextAttributes.WEBHOOK_MODEL_TIER));
    }

    @Test
    void agentShouldRecordSynchronousSchemaValidationTraceSpan() {
        Map<String, Object> schema = aliceResponseSchema();
        Map<String, Object> payload = Map.of(
                "version", "1.0",
                "response", Map.of("text", "Ready", "tts", "Ready", "end_session", true));
        AgentSession session = AgentSession.builder()
                .id("webhook:trace-chat")
                .channelType("webhook")
                .chatId("trace-chat")
                .build();
        TraceContext schemaSpan = TraceContext.builder()
                .traceId("trace-1")
                .spanId("schema-span")
                .parentSpanId("root-span")
                .rootKind("INGRESS")
                .build();
        AgentRequest request = AgentRequest.builder()
                .message("Answer in Alice format")
                .chatId("trace-chat")
                .syncResponse(true)
                .responseJsonSchema(schema)
                .responseValidationModelTier("smart")
                .build();
        when(channelAdapter.registerPendingRun(anyString(), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("Ready"));
        when(responseSchemaService.renderSchema(schema)).thenReturn("{\"type\":\"object\"}");
        when(responseSchemaService.validateAndRepair(eq("Ready"), eq(schema), eq("smart"), any(), any()))
                .thenReturn(new WebhookResponseSchemaService.SchemaResult(payload, 0));
        when(sessionPort.get("webhook:trace-chat")).thenReturn(Optional.of(session));
        when(traceService.startSpan(eq(session), any(), eq("webhook.response.schema.validation"),
                eq(TraceSpanKind.INTERNAL), any(), any()))
                .thenReturn(schemaSpan);

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(traceService).appendEvent(eq(session), eq(schemaSpan), eq("schema.validation.started"), any(), any());
        verify(traceService).appendEvent(eq(session), eq(schemaSpan), eq("schema.validation.finished"), any(),
                argThat(attributes -> Boolean.TRUE.equals(attributes.get("success"))
                        && Integer.valueOf(0).equals(attributes.get("repair_attempts"))));
        verify(traceService).finishSpan(eq(session), eq(schemaSpan), eq(TraceStatusCode.OK), isNull(), any());
        verify(sessionPort).save(session);
    }

    @Test
    void agentShouldRejectInvalidResponseSchemaBeforeDispatch() {
        Map<String, Object> schema = Map.of("type", "invalid");
        AgentRequest request = AgentRequest.builder()
                .message("Answer in JSON")
                .syncResponse(true)
                .responseJsonSchema(schema)
                .build();
        doThrow(new WebhookResponseSchemaService.SchemaProcessingException("Invalid responseJsonSchema"))
                .when(responseSchemaService).validateSchemaDefinition(schema);

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid responseJsonSchema", webhookBody(response).getErrorMessage());
        verify(channelAdapter, never()).registerPendingRun(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void agentShouldRejectResponseSchemaWithoutSynchronousResponse() {
        AgentRequest request = AgentRequest.builder()
                .message("Answer in JSON")
                .responseJsonSchema(aliceResponseSchema())
                .build();

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("'responseJsonSchema' requires syncResponse=true", webhookBody(response).getErrorMessage());
        verify(channelAdapter, never()).registerPendingRun(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void agentShouldReturnGatewayTimeoutWhenSchemaRepairBudgetExpires() {
        Map<String, Object> schema = aliceResponseSchema();
        AgentRequest request = AgentRequest.builder()
                .message("Answer in Alice format")
                .syncResponse(true)
                .responseJsonSchema(schema)
                .build();
        when(channelAdapter.registerPendingRun(anyString(), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("Ready"));
        when(responseSchemaService.renderSchema(schema)).thenReturn("{\"type\":\"object\"}");
        when(responseSchemaService.validateAndRepair(eq("Ready"), eq(schema), any(), any(), any()))
                .thenThrow(new WebhookResponseSchemaService.SchemaTimeoutException("Response schema repair timed out"));

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
        assertEquals("Synchronous webhook response timed out", webhookBody(response).getErrorMessage());
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

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

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

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("'model' must be a known tier id", webhookBody(response).getErrorMessage());
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

        ResponseEntity<?> response = controller.agent(toJsonBytes(request), new HttpHeaders()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("error", webhookBody(response).getStatus());
        assertEquals("callbackUrl must be a valid http(s) URL", webhookBody(response).getErrorMessage());

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
        ResponseEntity<?> response = controller.customHook("github-push", body, new HttpHeaders())
                .block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void customHookShouldReturn404ForUnknownMapping() {
        byte[] body = "{}".getBytes();
        ResponseEntity<?> response = controller.customHook("unknown", body, new HttpHeaders()).block();

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
        ResponseEntity<?> response = controller.customHook("auth-fail", body, new HttpHeaders()).block();

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
        ResponseEntity<?> response = controller.customHook("big", body, new HttpHeaders()).block();

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
        ResponseEntity<?> response = controller.customHook("agent-hook", body, new HttpHeaders())
                .block();

        assertNotNull(response);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(webhookBody(response).getRunId());
    }

    @Test
    void customHookAgentActionShouldReturnSynchronousResponseWhenConfigured() {
        UserPreferences.HookMapping mapping = UserPreferences.HookMapping.builder()
                .name("agent-hook")
                .action("agent")
                .messageTemplate("Process: {event}")
                .syncResponse(true)
                .build();
        when(preferencesService.getPreferences()).thenReturn(buildPrefsWithMapping(mapping));
        when(authenticator.authenticate(any(), any(), any())).thenReturn(true);
        when(transformer.transform(any(), any())).thenReturn("Process: deploy");
        when(channelAdapter.registerPendingRun(anyString(), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("Deployment done"));

        byte[] body = "{\"event\":\"deploy\"}".getBytes();
        ResponseEntity<?> response = controller.customHook("agent-hook", body, new HttpHeaders())
                .block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("completed", webhookBody(response).getStatus());
        assertEquals("Deployment done", webhookBody(response).getResponse());
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
        ResponseEntity<?> response = controller.customHook("agent-hook", body, new HttpHeaders())
                .block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Webhook mapping model must be a known tier id", webhookBody(response).getErrorMessage());
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

    private WebhookResponse webhookBody(ResponseEntity<?> response) {
        return (WebhookResponse) response.getBody();
    }

    private Map<String, Object> aliceResponseSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("version", "response"),
                "properties", Map.of(
                        "version", Map.of("const", "1.0"),
                        "response", Map.of("type", "object")));
    }
}

package me.golemcore.bot.adapter.inbound.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookResponseSchemaServiceTest {

    private ModelSelectionService modelSelectionService;
    private LlmPort llmPort;
    private WebhookResponseSchemaService service;

    @BeforeEach
    void setUp() {
        modelSelectionService = mock(ModelSelectionService.class);
        llmPort = mock(LlmPort.class);
        service = new WebhookResponseSchemaService(new ObjectMapper(), modelSelectionService, llmPort);
    }

    @Test
    void shouldAcceptValidJsonWithoutRepair() {
        WebhookResponseSchemaService.SchemaResult result = service.validateAndRepair(
                """
                        {"version":"1.0","response":{"text":"Ready","tts":"Ready","end_session":true}}
                        """,
                aliceResponseSchema(),
                "smart",
                "coding");

        Map<?, ?> payload = assertInstanceOf(Map.class, result.payload());
        assertEquals("1.0", payload.get("version"));
        assertEquals(0, result.repairAttempts());
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldReturnRawResponseWhenNoSchemaConfigured() {
        WebhookResponseSchemaService.SchemaResult result = service.validateAndRepair(
                "plain response",
                null,
                null,
                null);

        assertEquals("plain response", result.payload());
        assertEquals(0, result.repairAttempts());
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldRenderAndValidateSchemaDefinition() {
        service.validateSchemaDefinition(aliceResponseSchema());

        String rendered = service.renderSchema(aliceResponseSchema());

        assertTrue(rendered.contains("\"version\""));
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldRepairInvalidJsonWithConfiguredTier() {
        when(modelSelectionService.resolveForTier("smart"))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-test", "low"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("""
                                {"version":"1.0","response":{"text":"Ready","tts":"Ready","end_session":true}}
                                """)
                        .build()));

        WebhookResponseSchemaService.SchemaResult result = service.validateAndRepair(
                "Ready",
                aliceResponseSchema(),
                "smart",
                "coding");

        Map<?, ?> payload = assertInstanceOf(Map.class, result.payload());
        Map<?, ?> response = assertInstanceOf(Map.class, payload.get("response"));
        assertEquals("Ready", response.get("text"));
        assertEquals(1, result.repairAttempts());

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(requestCaptor.capture());
        assertEquals("openai/gpt-test", requestCaptor.getValue().getModel());
        assertEquals("low", requestCaptor.getValue().getReasoningEffort());
        assertEquals("smart", requestCaptor.getValue().getModelTier());
    }

    @Test
    void shouldRepairSchemaValidationErrors() {
        when(modelSelectionService.resolveForTier("smart"))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-test", "low"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("""
                                {"version":"1.0","response":{"text":"Ready","tts":"Ready","end_session":true}}
                                """)
                        .build()));

        WebhookResponseSchemaService.SchemaResult result = service.validateAndRepair(
                """
                        {"version":"1.0"}
                        """,
                aliceResponseSchema(),
                "smart",
                "coding");

        Map<?, ?> payload = assertInstanceOf(Map.class, result.payload());
        Map<?, ?> response = assertInstanceOf(Map.class, payload.get("response"));
        assertEquals("Ready", response.get("tts"));
        assertEquals(1, result.repairAttempts());
    }

    @Test
    void shouldAcceptJsonFromMarkdownFence() {
        WebhookResponseSchemaService.SchemaResult result = service.validateAndRepair(
                """
                        ```json
                        {"version":"1.0","response":{"text":"Ready","tts":"Ready","end_session":true}}
                        ```
                        """,
                aliceResponseSchema(),
                null,
                null);

        Map<?, ?> payload = assertInstanceOf(Map.class, result.payload());
        assertEquals("1.0", payload.get("version"));
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldAcceptArrayPayloadExtractedFromProse() {
        WebhookResponseSchemaService.SchemaResult result = service.validateAndRepair(
                "Result: [\"ready\"] done",
                Map.of("type", "array", "items", Map.of("type", "string")),
                null,
                null);

        List<?> payload = assertInstanceOf(List.class, result.payload());
        assertEquals("ready", payload.get(0));
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldAcceptDraft202012SchemaAsSerializablePayload() {
        WebhookResponseSchemaService.SchemaResult result = service.validateAndRepair(
                """
                        {"version":"1.0","response":{"text":"Ready","tts":"Ready","end_session":true}}
                        """,
                strictResponseSchema(),
                null,
                null);

        Map<?, ?> payload = assertInstanceOf(Map.class, result.payload());
        Map<?, ?> response = assertInstanceOf(Map.class, payload.get("response"));
        assertEquals("1.0", payload.get("version"));
        assertEquals("Ready", response.get("text"));
        assertEquals(0, result.repairAttempts());
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldFailAfterThreeUnsuccessfulRepairAttempts() {
        when(modelSelectionService.resolveForTier("smart"))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-test", "low"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("""
                                {"version":"wrong"}
                                """)
                        .build()));

        WebhookResponseSchemaService.SchemaProcessingException exception = assertThrows(
                WebhookResponseSchemaService.SchemaProcessingException.class,
                () -> service.validateAndRepair("Ready", aliceResponseSchema(), "smart", null));

        assertTrue(exception.getMessage().contains("repair attempts"));
        verify(llmPort, times(3)).chat(any());
    }

    @Test
    void shouldRejectRepairWhenBudgetIsExhausted() {
        WebhookResponseSchemaService.SchemaTimeoutException exception = assertThrows(
                WebhookResponseSchemaService.SchemaTimeoutException.class,
                () -> service.validateAndRepair("Ready", aliceResponseSchema(), "smart", null, Duration.ZERO));

        assertTrue(exception.getMessage().contains("timed out"));
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldRejectEmptyRepairResponse() {
        when(modelSelectionService.resolveForTier("smart"))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-test", "low"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content(" ")
                        .build()));

        WebhookResponseSchemaService.SchemaProcessingException exception = assertThrows(
                WebhookResponseSchemaService.SchemaProcessingException.class,
                () -> service.validateAndRepair("Ready", aliceResponseSchema(), "smart", null));

        assertTrue(exception.getMessage().contains("empty response"));
    }

    @Test
    void shouldUseFallbackTierWhenValidationTierIsBlank() {
        when(modelSelectionService.resolveForTier("coding"))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-coding", "medium"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("""
                                {"version":"1.0","response":{"text":"Ready","tts":"Ready","end_session":true}}
                                """)
                        .build()));

        service.validateAndRepair("Ready", aliceResponseSchema(), " ", "coding");

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(requestCaptor.capture());
        assertEquals("coding", requestCaptor.getValue().getModelTier());
    }

    @Test
    void shouldUseBalancedTierWhenNoTierIsConfigured() {
        when(modelSelectionService.resolveForTier("balanced"))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-balanced", "low"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("""
                                {"version":"1.0","response":{"text":"Ready","tts":"Ready","end_session":true}}
                                """)
                        .build()));

        service.validateAndRepair("Ready", aliceResponseSchema(), null, null);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(requestCaptor.capture());
        assertEquals("balanced", requestCaptor.getValue().getModelTier());
    }

    @Test
    void shouldReportRepairExecutionFailure() {
        when(modelSelectionService.resolveForTier("smart"))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-test", "low"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("downstream unavailable")));

        WebhookResponseSchemaService.SchemaProcessingException exception = assertThrows(
                WebhookResponseSchemaService.SchemaProcessingException.class,
                () -> service.validateAndRepair("Ready", aliceResponseSchema(), "smart", null));

        assertTrue(exception.getMessage().contains("repair failed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPreserveInterruptedFlagWhenRepairIsInterrupted() throws Exception {
        CompletableFuture<LlmResponse> responseFuture = mock(CompletableFuture.class);
        when(responseFuture.get(anyLong(), any()))
                .thenThrow(new InterruptedException("stop"));
        when(modelSelectionService.resolveForTier("smart"))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-test", "low"));
        when(llmPort.chat(any())).thenReturn(responseFuture);

        WebhookResponseSchemaService.SchemaProcessingException exception = assertThrows(
                WebhookResponseSchemaService.SchemaProcessingException.class,
                () -> service.validateAndRepair("Ready", aliceResponseSchema(), "smart", null));

        assertTrue(exception.getMessage().contains("interrupted"));
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
    }

    @Test
    void shouldRejectInvalidSchemaDefinition() {
        WebhookResponseSchemaService.SchemaProcessingException exception = assertThrows(
                WebhookResponseSchemaService.SchemaProcessingException.class,
                () -> service.validateSchemaDefinition(Map.of("type", "invalid")));

        assertTrue(exception.getMessage().contains("Invalid responseJsonSchema"));
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldRejectSelfReferentialSchemaRendering() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("self", schema);

        WebhookResponseSchemaService.SchemaProcessingException exception = assertThrows(
                WebhookResponseSchemaService.SchemaProcessingException.class,
                () -> service.renderSchema(schema));

        assertTrue(exception.getMessage().contains("Failed to render responseJsonSchema"));
    }

    private Map<String, Object> aliceResponseSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("version", "response"),
                "properties", Map.of(
                        "version", Map.of("const", "1.0"),
                        "response", Map.of(
                                "type", "object",
                                "required", List.of("text", "tts", "end_session"),
                                "properties", Map.of(
                                        "text", Map.of("type", "string"),
                                        "tts", Map.of("type", "string"),
                                        "end_session", Map.of("type", "boolean")))));
    }

    private Map<String, Object> strictResponseSchema() {
        return Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "title", "Response Schema",
                "type", "object",
                "additionalProperties", false,
                "required", List.of("version", "response"),
                "properties", Map.of(
                        "version", Map.of("type", "string", "const", "1.0"),
                        "response", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "required", List.of("text", "tts", "end_session"),
                                "properties", Map.of(
                                        "text", Map.of("type", "string", "description", "Response text"),
                                        "tts", Map.of("type", "string", "description", "Text to speak"),
                                        "end_session", Map.of("type", "boolean",
                                                "description", "Session completion flag")))));
    }
}

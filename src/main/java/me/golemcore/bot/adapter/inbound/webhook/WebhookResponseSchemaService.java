/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.adapter.inbound.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookResponseSchemaService {

    private static final int MAX_SCHEMA_REPAIR_ATTEMPTS = 3;
    private static final int MAX_REPORTED_SCHEMA_ERRORS = 8;
    private static final Duration REPAIR_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEFAULT_VALIDATION_TIER = "balanced";
    private static final SchemaLocation DRAFT_2020_12_META_SCHEMA = SchemaLocation
            .of("https://json-schema.org/draft/2020-12/schema");

    private final ObjectMapper objectMapper;
    private final ModelSelectionService modelSelectionService;
    private final LlmPort llmPort;

    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final JsonSchema schemaDefinitionSchema = schemaFactory.getSchema(DRAFT_2020_12_META_SCHEMA);

    public static boolean hasSchema(Map<String, Object> schema) {
        return schema != null && !schema.isEmpty();
    }

    public void validateSchemaDefinition(Map<String, Object> schema) {
        if (hasSchema(schema)) {
            buildSchema(schema);
        }
    }

    public String renderSchema(Map<String, Object> schema) {
        return writeJson(schema);
    }

    public SchemaResult validateAndRepair(String rawResponse, Map<String, Object> schema,
            String validationModelTier, String fallbackModelTier) {
        return validateAndRepair(rawResponse, schema, validationModelTier, fallbackModelTier,
                REPAIR_TIMEOUT.multipliedBy(MAX_SCHEMA_REPAIR_ATTEMPTS));
    }

    public SchemaResult validateAndRepair(String rawResponse, Map<String, Object> schema,
            String validationModelTier, String fallbackModelTier, Duration repairBudget) {
        if (!hasSchema(schema)) {
            return new SchemaResult(rawResponse, 0);
        }

        JsonSchema jsonSchema = buildSchema(schema);
        String schemaText = writeJson(schema);
        String candidate = rawResponse;
        ValidationAttempt validation = validateCandidate(jsonSchema, candidate);
        if (validation.valid()) {
            return new SchemaResult(toSerializablePayload(validation.payload()), 0);
        }

        String repairTier = resolveRepairTier(validationModelTier, fallbackModelTier);
        long repairStartedNanos = System.nanoTime();
        for (int attempt = 1; attempt <= MAX_SCHEMA_REPAIR_ATTEMPTS; attempt++) {
            Duration attemptTimeout = nextRepairAttemptTimeout(repairBudget, repairStartedNanos);
            candidate = repairCandidate(candidate, schemaText, validation.errors(), repairTier, attemptTimeout);
            validation = validateCandidate(jsonSchema, candidate);
            if (validation.valid()) {
                return new SchemaResult(toSerializablePayload(validation.payload()), attempt);
            }
            log.warn("[Webhook] Response schema repair attempt {} failed: {}", attempt, validation.errors());
        }

        throw new SchemaProcessingException(
                "Synchronous webhook response did not match responseJsonSchema after "
                        + MAX_SCHEMA_REPAIR_ATTEMPTS + " repair attempts: " + String.join("; ", validation.errors()));
    }

    private JsonSchema buildSchema(Map<String, Object> schema) {
        JsonNode schemaNode = objectMapper.valueToTree(schema);
        validateSchemaDefinition(schemaNode);
        try {
            return schemaFactory.getSchema(schemaNode);
        } catch (RuntimeException e) {
            throw new SchemaProcessingException("Invalid responseJsonSchema: " + e.getMessage(), e);
        }
    }

    private void validateSchemaDefinition(JsonNode schemaNode) {
        Set<ValidationMessage> validationMessages = schemaDefinitionSchema.validate(schemaNode);
        if (!validationMessages.isEmpty()) {
            List<String> errors = validationMessages.stream()
                    .map(ValidationMessage::getMessage)
                    .limit(MAX_REPORTED_SCHEMA_ERRORS)
                    .toList();
            throw new SchemaProcessingException("Invalid responseJsonSchema: " + String.join("; ", errors));
        }
    }

    private ValidationAttempt validateCandidate(JsonSchema jsonSchema, String candidate) {
        JsonNode payload = parseCandidate(candidate);
        if (payload == null) {
            return new ValidationAttempt(null, false, List.of("response is not valid JSON"));
        }

        Set<ValidationMessage> validationMessages = jsonSchema.validate(payload);
        if (validationMessages.isEmpty()) {
            return new ValidationAttempt(payload, true, List.of());
        }

        List<String> errors = validationMessages.stream()
                .map(ValidationMessage::getMessage)
                .limit(MAX_REPORTED_SCHEMA_ERRORS)
                .toList();
        return new ValidationAttempt(payload, false, errors);
    }

    private Object toSerializablePayload(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, Object.class);
        } catch (JsonProcessingException e) {
            throw new SchemaProcessingException("Failed to materialize responseJsonSchema payload", e);
        }
    }

    private JsonNode parseCandidate(String candidate) {
        String trimmed = candidate != null ? candidate.trim() : "";
        if (trimmed.isBlank()) {
            return null;
        }
        JsonNode parsed = tryParse(trimmed);
        if (parsed != null) {
            return parsed;
        }
        return tryParse(extractJsonPayload(trimmed));
    }

    private JsonNode tryParse(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String extractJsonPayload(String candidate) {
        String unfenced = stripMarkdownFence(candidate);
        int objectStart = unfenced.indexOf('{');
        int arrayStart = unfenced.indexOf('[');
        int start = resolvePayloadStart(objectStart, arrayStart);
        if (start < 0) {
            return unfenced;
        }

        char opening = unfenced.charAt(start);
        char closing = opening == '{' ? '}' : ']';
        int end = unfenced.lastIndexOf(closing);
        if (end < start) {
            return unfenced;
        }
        return unfenced.substring(start, end + 1);
    }

    private int resolvePayloadStart(int objectStart, int arrayStart) {
        if (objectStart < 0) {
            return arrayStart;
        }
        if (arrayStart < 0) {
            return objectStart;
        }
        return Math.min(objectStart, arrayStart);
    }

    private String stripMarkdownFence(String candidate) {
        String trimmed = candidate.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineBreak = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        if (firstLineBreak < 0 || closingFence <= firstLineBreak) {
            return trimmed;
        }
        return trimmed.substring(firstLineBreak + 1, closingFence).trim();
    }

    private Duration nextRepairAttemptTimeout(Duration repairBudget, long repairStartedNanos) {
        Duration effectiveBudget = repairBudget != null ? repairBudget : REPAIR_TIMEOUT;
        Duration elapsed = Duration.ofNanos(Math.max(0L, System.nanoTime() - repairStartedNanos));
        Duration remaining = effectiveBudget.minus(elapsed);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new SchemaTimeoutException("Response schema repair timed out");
        }
        return remaining.compareTo(REPAIR_TIMEOUT) < 0 ? remaining : REPAIR_TIMEOUT;
    }

    private String repairCandidate(String candidate, String schemaText, List<String> errors, String repairTier,
            Duration attemptTimeout) {
        ModelSelectionService.ModelSelection selection = modelSelectionService.resolveForTier(repairTier);
        LlmRequest request = LlmRequest.builder()
                .model(selection.model())
                .reasoningEffort(selection.reasoning())
                .temperature(0.0)
                .systemPrompt("""
                        You convert assistant output into strict JSON.
                        Return only JSON. Do not include markdown fences or commentary.
                        The JSON must satisfy the provided JSON Schema exactly.
                        """)
                .messages(List.of(Message.builder()
                        .role("user")
                        .content(buildRepairPrompt(candidate, schemaText, errors))
                        .timestamp(Instant.now())
                        .build()))
                .modelTier(repairTier)
                .build();
        try {
            long timeoutMillis = Math.max(1L, attemptTimeout.toMillis());
            LlmResponse response = llmPort.chat(request).get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response == null || response.getContent() == null || response.getContent().isBlank()) {
                throw new SchemaProcessingException("Response schema repair returned an empty response");
            }
            return response.getContent();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SchemaProcessingException("Response schema repair was interrupted", e);
        } catch (TimeoutException e) {
            throw new SchemaTimeoutException("Response schema repair timed out", e);
        } catch (ExecutionException e) {
            throw new SchemaProcessingException("Response schema repair failed: " + e.getMessage(), e);
        }
    }

    private String buildRepairPrompt(String candidate, String schemaText, List<String> errors) {
        return ("JSON Schema:%n%s%n%n"
                + "Validation errors:%n%s%n%n"
                + "Assistant output to reformat:%n%s%n%n"
                + "Return only corrected JSON.")
                .formatted(schemaText, String.join(System.lineSeparator(), errors), candidate != null ? candidate : "");
    }

    private String resolveRepairTier(String validationModelTier, String fallbackModelTier) {
        if (validationModelTier != null && !validationModelTier.isBlank()) {
            return validationModelTier;
        }
        if (fallbackModelTier != null && !fallbackModelTier.isBlank()) {
            return fallbackModelTier;
        }
        return DEFAULT_VALIDATION_TIER;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new SchemaProcessingException("Failed to render responseJsonSchema", e);
        }
    }

    public record SchemaResult(Object payload, int repairAttempts) {
    }

    private record ValidationAttempt(JsonNode payload, boolean valid, List<String> errors) {
    }

    public static class SchemaProcessingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SchemaProcessingException(String message) {
            super(message);
        }

        public SchemaProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class SchemaTimeoutException extends SchemaProcessingException {

        private static final long serialVersionUID = 1L;

        public SchemaTimeoutException(String message) {
            super(message);
        }

        public SchemaTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

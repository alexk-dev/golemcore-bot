package me.golemcore.bot.adapter.outbound.hive;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveInspectionResponse;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.service.HiveSessionStateStore;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class HiveEventBatchPublisher {

    private static final Integer SCHEMA_VERSION = 1;
    private static final String EVENT_TYPE_RUNTIME_EVENT = "runtime_event";
    private static final String EVENT_TYPE_CARD_LIFECYCLE_SIGNAL = "card_lifecycle_signal";
    private static final String EVENT_TYPE_INSPECTION_RESPONSE = "inspection_response";
    private static final String CONTROL_EVENT_TYPE_STOP = "command.stop";
    private static final String CONTROL_EVENT_TYPE_CANCEL = "command.cancel";
    private static final int SUMMARY_MAX_LENGTH = 240;

    private final HiveSessionStateStore hiveSessionStateStore;
    private final HiveApiClient hiveApiClient;
    private final HiveEventOutboxService hiveEventOutboxService;
    private final ObjectMapper objectMapper;

    public void publishCommandAcknowledged(HiveControlCommandEnvelope envelope) {
        if (envelope == null || isBlank(envelope.getThreadId()) || isBlank(envelope.getCommandId())) {
            return;
        }
        HiveEventContext context = new HiveEventContext(
                envelope.getThreadId(),
                envelope.getCardId(),
                envelope.getCommandId(),
                envelope.getRunId(),
                envelope.getGolemId());
        HiveEventPayload event = buildRuntimeEvent(
                context,
                "COMMAND_ACKNOWLEDGED",
                buildAcknowledgementSummary(envelope.getEventType()),
                envelope.getBody(),
                null,
                null,
                null,
                envelope.getCreatedAt());
        publishBatch(List.of(event));
    }

    public void publishInspectionResponse(HiveInspectionResponse response) {
        if (response == null || isBlank(response.threadId()) || isBlank(response.requestId())) {
            return;
        }
        HiveEventPayload event = HiveEventPayload.builder()
                .schemaVersion(SCHEMA_VERSION)
                .eventType(EVENT_TYPE_INSPECTION_RESPONSE)
                .threadId(response.threadId())
                .cardId(response.cardId())
                .commandId(null)
                .requestId(response.requestId())
                .runId(response.runId())
                .golemId(response.golemId())
                .operation(response.operation())
                .success(response.success())
                .errorCode(response.errorCode())
                .errorMessage(response.errorMessage())
                .payload(response.payload())
                .createdAt(response.createdAt() != null ? response.createdAt() : Instant.now())
                .build();
        publishBatch(List.of(event));
    }

    public void publishRuntimeEvents(List<RuntimeEvent> runtimeEvents, Map<String, Object> metadata) {
        if (runtimeEvents == null || runtimeEvents.isEmpty()) {
            return;
        }
        HiveEventContext eventContext = resolveEventContext(null, metadata);
        if (eventContext == null || isBlank(eventContext.threadId())) {
            return;
        }

        List<HiveEventPayload> events = new ArrayList<>();
        for (RuntimeEvent runtimeEvent : runtimeEvents) {
            String mappedType = mapRuntimeEventType(runtimeEvent);
            if (mappedType == null) {
                HiveEventPayload lifecycleSignalOnly = buildLifecycleSignalFromRuntimeEvent(eventContext, runtimeEvent);
                if (lifecycleSignalOnly != null) {
                    events.add(lifecycleSignalOnly);
                }
                continue;
            }
            Map<String, Object> payload = runtimeEvent.payload() != null
                    ? runtimeEvent.payload()
                    : Map.of();
            events.add(buildRuntimeEvent(
                    eventContext,
                    mappedType,
                    buildRuntimeSummary(runtimeEvent),
                    serializeDetails(payload),
                    null,
                    null,
                    null,
                    runtimeEvent.timestamp()));
            HiveEventPayload lifecycleSignal = buildLifecycleSignalFromRuntimeEvent(eventContext, runtimeEvent);
            if (lifecycleSignal != null) {
                events.add(lifecycleSignal);
            }
        }
        publishBatch(events);
    }

    public void publishLifecycleSignal(HiveLifecycleSignalRequest request, Map<String, Object> metadata) {
        if (request == null) {
            return;
        }
        HiveEventContext eventContext = resolveEventContext(null, metadata);
        HiveEventPayload payload = buildLifecycleSignal(eventContext, request);
        if (payload == null) {
            return;
        }
        publishBatch(List.of(payload));
    }

    public void publishThreadMessage(String threadId, String content, Map<String, Object> metadata) {
        if (isBlank(threadId) || isBlank(content)) {
            return;
        }
        HiveEventContext eventContext = resolveEventContext(threadId, metadata);
        if (eventContext == null || isBlank(eventContext.threadId())) {
            return;
        }

        List<HiveEventPayload> events = new ArrayList<>();
        events.add(buildRuntimeEvent(
                eventContext,
                "THREAD_MESSAGE",
                summarize(content),
                content,
                null,
                null,
                null,
                Instant.now()));

        HiveEventPayload usageEvent = buildUsageEvent(eventContext, metadata);
        if (usageEvent != null) {
            events.add(usageEvent);
        }
        publishBatch(events);
    }

    public void publishProgressUpdate(String threadId, ProgressUpdate update) {
        if (isBlank(threadId) || update == null) {
            return;
        }
        HiveEventContext eventContext = resolveEventContext(threadId, update.metadata());
        if (eventContext == null || isBlank(eventContext.threadId())) {
            return;
        }
        HiveEventPayload event = buildRuntimeEvent(
                eventContext,
                "RUN_PROGRESS",
                firstNonBlank(update.text(), "Progress update"),
                serializeDetails(stripHiveMetadata(update.metadata())),
                null,
                null,
                null,
                Instant.now());
        publishBatch(List.of(event));
    }

    private HiveEventPayload buildUsageEvent(HiveEventContext context, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Long inputTokens = extractLong(metadata.get("inputTokens"));
        Long outputTokens = extractLong(metadata.get("outputTokens"));
        Long accumulatedCostMicros = extractLong(metadata.get("accumulatedCostMicros"));
        if (inputTokens == null && outputTokens == null && accumulatedCostMicros == null) {
            return null;
        }
        Map<String, Object> usageDetails = new LinkedHashMap<>();
        if (inputTokens != null) {
            usageDetails.put("inputTokens", inputTokens);
        }
        if (outputTokens != null) {
            usageDetails.put("outputTokens", outputTokens);
        }
        if (accumulatedCostMicros != null) {
            usageDetails.put("accumulatedCostMicros", accumulatedCostMicros);
        }
        return buildRuntimeEvent(
                context,
                "USAGE_REPORTED",
                "Usage updated",
                serializeDetails(usageDetails),
                inputTokens,
                outputTokens,
                accumulatedCostMicros,
                Instant.now());
    }

    private HiveEventPayload buildRuntimeEvent(
            HiveEventContext context,
            String runtimeEventType,
            String summary,
            String details,
            Long inputTokens,
            Long outputTokens,
            Long accumulatedCostMicros,
            Instant createdAt) {
        return HiveEventPayload.builder()
                .schemaVersion(SCHEMA_VERSION)
                .eventType(EVENT_TYPE_RUNTIME_EVENT)
                .runtimeEventType(runtimeEventType)
                .threadId(context.threadId())
                .cardId(context.cardId())
                .commandId(context.commandId())
                .runId(context.runId())
                .golemId(context.golemId())
                .summary(summary)
                .details(details)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .accumulatedCostMicros(accumulatedCostMicros)
                .createdAt(createdAt != null ? createdAt : Instant.now())
                .build();
    }

    private HiveEventPayload buildLifecycleSignal(
            HiveEventContext context,
            HiveLifecycleSignalRequest request) {
        if (context == null || isBlank(context.threadId()) || isBlank(context.cardId()) || request == null
                || isBlank(request.signalType())) {
            return null;
        }
        return HiveEventPayload.builder()
                .schemaVersion(SCHEMA_VERSION)
                .eventType(EVENT_TYPE_CARD_LIFECYCLE_SIGNAL)
                .signalId(generateSignalId())
                .threadId(context.threadId())
                .cardId(context.cardId())
                .commandId(context.commandId())
                .runId(context.runId())
                .golemId(context.golemId())
                .signalType(request.signalType())
                .summary(firstNonBlank(request.summary(), defaultLifecycleSummary(request.signalType())))
                .details(request.details())
                .blockerCode(request.blockerCode())
                .evidenceRefs(request.evidenceRefs())
                .createdAt(request.createdAt() != null ? request.createdAt() : Instant.now())
                .build();
    }

    private HiveEventPayload buildLifecycleSignalFromRuntimeEvent(
            HiveEventContext context,
            RuntimeEvent runtimeEvent) {
        HiveLifecycleSignalRequest request = mapLifecycleSignal(runtimeEvent);
        if (request == null) {
            return null;
        }
        return buildLifecycleSignal(context, request);
    }

    private HiveEventContext resolveEventContext(String fallbackThreadId, Map<String, Object> metadata) {
        String threadId = firstNonBlank(
                readMetadataString(metadata, ContextAttributes.HIVE_THREAD_ID),
                fallbackThreadId);
        if (isBlank(threadId)) {
            return null;
        }
        Optional<HiveSessionState> sessionStateOptional = hiveSessionStateStore.load();
        String golemId = readMetadataString(metadata, ContextAttributes.HIVE_GOLEM_ID);
        if (isBlank(golemId) && sessionStateOptional.isPresent()) {
            golemId = sessionStateOptional.get().getGolemId();
        }
        return new HiveEventContext(
                threadId,
                readMetadataString(metadata, ContextAttributes.HIVE_CARD_ID),
                readMetadataString(metadata, ContextAttributes.HIVE_COMMAND_ID),
                readMetadataString(metadata, ContextAttributes.HIVE_RUN_ID),
                golemId);
    }

    private void publishBatch(List<HiveEventPayload> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        Optional<HiveSessionState> sessionStateOptional = hiveSessionStateStore.load();
        if (sessionStateOptional.isEmpty()) {
            throw new IllegalStateException("Hive session is not available");
        }
        HiveSessionState sessionState = sessionStateOptional.get();
        if (isBlank(sessionState.getServerUrl()) || isBlank(sessionState.getGolemId())
                || isBlank(sessionState.getAccessToken())) {
            throw new IllegalStateException("Hive session is incomplete");
        }
        hiveEventOutboxService.enqueue(sessionState, events);
        hiveEventOutboxService.flush(sessionState, hiveApiClientSender());
    }

    private HiveEventOutboxService.BatchSender hiveApiClientSender() {
        return (serverUrl, golemId, accessToken, events) -> hiveApiClient.publishEventsBatch(
                serverUrl,
                golemId,
                accessToken,
                events);
    }

    private String mapRuntimeEventType(RuntimeEvent runtimeEvent) {
        if (runtimeEvent == null || runtimeEvent.type() == null) {
            return null;
        }
        if (runtimeEvent.type() == RuntimeEventType.TURN_STARTED) {
            return "RUN_STARTED";
        }
        if (runtimeEvent.type() == RuntimeEventType.TURN_FINISHED) {
            String reason = extractRuntimeReason(runtimeEvent.payload());
            return "user_interrupt".equals(reason) ? "RUN_CANCELLED" : "RUN_COMPLETED";
        }
        if (runtimeEvent.type() == RuntimeEventType.TURN_FAILED) {
            return "RUN_FAILED";
        }
        if (runtimeEvent.type() == RuntimeEventType.TURN_INTERRUPT_REQUESTED) {
            return null;
        }
        return "RUN_PROGRESS";
    }

    private HiveLifecycleSignalRequest mapLifecycleSignal(RuntimeEvent runtimeEvent) {
        if (runtimeEvent == null || runtimeEvent.type() == null) {
            return null;
        }
        Map<String, Object> payload = runtimeEvent.payload() != null ? runtimeEvent.payload() : Map.of();
        if (runtimeEvent.type() == RuntimeEventType.TURN_STARTED) {
            return new HiveLifecycleSignalRequest(
                    "WORK_STARTED",
                    "Work started",
                    null,
                    null,
                    List.of(),
                    runtimeEvent.timestamp());
        }
        if (runtimeEvent.type() == RuntimeEventType.TURN_FAILED) {
            return new HiveLifecycleSignalRequest(
                    "WORK_FAILED",
                    buildFailureLifecycleSummary(payload),
                    serializeDetails(payload),
                    null,
                    List.of(),
                    runtimeEvent.timestamp());
        }
        if (runtimeEvent.type() == RuntimeEventType.TURN_FINISHED
                && "user_interrupt".equals(extractRuntimeReason(payload))) {
            return new HiveLifecycleSignalRequest(
                    "WORK_CANCELLED",
                    "Work cancelled",
                    serializeDetails(payload),
                    null,
                    List.of(),
                    runtimeEvent.timestamp());
        }
        return null;
    }

    private String buildRuntimeSummary(RuntimeEvent runtimeEvent) {
        if (runtimeEvent == null || runtimeEvent.type() == null) {
            return "Runtime event";
        }
        Map<String, Object> payload = runtimeEvent.payload() != null ? runtimeEvent.payload() : Map.of();
        return switch (runtimeEvent.type()) {
        case TURN_STARTED -> "Run started";
        case TURN_FINISHED -> {
            String reason = extractRuntimeReason(payload);
            yield reason != null ? "Run finished: " + reason : "Run finished";
        }
        case TURN_FAILED -> {
            String reason = extractRuntimeReason(payload);
            String code = readMetadataString(payload, "code");
            yield firstNonBlank(
                    reason != null ? "Run failed: " + reason : null,
                    code != null ? "Run failed: " + code : null,
                    "Run failed");
        }
        case LLM_STARTED -> "LLM request started";
        case LLM_FINISHED -> "LLM request finished";
        case TOOL_STARTED -> "Tool started: " + firstNonBlank(readMetadataString(payload, "tool"), "tool");
        case TOOL_FINISHED -> "Tool finished: " + firstNonBlank(readMetadataString(payload, "tool"), "tool");
        case RETRY_STARTED -> "Retry started";
        case RETRY_FINISHED -> "Retry finished";
        case COMPACTION_STARTED -> "Compaction started";
        case COMPACTION_FINISHED -> "Compaction finished";
        case TURN_INTERRUPT_REQUESTED -> "Interrupt requested";
        };
    }

    private String buildFailureLifecycleSummary(Map<String, Object> payload) {
        String reason = extractRuntimeReason(payload);
        String code = readMetadataString(payload, "code");
        return firstNonBlank(
                reason != null ? "Work failed: " + reason : null,
                code != null ? "Work failed: " + code : null,
                "Work failed");
    }

    private String buildAcknowledgementSummary(String eventType) {
        if (CONTROL_EVENT_TYPE_STOP.equals(eventType) || CONTROL_EVENT_TYPE_CANCEL.equals(eventType)) {
            return "Stop request acknowledged by bot";
        }
        return "Command acknowledged by bot";
    }

    private String defaultLifecycleSummary(String signalType) {
        if (isBlank(signalType)) {
            return "Lifecycle signal";
        }
        return switch (signalType) {
        case "WORK_STARTED" -> "Work started";
        case "PROGRESS_REPORTED" -> "Progress reported";
        case "BLOCKER_RAISED" -> "Blocker raised";
        case "BLOCKER_CLEARED" -> "Blocker cleared";
        case "REVIEW_REQUESTED" -> "Review requested";
        case "WORK_COMPLETED" -> "Work completed";
        case "WORK_FAILED" -> "Work failed";
        case "WORK_CANCELLED" -> "Work cancelled";
        default -> "Lifecycle signal";
        };
    }

    private String generateSignalId() {
        return "sig_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String extractRuntimeReason(Map<String, Object> payload) {
        return firstNonBlank(readMetadataString(payload, "reason"), readMetadataString(payload, "limit"));
    }

    private Map<String, Object> stripHiveMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> stripped = new LinkedHashMap<>(metadata);
        stripped.remove(ContextAttributes.HIVE_CARD_ID);
        stripped.remove(ContextAttributes.HIVE_THREAD_ID);
        stripped.remove(ContextAttributes.HIVE_COMMAND_ID);
        stripped.remove(ContextAttributes.HIVE_RUN_ID);
        stripped.remove(ContextAttributes.HIVE_GOLEM_ID);
        return stripped;
    }

    private String serializeDetails(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Map<String, Object> safePayload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getValue() != null) {
                safePayload.put(entry.getKey(), entry.getValue());
            }
        }
        if (safePayload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(safePayload);
        } catch (JsonProcessingException exception) {
            return safePayload.toString();
        }
    }

    private Long extractLong(Object value) {
        if (value instanceof Number numberValue) {
            return numberValue.longValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Long.parseLong(stringValue.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String summarize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SUMMARY_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SUMMARY_MAX_LENGTH - 3) + "...";
    }

    private String readMetadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || isBlank(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof String stringValue) {
            String normalized = stringValue.trim();
            return normalized.isEmpty() ? null : normalized;
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record HiveEventContext(
            String threadId,
            String cardId,
            String commandId,
            String runId,
            String golemId) {
    }
}

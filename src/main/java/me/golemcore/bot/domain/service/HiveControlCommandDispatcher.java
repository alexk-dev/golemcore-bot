package me.golemcore.bot.domain.service;

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

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.Message;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HiveControlCommandDispatcher {

    private static final String EVENT_TYPE_COMMAND = "command";
    private static final String EVENT_TYPE_COMMAND_STOP = "command.stop";
    private static final String EVENT_TYPE_COMMAND_CANCEL = "command.cancel";
    private static final String EVENT_TYPE_INSPECTION_REQUEST = "inspection.request";
    private static final String CANCELLED_BY_HIVE_MESSAGE = "Cancelled by Hive control command";

    private final SessionRunCoordinator sessionRunCoordinator;
    private final HiveControlInboxService hiveControlInboxService;
    private final HiveEventBatchPublisher hiveEventBatchPublisher;
    private final HiveInspectionCommandHandler hiveInspectionCommandHandler;
    private final Clock clock;

    public void dispatch(HiveControlCommandEnvelope envelope) {
        String eventType = validateEnvelope(envelope);
        String trackingId = resolveTrackingId(envelope);
        if (isStopEvent(eventType)) {
            sessionRunCoordinator.requestStop("hive", envelope.getThreadId(), envelope.getRunId(),
                    envelope.getCommandId());
            hiveEventBatchPublisher.publishCommandAcknowledged(envelope);
            log.info("[Hive] Requested stop for control command: commandId={}, threadId={}, runId={}, eventType={}",
                    envelope.getCommandId(), envelope.getThreadId(), envelope.getRunId(), eventType);
            return;
        }
        if (EVENT_TYPE_INSPECTION_REQUEST.equals(eventType)) {
            try {
                hiveInspectionCommandHandler.handle(envelope);
                hiveControlInboxService.markProcessed(trackingId);
            } catch (RuntimeException exception) {
                hiveControlInboxService.markFailedIfPending(trackingId, exception);
                throw exception;
            }
            log.info("[Hive] Handled inspection request: requestId={}, threadId={}, operation={}",
                    envelope.getRequestId(),
                    envelope.getThreadId(),
                    envelope.getInspection() != null ? envelope.getInspection().getOperation() : null);
            return;
        }

        Message inbound = buildInboundMessage(envelope);
        sessionRunCoordinator.submit(inbound, () -> hiveControlInboxService.markProcessed(trackingId))
                .whenComplete((ignored, failure) -> finalizeCommandDispatch(envelope, failure));
        hiveEventBatchPublisher.publishCommandAcknowledged(envelope);
        log.info("[Hive] Enqueued control command: commandId={}, threadId={}, runId={}",
                envelope.getCommandId(), envelope.getThreadId(), envelope.getRunId());
    }

    private boolean isStopEvent(String eventType) {
        return EVENT_TYPE_COMMAND_STOP.equals(eventType) || EVENT_TYPE_COMMAND_CANCEL.equals(eventType);
    }

    private void finalizeCommandDispatch(HiveControlCommandEnvelope envelope, Throwable failure) {
        String trackingId = resolveTrackingId(envelope);
        if (trackingId == null || trackingId.isBlank()) {
            return;
        }
        if (failure == null) {
            return;
        }
        Throwable rootCause = unwrap(failure);
        if (isResolvedCancellation(rootCause)) {
            hiveControlInboxService.markProcessed(trackingId);
            return;
        }
        hiveControlInboxService.markFailedIfPending(trackingId, rootCause);
    }

    private Message buildInboundMessage(HiveControlCommandEnvelope envelope) {
        return Message.builder()
                .id("hive:" + envelope.getCommandId())
                .role("user")
                .content(envelope.getBody())
                .channelType("hive")
                .chatId(envelope.getThreadId())
                .senderId("hive")
                .metadata(buildMetadata(envelope))
                .timestamp(envelope.getCreatedAt() != null ? envelope.getCreatedAt() : Instant.now(clock))
                .build();
    }

    private Map<String, Object> buildMetadata(HiveControlCommandEnvelope envelope) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, envelope.getThreadId());
        metadata.put(ContextAttributes.CONVERSATION_KEY, envelope.getThreadId());
        metadata.put(ContextAttributes.HIVE_THREAD_ID, envelope.getThreadId());
        metadata.put(ContextAttributes.HIVE_COMMAND_ID, envelope.getCommandId());
        putIfPresent(metadata, ContextAttributes.HIVE_CARD_ID, envelope.getCardId());
        putIfPresent(metadata, ContextAttributes.HIVE_RUN_ID, envelope.getRunId());
        putIfPresent(metadata, ContextAttributes.HIVE_GOLEM_ID, envelope.getGolemId());
        return metadata;
    }

    private String validateEnvelope(HiveControlCommandEnvelope envelope) {
        requireEnvelope(envelope);
        requireThreadId(envelope);
        String eventType = normalizeEventType(envelope.getEventType());
        requireTrackingId(envelope);
        requireSupportedEventType(eventType);
        requireEventPayload(eventType, envelope);
        return eventType;
    }

    private void requireEnvelope(HiveControlCommandEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Hive control command is required");
        }
    }

    private void requireThreadId(HiveControlCommandEnvelope envelope) {
        if (envelope.getThreadId() == null || envelope.getThreadId().isBlank()) {
            throw new IllegalArgumentException("Hive control command threadId is required");
        }
    }

    private void requireTrackingId(HiveControlCommandEnvelope envelope) {
        String trackingId = resolveTrackingId(envelope);
        if (trackingId == null || trackingId.isBlank()) {
            throw new IllegalArgumentException("Hive control command commandId is required");
        }
    }

    private void requireSupportedEventType(String eventType) {
        if (EVENT_TYPE_COMMAND.equals(eventType)
                || EVENT_TYPE_COMMAND_STOP.equals(eventType)
                || EVENT_TYPE_COMMAND_CANCEL.equals(eventType)
                || EVENT_TYPE_INSPECTION_REQUEST.equals(eventType)) {
            return;
        }
        throw new IllegalArgumentException("Unsupported Hive control command eventType: " + eventType);
    }

    private void requireEventPayload(String eventType, HiveControlCommandEnvelope envelope) {
        if (EVENT_TYPE_INSPECTION_REQUEST.equals(eventType)) {
            requireInspectionRequestId(envelope);
            return;
        }
        requireCommandId(envelope);
        if (EVENT_TYPE_COMMAND.equals(eventType)) {
            requireCommandBody(envelope);
        }
    }

    private void requireCommandId(HiveControlCommandEnvelope envelope) {
        if (envelope.getCommandId() == null || envelope.getCommandId().isBlank()) {
            throw new IllegalArgumentException("Hive control command commandId is required");
        }
    }

    private void requireCommandBody(HiveControlCommandEnvelope envelope) {
        if (envelope.getBody() == null || envelope.getBody().isBlank()) {
            throw new IllegalArgumentException("Hive control command body is required");
        }
    }

    private void requireInspectionRequestId(HiveControlCommandEnvelope envelope) {
        if (envelope.getRequestId() == null || envelope.getRequestId().isBlank()) {
            throw new IllegalArgumentException("Hive inspection requestId is required");
        }
    }

    private String resolveTrackingId(HiveControlCommandEnvelope envelope) {
        String trackingId = hiveControlInboxService.resolveTrackingId(envelope);
        if (trackingId != null && !trackingId.isBlank()) {
            return trackingId;
        }
        if (envelope == null) {
            return null;
        }
        if (envelope.getRequestId() != null && !envelope.getRequestId().isBlank()) {
            return envelope.getRequestId().trim();
        }
        if (envelope.getCommandId() != null && !envelope.getCommandId().isBlank()) {
            return envelope.getCommandId().trim();
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return EVENT_TYPE_COMMAND;
        }
        return eventType.trim().toLowerCase(Locale.ROOT);
    }

    private Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return failure;
    }

    private boolean isResolvedCancellation(Throwable failure) {
        if (failure == null) {
            return false;
        }
        if (failure instanceof InterruptedException || failure instanceof java.util.concurrent.CancellationException) {
            return true;
        }
        return failure instanceof IllegalStateException
                && CANCELLED_BY_HIVE_MESSAGE.equals(failure.getMessage());
    }
}

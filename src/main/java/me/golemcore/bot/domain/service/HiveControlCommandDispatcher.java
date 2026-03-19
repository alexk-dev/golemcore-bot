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
    private static final String CANCELLED_BY_HIVE_MESSAGE = "Cancelled by Hive control command";

    private final SessionRunCoordinator sessionRunCoordinator;
    private final HiveControlInboxService hiveControlInboxService;
    private final HiveEventBatchPublisher hiveEventBatchPublisher;
    private final Clock clock;

    public void dispatch(HiveControlCommandEnvelope envelope) {
        String eventType = validateEnvelope(envelope);
        if (EVENT_TYPE_COMMAND_STOP.equals(eventType) || EVENT_TYPE_COMMAND_CANCEL.equals(eventType)) {
            sessionRunCoordinator.requestStop("hive", envelope.getThreadId(), envelope.getRunId(),
                    envelope.getCommandId());
            hiveEventBatchPublisher.publishCommandAcknowledged(envelope);
            log.info("[Hive] Requested stop for control command: commandId={}, threadId={}, runId={}, eventType={}",
                    envelope.getCommandId(), envelope.getThreadId(), envelope.getRunId(), eventType);
            return;
        }

        Message inbound = buildInboundMessage(envelope);
        sessionRunCoordinator.submit(inbound, () -> hiveControlInboxService.markProcessed(envelope.getCommandId()))
                .whenComplete((ignored, failure) -> finalizeCommandDispatch(envelope, failure));
        hiveEventBatchPublisher.publishCommandAcknowledged(envelope);
        log.info("[Hive] Enqueued control command: commandId={}, threadId={}, runId={}",
                envelope.getCommandId(), envelope.getThreadId(), envelope.getRunId());
    }

    private void finalizeCommandDispatch(HiveControlCommandEnvelope envelope, Throwable failure) {
        if (envelope == null || envelope.getCommandId() == null || envelope.getCommandId().isBlank()) {
            return;
        }
        if (failure == null) {
            return;
        }
        Throwable rootCause = unwrap(failure);
        if (isResolvedCancellation(rootCause)) {
            hiveControlInboxService.markProcessed(envelope.getCommandId());
            return;
        }
        hiveControlInboxService.markFailedIfPending(envelope.getCommandId(), rootCause);
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
        if (envelope == null) {
            throw new IllegalArgumentException("Hive control command is required");
        }
        if (envelope.getThreadId() == null || envelope.getThreadId().isBlank()) {
            throw new IllegalArgumentException("Hive control command threadId is required");
        }
        if (envelope.getCommandId() == null || envelope.getCommandId().isBlank()) {
            throw new IllegalArgumentException("Hive control command commandId is required");
        }
        String eventType = normalizeEventType(envelope.getEventType());
        if (!EVENT_TYPE_COMMAND.equals(eventType)
                && !EVENT_TYPE_COMMAND_STOP.equals(eventType)
                && !EVENT_TYPE_COMMAND_CANCEL.equals(eventType)) {
            throw new IllegalArgumentException("Unsupported Hive control command eventType: " + eventType);
        }
        if (EVENT_TYPE_COMMAND.equals(eventType) && (envelope.getBody() == null || envelope.getBody().isBlank())) {
            throw new IllegalArgumentException("Hive control command body is required");
        }
        return eventType;
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
        return eventType.trim().toLowerCase();
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

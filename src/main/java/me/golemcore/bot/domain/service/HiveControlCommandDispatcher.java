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

    private final SessionRunCoordinator sessionRunCoordinator;
    private final HiveEventBatchPublisher hiveEventBatchPublisher;
    private final Clock clock;

    public void dispatch(HiveControlCommandEnvelope envelope) {
        validateEnvelope(envelope);
        Message inbound = buildInboundMessage(envelope);
        sessionRunCoordinator.enqueue(inbound);
        hiveEventBatchPublisher.publishCommandAcknowledged(envelope);
        log.info("[Hive] Enqueued control command: commandId={}, threadId={}, runId={}",
                envelope.getCommandId(), envelope.getThreadId(), envelope.getRunId());
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

    private void validateEnvelope(HiveControlCommandEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Hive control command is required");
        }
        if (envelope.getThreadId() == null || envelope.getThreadId().isBlank()) {
            throw new IllegalArgumentException("Hive control command threadId is required");
        }
        if (envelope.getCommandId() == null || envelope.getCommandId().isBlank()) {
            throw new IllegalArgumentException("Hive control command commandId is required");
        }
        if (envelope.getBody() == null || envelope.getBody().isBlank()) {
            throw new IllegalArgumentException("Hive control command body is required");
        }
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }
}

package me.golemcore.bot.domain.model;

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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable one-shot delayed action bound to a logical session identity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DelayedSessionAction {

    private String id;
    private String channelType;
    private String conversationKey;
    private String transportChatId;
    private String jobId;
    private DelayedActionKind kind;
    private DelayedActionDeliveryMode deliveryMode;
    @Builder.Default
    private DelayedActionStatus status = DelayedActionStatus.SCHEDULED;
    private Instant runAt;
    private Instant leaseUntil;
    @Builder.Default
    private int attempts = 0;
    @Builder.Default
    private int maxAttempts = 4;
    private String dedupeKey;
    @Builder.Default
    private boolean cancelOnUserActivity = false;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private Instant expiresAt;
    private String lastError;
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();

    public boolean isTerminal() {
        return status == DelayedActionStatus.COMPLETED
                || status == DelayedActionStatus.CANCELLED
                || status == DelayedActionStatus.DEAD_LETTER;
    }
}

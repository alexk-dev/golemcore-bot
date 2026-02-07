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

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a persistent conversation session with a user. Tracks message
 * history, metadata, session state, and timestamps. Sessions are persisted to
 * storage and can be resumed across bot restarts.
 */
@Data
@Builder
public class AgentSession {

    private String id;
    private String channelType;
    private String chatId;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Builder.Default
    private SessionState state = SessionState.ACTIVE;

    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Adds a message to the session history and updates the session timestamp.
     */
    public void addMessage(Message message) {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(message);
        this.updatedAt = Instant.now();
    }

    /**
     * Convenience method to add a simple text message to the session.
     */
    public void addMessage(String role, String content) {
        addMessage(Message.builder()
                .role(role)
                .content(content)
                .timestamp(Instant.now())
                .build());
    }

    /**
     * Session lifecycle states.
     */
    public enum SessionState {
        ACTIVE, PAUSED, TERMINATED
    }
}

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

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing agent conversation sessions including message history
 * and compaction. Sessions are keyed by channel type and chat ID, cached in
 * memory, and persisted to storage. Provides conversation compaction via LLM
 * summarization to manage context window limits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService implements SessionPort {

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private static final String SESSIONS_DIR = "sessions";
    private static final String JSON_EXTENSION = ".json";
    private static final String PATH_SEPARATOR = "/";
    private static final String LEGACY_PATH_SEPARATOR = "\\\\";
    private static final String SESSION_ID_SEPARATOR = ":";

    private final Map<String, AgentSession> sessionCache = new ConcurrentHashMap<>();

    public AgentSession getOrCreate(String channelType, String chatId) {
        String sessionId = buildSessionId(channelType, chatId);

        return sessionCache.computeIfAbsent(sessionId, id -> {
            Optional<AgentSession> existing = load(id);
            if (existing.isPresent()) {
                log.debug("Loaded existing session: {}", id);
                return existing.get();
            }

            AgentSession session = AgentSession.builder()
                    .id(id)
                    .channelType(channelType)
                    .chatId(chatId)
                    .createdAt(clock.instant())
                    .updatedAt(clock.instant())
                    .build();

            log.info("Created new session: {}", id);
            return session;
        });
    }

    public Optional<AgentSession> get(String sessionId) {
        AgentSession cached = sessionCache.get(sessionId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return load(sessionId);
    }

    public void save(AgentSession session) {
        session.setUpdatedAt(clock.instant());
        sessionCache.put(session.getId(), session);

        try {
            String json = objectMapper.writeValueAsString(session);
            storagePort.putText(SESSIONS_DIR, session.getId() + ".json", json).join();
            log.debug("Saved session: {}", session.getId());
        } catch (Exception e) {
            log.error("Failed to save session: {}", session.getId(), e);
        }
    }

    public void delete(String sessionId) {
        sessionCache.remove(sessionId);
        try {
            storagePort.deleteObject(SESSIONS_DIR, sessionId + ".json").join();
            log.info("Deleted session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to delete session: {}", sessionId, e);
        }
    }

    public void clearMessages(String sessionId) {
        AgentSession session = sessionCache.get(sessionId);
        if (session != null) {
            synchronized (session) {
                session.getMessages().clear();
                save(session);
            }
            log.info("Cleared messages for session: {}", sessionId);
        }
    }

    public int compactMessages(String sessionId, int keepLast) {
        AgentSession session = sessionCache.get(sessionId);
        if (session == null) {
            return -1;
        }

        var messages = session.getMessages();
        int total = messages.size();
        if (total <= keepLast) {
            return 0;
        }

        int toRemove = total - keepLast;
        List<Message> kept = Message.flattenToolMessages(
                new ArrayList<>(messages.subList(toRemove, total)));
        messages.clear();
        messages.addAll(kept);
        save(session);
        log.info("Compacted session {}: removed {} messages, kept {}", sessionId, toRemove, kept.size());
        return toRemove;
    }

    public int compactWithSummary(String sessionId, int keepLast, Message summaryMessage) {
        AgentSession session = sessionCache.get(sessionId);
        if (session == null) {
            return -1;
        }

        var messages = session.getMessages();
        int total = messages.size();
        if (total <= keepLast) {
            return 0;
        }

        int toRemove = total - keepLast;
        List<Message> kept = Message.flattenToolMessages(
                new ArrayList<>(messages.subList(toRemove, total)));
        messages.clear();
        messages.add(summaryMessage);
        messages.addAll(kept);
        save(session);
        log.info("Compacted session {} with summary: removed {} messages, kept {} + summary",
                sessionId, toRemove, kept.size());
        return toRemove;
    }

    public List<Message> getMessagesToCompact(String sessionId, int keepLast) {
        AgentSession session = sessionCache.get(sessionId);
        if (session == null) {
            return List.of();
        }

        var messages = session.getMessages();
        int total = messages.size();
        if (total <= keepLast) {
            return List.of();
        }

        int toRemove = total - keepLast;
        return new ArrayList<>(messages.subList(0, toRemove));
    }

    public int getMessageCount(String sessionId) {
        AgentSession session = sessionCache.get(sessionId);
        return session != null ? session.getMessages().size() : 0;
    }

    @Override
    public List<AgentSession> listAll() {
        // Merge cached sessions with any on-disk sessions not yet loaded
        try {
            List<String> files = storagePort.listObjects(SESSIONS_DIR, "").join();
            for (String file : files) {
                if (!file.endsWith(JSON_EXTENSION)) {
                    continue;
                }
                Optional<AgentSession> loaded = loadFromStoredFile(file);
                if (loaded.isPresent()) {
                    AgentSession session = loaded.get();
                    if (session.getId() != null && !session.getId().isBlank()) {
                        sessionCache.putIfAbsent(session.getId(), session);
                    }
                }
            }
        } catch (Exception e) { // NOSONAR
            log.warn("Failed to scan sessions directory: {}", e.getMessage());
        }
        return sessionCache.values().stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Optional<AgentSession> load(String sessionId) {
        try {
            String json = storagePort.getText(SESSIONS_DIR, sessionId + ".json").join();
            if (json != null && !json.isBlank()) {
                AgentSession session = objectMapper.readValue(json, AgentSession.class);
                return Optional.of(session);
            }
        } catch (IOException | RuntimeException e) { // NOSONAR - intentionally catch all for fallback
            log.debug("Session not found or failed to load: {} - {}", sessionId, e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<AgentSession> loadFromStoredFile(String filePath) {
        try {
            String json = storagePort.getText(SESSIONS_DIR, filePath).join();
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            AgentSession session;
            try {
                session = objectMapper.readValue(json, AgentSession.class);
            } catch (IOException e) {
                session = parseSessionFromJsonTree(json);
            }
            enrichLegacySessionFields(session, filePath);
            return Optional.of(session);
        } catch (IOException | RuntimeException e) { // NOSONAR - intentionally catch all for fallback
            log.debug("Failed to parse session file {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    private void enrichLegacySessionFields(AgentSession session, String filePath) {
        if (session.getId() == null || session.getId().isBlank()) {
            String normalized = filePath.replace(LEGACY_PATH_SEPARATOR, PATH_SEPARATOR);
            String withoutExtension = normalized.endsWith(JSON_EXTENSION)
                    ? normalized.substring(0, normalized.length() - JSON_EXTENSION.length())
                    : normalized;
            String derivedId = withoutExtension.replace(PATH_SEPARATOR, SESSION_ID_SEPARATOR);
            session.setId(derivedId);
        }

        String sessionId = session.getId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        int separatorIndex = sessionId.indexOf(SESSION_ID_SEPARATOR);
        if ((session.getChannelType() == null || session.getChannelType().isBlank()) && separatorIndex > 0) {
            session.setChannelType(sessionId.substring(0, separatorIndex));
        }
        if ((session.getChatId() == null || session.getChatId().isBlank())
                && separatorIndex >= 0 && separatorIndex + 1 < sessionId.length()) {
            session.setChatId(sessionId.substring(separatorIndex + 1));
        }
    }

    private String buildSessionId(String channelType, String chatId) {
        return channelType + ":" + chatId;
    }

    private AgentSession parseSessionFromJsonTree(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);

        List<Message> messages = new ArrayList<>();
        JsonNode messagesNode = root.path("messages");
        if (messagesNode.isArray()) {
            for (JsonNode messageNode : messagesNode) {
                Message message = Message.builder()
                        .id(readText(messageNode, "id"))
                        .role(readText(messageNode, "role"))
                        .content(readText(messageNode, "content"))
                        .timestamp(parseInstant(readText(messageNode, "timestamp")))
                        .build();
                messages.add(message);
            }
        }

        AgentSession.AgentSessionBuilder builder = AgentSession.builder()
                .id(readText(root, "id"))
                .channelType(readText(root, "channelType"))
                .chatId(readText(root, "chatId"))
                .messages(messages)
                .createdAt(parseInstant(readText(root, "createdAt")))
                .updatedAt(parseInstant(readText(root, "updatedAt")));

        String state = readText(root, "state");
        if (state != null && !state.isBlank()) {
            try {
                builder.state(AgentSession.SessionState.valueOf(state));
            } catch (IllegalArgumentException e) {
                // keep default state
            }
        }
        return builder.build();
    }

    private String readText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        return text;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

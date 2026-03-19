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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

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
    private final Clock clock;
    private final SessionProtoMapper sessionProtoMapper = new SessionProtoMapper();

    private static final String SESSIONS_DIR = "sessions";
    private static final String PROTO_EXTENSION = ".pb";
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
            byte[] proto = sessionProtoMapper.toProto(session).toByteArray();
            storagePort.putObject(SESSIONS_DIR, session.getId() + PROTO_EXTENSION, proto).join();
            log.debug("Saved session: {}", session.getId());
        } catch (Exception e) {
            log.error("Failed to save session: {}", session.getId(), e);
            throw new IllegalStateException("Failed to save session: " + session.getId(), e);
        }
    }

    public void delete(String sessionId) {
        sessionCache.remove(sessionId);
        try {
            storagePort.deleteObject(SESSIONS_DIR, sessionId + PROTO_EXTENSION).join();
            log.info("Deleted session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to delete session: {}", sessionId, e);
        }
    }

    public void clearMessages(String sessionId) {
        AgentSession session = sessionCache.get(sessionId);
        if (session != null) {
            synchronized (session) {
                session.mutableMessages().clear();
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

        List<Message> messages = session.mutableMessages();
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

        List<Message> messages = session.mutableMessages();
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

        List<Message> messages = session.getMessages();
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
        hydrateCacheFromStorage(path -> true);
        return sessionCache.values().stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public List<AgentSession> listByChannelType(String channelType) {
        if (StringValueSupport.isBlank(channelType)) {
            return List.of();
        }
        String normalizedChannel = channelType.trim();
        hydrateCacheFromStorage(path -> isStoredFileForChannel(path, normalizedChannel));
        return sessionCache.values().stream()
                .filter(java.util.Objects::nonNull)
                .filter(session -> normalizedChannel.equals(session.getChannelType()))
                .toList();
    }

    @Override
    public List<AgentSession> listByChannelTypeAndTransportChatId(String channelType, String transportChatId) {
        if (StringValueSupport.isBlank(channelType)) {
            return List.of();
        }
        if (StringValueSupport.isBlank(transportChatId)) {
            return listByChannelType(channelType);
        }

        String normalizedTransportChatId = transportChatId.trim();
        return listByChannelType(channelType).stream()
                .filter(session -> SessionIdentitySupport.belongsToTransport(session, normalizedTransportChatId))
                .toList();
    }

    private void hydrateCacheFromStorage(Predicate<String> pathFilter) {
        try {
            List<String> files = storagePort.listObjects(SESSIONS_DIR, "").join();
            for (String file : files) {
                if (!pathFilter.test(file)) {
                    continue;
                }
                Optional<AgentSession> loaded = loadFromStoredFile(file);
                if (loaded.isPresent()) {
                    AgentSession session = loaded.get();
                    if (!StringValueSupport.isBlank(session.getId())) {
                        sessionCache.putIfAbsent(session.getId(), session);
                    }
                }
            }
        } catch (Exception e) { // NOSONAR
            log.warn("Failed to scan sessions directory: {}", e.getMessage());
        }
    }

    private boolean isStoredFileForChannel(String path, String channelType) {
        if (StringValueSupport.isBlank(path) || StringValueSupport.isBlank(channelType)) {
            return false;
        }

        String normalizedPath = path;
        int slashIndex = Math.max(normalizedPath.lastIndexOf(PATH_SEPARATOR), normalizedPath.lastIndexOf('\\'));
        String fileName = slashIndex >= 0 ? normalizedPath.substring(slashIndex + 1) : normalizedPath;
        if (!fileName.endsWith(PROTO_EXTENSION)) {
            return false;
        }
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex <= 0) {
            return false;
        }

        String sessionId = fileName.substring(0, extensionIndex);
        int separatorIndex = sessionId.indexOf(SESSION_ID_SEPARATOR);
        if (separatorIndex <= 0) {
            return false;
        }
        return channelType.equals(sessionId.substring(0, separatorIndex));
    }

    private Optional<AgentSession> load(String sessionId) {
        try {
            byte[] bytes = storagePort.getObject(SESSIONS_DIR, sessionId + PROTO_EXTENSION).join();
            if (bytes != null && bytes.length > 0) {
                AgentSession loaded = sessionProtoMapper.fromProto(
                        me.golemcore.bot.proto.session.v1.AgentSessionRecord.parseFrom(bytes));
                enrichSessionFields(loaded, sessionId + PROTO_EXTENSION);
                return Optional.of(loaded);
            }
        } catch (IOException e) {
            log.error("Failed to parse protobuf session {}: {}", sessionId, e.getMessage());
            throw new IllegalStateException("Failed to parse protobuf session: " + sessionId, e);
        } catch (RuntimeException e) { // NOSONAR - intentionally catch all for storage fallback
            log.debug("Failed protobuf load for session {}: {}", sessionId, e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<AgentSession> loadFromStoredFile(String filePath) {
        return filePath.endsWith(PROTO_EXTENSION) ? loadProtoFile(filePath) : Optional.empty();
    }

    private Optional<AgentSession> loadProtoFile(String filePath) {
        try {
            byte[] bytes = storagePort.getObject(SESSIONS_DIR, filePath).join();
            if (bytes == null || bytes.length == 0) {
                return Optional.empty();
            }
            AgentSession session = sessionProtoMapper.fromProto(
                    me.golemcore.bot.proto.session.v1.AgentSessionRecord.parseFrom(bytes));
            enrichSessionFields(session, filePath);
            return Optional.of(session);
        } catch (IOException | RuntimeException e) { // NOSONAR - intentionally catch all for fallback
            log.debug("Failed to parse protobuf session file {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    private void enrichSessionFields(AgentSession session, String filePath) {
        if (session.getId() == null || session.getId().isBlank()) {
            String normalized = filePath.replace(LEGACY_PATH_SEPARATOR, PATH_SEPARATOR);
            String withoutExtension = stripKnownExtension(normalized);
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

    private String stripKnownExtension(String filePath) {
        if (filePath.endsWith(PROTO_EXTENSION)) {
            return filePath.substring(0, filePath.length() - PROTO_EXTENSION.length());
        }
        return filePath;
    }

    private String buildSessionId(String channelType, String chatId) {
        return channelType + ":" + chatId;
    }
}

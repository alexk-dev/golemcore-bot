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
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

/**
 * Compatibility facade for session lifecycle operations.
 */
@Service
@Slf4j
public class SessionService implements SessionPort {

    private final SessionIdFactory sessionIdFactory;
    private final SessionCache sessionCache;
    private final SessionRepository sessionRepository;
    private final SessionCompactionBoundary sessionCompactionBoundary;
    private final SessionModelSettingsInheritancePolicy modelSettingsInheritancePolicy;
    private final SessionDeletionCoordinator sessionDeletionCoordinator;
    private final Clock clock;

    public SessionService(SessionIdFactory sessionIdFactory, SessionCache sessionCache,
            SessionRepository sessionRepository, SessionCompactionBoundary sessionCompactionBoundary,
            SessionModelSettingsInheritancePolicy modelSettingsInheritancePolicy,
            SessionDeletionCoordinator sessionDeletionCoordinator, Clock clock) {
        this.sessionIdFactory = sessionIdFactory;
        this.sessionCache = sessionCache;
        this.sessionRepository = sessionRepository;
        this.sessionCompactionBoundary = sessionCompactionBoundary;
        this.modelSettingsInheritancePolicy = modelSettingsInheritancePolicy;
        this.sessionDeletionCoordinator = sessionDeletionCoordinator;
        this.clock = clock;
    }

    public AgentSession getOrCreate(String channelType, String chatId) {
        String sessionId = sessionIdFactory.buildSessionId(channelType, chatId);

        return sessionCache.computeIfAbsent(sessionId, id -> {
            Optional<AgentSession> existing = sessionRepository.load(id);
            if (existing.isPresent()) {
                log.debug("Loaded existing session: {}", id);
                return existing.get();
            }

            AgentSession session = AgentSession.builder().id(id).channelType(channelType).chatId(chatId)
                    .createdAt(clock.instant()).updatedAt(clock.instant()).build();
            modelSettingsInheritancePolicy.inheritModelSettings(session);

            log.info("Created new session: {}", id);
            return session;
        });
    }

    public Optional<AgentSession> get(String sessionId) {
        return sessionCache.get(sessionId).or(() -> sessionRepository.load(sessionId));
    }

    public void save(AgentSession session) {
        session.setUpdatedAt(clock.instant());
        sessionCache.put(session);
        sessionRepository.save(session);
    }

    public void delete(String sessionId) {
        sessionDeletionCoordinator.delete(sessionId);
    }

    public void clearMessages(String sessionId) {
        sessionCache.get(sessionId).ifPresent(session -> {
            synchronized (session) {
                session.mutableMessages().clear();
                save(session);
            }
            log.info("Cleared messages for session: {}", sessionId);
        });
    }

    public int compactMessages(String sessionId, int keepLast) {
        return sessionCompactionBoundary.compactMessages(sessionCache.get(sessionId).orElse(null), keepLast,
                this::save);
    }

    public int compactWithSummary(String sessionId, int keepLast, Message summaryMessage) {
        return sessionCompactionBoundary.compactWithSummary(sessionCache.get(sessionId).orElse(null), keepLast,
                summaryMessage, this::save);
    }

    public List<Message> getMessagesToCompact(String sessionId, int keepLast) {
        return sessionCompactionBoundary.getMessagesToCompact(sessionCache.get(sessionId).orElse(null), keepLast);
    }

    public int getMessageCount(String sessionId) {
        return sessionCompactionBoundary.getMessageCount(sessionCache.get(sessionId).orElse(null));
    }

    @Override
    public List<AgentSession> listAll() {
        hydrateCacheFromStorage(path -> true);
        return sessionCache.values().stream().filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<AgentSession> listByChannelType(String channelType) {
        if (StringValueSupport.isBlank(channelType)) {
            return List.of();
        }
        String normalizedChannel = channelType.trim();
        hydrateCacheFromStorage(path -> sessionIdFactory.isStoredFileForChannel(path, normalizedChannel));
        return sessionCache.values().stream().filter(java.util.Objects::nonNull)
                .filter(session -> normalizedChannel.equals(session.getChannelType())).toList();
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

    @Override
    public int cleanupExpiredSessions(Instant cutoff, Predicate<AgentSession> shouldRetain) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff is required");
        }
        if (shouldRetain == null) {
            throw new IllegalArgumentException("shouldRetain predicate is required");
        }
        hydrateCacheFromStorage(path -> true);
        return sessionDeletionCoordinator.cleanupExpiredSessions(cutoff, shouldRetain);
    }

    private void hydrateCacheFromStorage(Predicate<String> pathFilter) {
        sessionRepository.loadStoredSessions(pathFilter).forEach(sessionCache::putIfAbsent);
    }
}

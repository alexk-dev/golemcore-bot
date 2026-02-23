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

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Telegram-specific session switch service based on active pointer registry.
 */
@Service
@RequiredArgsConstructor
public class TelegramSessionService {

    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final SessionPort sessionPort;
    private final ActiveSessionPointerService pointerService;

    public String resolveActiveConversationKey(String transportChatId) {
        validateTransportChatId(transportChatId);
        String pointerKey = pointerService.buildTelegramPointerKey(transportChatId);
        Optional<String> activeConversation = pointerService.getActiveConversationKey(pointerKey);
        if (activeConversation.isPresent()) {
            String candidate = activeConversation.get();
            if (isConversationResolvableForTransport(transportChatId, candidate)) {
                bindSessionToTransport(transportChatId, candidate);
                return candidate;
            }
        }

        String fallbackConversation = findLatestConversationKey(transportChatId)
                .orElseGet(this::generateConversationKey);
        pointerService.setActiveConversationKey(pointerKey, fallbackConversation);
        bindSessionToTransport(transportChatId, fallbackConversation);
        return fallbackConversation;
    }

    public String createAndActivateConversation(String transportChatId) {
        validateTransportChatId(transportChatId);
        String conversationKey = UUID.randomUUID().toString();
        activateConversation(transportChatId, conversationKey);
        return conversationKey;
    }

    public void activateConversation(String transportChatId, String conversationKey) {
        validateTransportChatId(transportChatId);
        String normalizedConversationKey = normalizeConversationKeyForActivation(conversationKey);
        String pointerKey = pointerService.buildTelegramPointerKey(transportChatId);
        pointerService.setActiveConversationKey(pointerKey, normalizedConversationKey);
        bindSessionToTransport(transportChatId, normalizedConversationKey);
    }

    public List<AgentSession> listRecentSessions(String transportChatId, int limit) {
        validateTransportChatId(transportChatId);
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return sessionPort.listAll().stream()
                .filter(session -> CHANNEL_TELEGRAM.equals(session.getChannelType()))
                .filter(session -> SessionIdentitySupport.belongsToTransport(session, transportChatId))
                .sorted(sessionComparator())
                .limit(normalizedLimit)
                .toList();
    }

    private Optional<String> findLatestConversationKey(String transportChatId) {
        return listRecentSessions(transportChatId, DEFAULT_LIMIT).stream()
                .map(SessionIdentitySupport::resolveConversationKey)
                .filter(value -> !isBlank(value))
                .findFirst();
    }

    private void bindSessionToTransport(String transportChatId, String conversationKey) {
        AgentSession session = sessionPort.getOrCreate(CHANNEL_TELEGRAM, conversationKey);
        SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        sessionPort.save(session);
    }

    private Comparator<AgentSession> sessionComparator() {
        return Comparator.comparing(
                (AgentSession session) -> session.getUpdatedAt() != null ? session.getUpdatedAt() : session.getCreatedAt(),
                Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed();
    }

    private void validateTransportChatId(String transportChatId) {
        if (isBlank(transportChatId)) {
            throw new IllegalArgumentException("transportChatId must not be blank");
        }
    }

    private String normalizeConversationKeyForActivation(String conversationKey) {
        if (ConversationKeyValidator.isStrictConversationKey(conversationKey)) {
            return ConversationKeyValidator.normalizeStrictOrThrow(conversationKey);
        }

        String legacyCandidate = ConversationKeyValidator.normalizeLegacyCompatibleOrThrow(conversationKey);
        if (sessionPort.get(CHANNEL_TELEGRAM + ":" + legacyCandidate).isPresent()) {
            return legacyCandidate;
        }

        throw new IllegalArgumentException("conversationKey must match ^[a-zA-Z0-9_-]{8,64}$");
    }

    private boolean isConversationResolvableForTransport(String transportChatId, String conversationKey) {
        if (!ConversationKeyValidator.isLegacyCompatibleConversationKey(conversationKey)) {
            return false;
        }
        Optional<AgentSession> sessionOptional = sessionPort.get(CHANNEL_TELEGRAM + ":" + conversationKey.trim());
        if (sessionOptional.isEmpty()) {
            return false;
        }

        AgentSession session = sessionOptional.get();
        String explicitTransportChatId = SessionIdentitySupport.readMetadataString(
                session,
                ContextAttributes.TRANSPORT_CHAT_ID);
        if (isBlank(explicitTransportChatId)) {
            // Legacy sessions may not have explicit transport binding yet.
            return true;
        }
        return transportChatId.equals(explicitTransportChatId);
    }

    private String generateConversationKey() {
        return UUID.randomUUID().toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

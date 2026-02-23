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
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Telegram-specific session switch service based on active pointer registry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
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
            log.info(
                    "[SessionMetrics] metric=sessions.active.pointer.stale.count channel=telegram pointerKey={} staleConversation={}",
                    pointerKey, candidate);
        } else {
            log.info("[SessionMetrics] metric=sessions.active.pointer.miss.count channel=telegram pointerKey={}",
                    pointerKey);
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
        log.info("[SessionMetrics] metric=sessions.create.count channel=telegram conversationKey={}", conversationKey);
        return conversationKey;
    }

    public void activateConversation(String transportChatId, String conversationKey) {
        validateTransportChatId(transportChatId);
        String normalizedConversationKey = ConversationKeyValidator.normalizeForActivationOrThrow(
                conversationKey,
                candidate -> sessionPort.get(CHANNEL_TELEGRAM + ":" + candidate).isPresent());
        String pointerKey = pointerService.buildTelegramPointerKey(transportChatId);
        pointerService.setActiveConversationKey(pointerKey, normalizedConversationKey);
        log.info(
                "[SessionMetrics] metric=sessions.switch.count channel=telegram pointerKey={} conversationKey={}",
                pointerKey, normalizedConversationKey);
        bindSessionToTransport(transportChatId, normalizedConversationKey);
    }

    public List<AgentSession> listRecentSessions(String transportChatId, int limit) {
        validateTransportChatId(transportChatId);
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return sessionPort.listByChannelTypeAndTransportChatId(CHANNEL_TELEGRAM, transportChatId).stream()
                .filter(session -> CHANNEL_TELEGRAM.equals(session.getChannelType()))
                .filter(session -> SessionIdentitySupport.belongsToTransport(session, transportChatId))
                .sorted(ConversationKeyValidator.byRecentActivity())
                .limit(normalizedLimit)
                .toList();
    }

    private Optional<String> findLatestConversationKey(String transportChatId) {
        return listRecentSessions(transportChatId, DEFAULT_LIMIT).stream()
                .map(SessionIdentitySupport::resolveConversationKey)
                .filter(value -> !StringValueSupport.isBlank(value))
                .findFirst();
    }

    private void bindSessionToTransport(String transportChatId, String conversationKey) {
        AgentSession session = sessionPort.getOrCreate(CHANNEL_TELEGRAM, conversationKey);
        SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        sessionPort.save(session);
    }

    private void validateTransportChatId(String transportChatId) {
        if (StringValueSupport.isBlank(transportChatId)) {
            throw new IllegalArgumentException("transportChatId must not be blank");
        }
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
        if (StringValueSupport.isBlank(explicitTransportChatId)) {
            // Legacy sessions may not have explicit transport binding yet.
            return true;
        }
        return transportChatId.equals(explicitTransportChatId);
    }

    private String generateConversationKey() {
        return UUID.randomUUID().toString();
    }
}

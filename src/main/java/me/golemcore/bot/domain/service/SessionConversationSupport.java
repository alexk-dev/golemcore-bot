package me.golemcore.bot.domain.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.port.outbound.SessionPort;

public final class SessionConversationSupport {

    private static final String CHANNEL_TELEGRAM = "telegram";

    private SessionConversationSupport() {
    }

    public static String resolveOrCreateConversationKey(
            SessionPort sessionPort,
            String channel,
            String transportChatId,
            String preferredConversation) {
        if (!StringValueSupport.isBlank(preferredConversation)
                && isConversationResolvable(sessionPort, channel, transportChatId, preferredConversation)) {
            return preferredConversation;
        }

        String fallbackConversation = findLatestConversationKey(sessionPort, channel, transportChatId,
                preferredConversation)
                .orElseGet(SessionConversationSupport::generateConversationKey);

        if (!isConversationResolvable(sessionPort, channel, transportChatId, fallbackConversation)) {
            ensureSessionExists(sessionPort, channel, transportChatId, fallbackConversation);
        }
        return fallbackConversation;
    }

    public static boolean isConversationResolvable(
            SessionPort sessionPort,
            String channel,
            String transportChatId,
            String conversationKey) {
        if (StringValueSupport.isBlank(channel) || StringValueSupport.isBlank(conversationKey)) {
            return false;
        }

        Optional<AgentSession> session = sessionPort.get(channel + ":" + conversationKey);
        if (session.isEmpty()) {
            return false;
        }

        if (!CHANNEL_TELEGRAM.equals(channel) || StringValueSupport.isBlank(transportChatId)) {
            return true;
        }
        return transportChatId.equals(SessionIdentitySupport.resolveTransportChatId(session.get()));
    }

    public static List<AgentSession> listRecentSessionsByOwner(
            SessionPort sessionPort,
            String channel,
            String transportChatId) {
        if (!CHANNEL_TELEGRAM.equals(channel) || StringValueSupport.isBlank(transportChatId)) {
            return sessionPort.listByChannelType(channel);
        }
        return sessionPort.listByChannelTypeAndTransportChatId(channel, transportChatId);
    }

    private static Optional<String> findLatestConversationKey(
            SessionPort sessionPort,
            String channel,
            String transportChatId,
            String excludedConversation) {
        return listRecentSessionsByOwner(sessionPort, channel, transportChatId).stream()
                .sorted(ConversationKeyValidator.byRecentActivity())
                .map(SessionIdentitySupport::resolveConversationKey)
                .filter(value -> !StringValueSupport.isBlank(value) && !value.equals(excludedConversation))
                .findFirst();
    }

    private static void ensureSessionExists(
            SessionPort sessionPort,
            String channel,
            String transportChatId,
            String conversationKey) {
        AgentSession session = sessionPort.getOrCreate(channel, conversationKey);
        if (CHANNEL_TELEGRAM.equals(channel)) {
            SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        }
        sessionPort.save(session);
    }

    private static String generateConversationKey() {
        return UUID.randomUUID().toString();
    }
}

package me.golemcore.bot.domain.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.view.ActiveSessionSelectionView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionSelectionService {

    private static final String CHANNEL_WEB = "web";
    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final int MAX_RECENT_LIMIT = 20;
    private static final String POINTER_SOURCE = "pointer";
    private static final String DEFAULT_SOURCE = "default";
    private static final String REPAIRED_SOURCE = "repaired";

    private final SessionPort sessionPort;
    private final ActiveSessionPointerService pointerService;

    public List<SessionSummaryView> listRecentSessions(
            String channel,
            String clientInstanceId,
            String transportChatId,
            String principalName,
            int limit) {
        String normalizedChannel = defaultIfBlank(channel, CHANNEL_WEB);
        String pointerKey = resolvePointerKey(normalizedChannel, clientInstanceId, transportChatId, principalName);
        String activeConversation = pointerService.getActiveConversationKey(pointerKey).orElse(null);
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_RECENT_LIMIT));
        return SessionConversationSupport.listRecentSessionsByOwner(
                sessionPort,
                normalizedChannel,
                resolveEffectiveTransportChatId(normalizedChannel, clientInstanceId, transportChatId)).stream()
                .sorted(ConversationKeyValidator.byRecentActivity())
                .map(session -> SessionPresentationSupport.toSummary(session,
                        isActiveSession(session, activeConversation)))
                .limit(normalizedLimit)
                .toList();
    }

    public ActiveSessionSelectionView getActiveSession(
            String channel,
            String clientInstanceId,
            String transportChatId,
            String principalName) {
        String normalizedChannel = defaultIfBlank(channel, CHANNEL_WEB);
        String pointerKey = resolvePointerKey(normalizedChannel, clientInstanceId, transportChatId, principalName);
        String effectiveTransportChatId = resolveEffectiveTransportChatId(
                normalizedChannel,
                clientInstanceId,
                transportChatId);
        Optional<String> activeConversation = pointerService.getActiveConversationKey(pointerKey);

        if (activeConversation.isPresent()) {
            String currentConversation = activeConversation.get();
            if (SessionConversationSupport.isConversationResolvable(
                    sessionPort,
                    normalizedChannel,
                    effectiveTransportChatId,
                    currentConversation)) {
                return toActiveSessionSelection(
                        normalizedChannel,
                        clientInstanceId,
                        effectiveTransportChatId,
                        currentConversation,
                        POINTER_SOURCE);
            }

            log.info(
                    "[SessionMetrics] metric=sessions.active.pointer.stale.count channel={} transportHash={} staleConversation={}",
                    normalizedChannel,
                    TelemetrySupport.shortHash(resolveTelemetryTransport(
                            normalizedChannel,
                            clientInstanceId,
                            transportChatId)),
                    currentConversation);
            String repairedConversation = SessionConversationSupport.resolveOrCreateConversationKey(
                    sessionPort,
                    normalizedChannel,
                    effectiveTransportChatId,
                    currentConversation);
            pointerService.setActiveConversationKey(pointerKey, repairedConversation);
            ensureTelegramSessionBinding(normalizedChannel, effectiveTransportChatId, repairedConversation);
            return toActiveSessionSelection(
                    normalizedChannel,
                    clientInstanceId,
                    effectiveTransportChatId,
                    repairedConversation,
                    REPAIRED_SOURCE);
        }

        log.info("[SessionMetrics] metric=sessions.active.pointer.miss.count channel={} transportHash={}",
                normalizedChannel,
                TelemetrySupport
                        .shortHash(resolveTelemetryTransport(normalizedChannel, clientInstanceId, transportChatId)));
        String fallbackConversation = SessionConversationSupport.resolveOrCreateConversationKey(
                sessionPort,
                normalizedChannel,
                effectiveTransportChatId,
                null);
        pointerService.setActiveConversationKey(pointerKey, fallbackConversation);
        ensureTelegramSessionBinding(normalizedChannel, effectiveTransportChatId, fallbackConversation);
        return toActiveSessionSelection(
                normalizedChannel,
                clientInstanceId,
                effectiveTransportChatId,
                fallbackConversation,
                DEFAULT_SOURCE);
    }

    public ActiveSessionSelectionView setActiveSession(
            String channel,
            String clientInstanceId,
            String transportChatId,
            String principalName,
            String conversationKey) {
        if (StringValueSupport.isBlank(conversationKey)) {
            throw new IllegalArgumentException("conversationKey is required");
        }

        String normalizedChannel = defaultIfBlank(channel, CHANNEL_WEB);
        String normalizedConversationKey = normalizeConversationKeyForActivationOrThrow(normalizedChannel,
                conversationKey);
        String pointerKey = resolvePointerKey(normalizedChannel, clientInstanceId, transportChatId, principalName);
        String effectiveTransportChatId = resolveEffectiveTransportChatId(
                normalizedChannel,
                clientInstanceId,
                transportChatId);
        pointerService.setActiveConversationKey(pointerKey, normalizedConversationKey);
        log.info("[SessionMetrics] metric=sessions.switch.count channel={} transportHash={} conversationKey={}",
                normalizedChannel,
                TelemetrySupport.shortHash(resolveTelemetryTransport(
                        normalizedChannel,
                        clientInstanceId,
                        transportChatId)),
                normalizedConversationKey);
        ensureTelegramSessionBinding(normalizedChannel, effectiveTransportChatId, normalizedConversationKey);
        return toActiveSessionSelection(
                normalizedChannel,
                clientInstanceId,
                effectiveTransportChatId,
                normalizedConversationKey,
                POINTER_SOURCE);
    }

    public SessionSummaryView createSession(
            String channel,
            String clientInstanceId,
            String principalName,
            String requestedConversationKey,
            Boolean activate) {
        String normalizedChannel = defaultIfBlank(channel, CHANNEL_WEB);
        if (!CHANNEL_WEB.equals(normalizedChannel)) {
            throw new IllegalArgumentException("Only web channel session creation is supported");
        }

        String conversationKey = StringValueSupport.isBlank(requestedConversationKey)
                ? generateConversationKey()
                : normalizeConversationKeyForCreation(requestedConversationKey);

        AgentSession session = sessionPort.getOrCreate(normalizedChannel, conversationKey);
        sessionPort.save(session);
        log.info("[SessionMetrics] metric=sessions.create.count channel={} transportHash={} conversationKey={}",
                normalizedChannel,
                TelemetrySupport.shortHash(resolveTelemetryTransport(normalizedChannel, clientInstanceId, null)),
                conversationKey);

        boolean shouldActivate = activate == null || activate;
        if (shouldActivate) {
            String pointerKey = resolvePointerKey(normalizedChannel, clientInstanceId, null, principalName);
            pointerService.setActiveConversationKey(pointerKey, conversationKey);
        }

        return SessionPresentationSupport.toSummary(session, shouldActivate);
    }

    private boolean isActiveSession(AgentSession session, String activeConversation) {
        if (StringValueSupport.isBlank(activeConversation)) {
            return false;
        }
        return activeConversation.equals(SessionIdentitySupport.resolveConversationKey(session));
    }

    private ActiveSessionSelectionView toActiveSessionSelection(
            String channel,
            String clientInstanceId,
            String transportChatId,
            String conversationKey,
            String source) {
        return ActiveSessionSelectionView.builder()
                .channelType(channel)
                .clientInstanceId(clientInstanceId)
                .transportChatId(CHANNEL_WEB.equals(channel) ? clientInstanceId : transportChatId)
                .conversationKey(conversationKey)
                .sessionId(channel + ":" + conversationKey)
                .source(source)
                .build();
    }

    private String resolvePointerKey(
            String channel,
            String clientInstanceId,
            String transportChatId,
            String principalName) {
        if (CHANNEL_WEB.equals(channel)) {
            if (StringValueSupport.isBlank(clientInstanceId)) {
                throw new IllegalArgumentException("clientInstanceId is required for web");
            }
            return pointerService.buildWebPointerKey(resolvePrincipalName(principalName), clientInstanceId);
        }
        if (CHANNEL_TELEGRAM.equals(channel)) {
            if (StringValueSupport.isBlank(transportChatId)) {
                throw new IllegalArgumentException("transportChatId is required for telegram");
            }
            return pointerService.buildTelegramPointerKey(transportChatId);
        }
        throw new IllegalArgumentException("Unsupported channel: " + channel);
    }

    private String resolvePrincipalName(String principalName) {
        if (StringValueSupport.isBlank(principalName)) {
            throw new SecurityException("Unauthorized");
        }
        return principalName.trim();
    }

    private String normalizeConversationKeyForCreation(String value) {
        return ConversationKeyValidator.normalizeStrictOrThrow(value);
    }

    private String normalizeConversationKeyForActivationOrThrow(String channel, String value) {
        return ConversationKeyValidator.normalizeForActivationOrThrow(
                value,
                candidate -> sessionPort.get(channel + ":" + candidate).isPresent());
    }

    private String defaultIfBlank(String value, String defaultValue) {
        if (StringValueSupport.isBlank(value)) {
            return defaultValue;
        }
        return value.trim();
    }

    private String resolveTelemetryTransport(String channel, String clientInstanceId, String transportChatId) {
        return CHANNEL_TELEGRAM.equals(channel) ? transportChatId : clientInstanceId;
    }

    private String resolveEffectiveTransportChatId(String channel, String clientInstanceId, String transportChatId) {
        return CHANNEL_WEB.equals(channel) ? clientInstanceId : transportChatId;
    }

    private String generateConversationKey() {
        return UUID.randomUUID().toString();
    }

    private void ensureTelegramSessionBinding(String channel, String transportChatId, String conversationKey) {
        if (!CHANNEL_TELEGRAM.equals(channel)
                || StringValueSupport.isBlank(transportChatId)
                || StringValueSupport.isBlank(conversationKey)) {
            return;
        }
        AgentSession session = sessionPort.getOrCreate(CHANNEL_TELEGRAM, conversationKey);
        SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        sessionPort.save(session);
    }
}

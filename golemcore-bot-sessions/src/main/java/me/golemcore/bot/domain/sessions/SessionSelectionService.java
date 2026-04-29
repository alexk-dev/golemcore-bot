package me.golemcore.bot.domain.sessions;

import me.golemcore.bot.domain.telemetry.TelemetrySupport;

import me.golemcore.bot.domain.validation.ConversationKeyValidator;

import me.golemcore.bot.domain.identity.SessionIdentitySupport;

import me.golemcore.bot.domain.support.StringValueSupport;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.view.ActiveSessionSelectionView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SessionSelectionService {

    private static final int MAX_RECENT_LIMIT = 20;
    private static final String POINTER_SOURCE = "pointer";
    private static final String DEFAULT_SOURCE = "default";
    private static final String REPAIRED_SOURCE = "repaired";

    private final SessionPort sessionPort;
    private final ActiveSessionPointerService pointerService;

    public SessionSelectionService(SessionPort sessionPort, ActiveSessionPointerService pointerService) {
        this.sessionPort = sessionPort;
        this.pointerService = pointerService;
    }

    public List<SessionSummaryView> listRecentSessions(String channel, String clientInstanceId, String transportChatId,
            String principalName, int limit) {
        String normalizedChannel = defaultIfBlank(channel, ChannelTypes.WEB);
        String pointerKey = resolvePointerKey(normalizedChannel, clientInstanceId, transportChatId, principalName);
        String activeConversation = pointerService.getActiveConversationKey(pointerKey).orElse(null);
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_RECENT_LIMIT));
        String effectiveTransportChatId = resolveEffectiveTransportChatId(normalizedChannel, clientInstanceId,
                transportChatId);
        return listSessionsByOwner(normalizedChannel, clientInstanceId, effectiveTransportChatId).stream()
                .sorted(ConversationKeyValidator.byRecentActivity()).map(session -> SessionPresentationSupport
                        .toSummary(session, isActiveSession(session, activeConversation)))
                .limit(normalizedLimit).toList();
    }

    public ActiveSessionSelectionView getActiveSession(String channel, String clientInstanceId, String transportChatId,
            String principalName) {
        String normalizedChannel = defaultIfBlank(channel, ChannelTypes.WEB);
        String pointerKey = resolvePointerKey(normalizedChannel, clientInstanceId, transportChatId, principalName);
        String effectiveTransportChatId = resolveEffectiveTransportChatId(normalizedChannel, clientInstanceId,
                transportChatId);
        Optional<String> activeConversation = pointerService.getActiveConversationKey(pointerKey);

        if (activeConversation.isPresent()) {
            String currentConversation = activeConversation.get();
            if (isConversationResolvable(normalizedChannel, clientInstanceId, effectiveTransportChatId,
                    currentConversation, true)) {
                return toActiveSessionSelection(normalizedChannel, clientInstanceId, effectiveTransportChatId,
                        currentConversation, POINTER_SOURCE);
            }

            log.info(
                    "[SessionMetrics] metric=sessions.active.pointer.stale.count channel={} transportHash={} staleConversation={}",
                    normalizedChannel,
                    TelemetrySupport
                            .shortHash(resolveTelemetryTransport(normalizedChannel, clientInstanceId, transportChatId)),
                    currentConversation);
            String repairedConversation = resolveOrCreateConversationKey(normalizedChannel, clientInstanceId,
                    effectiveTransportChatId, currentConversation);
            pointerService.setActiveConversationKey(pointerKey, repairedConversation);
            return toActiveSessionSelection(normalizedChannel, clientInstanceId, effectiveTransportChatId,
                    repairedConversation, REPAIRED_SOURCE);
        }

        log.info("[SessionMetrics] metric=sessions.active.pointer.miss.count channel={} transportHash={}",
                normalizedChannel, TelemetrySupport
                        .shortHash(resolveTelemetryTransport(normalizedChannel, clientInstanceId, transportChatId)));
        String fallbackConversation = resolveOrCreateConversationKey(normalizedChannel, clientInstanceId,
                effectiveTransportChatId, null);
        pointerService.setActiveConversationKey(pointerKey, fallbackConversation);
        return toActiveSessionSelection(normalizedChannel, clientInstanceId, effectiveTransportChatId,
                fallbackConversation, DEFAULT_SOURCE);
    }

    public ActiveSessionSelectionView setActiveSession(String channel, String clientInstanceId, String transportChatId,
            String principalName, String conversationKey) {
        if (StringValueSupport.isBlank(conversationKey)) {
            throw new IllegalArgumentException("conversationKey is required");
        }

        String normalizedChannel = defaultIfBlank(channel, ChannelTypes.WEB);
        String normalizedConversationKey = normalizeConversationKeyForActivationOrThrow(normalizedChannel,
                conversationKey);
        String pointerKey = resolvePointerKey(normalizedChannel, clientInstanceId, transportChatId, principalName);
        String effectiveTransportChatId = resolveEffectiveTransportChatId(normalizedChannel, clientInstanceId,
                transportChatId);
        validateActivatableConversationOwnership(normalizedChannel, clientInstanceId, normalizedConversationKey);
        pointerService.setActiveConversationKey(pointerKey, normalizedConversationKey);
        log.info("[SessionMetrics] metric=sessions.switch.count channel={} transportHash={} conversationKey={}",
                normalizedChannel,
                TelemetrySupport
                        .shortHash(resolveTelemetryTransport(normalizedChannel, clientInstanceId, transportChatId)),
                normalizedConversationKey);
        ensureTelegramSessionBinding(normalizedChannel, effectiveTransportChatId, normalizedConversationKey);
        return toActiveSessionSelection(normalizedChannel, clientInstanceId, effectiveTransportChatId,
                normalizedConversationKey, POINTER_SOURCE);
    }

    public SessionSummaryView createSession(String channel, String clientInstanceId, String principalName,
            String requestedConversationKey, Boolean activate) {
        String normalizedChannel = defaultIfBlank(channel, ChannelTypes.WEB);
        if (!ChannelTypes.WEB.equals(normalizedChannel)) {
            throw new IllegalArgumentException("Only web channel session creation is supported");
        }
        String normalizedClientInstanceId = requireWebClientInstanceId(clientInstanceId);

        String conversationKey = StringValueSupport.isBlank(requestedConversationKey) ? generateConversationKey()
                : normalizeConversationKeyForCreation(requestedConversationKey);

        boolean shouldActivate = activate == null || activate;
        if (shouldActivate) {
            resolvePointerKey(normalizedChannel, normalizedClientInstanceId, null, principalName);
        }

        AgentSession session = getOrCreateWebSession(normalizedClientInstanceId, conversationKey);
        sessionPort.save(session);
        log.info("[SessionMetrics] metric=sessions.create.count channel={} transportHash={} conversationKey={}",
                normalizedChannel,
                TelemetrySupport
                        .shortHash(resolveTelemetryTransport(normalizedChannel, normalizedClientInstanceId, null)),
                conversationKey);

        if (shouldActivate) {
            String pointerKey = resolvePointerKey(normalizedChannel, normalizedClientInstanceId, null, principalName);
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

    private ActiveSessionSelectionView toActiveSessionSelection(String channel, String clientInstanceId,
            String transportChatId, String conversationKey, String source) {
        return ActiveSessionSelectionView.builder().channelType(channel).clientInstanceId(clientInstanceId)
                .transportChatId(ChannelTypes.WEB.equals(channel) ? clientInstanceId : transportChatId)
                .conversationKey(conversationKey).sessionId(channel + ":" + conversationKey).source(source).build();
    }

    private String resolvePointerKey(String channel, String clientInstanceId, String transportChatId,
            String principalName) {
        if (ChannelTypes.WEB.equals(channel)) {
            if (StringValueSupport.isBlank(clientInstanceId)) {
                throw new IllegalArgumentException("clientInstanceId is required for web");
            }
            return pointerService.buildWebPointerKey(resolvePrincipalName(principalName), clientInstanceId);
        }
        if (ChannelTypes.TELEGRAM.equals(channel)) {
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
        return ConversationKeyValidator.normalizeForActivationOrThrow(value,
                candidate -> sessionPort.get(channel + ":" + candidate).isPresent());
    }

    private String defaultIfBlank(String value, String defaultValue) {
        if (StringValueSupport.isBlank(value)) {
            return defaultValue;
        }
        return value.trim();
    }

    private String resolveTelemetryTransport(String channel, String clientInstanceId, String transportChatId) {
        return ChannelTypes.TELEGRAM.equals(channel) ? transportChatId : clientInstanceId;
    }

    private String resolveEffectiveTransportChatId(String channel, String clientInstanceId, String transportChatId) {
        return ChannelTypes.WEB.equals(channel) ? clientInstanceId : transportChatId;
    }

    private String generateConversationKey() {
        return UUID.randomUUID().toString();
    }

    private List<AgentSession> listSessionsByOwner(String channel, String clientInstanceId, String transportChatId) {
        if (ChannelTypes.WEB.equals(channel)) {
            return listWebSessionsByClient(clientInstanceId);
        }
        return SessionConversationSupport.listRecentSessionsByOwner(sessionPort, channel, transportChatId);
    }

    private List<AgentSession> listWebSessionsByClient(String clientInstanceId) {
        String normalizedClientInstanceId = requireWebClientInstanceId(clientInstanceId);
        return sessionPort.listByChannelType(ChannelTypes.WEB).stream()
                .filter(session -> SessionIdentitySupport.belongsToWebClient(session, normalizedClientInstanceId))
                .toList();
    }

    private boolean isConversationResolvable(String channel, String clientInstanceId, String transportChatId,
            String conversationKey, boolean adoptLegacyWebSession) {
        if (ChannelTypes.WEB.equals(channel)) {
            return resolveOwnedWebSession(conversationKey, clientInstanceId, adoptLegacyWebSession).isPresent();
        }
        return SessionConversationSupport.isConversationResolvable(sessionPort, channel, transportChatId,
                conversationKey);
    }

    private String resolveOrCreateConversationKey(String channel, String clientInstanceId, String transportChatId,
            String preferredConversation) {
        if (ChannelTypes.WEB.equals(channel)) {
            return resolveOrCreateWebConversationKey(clientInstanceId, preferredConversation);
        }
        String conversationKey = SessionConversationSupport.resolveOrCreateConversationKey(sessionPort, channel,
                transportChatId, preferredConversation);
        ensureTelegramSessionBinding(channel, transportChatId, conversationKey);
        return conversationKey;
    }

    private String resolveOrCreateWebConversationKey(String clientInstanceId, String preferredConversation) {
        Optional<AgentSession> preferredSession = resolveOwnedWebSession(preferredConversation, clientInstanceId, true);
        if (preferredSession.isPresent()) {
            return SessionIdentitySupport.resolveConversationKey(preferredSession.get());
        }

        return listWebSessionsByClient(clientInstanceId).stream().sorted(ConversationKeyValidator.byRecentActivity())
                .map(SessionIdentitySupport::resolveConversationKey)
                .filter(value -> !StringValueSupport.isBlank(value) && !value.equals(preferredConversation)).findFirst()
                .orElseGet(() -> createOwnedWebSession(clientInstanceId));
    }

    private Optional<AgentSession> resolveOwnedWebSession(String conversationKey, String clientInstanceId,
            boolean adoptLegacyWebSession) {
        if (StringValueSupport.isBlank(conversationKey)) {
            return Optional.empty();
        }

        Optional<AgentSession> session = sessionPort.get(ChannelTypes.WEB + ":" + conversationKey);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        if (tryBindWebSessionOwnership(session.get(), clientInstanceId, adoptLegacyWebSession)) {
            return session;
        }
        return Optional.empty();
    }

    private void validateActivatableConversationOwnership(String channel, String clientInstanceId,
            String conversationKey) {
        if (!ChannelTypes.WEB.equals(channel)) {
            return;
        }

        Optional<AgentSession> existingSession = sessionPort.get(ChannelTypes.WEB + ":" + conversationKey);
        if (existingSession.isPresent() && !tryBindWebSessionOwnership(existingSession.get(), clientInstanceId, true)) {
            throw new IllegalArgumentException("conversationKey belongs to another web client");
        }
    }

    private AgentSession getOrCreateWebSession(String clientInstanceId, String conversationKey) {
        Optional<AgentSession> existingSession = sessionPort.get(ChannelTypes.WEB + ":" + conversationKey);
        if (existingSession.isPresent()) {
            if (!tryBindWebSessionOwnership(existingSession.get(), clientInstanceId, true)) {
                throw new IllegalArgumentException("conversationKey belongs to another web client");
            }
            return existingSession.get();
        }

        AgentSession session = sessionPort.getOrCreate(ChannelTypes.WEB, conversationKey);
        SessionIdentitySupport.bindWebClientInstance(session, requireWebClientInstanceId(clientInstanceId));
        return session;
    }

    private String createOwnedWebSession(String clientInstanceId) {
        AgentSession session = sessionPort.getOrCreate(ChannelTypes.WEB, generateConversationKey());
        SessionIdentitySupport.bindWebClientInstance(session, requireWebClientInstanceId(clientInstanceId));
        sessionPort.save(session);
        return SessionIdentitySupport.resolveConversationKey(session);
    }

    private boolean tryBindWebSessionOwnership(AgentSession session, String clientInstanceId,
            boolean adoptLegacyWebSession) {
        String normalizedClientInstanceId = requireWebClientInstanceId(clientInstanceId);
        String currentOwner = SessionIdentitySupport.resolveWebClientInstanceId(session);
        if (!StringValueSupport.isBlank(currentOwner)) {
            return normalizedClientInstanceId.equals(currentOwner);
        }
        if (!adoptLegacyWebSession) {
            return false;
        }
        boolean changed = SessionIdentitySupport.bindWebClientInstance(session, normalizedClientInstanceId);
        if (changed) {
            sessionPort.save(session);
        }
        return true;
    }

    private String requireWebClientInstanceId(String clientInstanceId) {
        if (StringValueSupport.isBlank(clientInstanceId)) {
            throw new IllegalArgumentException("clientInstanceId is required for web");
        }
        return clientInstanceId.trim();
    }

    private void ensureTelegramSessionBinding(String channel, String transportChatId, String conversationKey) {
        if (!ChannelTypes.TELEGRAM.equals(channel) || StringValueSupport.isBlank(transportChatId)
                || StringValueSupport.isBlank(conversationKey)) {
            return;
        }
        AgentSession session = sessionPort.getOrCreate(ChannelTypes.TELEGRAM, conversationKey);
        SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        sessionPort.save(session);
    }
}

package me.golemcore.bot.domain.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;

@Slf4j
public class SessionModelSettingsInheritancePolicy {

    private final SessionCache sessionCache;
    private final SessionRepository sessionRepository;

    public SessionModelSettingsInheritancePolicy(SessionCache sessionCache, SessionRepository sessionRepository) {
        this.sessionCache = sessionCache;
        this.sessionRepository = sessionRepository;
    }

    public void inheritModelSettings(AgentSession session) {
        if (session == null || !SessionModelSettingsSupport.shouldInheritModelSettings(session.getChannelType())) {
            return;
        }

        findModelSettingsSource(session).ifPresent(source -> {
            SessionModelSettingsSupport.inheritModelSettings(source, session);
            log.debug("Inherited model settings from session {} into {}", source.getId(), session.getId());
        });
    }

    private Optional<AgentSession> findModelSettingsSource(AgentSession target) {
        Map<String, AgentSession> candidates = new LinkedHashMap<>();
        String targetId = target.getId();
        String channelType = target.getChannelType();

        sessionCache.values().stream()
                .filter(candidate -> isModelSettingsInheritanceCandidate(candidate, channelType, targetId))
                .forEach(candidate -> candidates.putIfAbsent(candidate.getId(), candidate));

        sessionRepository.loadStoredSessionsForChannel(channelType).stream()
                .filter(candidate -> isModelSettingsInheritanceCandidate(candidate, channelType, targetId))
                .forEach(candidate -> candidates.putIfAbsent(candidate.getId(), candidate));

        return candidates.values().stream().sorted(ConversationKeyValidator.byRecentActivity()).findFirst();
    }

    private boolean isModelSettingsInheritanceCandidate(AgentSession candidate, String channelType, String targetId) {
        if (candidate == null || targetId.equals(candidate.getId())) {
            return false;
        }
        if (StringValueSupport.isBlank(channelType) || !channelType.equals(candidate.getChannelType())) {
            return false;
        }
        return SessionModelSettingsSupport.hasModelSettings(candidate);
    }
}

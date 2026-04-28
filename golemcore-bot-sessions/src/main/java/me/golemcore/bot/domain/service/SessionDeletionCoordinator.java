package me.golemcore.bot.domain.service;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.port.outbound.SessionGoalCleanupPort;

public class SessionDeletionCoordinator {

    private final SessionCache sessionCache;
    private final SessionRepository sessionRepository;
    private final List<SessionGoalCleanupPort> sessionGoalCleanupPorts;

    public SessionDeletionCoordinator(SessionCache sessionCache, SessionRepository sessionRepository,
            List<SessionGoalCleanupPort> sessionGoalCleanupPorts) {
        this.sessionCache = sessionCache;
        this.sessionRepository = sessionRepository;
        this.sessionGoalCleanupPorts = sessionGoalCleanupPorts != null ? List.copyOf(sessionGoalCleanupPorts)
                : List.of();
    }

    public void delete(String sessionId) {
        AgentSession removed = sessionCache.remove(sessionId);
        if (sessionRepository.delete(sessionId)) {
            deleteSessionGoals(sessionId);
            return;
        }
        sessionCache.restore(sessionId, removed);
    }

    public int cleanupExpiredSessions(Instant cutoff, Predicate<AgentSession> shouldRetain) {
        int deletedCount = 0;
        for (AgentSession session : List.copyOf(sessionCache.values())) {
            if (session == null || StringValueSupport.isBlank(session.getId())) {
                continue;
            }
            if (shouldRetain.test(session)) {
                continue;
            }
            Instant updatedAt = session.getUpdatedAt() != null ? session.getUpdatedAt() : session.getCreatedAt();
            if (updatedAt == null || !updatedAt.isBefore(cutoff)) {
                continue;
            }
            sessionCache.remove(session.getId());
            if (sessionRepository.delete(session.getId())) {
                deleteSessionGoals(session.getId());
                deletedCount++;
                continue;
            }
            sessionCache.restore(session.getId(), session);
        }
        return deletedCount;
    }

    private void deleteSessionGoals(String sessionId) {
        for (SessionGoalCleanupPort sessionGoalCleanupPort : sessionGoalCleanupPorts) {
            sessionGoalCleanupPort.deleteSessionGoals(sessionId);
        }
    }
}

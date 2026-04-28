package me.golemcore.bot.domain.sessions;

import me.golemcore.bot.domain.support.StringValueSupport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import me.golemcore.bot.domain.model.AgentSession;

public class SessionCache {

    private static final int LOCK_STRIPES = 64;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final Object[] sessionLocks = new Object[LOCK_STRIPES];

    public SessionCache() {
        Arrays.setAll(sessionLocks, ignored -> new Object());
    }

    public AgentSession computeIfAbsent(String sessionId, Function<String, AgentSession> loader) {
        return sessions.computeIfAbsent(sessionId, loader);
    }

    public Optional<AgentSession> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void put(AgentSession session) {
        sessions.put(session.getId(), session);
    }

    public AgentSession remove(String sessionId) {
        return sessions.remove(sessionId);
    }

    public void restore(String sessionId, AgentSession session) {
        if (session == null || StringValueSupport.isBlank(sessionId)) {
            return;
        }
        sessions.putIfAbsent(sessionId, session);
    }

    public void putIfAbsent(AgentSession session) {
        if (session != null && !StringValueSupport.isBlank(session.getId())) {
            sessions.putIfAbsent(session.getId(), session);
        }
    }

    public Object lockFor(String sessionId) {
        int index = Math.floorMod(String.valueOf(sessionId).hashCode(), sessionLocks.length);
        return sessionLocks[index];
    }

    public List<AgentSession> values() {
        return sessions.values().stream().filter(java.util.Objects::nonNull).toList();
    }
}

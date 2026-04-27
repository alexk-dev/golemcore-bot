package me.golemcore.bot.domain.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import me.golemcore.bot.domain.model.AgentSession;

public class SessionCache {

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    public SessionCache() {
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

    public List<AgentSession> values() {
        return sessions.values().stream().filter(java.util.Objects::nonNull).toList();
    }
}

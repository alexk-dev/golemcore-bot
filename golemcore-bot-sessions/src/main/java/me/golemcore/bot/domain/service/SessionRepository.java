package me.golemcore.bot.domain.service;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.port.outbound.SessionRecordCodecPort;
import me.golemcore.bot.port.outbound.StoragePort;

@Slf4j
public class SessionRepository {

    private final StoragePort storagePort;
    private final SessionRecordCodecPort sessionRecordCodecPort;
    private final SessionIdFactory sessionIdFactory;

    public SessionRepository(StoragePort storagePort, SessionRecordCodecPort sessionRecordCodecPort,
            SessionIdFactory sessionIdFactory) {
        this.storagePort = storagePort;
        this.sessionRecordCodecPort = sessionRecordCodecPort;
        this.sessionIdFactory = sessionIdFactory;
    }

    public Optional<AgentSession> load(String sessionId) {
        try {
            byte[] bytes = storagePort.getObject(SessionIdFactory.SESSIONS_DIR,
                    sessionIdFactory.storageFileName(sessionId)).join();
            if (bytes != null && bytes.length > 0) {
                AgentSession loaded = sessionRecordCodecPort.decode(bytes);
                sessionIdFactory.enrichSessionFields(loaded, sessionIdFactory.storageFileName(sessionId));
                return Optional.of(loaded);
            }
        } catch (IllegalStateException e) {
            log.error("Failed to parse protobuf session {}: {}", sessionId, e.getMessage());
            throw new IllegalStateException("Failed to parse protobuf session: " + sessionId, e);
        } catch (RuntimeException e) { // NOSONAR - storage miss is represented as a failed future by adapters/tests
            log.debug("Failed protobuf load for session {}: {}", sessionId, e.getMessage());
        }
        return Optional.empty();
    }

    public void save(AgentSession session) {
        try {
            byte[] proto = sessionRecordCodecPort.encode(session);
            storagePort.putObject(SessionIdFactory.SESSIONS_DIR, sessionIdFactory.storageFileName(session.getId()),
                    proto).join();
            log.debug("Saved session: {}", session.getId());
        } catch (Exception e) {
            log.error("Failed to save session: {}", session.getId(), e);
            throw new IllegalStateException("Failed to save session: " + session.getId(), e);
        }
    }

    public boolean delete(String sessionId) {
        try {
            storagePort.deleteObject(SessionIdFactory.SESSIONS_DIR, sessionIdFactory.storageFileName(sessionId)).join();
            log.info("Deleted session: {}", sessionId);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete session: {}", sessionId, e);
            return false;
        }
    }

    public List<AgentSession> loadStoredSessions(Predicate<String> pathFilter) {
        try {
            return storagePort.listObjects(SessionIdFactory.SESSIONS_DIR, "").join().stream().filter(pathFilter)
                    .map(this::loadFromStoredFile).flatMap(Optional::stream).toList();
        } catch (Exception e) { // NOSONAR
            log.warn("Failed to scan sessions directory: {}", e.getMessage());
            return List.of();
        }
    }

    public List<AgentSession> loadStoredSessionsForChannel(String channelType) {
        return loadStoredSessions(path -> sessionIdFactory.isStoredFileForChannel(path, channelType));
    }

    private Optional<AgentSession> loadFromStoredFile(String filePath) {
        if (!filePath.endsWith(SessionIdFactory.PROTO_EXTENSION)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = storagePort.getObject(SessionIdFactory.SESSIONS_DIR, filePath).join();
            if (bytes == null || bytes.length == 0) {
                return Optional.empty();
            }
            AgentSession session = sessionRecordCodecPort.decode(bytes);
            sessionIdFactory.enrichSessionFields(session, filePath);
            return Optional.of(session);
        } catch (RuntimeException e) { // NOSONAR - intentionally catch all for tolerant directory scans
            log.debug("Failed to parse protobuf session file {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }
}

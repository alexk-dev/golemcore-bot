package me.golemcore.bot.domain.session;

import java.util.Deque;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.SessionPort;

@Slf4j
final class QueuedMessageFlushService {

    private final SessionPort sessionPort;

    QueuedMessageFlushService(SessionPort sessionPort) {
        this.sessionPort = sessionPort;
    }

    void flush(String channelType, String chatId, Deque<Message> prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return;
        }

        AgentSession session = sessionPort.getOrCreate(channelType, chatId);
        if (session == null) {
            return;
        }

        int before = session.getMessages().size();
        for (Message message : prefix) {
            session.addMessage(message);
        }
        try {
            sessionPort.save(session);
        } catch (Exception e) { // NOSONAR - best-effort persistence
            log.error("[QueuedMessageFlushService] failed to persist prefix flush: sessionId={}", session.getId(), e);
        }
        log.info("[QueuedMessageFlushService] flushed {} queued messages ({} -> {})",
                prefix.size(), before, session.getMessages().size());
    }
}

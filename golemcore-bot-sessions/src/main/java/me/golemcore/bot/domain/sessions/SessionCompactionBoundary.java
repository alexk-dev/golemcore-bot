package me.golemcore.bot.domain.sessions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;

@Slf4j
public class SessionCompactionBoundary {

    public int compactMessages(AgentSession session, int keepLast, Consumer<AgentSession> saveSession) {
        if (session == null) {
            return -1;
        }

        List<Message> messages = session.mutableMessages();
        int total = messages.size();
        if (total <= keepLast) {
            return 0;
        }

        int toRemove = total - keepLast;
        List<Message> kept = Message.flattenToolMessages(new ArrayList<>(messages.subList(toRemove, total)));
        messages.clear();
        messages.addAll(kept);
        saveSession.accept(session);
        log.info("Compacted session {}: removed {} messages, kept {}", session.getId(), toRemove, kept.size());
        return toRemove;
    }

    public int compactWithSummary(AgentSession session, int keepLast, Message summaryMessage,
            Consumer<AgentSession> saveSession) {
        if (session == null) {
            return -1;
        }

        List<Message> messages = session.mutableMessages();
        int total = messages.size();
        if (total <= keepLast) {
            return 0;
        }

        int toRemove = total - keepLast;
        List<Message> kept = Message.flattenToolMessages(new ArrayList<>(messages.subList(toRemove, total)));
        messages.clear();
        messages.add(summaryMessage);
        messages.addAll(kept);
        saveSession.accept(session);
        log.info("Compacted session {} with summary: removed {} messages, kept {} + summary", session.getId(), toRemove,
                kept.size());
        return toRemove;
    }

    public List<Message> getMessagesToCompact(AgentSession session, int keepLast) {
        if (session == null) {
            return List.of();
        }

        List<Message> messages = session.getMessages();
        int total = messages.size();
        if (total <= keepLast) {
            return List.of();
        }

        int toRemove = total - keepLast;
        return new ArrayList<>(messages.subList(0, toRemove));
    }

    public int getMessageCount(AgentSession session) {
        return session != null ? session.getMessages().size() : 0;
    }
}

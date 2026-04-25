package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.AgentSession;

/**
 * Serializes and deserializes persisted session records.
 */
public interface SessionRecordCodecPort {

    byte[] encode(AgentSession session);

    AgentSession decode(byte[] bytes);
}

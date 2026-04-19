package me.golemcore.bot.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Structured runtime event emitted during turn execution.
 *
 * @param type
 *            runtime event type
 * @param timestamp
 *            event timestamp
 * @param sessionId
 *            session identifier
 * @param channelType
 *            channel type associated with the event
 * @param chatId
 *            chat identifier associated with the event
 * @param payload
 *            structured event payload
 */
@Builder public record RuntimeEvent(RuntimeEventType type,Instant timestamp,String sessionId,String channelType,String chatId,Map<String,Object>payload){}

package me.golemcore.bot.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Structured runtime event emitted during turn execution.
 *
 * @param schemaVersion
 *            externally visible event schema version
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
@Builder public record RuntimeEvent(int schemaVersion,RuntimeEventType type,Instant timestamp,String sessionId,String channelType,String chatId,Map<String,Object>payload){

public static final int SCHEMA_VERSION=1;

public RuntimeEvent{if(schemaVersion<=0){schemaVersion=SCHEMA_VERSION;}}}

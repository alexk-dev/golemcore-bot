package me.golemcore.bot.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Structured runtime event emitted during turn execution.
 */
@Builder public record RuntimeEvent(RuntimeEventType type,Instant timestamp,String sessionId,String channelType,String chatId,Map<String,Object>payload){}

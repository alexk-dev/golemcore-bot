package me.golemcore.bot.domain.model;

import lombok.Builder;

import java.util.List;

/**
 * Prepared split of message history before compaction execution.
 */
@Builder public record CompactionPreparation(String sessionId,CompactionReason reason,int totalMessages,int keepLastRequested,int rawCutIndex,int adjustedCutIndex,boolean splitTurnDetected,List<Message>messagesToCompact,List<Message>messagesToKeep){}

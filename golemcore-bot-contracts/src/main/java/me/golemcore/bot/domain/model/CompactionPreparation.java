package me.golemcore.bot.domain.model;

import lombok.Builder;

import java.util.List;

/**
 * Prepared split of message history before compaction execution.
 *
 * @param sessionId
 *            session being compacted
 * @param reason
 *            reason compaction was requested
 * @param totalMessages
 *            total message count before compaction
 * @param keepLastRequested
 *            requested number of trailing messages to keep
 * @param rawCutIndex
 *            initial split index before safety adjustments
 * @param adjustedCutIndex
 *            final split index after safety adjustments
 * @param splitTurnDetected
 *            whether the split would cut through an active turn
 * @param messagesToCompact
 *            messages selected for compaction
 * @param messagesToKeep
 *            messages kept verbatim
 */
@Builder public record CompactionPreparation(String sessionId,CompactionReason reason,int totalMessages,int keepLastRequested,int rawCutIndex,int adjustedCutIndex,boolean splitTurnDetected,List<Message>messagesToCompact,List<Message>messagesToKeep){}

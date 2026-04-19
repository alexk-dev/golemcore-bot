package me.golemcore.bot.domain.model;

import lombok.Builder;

/**
 * Final result of compaction execution.
 *
 * @param removed
 *            number of messages removed from the active history
 * @param usedSummary
 *            whether compaction produced or reused a summary message
 * @param summaryMessage
 *            summary message inserted into the compacted history
 * @param details
 *            detailed compaction diagnostics
 */
@Builder public record CompactionResult(int removed,boolean usedSummary,Message summaryMessage,CompactionDetails details){}

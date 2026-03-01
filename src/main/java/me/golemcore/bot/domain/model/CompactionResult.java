package me.golemcore.bot.domain.model;

import lombok.Builder;

/**
 * Final result of compaction execution.
 */
@Builder public record CompactionResult(int removed,boolean usedSummary,Message summaryMessage,CompactionDetails details){}

package me.golemcore.bot.domain.model;

import lombok.Builder;

import java.util.List;

/**
 * Structured details captured during compaction for diagnostics and
 * continuation.
 */
@Builder public record CompactionDetails(int schemaVersion,CompactionReason reason,int summarizedCount,int keptCount,boolean usedLlmSummary,int summaryLength,int toolCount,int readFilesCount,int modifiedFilesCount,long durationMs,List<String>toolNames,List<String>readFiles,List<String>modifiedFiles,List<FileChangeStat>fileChanges,boolean splitTurnDetected,boolean fallbackUsed){

@Builder public static record FileChangeStat(String path,int addedLines,int removedLines,boolean deleted){}}

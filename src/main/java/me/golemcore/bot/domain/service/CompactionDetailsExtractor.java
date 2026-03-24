package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.CompactionDetails;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts structured compaction context from compacted messages.
 */
@Service
public class CompactionDetailsExtractor {

    private static final int SCHEMA_VERSION = 1;

    public CompactionDetails extract(
            CompactionReason reason,
            List<Message> compactedMessages,
            int summarizedCount,
            int keptCount,
            boolean usedLlmSummary,
            int summaryLength,
            boolean splitTurnDetected,
            boolean fallbackUsed,
            long durationMs,
            int maxItemsPerCategory) {
        List<Message> safeMessages = compactedMessages != null ? compactedMessages : List.of();
        int maxItems = maxItemsPerCategory > 0 ? maxItemsPerCategory : 50;

        Set<String> toolNames = new LinkedHashSet<>();
        Set<String> readFiles = new LinkedHashSet<>();
        Set<String> modifiedFiles = new LinkedHashSet<>();
        Map<String, CompactionDetails.FileChangeStat> fileChanges = new LinkedHashMap<>();

        for (Message message : safeMessages) {
            collectFromMessage(message, toolNames, readFiles, modifiedFiles, fileChanges, maxItems);
            if (toolNames.size() >= maxItems && readFiles.size() >= maxItems
                    && modifiedFiles.size() >= maxItems && fileChanges.size() >= maxItems) {
                break;
            }
        }

        return CompactionDetails.builder()
                .schemaVersion(SCHEMA_VERSION)
                .reason(reason)
                .summarizedCount(summarizedCount)
                .keptCount(keptCount)
                .usedLlmSummary(usedLlmSummary)
                .summaryLength(summaryLength)
                .toolCount(toolNames.size())
                .readFilesCount(readFiles.size())
                .modifiedFilesCount(modifiedFiles.size())
                .durationMs(durationMs)
                .toolNames(new ArrayList<>(toolNames))
                .readFiles(new ArrayList<>(readFiles))
                .modifiedFiles(new ArrayList<>(modifiedFiles))
                .fileChanges(new ArrayList<>(fileChanges.values()))
                .splitTurnDetected(splitTurnDetected)
                .fallbackUsed(fallbackUsed)
                .build();
    }

    private void collectFromMessage(
            Message message,
            Set<String> toolNames,
            Set<String> readFiles,
            Set<String> modifiedFiles,
            Map<String, CompactionDetails.FileChangeStat> fileChanges,
            int maxItems) {
        if (message == null) {
            return;
        }

        if (message.getToolName() != null && toolNames.size() < maxItems) {
            toolNames.add(message.getToolName());
        }

        if (message.hasToolCalls()) {
            for (Message.ToolCall toolCall : message.getToolCalls()) {
                collectFromToolCall(toolCall, toolNames, readFiles, modifiedFiles, fileChanges, maxItems);
            }
        }
    }

    private void collectFromToolCall(
            Message.ToolCall toolCall,
            Set<String> toolNames,
            Set<String> readFiles,
            Set<String> modifiedFiles,
            Map<String, CompactionDetails.FileChangeStat> fileChanges,
            int maxItems) {
        if (toolCall == null) {
            return;
        }

        String toolName = toolCall.getName();
        if (toolName != null && toolNames.size() < maxItems) {
            toolNames.add(toolName);
        }

        if (toolCall.getArguments() == null || toolCall.getArguments().isEmpty()) {
            return;
        }

        if ("filesystem".equals(toolName)) {
            collectFileSystemArgs(toolCall.getArguments(), readFiles, modifiedFiles, fileChanges, maxItems);
        }
    }

    private void collectFileSystemArgs(
            Map<String, Object> args,
            Set<String> readFiles,
            Set<String> modifiedFiles,
            Map<String, CompactionDetails.FileChangeStat> fileChanges,
            int maxItems) {
        String operation = asString(args.get("operation"));
        String path = asString(args.get("path"));

        if (operation == null || path == null) {
            return;
        }

        if ("read_file".equals(operation) && readFiles.size() < maxItems) {
            readFiles.add(path);
            return;
        }

        if (("write_file".equals(operation) || "delete".equals(operation) || "create_directory".equals(operation))
                && modifiedFiles.size() < maxItems) {
            modifiedFiles.add(path);
            if (!fileChanges.containsKey(path) && fileChanges.size() < maxItems) {
                int addedLines = 0;
                int removedLines = 0;
                boolean deleted = false;
                if ("write_file".equals(operation)) {
                    String content = asString(args.get("content"));
                    if (content != null && !content.isBlank()) {
                        addedLines = content.split("\\R", -1).length;
                    }
                }
                if ("delete".equals(operation)) {
                    deleted = true;
                    removedLines = 1;
                }
                fileChanges.put(path, CompactionDetails.FileChangeStat.builder()
                        .path(path)
                        .addedLines(addedLines)
                        .removedLines(removedLines)
                        .deleted(deleted)
                        .build());
            }
        }
    }

    private String asString(Object value) {
        if (value instanceof String stringValue) {
            return stringValue;
        }
        return null;
    }
}

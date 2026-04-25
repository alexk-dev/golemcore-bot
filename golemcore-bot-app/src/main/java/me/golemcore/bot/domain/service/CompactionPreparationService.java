package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.CompactionPreparation;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes compaction boundaries and prevents split-turn truncation.
 */
@Service
public class CompactionPreparationService {

    public CompactionPreparation prepare(String sessionId, List<Message> messages, int keepLast,
            CompactionReason reason, boolean preserveTurnBoundaries) {
        List<Message> safeMessages = messages != null ? messages : List.of();
        int total = safeMessages.size();
        int normalizedKeepLast = Math.max(1, keepLast);
        int rawCutIndex = Math.max(0, total - normalizedKeepLast);
        int adjustedCutIndex = rawCutIndex;

        boolean splitTurnDetected = false;
        if (preserveTurnBoundaries) {
            int safeCutIndex = moveCutIndexToSafeBoundary(safeMessages, rawCutIndex);
            splitTurnDetected = safeCutIndex != rawCutIndex;
            adjustedCutIndex = safeCutIndex;
        }

        List<Message> toCompact = new ArrayList<>(safeMessages.subList(0, adjustedCutIndex));
        List<Message> toKeep = new ArrayList<>(safeMessages.subList(adjustedCutIndex, total));

        return CompactionPreparation.builder()
                .sessionId(sessionId)
                .reason(reason)
                .totalMessages(total)
                .keepLastRequested(normalizedKeepLast)
                .rawCutIndex(rawCutIndex)
                .adjustedCutIndex(adjustedCutIndex)
                .splitTurnDetected(splitTurnDetected)
                .messagesToCompact(toCompact)
                .messagesToKeep(toKeep)
                .build();
    }

    private int moveCutIndexToSafeBoundary(List<Message> messages, int rawCutIndex) {
        if (rawCutIndex <= 0 || rawCutIndex >= messages.size()) {
            return rawCutIndex;
        }

        int safeCutIndex = rawCutIndex;
        while (safeCutIndex > 0 && isBoundarySplit(messages, safeCutIndex)) {
            safeCutIndex--;
        }
        return safeCutIndex;
    }

    private boolean isBoundarySplit(List<Message> messages, int cutIndex) {
        Message firstKept = messages.get(cutIndex);
        if (firstKept != null && firstKept.isToolMessage()) {
            String toolCallId = firstKept.getToolCallId();
            return toolCallId == null || !hasAssistantToolCallBefore(messages, cutIndex, toolCallId);
        }

        Message previous = messages.get(cutIndex - 1);
        if (previous != null && previous.isAssistantMessage() && previous.hasToolCalls()) {
            Set<String> danglingIds = new HashSet<>();
            for (Message.ToolCall toolCall : previous.getToolCalls()) {
                if (toolCall != null && toolCall.getId() != null) {
                    danglingIds.add(toolCall.getId());
                }
            }
            if (danglingIds.isEmpty()) {
                return false;
            }
            for (int index = cutIndex; index < messages.size(); index++) {
                Message candidate = messages.get(index);
                if (candidate != null && candidate.isToolMessage() && candidate.getToolCallId() != null) {
                    danglingIds.remove(candidate.getToolCallId());
                }
                if (danglingIds.isEmpty()) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean hasAssistantToolCallBefore(List<Message> messages, int boundaryIndex, String toolCallId) {
        for (int index = boundaryIndex - 1; index >= 0; index--) {
            Message candidate = messages.get(index);
            if (candidate == null || !candidate.isAssistantMessage() || !candidate.hasToolCalls()) {
                continue;
            }
            for (Message.ToolCall toolCall : candidate.getToolCalls()) {
                if (toolCall != null && toolCallId.equals(toolCall.getId())) {
                    return true;
                }
            }
        }
        return false;
    }
}

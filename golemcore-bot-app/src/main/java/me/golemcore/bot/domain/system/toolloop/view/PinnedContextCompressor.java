package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ContextTokenEstimator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Hard fallback for pinned context that still exceeds the conversation budget.
 */
final class PinnedContextCompressor {

    private static final int ASSISTANT_SUMMARY_CHARS = 240;
    private static final int SOFT_TOOL_SUMMARY_CHARS = 420;
    private static final int HARD_TOOL_SUMMARY_CHARS = 96;
    private static final int HARD_MESSAGE_SUMMARY_CHARS = 160;

    private final ContextTokenEstimator tokenEstimator;
    private final ContextGarbagePolicy garbagePolicy;

    PinnedContextCompressor(ContextTokenEstimator tokenEstimator, ContextGarbagePolicy garbagePolicy) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        this.garbagePolicy = Objects.requireNonNull(garbagePolicy, "garbagePolicy");
    }

    List<ProjectedItem> compressWithinBudget(List<ProjectedItem> preparedItems,
            int limit, ContextHygieneReport report) {
        int lastUserOrdinal = findLastUserOrdinal(preparedItems);
        List<ProjectedItem> compressedPinned = compressPinned(preparedItems, SOFT_TOOL_SUMMARY_CHARS, report);
        if (estimate(compressedPinned) <= limit) {
            return compressedPinned;
        }

        List<ProjectedItem> critical = new ArrayList<>();
        for (ProjectedItem item : compressedPinned) {
            if (isProtocolCritical(item, lastUserOrdinal)) {
                critical.add(item);
            } else {
                report.drop(dropReason(item));
            }
        }
        if (estimate(critical) <= limit) {
            return critical;
        }

        List<ProjectedItem> hardCompressed = recompress(critical, HARD_TOOL_SUMMARY_CHARS, report);
        if (estimate(hardCompressed) <= limit) {
            return hardCompressed;
        }
        List<ProjectedItem> minimal = recompressLargeMessages(hardCompressed, report);
        if (estimate(minimal) <= limit) {
            return minimal;
        }
        List<ProjectedItem> mostRecent = selectMostRecentThatFit(minimal, limit, report);
        int projectedTokens = estimate(mostRecent);
        if (projectedTokens <= limit) {
            return mostRecent;
        }
        throw new ContextWindowBudgetExceededException(limit, projectedTokens);
    }

    private List<ProjectedItem> compressPinned(List<ProjectedItem> items,
            int toolSummaryChars, ContextHygieneReport report) {
        List<ProjectedItem> compressed = new ArrayList<>();
        for (ProjectedItem item : items) {
            if (item.pinned()) {
                compressed.add(compressItem(item, toolSummaryChars, report));
            }
        }
        return compressed;
    }

    private List<ProjectedItem> recompress(List<ProjectedItem> items,
            int toolSummaryChars, ContextHygieneReport report) {
        List<ProjectedItem> compressed = new ArrayList<>();
        for (ProjectedItem item : items) {
            compressed.add(compressItem(item, toolSummaryChars, report));
        }
        return compressed;
    }

    private ProjectedItem compressItem(ProjectedItem item,
            int toolSummaryChars, ContextHygieneReport report) {
        List<Message> messages = switch (item.item().kind()) {
        case TOOL_INTERACTION -> summarizeToolInteraction(item.item(), toolSummaryChars);
        case TOOL_RESULT -> List.of(summarizeToolResult(item.item().first(), toolSummaryChars));
        case ASSISTANT, SYSTEM, OTHER -> summarizeLargeMessages(item.messages(), ASSISTANT_SUMMARY_CHARS);
        case USER -> item.messages();
        };
        return rebuild(item, messages, report);
    }

    private List<ProjectedItem> recompressLargeMessages(List<ProjectedItem> items,
            ContextHygieneReport report) {
        List<ProjectedItem> compressed = new ArrayList<>();
        for (ProjectedItem item : items) {
            if (item.item().kind() == ContextItemKind.USER) {
                compressed.add(item);
                continue;
            }
            compressed.add(rebuild(item, summarizeLargeMessages(item.messages(), HARD_MESSAGE_SUMMARY_CHARS), report));
        }
        return compressed;
    }

    private List<ProjectedItem> selectMostRecentThatFit(List<ProjectedItem> items,
            int limit, ContextHygieneReport report) {
        ProjectedItem latestUser = latestUser(items);
        List<ProjectedItem> selected = new ArrayList<>();
        int usedTokens = 0;
        if (latestUser != null) {
            ProjectedItem user = fitLargeUser(latestUser, limit, report);
            selected.add(user);
            usedTokens += user.estimatedTokens();
        }
        for (int index = items.size() - 1; index >= 0; index--) {
            ProjectedItem item = items.get(index);
            if (sameContextItem(item, latestUser)) {
                continue;
            }
            if (usedTokens + item.estimatedTokens() <= limit) {
                selected.add(0, item);
                usedTokens += item.estimatedTokens();
            } else {
                report.drop(dropReason(item));
            }
        }
        return selected;
    }

    private ProjectedItem latestUser(List<ProjectedItem> items) {
        for (int index = items.size() - 1; index >= 0; index--) {
            ProjectedItem item = items.get(index);
            if (item.item().kind() == ContextItemKind.USER) {
                return item;
            }
        }
        return null;
    }

    private boolean sameContextItem(ProjectedItem left, ProjectedItem right) {
        return left != null && right != null && left.item().ordinal() == right.item().ordinal();
    }

    private ProjectedItem fitLargeUser(ProjectedItem item, int limit, ContextHygieneReport report) {
        if (item.estimatedTokens() <= limit) {
            return item;
        }
        List<Message> compressed = summarizeLargeMessages(item.messages(), HARD_MESSAGE_SUMMARY_CHARS);
        ProjectedItem rebuilt = rebuild(item, compressed, report);
        if (rebuilt.estimatedTokens() <= limit) {
            return rebuilt;
        }
        List<Message> minimal = summarizeLargeMessages(item.messages(), 32);
        return rebuild(item, minimal, report);
    }

    private List<Message> summarizeToolInteraction(ContextItem item, int toolSummaryChars) {
        List<Message> messages = item.messages();
        if (messages.isEmpty()) {
            return List.of();
        }
        List<Message> projected = new ArrayList<>();
        Message assistant = messages.get(0);
        projected.add(copyMessage(assistant, truncate(assistant.getContent(), ASSISTANT_SUMMARY_CHARS)));
        for (int index = 1; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message != null && message.isToolMessage()) {
                projected.add(summarizeToolResult(message, toolSummaryChars));
            } else if (message != null) {
                projected.add(copyMessage(message, truncate(message.getContent(), HARD_MESSAGE_SUMMARY_CHARS)));
            }
        }
        return projected;
    }

    private Message summarizeToolResult(Message message, int maxChars) {
        if (message == null) {
            return Message.builder().role("tool").content("[Tool result missing]").build();
        }
        String toolName = message.getToolName() != null ? message.getToolName() : "unknown";
        StringBuilder content = new StringBuilder("[Tool result summarized for context budget]\nTool: ")
                .append(toolName)
                .append("\nResult summary: ")
                .append(truncate(message.getContent(), maxChars));
        ToolArtifactReferenceFormatter.appendArtifactRefs(content, message);
        return copyMessage(message, content.toString());
    }

    private List<Message> summarizeLargeMessages(List<Message> messages, int maxChars) {
        List<Message> projected = new ArrayList<>();
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            if (message.getContent() != null && message.getContent().length() > maxChars * 2) {
                projected.add(copyMessage(message, truncate(message.getContent(), maxChars)));
            } else {
                projected.add(message);
            }
        }
        return projected;
    }

    private ProjectedItem rebuild(ProjectedItem item, List<Message> messages, ContextHygieneReport report) {
        int tokens = tokenEstimator.estimateMessages(messages);
        if (tokens < item.estimatedTokens()) {
            report.incrementCompressed();
        }
        return new ProjectedItem(item.item(), messages, item.pinned(), item.score(), tokens);
    }

    private boolean isProtocolCritical(ProjectedItem item, int lastUserOrdinal) {
        return item.item().ordinal() >= lastUserOrdinal || hasUnresolvedToolCall(item.item());
    }

    private boolean hasUnresolvedToolCall(ContextItem item) {
        if (item == null || item.kind() != ContextItemKind.TOOL_INTERACTION || item.messages().isEmpty()) {
            return false;
        }
        Message assistant = item.messages().get(0);
        if (assistant == null || assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty()) {
            return false;
        }
        for (Message.ToolCall toolCall : assistant.getToolCalls()) {
            if (toolCall != null && !hasToolResult(item, toolCall.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasToolResult(ContextItem item, String toolCallId) {
        if (toolCallId == null) {
            return false;
        }
        for (Message message : item.messages()) {
            if (message != null && message.isToolMessage() && toolCallId.equals(message.getToolCallId())) {
                return true;
            }
        }
        return false;
    }

    private int findLastUserOrdinal(List<ProjectedItem> items) {
        for (int index = items.size() - 1; index >= 0; index--) {
            ProjectedItem item = items.get(index);
            if (item.item().kind() == ContextItemKind.USER) {
                return item.item().ordinal();
            }
        }
        return items.isEmpty() ? 0 : items.get(items.size() - 1).item().ordinal();
    }

    private int estimate(List<ProjectedItem> items) {
        List<Message> messages = new ArrayList<>();
        for (ProjectedItem item : items) {
            messages.addAll(item.messages());
        }
        return tokenEstimator.estimateMessages(messages);
    }

    private GarbageReason dropReason(ProjectedItem item) {
        if (item.item().kind() == ContextItemKind.TOOL_INTERACTION) {
            for (Message message : item.item().messages()) {
                GarbageReason reason = garbagePolicy.reasonFor(message);
                if (reason != GarbageReason.BUDGET_EXCEEDED) {
                    return reason;
                }
            }
            return GarbageReason.RAW_TOOL_BLOB;
        }
        return garbagePolicy.reasonFor(item.item().first());
    }

    private Message copyMessage(Message message, String content) {
        if (message == null) {
            return Message.builder().content(content).timestamp(Instant.now()).build();
        }
        return Message.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(content)
                .channelType(message.getChannelType())
                .chatId(message.getChatId())
                .senderId(message.getSenderId())
                .toolCalls(message.getToolCalls())
                .toolCallId(message.getToolCallId())
                .toolName(message.getToolName())
                .metadata(message.getMetadata())
                .timestamp(message.getTimestamp())
                .voiceData(message.getVoiceData())
                .voiceTranscription(message.getVoiceTranscription())
                .audioFormat(message.getAudioFormat())
                .build();
    }

    private String truncate(String content, int maxChars) {
        if (content == null || content.isBlank()) {
            return "<empty>";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, Math.max(0, maxChars)) + "...[truncated]";
    }
}

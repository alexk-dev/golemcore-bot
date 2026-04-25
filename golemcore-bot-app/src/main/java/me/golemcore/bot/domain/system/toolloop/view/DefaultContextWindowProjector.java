package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.context.layer.TokenEstimator;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ContextTokenEstimator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Budgeted, non-destructive projection of conversation history.
 */
public class DefaultContextWindowProjector implements ContextWindowProjector {

    private static final int RECENT_PINNED_ITEMS = 4;
    private static final int TOOL_RESULT_SUMMARY_CHARS = 700;
    private static final int MESSAGE_SUMMARY_CHARS = 1_200;

    private final ContextTokenEstimator tokenEstimator;
    private final ContextGarbagePolicy garbagePolicy;
    private final PinnedContextCompressor pinnedCompressor;

    public DefaultContextWindowProjector(ContextTokenEstimator tokenEstimator, ContextGarbagePolicy garbagePolicy) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        this.garbagePolicy = Objects.requireNonNull(garbagePolicy, "garbagePolicy");
        this.pinnedCompressor = new PinnedContextCompressor(tokenEstimator, garbagePolicy);
    }

    @Override
    public ConversationView project(AgentContext context, ConversationView rawView, ContextBudget budget) {
        ConversationView baseView = rawView != null ? rawView : ConversationView.ofMessages(List.of());
        List<Message> messages = baseView.messages();
        ContextBudget effectiveBudget = budget != null ? budget : ContextBudget.unlimited();
        ContextHygieneReport report = new ContextHygieneReport();
        report.setRawTokens(tokenEstimator.estimateMessages(messages));
        report.setSystemPromptTokens(TokenEstimator.estimate(context != null ? context.getSystemPrompt() : null));
        report.setMemoryTokens(TokenEstimator.estimate(context != null ? context.getMemoryContext() : null));

        List<ContextItem> items = buildItems(messages);
        int lastUserIndex = findLastUserItemIndex(items);
        List<ProjectedItem> preparedItems = prepareItems(items, lastUserIndex, report);
        int preparedTokens = estimateProjected(preparedItems);
        if (preparedTokens <= effectiveBudget.conversationTokens()) {
            return finish(context, baseView, flatten(preparedItems), report);
        }

        List<ProjectedItem> selectedItems = selectWithinBudget(preparedItems, effectiveBudget, report);
        return finish(context, baseView, flatten(selectedItems), report);
    }

    private List<ContextItem> buildItems(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ContextItem> items = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            Message message = messages.get(index);
            if (message != null && message.isAssistantMessage() && message.hasToolCalls()) {
                List<Message> group = new ArrayList<>();
                group.add(message);
                Set<String> toolCallIds = toolCallIds(message);
                int cursor = index + 1;
                while (cursor < messages.size()) {
                    Message candidate = messages.get(cursor);
                    if (candidate == null || !candidate.isToolMessage()
                            || !toolCallIds.contains(candidate.getToolCallId())) {
                        break;
                    }
                    group.add(candidate);
                    cursor++;
                }
                items.add(new ContextItem(items.size(), ContextItemKind.TOOL_INTERACTION, group));
                index = cursor;
                continue;
            }
            items.add(new ContextItem(items.size(), kindOf(message), List.of(message)));
            index++;
        }
        return items;
    }

    private Set<String> toolCallIds(Message message) {
        Set<String> ids = new LinkedHashSet<>();
        if (message.getToolCalls() == null) {
            return ids;
        }
        for (Message.ToolCall toolCall : message.getToolCalls()) {
            if (toolCall != null && toolCall.getId() != null && !toolCall.getId().isBlank()) {
                ids.add(toolCall.getId());
            }
        }
        return ids;
    }

    private ContextItemKind kindOf(Message message) {
        if (message == null) {
            return ContextItemKind.OTHER;
        }
        if (message.isUserMessage()) {
            return ContextItemKind.USER;
        }
        if (message.isAssistantMessage()) {
            return ContextItemKind.ASSISTANT;
        }
        if (message.isToolMessage()) {
            return ContextItemKind.TOOL_RESULT;
        }
        if (message.isSystemMessage()) {
            return ContextItemKind.SYSTEM;
        }
        return ContextItemKind.OTHER;
    }

    private int findLastUserItemIndex(List<ContextItem> items) {
        for (int index = items.size() - 1; index >= 0; index--) {
            if (items.get(index).kind() == ContextItemKind.USER) {
                return index;
            }
        }
        return items.isEmpty() ? -1 : items.size() - 1;
    }

    private List<ProjectedItem> prepareItems(List<ContextItem> items, int lastUserIndex, ContextHygieneReport report) {
        List<ProjectedItem> projected = new ArrayList<>();
        int recentBoundary = Math.max(0, items.size() - RECENT_PINNED_ITEMS);
        for (ContextItem item : items) {
            boolean currentTurn = item.ordinal() >= lastUserIndex;
            boolean pinned = currentTurn || item.ordinal() >= recentBoundary || item.kind() == ContextItemKind.USER
                    && item.ordinal() == lastUserIndex || hasUnresolvedToolCall(item);
            if (pinned) {
                report.incrementPinned();
            }
            List<Message> projectedMessages = projectMessages(item, pinned, report);
            ProjectedItem prepared = new ProjectedItem(item, projectedMessages, pinned,
                    score(item, pinned, currentTurn, items.size()), estimateMessages(projectedMessages));
            projected.add(prepared);
        }
        return projected;
    }

    private boolean hasUnresolvedToolCall(ContextItem item) {
        if (item == null || item.kind() != ContextItemKind.TOOL_INTERACTION || item.messages().isEmpty()) {
            return false;
        }
        Message assistant = item.messages().get(0);
        Set<String> expected = toolCallIds(assistant);
        if (expected.isEmpty()) {
            return false;
        }
        Set<String> actual = new LinkedHashSet<>();
        for (int index = 1; index < item.messages().size(); index++) {
            Message message = item.messages().get(index);
            if (message != null && message.isToolMessage() && message.getToolCallId() != null) {
                actual.add(message.getToolCallId());
            }
        }
        return !actual.containsAll(expected);
    }

    private List<Message> projectMessages(ContextItem item, boolean pinned, ContextHygieneReport report) {
        if (item.kind() == ContextItemKind.TOOL_INTERACTION && !pinned && containsNoisyToolResult(item)) {
            report.incrementCompressed();
            return List.of(summarizeToolInteraction(item));
        }
        if (item.kind() == ContextItemKind.TOOL_RESULT && !pinned && garbagePolicy.isNoisyToolResult(item.first())) {
            report.incrementCompressed();
            return List.of(summarizeOrphanToolResult(item.first()));
        }
        if (!pinned && item.first() != null && item.first().getContent() != null
                && item.first().getContent().length() > MESSAGE_SUMMARY_CHARS * 3) {
            report.incrementCompressed();
            return List.of(truncateMessage(item.first(), MESSAGE_SUMMARY_CHARS));
        }
        return item.messages();
    }

    private boolean containsNoisyToolResult(ContextItem item) {
        for (Message message : item.messages()) {
            if (garbagePolicy.isNoisyToolResult(message)) {
                return true;
            }
        }
        return false;
    }

    private Message summarizeToolInteraction(ContextItem item) {
        Message assistant = item.first();
        StringBuilder content = new StringBuilder();
        content.append("[Previous tool interaction summarized]");
        if (assistant != null && assistant.getContent() != null && !assistant.getContent().isBlank()) {
            content.append("\nAssistant: ").append(truncate(assistant.getContent(), 240));
        }
        if (assistant != null && assistant.getToolCalls() != null) {
            for (Message.ToolCall toolCall : assistant.getToolCalls()) {
                content.append("\nTool: ").append(toolCall.getName() != null ? toolCall.getName() : "unknown");
                Message result = findToolResult(item, toolCall.getId());
                if (result != null) {
                    content.append("\nResult summary: ")
                            .append(truncate(result.getContent(), TOOL_RESULT_SUMMARY_CHARS));
                    ToolArtifactReferenceFormatter.appendArtifactRefs(content, result);
                } else {
                    content.append("\nResult summary: <missing>");
                }
            }
        }
        return Message.builder()
                .id(assistant != null ? assistant.getId() : null)
                .role("assistant")
                .content(content.toString())
                .timestamp(timestampOf(assistant))
                .metadata(assistant != null ? assistant.getMetadata() : null)
                .build();
    }

    private Message findToolResult(ContextItem item, String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        for (Message message : item.messages()) {
            if (message != null && message.isToolMessage() && toolCallId.equals(message.getToolCallId())) {
                return message;
            }
        }
        return null;
    }

    private Message summarizeOrphanToolResult(Message message) {
        String toolName = message.getToolName() != null ? message.getToolName() : "unknown";
        StringBuilder content = new StringBuilder("[Previous tool result summarized]\nTool: " + toolName
                + "\nResult summary: " + truncate(message.getContent(), TOOL_RESULT_SUMMARY_CHARS));
        ToolArtifactReferenceFormatter.appendArtifactRefs(content, message);
        return Message.builder()
                .id(message.getId())
                .role("assistant")
                .content(content.toString())
                .timestamp(message.getTimestamp())
                .metadata(message.getMetadata())
                .build();
    }

    private Message truncateMessage(Message message, int maxChars) {
        return Message.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(truncate(message.getContent(), maxChars))
                .channelType(message.getChannelType())
                .chatId(message.getChatId())
                .senderId(message.getSenderId())
                .metadata(message.getMetadata())
                .timestamp(message.getTimestamp())
                .build();
    }

    private Instant timestampOf(Message message) {
        return message != null ? message.getTimestamp() : null;
    }

    private int score(ContextItem item, boolean pinned, boolean currentTurn, int totalItems) {
        int score = 0;
        if (pinned) {
            score += 100;
        }
        if (currentTurn) {
            score += 25;
        }
        if (item.kind() == ContextItemKind.USER) {
            score += 15;
        }
        int recency = Math.max(0, totalItems - item.ordinal());
        score += Math.max(0, 8 - recency / 2);
        if (item.kind() == ContextItemKind.TOOL_RESULT || item.kind() == ContextItemKind.TOOL_INTERACTION) {
            score -= 10;
        }
        score -= Math.min(20, estimateMessages(item.messages()) / 250);
        return score;
    }

    private List<ProjectedItem> selectWithinBudget(List<ProjectedItem> preparedItems, ContextBudget budget,
            ContextHygieneReport report) {
        int limit = Math.max(1, budget.conversationTokens());
        Set<ProjectedItem> selected = new LinkedHashSet<>();
        int usedTokens = 0;
        for (ProjectedItem item : preparedItems) {
            if (!item.pinned()) {
                continue;
            }
            selected.add(item);
            usedTokens += item.estimatedTokens();
        }
        if (usedTokens > limit) {
            return pinnedCompressor.compressWithinBudget(preparedItems, limit, report);
        }

        List<ProjectedItem> candidates = preparedItems.stream()
                .filter(item -> !item.pinned())
                .sorted(Comparator.comparingInt(ProjectedItem::score).reversed()
                        .thenComparing(item -> item.item().ordinal(), Comparator.reverseOrder()))
                .toList();

        for (ProjectedItem item : candidates) {
            int itemTokens = item.estimatedTokens();
            if (usedTokens + itemTokens <= limit) {
                selected.add(item);
                usedTokens += itemTokens;
            } else {
                report.drop(dropReason(item));
            }
        }

        List<ProjectedItem> ordered = new ArrayList<>();
        for (ProjectedItem item : preparedItems) {
            if (selected.contains(item)) {
                ordered.add(item);
            }
        }
        return ordered;
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

    private ConversationView finish(AgentContext context, ConversationView baseView, List<Message> messages,
            ContextHygieneReport report) {
        int projectedTokens = tokenEstimator.estimateMessages(messages);
        report.setProjectedTokens(projectedTokens);
        report.setHistoryTokens(projectedTokens);
        report.setToolTokens(estimateToolTokens(messages));
        if (context != null) {
            context.setAttribute(ContextAttributes.CONTEXT_HYGIENE_REPORT, report.toMap());
        }
        if (!report.changed()) {
            return baseView;
        }
        List<String> diagnostics = new ArrayList<>(baseView.diagnostics());
        diagnostics.add(report.summary());
        return new ConversationView(messages, diagnostics);
    }

    private int estimateToolTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        List<Message> toolMessages = messages.stream()
                .filter(message -> message != null && (message.isToolMessage() || message.hasToolCalls()))
                .toList();
        return tokenEstimator.estimateMessages(toolMessages);
    }

    private int estimateProjected(List<ProjectedItem> items) {
        return estimateMessages(flatten(items));
    }

    private int estimateMessages(List<Message> messages) {
        return tokenEstimator.estimateMessages(messages);
    }

    private List<Message> flatten(List<ProjectedItem> items) {
        List<Message> messages = new ArrayList<>();
        for (ProjectedItem item : items) {
            messages.addAll(item.messages());
        }
        return messages;
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

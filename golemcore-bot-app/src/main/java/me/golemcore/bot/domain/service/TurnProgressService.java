package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.ProgressUpdateType;
import me.golemcore.bot.domain.model.ToolExecutionTrace;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns user-facing live progress updates for a single tool-loop turn.
 */
@Service
public class TurnProgressService {

    private final RuntimeConfigService runtimeConfigService;
    private final ChannelRuntimePort channelRuntimePort;
    private final ToolExecutionTraceExtractor traceExtractor;
    private final TurnProgressSummaryService summaryService;
    private final Clock clock;

    public TurnProgressService(RuntimeConfigService runtimeConfigService,
            ChannelRuntimePort channelRuntimePort,
            ToolExecutionTraceExtractor traceExtractor,
            TurnProgressSummaryService summaryService,
            Clock clock) {
        this.runtimeConfigService = runtimeConfigService;
        this.channelRuntimePort = channelRuntimePort;
        this.traceExtractor = traceExtractor;
        this.summaryService = summaryService;
        this.clock = clock;
    }

    public void maybePublishIntent(AgentContext context, LlmResponse response) {
        if (!isEnabled() || !runtimeConfigService.isTurnProgressIntentEnabled() || context == null || response == null
                || !response.hasToolCalls()) {
            return;
        }
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.TURN_PROGRESS_INTENT_PUBLISHED))) {
            return;
        }

        String intentText = buildIntentText(context, response);
        if (intentText.isBlank()) {
            return;
        }

        publish(context, new ProgressUpdate(
                ProgressUpdateType.INTENT,
                intentText,
                Map.of("toolCount", response.getToolCalls().size())));
        context.setAttribute(ContextAttributes.TURN_PROGRESS_INTENT_PUBLISHED, true);
    }

    public void recordToolExecution(AgentContext context, Message.ToolCall toolCall, ToolExecutionOutcome outcome,
            long durationMs) {
        if (!isEnabled() || context == null || toolCall == null) {
            return;
        }

        ToolExecutionTrace trace = traceExtractor.extract(toolCall, outcome, durationMs);
        List<ToolExecutionTrace> buffer = getOrCreateBuffer(context);
        if (!buffer.isEmpty() && shouldFlushBeforeAdding(context, buffer, trace, clock.instant())) {
            flushBufferedTools(context, "batch_rotation");
        }

        buffer.add(trace);
        if (buffer.size() == 1) {
            context.setAttribute(ContextAttributes.TURN_PROGRESS_BATCH_STARTED_AT, clock.instant());
        }

        if (buffer.size() >= runtimeConfigService.getTurnProgressBatchSize()
                || hasExceededSilence(context, clock.instant())) {
            flushBufferedTools(context, "batch_full");
        }
    }

    public void flushBufferedTools(AgentContext context, String reason) {
        if (!isEnabled() || context == null) {
            return;
        }

        List<ToolExecutionTrace> buffer = getOrCreateBuffer(context);
        if (buffer.isEmpty()) {
            return;
        }

        List<ToolExecutionTrace> snapshot = List.copyOf(buffer);
        String summary = summaryService.summarize(context, snapshot);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", reason);
        metadata.put("toolCount", snapshot.size());
        metadata.put("families", new ArrayList<>(collectFamilies(snapshot)));

        publish(context, new ProgressUpdate(ProgressUpdateType.SUMMARY, summary, metadata));
        buffer.clear();
        context.setAttribute(ContextAttributes.TURN_PROGRESS_BATCH_STARTED_AT, null);
    }

    public void clearProgress(AgentContext context) {
        if (!isEnabled() || context == null) {
            return;
        }
        getOrCreateBuffer(context).clear();
        context.setAttribute(ContextAttributes.TURN_PROGRESS_BATCH_STARTED_AT, null);
        publish(context, new ProgressUpdate(ProgressUpdateType.CLEAR, "", Map.of()));
    }

    public void publishSummary(AgentContext context, String text, Map<String, Object> metadata) {
        if (!isEnabled() || context == null || text == null || text.isBlank()) {
            return;
        }
        publish(context, new ProgressUpdate(ProgressUpdateType.SUMMARY, text, metadata));
    }

    private boolean shouldFlushBeforeAdding(AgentContext context, List<ToolExecutionTrace> buffer,
            ToolExecutionTrace nextTrace, Instant now) {
        String currentFamily = buffer.get(0).family();
        if (!currentFamily.equals(nextTrace.family())) {
            return true;
        }
        return hasExceededSilence(context, now);
    }

    private boolean hasExceededSilence(AgentContext context, Instant now) {
        Instant batchStartedAt = context.getAttribute(ContextAttributes.TURN_PROGRESS_BATCH_STARTED_AT);
        if (batchStartedAt == null) {
            return false;
        }
        return !now.isBefore(batchStartedAt.plus(runtimeConfigService.getTurnProgressMaxSilence()));
    }

    private String buildIntentText(AgentContext context, LlmResponse response) {
        String assistantPreface = response.getContent();
        if (assistantPreface != null && !assistantPreface.isBlank()) {
            return truncate(cleanWhitespace(assistantPreface), 240);
        }

        String latestUserMessage = findLatestUserMessage(context);
        String nextAction = summarizePlannedActions(response.getToolCalls());
        if (latestUserMessage != null && !latestUserMessage.isBlank()) {
            return "Working on: " + truncate(cleanWhitespace(latestUserMessage), 140) + ". Next I'll " + nextAction
                    + ".";
        }
        return "I'll " + nextAction + ".";
    }

    private String summarizePlannedActions(List<Message.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "inspect the current state and summarize what changes";
        }
        Set<String> families = new LinkedHashSet<>();
        for (Message.ToolCall toolCall : toolCalls) {
            String toolName = toolCall != null ? toolCall.getName() : null;
            String family = toolName != null ? traceExtractor.extract(toolCall, null, 0L).family() : "tool";
            families.add(family);
            if (families.size() >= 3) {
                break;
            }
        }
        List<String> actions = new ArrayList<>();
        for (String family : families) {
            actions.add(switch (family) {
            case "shell" -> "run a few shell commands";
            case "filesystem" -> "inspect the workspace files";
            case "search" -> "look up current information";
            case "browse" -> "inspect web content";
            default -> "use a few targeted tools";
            });
        }
        if (actions.isEmpty()) {
            return "inspect the current state and summarize what changes";
        }
        if (actions.size() == 1) {
            return actions.get(0);
        }
        if (actions.size() == 2) {
            return actions.get(0) + " and " + actions.get(1);
        }
        return actions.get(0) + ", " + actions.get(1) + ", and " + actions.get(2);
    }

    private String findLatestUserMessage(AgentContext context) {
        List<Message> messages = context != null ? context.getMessages() : null;
        if (messages == null) {
            return null;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message != null && message.isUserMessage() && message.getContent() != null
                    && !message.getContent().isBlank()) {
                return message.getContent();
            }
        }
        return null;
    }

    private List<String> collectFamilies(List<ToolExecutionTrace> traces) {
        Set<String> families = new LinkedHashSet<>();
        for (ToolExecutionTrace trace : traces) {
            families.add(trace.family());
        }
        return List.copyOf(families);
    }

    private void publish(AgentContext context, ProgressUpdate update) {
        if (context == null || context.getSession() == null) {
            return;
        }
        String chatId = SessionIdentitySupport.resolveTransportChatId(context.getSession());
        String channelType = context.getSession().getChannelType();
        if (chatId == null || chatId.isBlank() || channelType == null || channelType.isBlank()) {
            return;
        }
        ChannelDeliveryPort channel = channelRuntimePort.findChannel(channelType).orElse(null);
        if (channel == null) {
            return;
        }
        channel.sendProgressUpdate(chatId, enrichHiveMetadata(context, update));
    }

    @SuppressWarnings("unchecked")
    private List<ToolExecutionTrace> getOrCreateBuffer(AgentContext context) {
        Object existing = context.getAttribute(ContextAttributes.TURN_PROGRESS_BUFFER);
        if (existing instanceof List<?> list) {
            return (List<ToolExecutionTrace>) list;
        }
        List<ToolExecutionTrace> created = new ArrayList<>();
        context.setAttribute(ContextAttributes.TURN_PROGRESS_BUFFER, created);
        return created;
    }

    private boolean isEnabled() {
        return runtimeConfigService != null && runtimeConfigService.isTurnProgressUpdatesEnabled();
    }

    private String cleanWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private ProgressUpdate enrichHiveMetadata(AgentContext context, ProgressUpdate update) {
        if (context == null || update == null) {
            return update;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(update.metadata() != null ? update.metadata() : Map.of());
        HiveMetadataSupport.copyContextAttributes(context, metadata);
        if (metadata.equals(update.metadata())) {
            return update;
        }
        return new ProgressUpdate(update.type(), update.text(), metadata);
    }
}

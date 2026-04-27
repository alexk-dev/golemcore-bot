package me.golemcore.bot.domain.progress;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolExecutionTrace;
import me.golemcore.bot.port.outbound.LlmPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Builds short user-facing summaries for batched tool executions.
 */
@Service
@Slf4j
public class TurnProgressSummaryService {

    private static final String SYSTEM_PROMPT = """
            Summarize the agent's recent work for the user in 1-2 short sentences.
            Focus on what was done and what it helps with.
            Group repetitive tool calls together.
            Do not reveal chain-of-thought, hidden reasoning, or raw logs.
            Do not use bullet points.
            Output only the summary text.""";
    private static final int MAX_SUMMARY_TOKENS = 160;

    private final LlmPort llmPort;
    private final RuntimeConfigService runtimeConfigService;
    private final Clock clock;

    public TurnProgressSummaryService(
            LlmPort llmPort,
            RuntimeConfigService runtimeConfigService,
            Clock clock) {
        this.llmPort = llmPort;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
    }

    public String summarize(AgentContext context, List<ToolExecutionTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return "";
        }

        String fallback = buildFallbackSummary(traces);
        if (llmPort == null || !llmPort.isAvailable()) {
            return fallback;
        }

        LlmRequest request = LlmRequest.builder()
                .model(runtimeConfigService.getBalancedModel())
                .reasoningEffort(runtimeConfigService.getBalancedModelReasoning())
                .systemPrompt(SYSTEM_PROMPT)
                .messages(List.of(Message.builder()
                        .role("user")
                        .content(buildPrompt(context, traces))
                        .build()))
                .maxTokens(MAX_SUMMARY_TOKENS)
                .temperature(0.2)
                .sessionId(context != null && context.getSession() != null ? context.getSession().getId() : null)
                .traceId(context != null && context.getTraceContext() != null ? context.getTraceContext().getTraceId()
                        : null)
                .traceSpanId(
                        context != null && context.getTraceContext() != null ? context.getTraceContext().getSpanId()
                                : null)
                .traceParentSpanId(
                        context != null && context.getTraceContext() != null
                                ? context.getTraceContext().getParentSpanId()
                                : null)
                .traceRootKind(
                        context != null && context.getTraceContext() != null ? context.getTraceContext().getRootKind()
                                : null)
                .build();

        try {
            long start = clock.millis();
            int timeoutMs = runtimeConfigService.getTurnProgressSummaryTimeoutMs();
            LlmResponse response = llmPort.chat(request).get(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = clock.millis() - start;
            String content = response != null ? response.getContent() : null;
            if (content == null || content.isBlank()) {
                log.debug("[TurnProgress] Summary model returned empty content");
                return fallback;
            }
            log.debug("[TurnProgress] Summarized {} tool traces in {}ms", traces.size(), elapsed);
            return content.trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException | TimeoutException e) {
            log.debug("[TurnProgress] Falling back to deterministic summary: {}", e.getMessage());
            return fallback;
        }
    }

    String buildFallbackSummary(List<ToolExecutionTrace> traces) {
        Map<String, Integer> countsByFamily = new LinkedHashMap<>();
        int failedCount = 0;
        for (ToolExecutionTrace trace : traces) {
            countsByFamily.merge(trace.family(), 1, Integer::sum);
            if (!trace.success()) {
                failedCount++;
            }
        }

        StringJoiner joiner = new StringJoiner(", ");
        countsByFamily.forEach((family, count) -> joiner.add(describeFamilyCount(family, count)));

        StringBuilder summary = new StringBuilder();
        if (joiner.length() > 0) {
            summary.append("Worked through ").append(joiner).append(".");
        } else {
            summary.append("Worked through recent tool steps.");
        }

        String detail = traces.get(traces.size() - 1).action();
        if (detail != null && !detail.isBlank()) {
            summary.append(" Latest step: ").append(capitalize(detail)).append(".");
        }
        if (failedCount > 0) {
            summary.append(" ").append(failedCount == 1
                    ? "One step failed and will be handled in the next pass."
                    : failedCount + " steps failed and will be handled in the next pass.");
        }
        return summary.toString().trim();
    }

    private String buildPrompt(AgentContext context, List<ToolExecutionTrace> traces) {
        String latestUserMessage = findLatestUserMessage(context);
        StringBuilder prompt = new StringBuilder();
        if (latestUserMessage != null && !latestUserMessage.isBlank()) {
            prompt.append("User request:\n")
                    .append(latestUserMessage)
                    .append("\n\n");
        }
        prompt.append("Recent tool executions:\n");
        for (ToolExecutionTrace trace : traces) {
            prompt.append("- ")
                    .append(trace.action())
                    .append(" | family=")
                    .append(trace.family())
                    .append(" | success=")
                    .append(trace.success())
                    .append(" | durationMs=")
                    .append(trace.durationMs())
                    .append('\n');
        }
        return prompt.toString();
    }

    private String findLatestUserMessage(AgentContext context) {
        List<Message> messages = context != null ? context.getMessages() : null;
        if (messages == null || messages.isEmpty()) {
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

    private String describeFamilyCount(String family, int count) {
        return switch (family) {
        case "shell" -> count + (count == 1 ? " shell command" : " shell commands");
        case "filesystem" -> count + (count == 1 ? " file step" : " file steps");
        case "search" -> count + (count == 1 ? " search" : " searches");
        case "browse" -> count + (count == 1 ? " web check" : " web checks");
        default -> count + (count == 1 ? " tool step" : " tool steps");
        };
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}

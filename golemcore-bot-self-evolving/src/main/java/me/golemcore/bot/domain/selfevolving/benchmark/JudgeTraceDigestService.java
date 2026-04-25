package me.golemcore.bot.domain.selfevolving.benchmark;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.service.StringValueSupport;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds a structured trace digest for LLM judges, extracting the most
 * diagnostic-relevant data from raw trace spans within a token budget.
 */
@Service
public class JudgeTraceDigestService {

    private static final int MAX_ERROR_SPANS = 5;
    private static final int MAX_TOOL_SEQUENCE_LENGTH = 30;
    private static final int MAX_STATUS_MESSAGE_LENGTH = 400;
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 200;
    private static final int MAX_EVENT_NAME_LENGTH = 120;
    private static final int MAX_EVENTS_PER_SPAN = 3;

    /**
     * Produce a multi-section text digest of the trace suitable for inclusion in an
     * LLM judge prompt. Returns empty string when no trace data is available.
     */
    public String buildDigest(RunRecord runRecord, TraceRecord traceRecord) {
        if (traceRecord == null || traceRecord.getSpans() == null || traceRecord.getSpans().isEmpty()) {
            return "";
        }

        StringBuilder digest = new StringBuilder();
        List<TraceSpanRecord> spans = traceRecord.getSpans();

        appendRunOverview(digest, runRecord, traceRecord, spans);
        appendToolCallSequence(digest, spans);
        appendErrorSpans(digest, spans, traceRecord.getTraceId());
        appendLlmSpanSummary(digest, spans);
        appendSpanKindBreakdown(digest, spans);
        appendKeyEvents(digest, spans);

        return digest.toString();
    }

    private void appendRunOverview(StringBuilder digest, RunRecord runRecord, TraceRecord traceRecord,
            List<TraceSpanRecord> spans) {
        digest.append("--- RUN OVERVIEW ---\n");

        if (runRecord != null) {
            if (runRecord.getStartedAt() != null && runRecord.getCompletedAt() != null) {
                Duration duration = Duration.between(runRecord.getStartedAt(), runRecord.getCompletedAt());
                digest.append("duration=").append(formatDuration(duration)).append('\n');
            }
            if (runRecord.getAppliedTacticIds() != null && !runRecord.getAppliedTacticIds().isEmpty()) {
                digest.append("appliedTactics=").append(String.join(", ", runRecord.getAppliedTacticIds()))
                        .append('\n');
            }
            if (runRecord.getMetrics() != null && !runRecord.getMetrics().isEmpty()) {
                for (Map.Entry<String, Object> entry : runRecord.getMetrics().entrySet()) {
                    digest.append("metric.").append(entry.getKey()).append('=')
                            .append(entry.getValue()).append('\n');
                }
            }
        }

        long totalSpans = spans.size();
        long errorSpans = spans.stream()
                .filter(span -> span.getStatusCode() == TraceStatusCode.ERROR)
                .count();
        long toolSpans = spans.stream()
                .filter(span -> span.getKind() == TraceSpanKind.TOOL)
                .count();
        long llmSpans = spans.stream()
                .filter(span -> span.getKind() == TraceSpanKind.LLM)
                .count();

        digest.append("totalSpans=").append(totalSpans).append('\n');
        digest.append("errorSpans=").append(errorSpans).append('\n');
        digest.append("toolSpans=").append(toolSpans).append('\n');
        digest.append("llmSpans=").append(llmSpans).append('\n');
        if (traceRecord.isTruncated()) {
            digest.append("traceTruncated=true\n");
        }
        digest.append('\n');
    }

    private void appendToolCallSequence(StringBuilder digest, List<TraceSpanRecord> spans) {
        List<TraceSpanRecord> toolSpans = spans.stream()
                .filter(span -> span.getKind() == TraceSpanKind.TOOL)
                .toList();
        if (toolSpans.isEmpty()) {
            return;
        }

        digest.append("--- TOOL CALL SEQUENCE ---\n");
        int limit = Math.min(toolSpans.size(), MAX_TOOL_SEQUENCE_LENGTH);
        for (int i = 0; i < limit; i++) {
            TraceSpanRecord span = toolSpans.get(i);
            String status = span.getStatusCode() == TraceStatusCode.ERROR ? "ERROR" : "OK";
            String name = defaultIfBlank(span.getName(), span.getSpanId());
            digest.append(i + 1).append(". ").append(name);
            digest.append(" [").append(status).append("]");
            digest.append(" spanId=").append(span.getSpanId());
            if (span.getStartedAt() != null && span.getEndedAt() != null) {
                Duration spanDuration = Duration.between(span.getStartedAt(), span.getEndedAt());
                digest.append(" (").append(formatDuration(spanDuration)).append(')');
            }
            String toolName = resolveSpanAttribute(span, "tool.name");
            if (!StringValueSupport.isBlank(toolName)) {
                digest.append(" tool=").append(toolName);
            }
            String toolInput = resolveSpanAttribute(span, "tool.input");
            if (!StringValueSupport.isBlank(toolInput)) {
                digest.append(" input=").append(truncate(toolInput, MAX_ATTRIBUTE_VALUE_LENGTH));
            }
            if (span.getStatusCode() == TraceStatusCode.ERROR && !StringValueSupport.isBlank(span.getStatusMessage())) {
                digest.append(" -> ").append(truncate(span.getStatusMessage(), MAX_STATUS_MESSAGE_LENGTH));
            }
            digest.append('\n');
        }
        if (toolSpans.size() > MAX_TOOL_SEQUENCE_LENGTH) {
            digest.append("... and ").append(toolSpans.size() - MAX_TOOL_SEQUENCE_LENGTH).append(" more tool calls\n");
        }
        digest.append('\n');
    }

    private void appendErrorSpans(StringBuilder digest, List<TraceSpanRecord> spans, String traceId) {
        List<TraceSpanRecord> errorSpans = spans.stream()
                .filter(span -> span.getStatusCode() == TraceStatusCode.ERROR)
                .limit(MAX_ERROR_SPANS)
                .toList();
        if (errorSpans.isEmpty()) {
            return;
        }

        digest.append("--- ERROR DETAILS ---\n");
        for (TraceSpanRecord span : errorSpans) {
            digest.append("spanId=").append(span.getSpanId()).append('\n');
            digest.append("  name=").append(defaultIfBlank(span.getName(), "unknown")).append('\n');
            digest.append("  kind=").append(span.getKind()).append('\n');
            if (!StringValueSupport.isBlank(span.getStatusMessage())) {
                digest.append("  error=").append(truncate(span.getStatusMessage(), MAX_STATUS_MESSAGE_LENGTH))
                        .append('\n');
            }
            appendDiagnosticAttributes(digest, span);
            appendSpanEvents(digest, span);
        }
        long totalErrors = spans.stream()
                .filter(span -> span.getStatusCode() == TraceStatusCode.ERROR)
                .count();
        if (totalErrors > MAX_ERROR_SPANS) {
            digest.append("... and ").append(totalErrors - MAX_ERROR_SPANS).append(" more error spans\n");
        }
        digest.append('\n');
    }

    private void appendLlmSpanSummary(StringBuilder digest, List<TraceSpanRecord> spans) {
        List<TraceSpanRecord> llmSpans = spans.stream()
                .filter(span -> span.getKind() == TraceSpanKind.LLM)
                .toList();
        if (llmSpans.isEmpty()) {
            return;
        }

        digest.append("--- LLM CALLS ---\n");
        Map<String, List<TraceSpanRecord>> byModel = new LinkedHashMap<>();
        for (TraceSpanRecord span : llmSpans) {
            String model = resolveSpanAttribute(span, "llm.model", "model_id", "llm.provider", "model");
            String key = StringValueSupport.isBlank(model) ? "unknown" : model;
            byModel.computeIfAbsent(key, ignored -> new ArrayList<>()).add(span);
        }

        for (Map.Entry<String, List<TraceSpanRecord>> entry : byModel.entrySet()) {
            List<TraceSpanRecord> modelSpans = entry.getValue();
            long errorCount = modelSpans.stream()
                    .filter(span -> span.getStatusCode() == TraceStatusCode.ERROR)
                    .count();
            long totalTokens = modelSpans.stream()
                    .mapToLong(span -> resolveNumericAttribute(span, "llm.total_tokens", "llm.usage.total_tokens"))
                    .sum();

            digest.append("model=").append(entry.getKey());
            digest.append(" calls=").append(modelSpans.size());
            if (errorCount > 0) {
                digest.append(" errors=").append(errorCount);
            }
            if (totalTokens > 0) {
                digest.append(" totalTokens=").append(totalTokens);
            }
            digest.append('\n');

            for (TraceSpanRecord span : modelSpans) {
                String tier = resolveSpanAttribute(span, "tier", "tier.name");
                String reasoning = resolveSpanAttribute(span, "reasoning", "llm.reasoning");
                if (!StringValueSupport.isBlank(tier) || !StringValueSupport.isBlank(reasoning)) {
                    digest.append("  spanId=").append(span.getSpanId());
                    if (!StringValueSupport.isBlank(tier)) {
                        digest.append(" tier=").append(tier);
                    }
                    if (!StringValueSupport.isBlank(reasoning)) {
                        digest.append(" reasoning=").append(reasoning);
                    }
                    digest.append('\n');
                }
            }

            List<TraceSpanRecord> llmErrors = modelSpans.stream()
                    .filter(span -> span.getStatusCode() == TraceStatusCode.ERROR)
                    .limit(2)
                    .toList();
            for (TraceSpanRecord errorSpan : llmErrors) {
                if (!StringValueSupport.isBlank(errorSpan.getStatusMessage())) {
                    digest.append("  llmError=")
                            .append(truncate(errorSpan.getStatusMessage(), MAX_STATUS_MESSAGE_LENGTH))
                            .append('\n');
                }
            }
        }
        digest.append('\n');
    }

    private void appendSpanKindBreakdown(StringBuilder digest, List<TraceSpanRecord> spans) {
        Map<TraceSpanKind, Long> kindCounts = spans.stream()
                .filter(span -> span.getKind() != null)
                .collect(Collectors.groupingBy(TraceSpanRecord::getKind, Collectors.counting()));
        if (kindCounts.isEmpty()) {
            return;
        }
        digest.append("--- SPAN BREAKDOWN ---\n");
        for (Map.Entry<TraceSpanKind, Long> entry : kindCounts.entrySet()) {
            long errors = spans.stream()
                    .filter(span -> span.getKind() == entry.getKey()
                            && span.getStatusCode() == TraceStatusCode.ERROR)
                    .count();
            digest.append(entry.getKey()).append(": ").append(entry.getValue());
            if (errors > 0) {
                digest.append(" (").append(errors).append(" errors)");
            }
            digest.append('\n');
        }
        digest.append('\n');
    }

    private void appendKeyEvents(StringBuilder digest, List<TraceSpanRecord> spans) {
        List<String> keyEvents = new ArrayList<>();
        Map<String, Integer> eventCounts = new LinkedHashMap<>();
        for (TraceSpanRecord span : spans) {
            if (span.getEvents() == null) {
                continue;
            }
            span.getEvents().stream()
                    .limit(MAX_EVENTS_PER_SPAN)
                    .forEach(event -> {
                        if (!StringValueSupport.isBlank(event.getName())) {
                            String eventLine = truncate(event.getName(), MAX_EVENT_NAME_LENGTH);
                            if (event.getAttributes() != null && !event.getAttributes().isEmpty()) {
                                String attrSummary = event.getAttributes().entrySet().stream()
                                        .limit(3)
                                        .map(entry -> entry.getKey() + "="
                                                + truncate(String.valueOf(entry.getValue()),
                                                        MAX_ATTRIBUTE_VALUE_LENGTH))
                                        .collect(Collectors.joining(", "));
                                eventLine += " {" + attrSummary + "}";
                            }
                            int count = eventCounts.getOrDefault(eventLine, 0);
                            eventCounts.put(eventLine, count + 1);
                            if (count == 0) {
                                keyEvents.add(eventLine);
                            }
                        }
                    });
        }

        if (keyEvents.isEmpty()) {
            return;
        }
        digest.append("--- KEY EVENTS ---\n");
        int limit = Math.min(keyEvents.size(), 15);
        for (int i = 0; i < limit; i++) {
            String eventLine = keyEvents.get(i);
            int count = eventCounts.getOrDefault(eventLine, 1);
            digest.append("- ").append(eventLine);
            if (count > 1) {
                digest.append(" (x").append(count).append(')');
            }
            digest.append('\n');
        }
        if (keyEvents.size() > limit) {
            digest.append("... and ").append(keyEvents.size() - limit).append(" more events\n");
        }
        digest.append('\n');
    }

    private void appendDiagnosticAttributes(StringBuilder digest, TraceSpanRecord span) {
        if (span.getAttributes() == null || span.getAttributes().isEmpty()) {
            return;
        }
        List<String> diagnosticKeys = List.of(
                "tool.name", "tool.input", "tool.error", "tool.exit_code",
                "llm.model", "llm.provider", "llm.total_tokens",
                "http.status_code", "http.url",
                "exception.type", "exception.message",
                "skill.name", "tier.name");
        for (String key : diagnosticKeys) {
            Object value = span.getAttributes().get(key);
            if (value != null && !StringValueSupport.isBlank(String.valueOf(value))) {
                digest.append("  ").append(key).append('=')
                        .append(truncate(String.valueOf(value), MAX_ATTRIBUTE_VALUE_LENGTH)).append('\n');
            }
        }
    }

    private void appendSpanEvents(StringBuilder digest, TraceSpanRecord span) {
        if (span.getEvents() == null || span.getEvents().isEmpty()) {
            return;
        }
        span.getEvents().stream()
                .limit(MAX_EVENTS_PER_SPAN)
                .forEach(event -> {
                    if (!StringValueSupport.isBlank(event.getName())) {
                        digest.append("  event=").append(truncate(event.getName(), MAX_EVENT_NAME_LENGTH)).append('\n');
                    }
                });
    }

    private String resolveSpanAttribute(TraceSpanRecord span, String... keys) {
        if (span.getAttributes() == null) {
            return null;
        }
        for (String key : keys) {
            Object value = span.getAttributes().get(key);
            if (value != null && !StringValueSupport.isBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private long resolveNumericAttribute(TraceSpanRecord span, String... keys) {
        if (span.getAttributes() == null) {
            return 0L;
        }
        for (String key : keys) {
            Object value = span.getAttributes().get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        return 0L;
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = duration.getSeconds();
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + "m" + seconds + "s";
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\n', ' ').replace('\r', ' ');
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength) + "...";
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringValueSupport.isBlank(value) ? fallback : value;
    }
}

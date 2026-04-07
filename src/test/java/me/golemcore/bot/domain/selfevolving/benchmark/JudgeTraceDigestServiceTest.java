package me.golemcore.bot.domain.selfevolving.benchmark;

import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.trace.TraceEventRecord;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeTraceDigestServiceTest {

    private JudgeTraceDigestService service;

    @BeforeEach
    void setUp() {
        service = new JudgeTraceDigestService();
    }

    // --- buildDigest null / empty guard ---

    @Test
    void shouldReturnEmptyStringWhenTraceRecordIsNull() {
        RunRecord runRecord = RunRecord.builder().id("run-1").build();

        String digest = service.buildDigest(runRecord, null);

        assertEquals("", digest);
    }

    @Test
    void shouldReturnEmptyStringWhenSpansAreNull() {
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(null)
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertEquals("", digest);
    }

    @Test
    void shouldReturnEmptyStringWhenSpansAreEmpty() {
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(Collections.emptyList())
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertEquals("", digest);
    }

    // --- RUN OVERVIEW section ---

    @Test
    void shouldIncludeRunOverviewWithDurationAndMetrics() {
        RunRecord runRecord = RunRecord.builder()
                .id("run-1")
                .startedAt(Instant.parse("2026-04-01T10:00:00Z"))
                .completedAt(Instant.parse("2026-04-01T10:02:30Z"))
                .appliedTacticIds(List.of("tactic-a", "tactic-b"))
                .metrics(new LinkedHashMap<>(Map.of("score", 0.85)))
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(toolSpan("span-1", "exec", TraceStatusCode.OK)))
                .truncated(false)
                .build();

        String digest = service.buildDigest(runRecord, traceRecord);

        assertTrue(digest.contains("--- RUN OVERVIEW ---"));
        assertTrue(digest.contains("duration=2m30s"));
        assertTrue(digest.contains("appliedTactics=tactic-a, tactic-b"));
        assertTrue(digest.contains("metric.score=0.85"));
        assertTrue(digest.contains("totalSpans=1"));
        assertTrue(digest.contains("toolSpans=1"));
        assertFalse(digest.contains("traceTruncated=true"));
    }

    @Test
    void shouldShowTruncatedFlagWhenTraceIsTruncated() {
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(toolSpan("span-1", "exec", TraceStatusCode.OK)))
                .truncated(true)
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("traceTruncated=true"));
    }

    @Test
    void shouldHandleNullRunRecordInOverview() {
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(llmSpan("span-1", "chat", TraceStatusCode.OK, null)))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("--- RUN OVERVIEW ---"));
        assertTrue(digest.contains("totalSpans=1"));
        assertTrue(digest.contains("llmSpans=1"));
        assertFalse(digest.contains("duration="));
    }

    @Test
    void shouldFormatDurationInSecondsWhenUnderOneMinute() {
        RunRecord runRecord = RunRecord.builder()
                .id("run-1")
                .startedAt(Instant.parse("2026-04-01T10:00:00Z"))
                .completedAt(Instant.parse("2026-04-01T10:00:45Z"))
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(toolSpan("span-1", "exec", TraceStatusCode.OK)))
                .build();

        String digest = service.buildDigest(runRecord, traceRecord);

        assertTrue(digest.contains("duration=45s"));
    }

    @Test
    void shouldHandleRunRecordWithNullTimestamps() {
        RunRecord runRecord = RunRecord.builder()
                .id("run-1")
                .startedAt(null)
                .completedAt(null)
                .appliedTacticIds(Collections.emptyList())
                .metrics(Collections.emptyMap())
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(toolSpan("span-1", "exec", TraceStatusCode.OK)))
                .build();

        String digest = service.buildDigest(runRecord, traceRecord);

        assertTrue(digest.contains("--- RUN OVERVIEW ---"));
        assertFalse(digest.contains("duration="));
        assertFalse(digest.contains("appliedTactics="));
        assertFalse(digest.contains("metric."));
    }

    // --- TOOL CALL SEQUENCE section ---

    @Test
    void shouldIncludeToolCallSequenceWithStatusAndDuration() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("tool.name", "shell");
        attrs.put("tool.input", "ls -la");
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("tool-1")
                .name("tool.exec")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.OK)
                .startedAt(Instant.parse("2026-04-01T10:00:00Z"))
                .endedAt(Instant.parse("2026-04-01T10:00:03Z"))
                .attributes(attrs)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("--- TOOL CALL SEQUENCE ---"));
        assertTrue(digest.contains("1. tool.exec [OK]"));
        assertTrue(digest.contains("spanId=tool-1"));
        assertTrue(digest.contains("(3s)"));
        assertTrue(digest.contains("tool=shell"));
        assertTrue(digest.contains("input=ls -la"));
    }

    @Test
    void shouldShowErrorMessageForFailedToolSpans() {
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("tool-err")
                .name("tool.exec")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.ERROR)
                .statusMessage("command not found")
                .startedAt(Instant.parse("2026-04-01T10:00:00Z"))
                .endedAt(Instant.parse("2026-04-01T10:00:01Z"))
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("[ERROR]"));
        assertTrue(digest.contains("-> command not found"));
    }

    @Test
    void shouldUseSpanIdWhenNameIsBlankInToolSequence() {
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("tool-no-name")
                .name("  ")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.OK)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("1. tool-no-name [OK]"));
    }

    @Test
    void shouldTruncateToolSequenceAtMaxLength() {
        List<TraceSpanRecord> spans = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            spans.add(toolSpan("tool-" + i, "step-" + i, TraceStatusCode.OK));
        }
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(spans)
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("30. step-29 [OK]"));
        assertFalse(digest.contains("31. step-30"));
        assertTrue(digest.contains("... and 5 more tool calls"));
    }

    @Test
    void shouldOmitToolCallSectionWhenNoToolSpans() {
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(llmSpan("span-1", "chat", TraceStatusCode.OK, null)))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertFalse(digest.contains("--- TOOL CALL SEQUENCE ---"));
    }

    // --- ERROR DETAILS section ---

    @Test
    void shouldIncludeErrorDetailsForErrorSpans() {
        TraceSpanRecord errorSpan = TraceSpanRecord.builder()
                .spanId("err-1")
                .name("failing-step")
                .kind(TraceSpanKind.INTERNAL)
                .statusCode(TraceStatusCode.ERROR)
                .statusMessage("null pointer")
                .attributes(new LinkedHashMap<>(Map.of("exception.type", "NPE", "exception.message", "at line 42")))
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(errorSpan))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("--- ERROR DETAILS ---"));
        assertTrue(digest.contains("spanId=err-1"));
        assertTrue(digest.contains("name=failing-step"));
        assertTrue(digest.contains("kind=INTERNAL"));
        assertTrue(digest.contains("error=null pointer"));
        assertTrue(digest.contains("exception.type=NPE"));
        assertTrue(digest.contains("exception.message=at line 42"));
    }

    @Test
    void shouldLimitErrorSpansToMax() {
        List<TraceSpanRecord> spans = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            spans.add(TraceSpanRecord.builder()
                    .spanId("err-" + i)
                    .name("error-step-" + i)
                    .kind(TraceSpanKind.INTERNAL)
                    .statusCode(TraceStatusCode.ERROR)
                    .statusMessage("failure " + i)
                    .build());
        }
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(spans)
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("spanId=err-4"));
        assertFalse(digest.contains("spanId=err-5"));
        assertTrue(digest.contains("... and 3 more error spans"));
    }

    @Test
    void shouldOmitErrorSectionWhenNoErrors() {
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(toolSpan("span-1", "ok-step", TraceStatusCode.OK)))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertFalse(digest.contains("--- ERROR DETAILS ---"));
    }

    @Test
    void shouldIncludeEventsInErrorSpans() {
        TraceEventRecord event = TraceEventRecord.builder()
                .name("exception.stacktrace")
                .build();
        TraceSpanRecord errorSpan = TraceSpanRecord.builder()
                .spanId("err-evt")
                .name("error-with-event")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.ERROR)
                .statusMessage("boom")
                .events(List.of(event))
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(errorSpan))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("event=exception.stacktrace"));
    }

    // --- LLM CALLS section ---

    @Test
    void shouldIncludeLlmSpanSummaryGroupedByModel() {
        TraceSpanRecord llm1 = llmSpan("llm-1", "chat-1", TraceStatusCode.OK,
                new LinkedHashMap<>(Map.of("llm.model", "gpt-4", "llm.total_tokens", 500)));
        TraceSpanRecord llm2 = llmSpan("llm-2", "chat-2", TraceStatusCode.OK,
                new LinkedHashMap<>(Map.of("llm.model", "gpt-4", "llm.total_tokens", 300)));
        TraceSpanRecord llm3 = llmSpan("llm-3", "chat-3", TraceStatusCode.OK,
                new LinkedHashMap<>(Map.of("llm.model", "claude-3", "llm.total_tokens", 1000)));
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(llm1, llm2, llm3))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("--- LLM CALLS ---"));
        assertTrue(digest.contains("model=gpt-4 calls=2"));
        assertTrue(digest.contains("totalTokens=800"));
        assertTrue(digest.contains("model=claude-3 calls=1"));
        assertTrue(digest.contains("totalTokens=1000"));
    }

    @Test
    void shouldShowLlmErrorCountAndMessages() {
        TraceSpanRecord llmErr = TraceSpanRecord.builder()
                .spanId("llm-err")
                .name("chat-err")
                .kind(TraceSpanKind.LLM)
                .statusCode(TraceStatusCode.ERROR)
                .statusMessage("rate limited")
                .attributes(new LinkedHashMap<>(Map.of("llm.model", "gpt-4")))
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(llmErr))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("errors=1"));
        assertTrue(digest.contains("llmError=rate limited"));
    }

    @Test
    void shouldUseUnknownModelWhenNoModelAttribute() {
        TraceSpanRecord llm = llmSpan("llm-1", "chat", TraceStatusCode.OK, new LinkedHashMap<>());
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(llm))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("model=unknown calls=1"));
    }

    @Test
    void shouldIncludeTierAndReasoningAnnotations() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("llm.model", "gpt-4");
        attrs.put("tier.name", "premium");
        attrs.put("reasoning", "complex query");
        TraceSpanRecord llm = TraceSpanRecord.builder()
                .spanId("llm-tier")
                .name("chat")
                .kind(TraceSpanKind.LLM)
                .statusCode(TraceStatusCode.OK)
                .attributes(attrs)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(llm))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("tier=premium"));
        assertTrue(digest.contains("reasoning=complex query"));
    }

    @Test
    void shouldOmitLlmSectionWhenNoLlmSpans() {
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(toolSpan("span-1", "exec", TraceStatusCode.OK)))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertFalse(digest.contains("--- LLM CALLS ---"));
    }

    @Test
    void shouldResolveTotalTokensFromAlternativeKey() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("llm.model", "gpt-4");
        attrs.put("llm.usage.total_tokens", 750);
        TraceSpanRecord llm = TraceSpanRecord.builder()
                .spanId("llm-alt")
                .name("chat")
                .kind(TraceSpanKind.LLM)
                .statusCode(TraceStatusCode.OK)
                .attributes(attrs)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(llm))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("totalTokens=750"));
    }

    // --- SPAN BREAKDOWN section ---

    @Test
    void shouldIncludeSpanKindBreakdownWithErrorCounts() {
        List<TraceSpanRecord> spans = List.of(
                toolSpan("t-1", "exec-1", TraceStatusCode.OK),
                toolSpan("t-2", "exec-2", TraceStatusCode.ERROR),
                llmSpan("l-1", "chat", TraceStatusCode.OK, null),
                TraceSpanRecord.builder()
                        .spanId("i-1")
                        .name("internal")
                        .kind(TraceSpanKind.INTERNAL)
                        .statusCode(TraceStatusCode.OK)
                        .build());
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(spans)
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("--- SPAN BREAKDOWN ---"));
        assertTrue(digest.contains("TOOL: 2 (1 errors)"));
        assertTrue(digest.contains("LLM: 1"));
        assertTrue(digest.contains("INTERNAL: 1"));
    }

    @Test
    void shouldOmitBreakdownWhenAllSpansHaveNullKind() {
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("null-kind")
                .name("mystery")
                .kind(null)
                .statusCode(TraceStatusCode.OK)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertFalse(digest.contains("--- SPAN BREAKDOWN ---"));
    }

    // --- KEY EVENTS section ---

    @Test
    void shouldIncludeKeyEventsWithAttributes() {
        Map<String, Object> eventAttrs = new LinkedHashMap<>();
        eventAttrs.put("level", "warn");
        eventAttrs.put("code", 429);
        TraceEventRecord event = TraceEventRecord.builder()
                .name("rate_limit_hit")
                .attributes(eventAttrs)
                .build();
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("span-evt")
                .name("some-span")
                .kind(TraceSpanKind.INTERNAL)
                .statusCode(TraceStatusCode.OK)
                .events(List.of(event))
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("--- KEY EVENTS ---"));
        assertTrue(digest.contains("- rate_limit_hit {level=warn, code=429}"));
    }

    @Test
    void shouldDeduplicateEventsAndShowCount() {
        TraceEventRecord event = TraceEventRecord.builder().name("retry").build();
        TraceSpanRecord span1 = TraceSpanRecord.builder()
                .spanId("s-1")
                .name("a")
                .kind(TraceSpanKind.INTERNAL)
                .statusCode(TraceStatusCode.OK)
                .events(List.of(event))
                .build();
        TraceSpanRecord span2 = TraceSpanRecord.builder()
                .spanId("s-2")
                .name("b")
                .kind(TraceSpanKind.INTERNAL)
                .statusCode(TraceStatusCode.OK)
                .events(List.of(event))
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span1, span2))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("- retry (x2)"));
    }

    @Test
    void shouldOmitKeyEventsSectionWhenNoEvents() {
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("no-evt")
                .name("span")
                .kind(TraceSpanKind.INTERNAL)
                .statusCode(TraceStatusCode.OK)
                .events(Collections.emptyList())
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertFalse(digest.contains("--- KEY EVENTS ---"));
    }

    @Test
    void shouldSkipEventsWithBlankNames() {
        TraceEventRecord blankEvent = TraceEventRecord.builder().name("  ").build();
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("s-blank")
                .name("span")
                .kind(TraceSpanKind.INTERNAL)
                .statusCode(TraceStatusCode.OK)
                .events(List.of(blankEvent))
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertFalse(digest.contains("--- KEY EVENTS ---"));
    }

    @Test
    void shouldHandleNullEventsListOnSpan() {
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("null-evt")
                .name("span")
                .kind(TraceSpanKind.INTERNAL)
                .statusCode(TraceStatusCode.OK)
                .events(null)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertFalse(digest.contains("--- KEY EVENTS ---"));
    }

    @Test
    void shouldTruncateKeyEventsListAtFifteen() {
        List<TraceSpanRecord> spans = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TraceEventRecord event = TraceEventRecord.builder()
                    .name("event-" + i)
                    .build();
            spans.add(TraceSpanRecord.builder()
                    .spanId("s-" + i)
                    .name("span-" + i)
                    .kind(TraceSpanKind.INTERNAL)
                    .statusCode(TraceStatusCode.OK)
                    .events(List.of(event))
                    .build());
        }
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(spans)
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("- event-14"));
        assertFalse(digest.contains("- event-15"));
        assertTrue(digest.contains("... and 5 more events"));
    }

    // --- truncation and formatting edge cases ---

    @Test
    void shouldTruncateLongAttributeValues() {
        String longInput = "x".repeat(300);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("tool.name", "shell");
        attrs.put("tool.input", longInput);
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("tool-long")
                .name("exec")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.OK)
                .attributes(attrs)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        // MAX_ATTRIBUTE_VALUE_LENGTH = 200, plus "..."
        assertTrue(digest.contains("input=" + "x".repeat(200) + "..."));
    }

    @Test
    void shouldTruncateLongStatusMessage() {
        String longMessage = "e".repeat(500);
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("tool-long-err")
                .name("exec")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.ERROR)
                .statusMessage(longMessage)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("-> " + "e".repeat(400) + "..."));
    }

    @Test
    void shouldReplaceNewlinesInTruncatedValues() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("tool.name", "shell");
        attrs.put("tool.input", "line1\nline2\rline3");
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("tool-nl")
                .name("exec")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.OK)
                .attributes(attrs)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("input=line1 line2 line3"));
    }

    // --- diagnostic attributes ---

    @Test
    void shouldOmitDiagnosticAttributesWhenAttributesAreNull() {
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("err-no-attr")
                .name("failing")
                .kind(TraceSpanKind.INTERNAL)
                .statusCode(TraceStatusCode.ERROR)
                .statusMessage("fail")
                .attributes(null)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("spanId=err-no-attr"));
        assertTrue(digest.contains("error=fail"));
        // Should not crash and should not contain diagnostic keys
        assertFalse(digest.contains("tool.name="));
    }

    @Test
    void shouldIncludeHttpDiagnosticAttributes() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("http.status_code", 503);
        attrs.put("http.url", "https://api.example.com/v1/chat");
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("err-http")
                .name("api-call")
                .kind(TraceSpanKind.OUTBOUND)
                .statusCode(TraceStatusCode.ERROR)
                .statusMessage("service unavailable")
                .attributes(attrs)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("http.status_code=503"));
        assertTrue(digest.contains("http.url=https://api.example.com/v1/chat"));
    }

    // --- full integration ---

    @Test
    void shouldProduceComprehensiveDigestWithAllSections() {
        TraceEventRecord event = TraceEventRecord.builder().name("tool.start").build();
        TraceSpanRecord toolOk = TraceSpanRecord.builder()
                .spanId("t-1")
                .name("exec-1")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.OK)
                .startedAt(Instant.parse("2026-04-01T10:00:00Z"))
                .endedAt(Instant.parse("2026-04-01T10:00:02Z"))
                .attributes(new LinkedHashMap<>(Map.of("tool.name", "shell")))
                .events(List.of(event))
                .build();
        TraceSpanRecord toolErr = TraceSpanRecord.builder()
                .spanId("t-2")
                .name("exec-2")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.ERROR)
                .statusMessage("permission denied")
                .attributes(new LinkedHashMap<>(Map.of("tool.name", "fs")))
                .build();
        TraceSpanRecord llm = TraceSpanRecord.builder()
                .spanId("l-1")
                .name("chat")
                .kind(TraceSpanKind.LLM)
                .statusCode(TraceStatusCode.OK)
                .attributes(new LinkedHashMap<>(Map.of("llm.model", "gpt-4", "llm.total_tokens", 200)))
                .build();
        RunRecord runRecord = RunRecord.builder()
                .id("run-full")
                .startedAt(Instant.parse("2026-04-01T10:00:00Z"))
                .completedAt(Instant.parse("2026-04-01T10:05:00Z"))
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-full")
                .spans(List.of(toolOk, toolErr, llm))
                .build();

        String digest = service.buildDigest(runRecord, traceRecord);

        assertTrue(digest.contains("--- RUN OVERVIEW ---"));
        assertTrue(digest.contains("--- TOOL CALL SEQUENCE ---"));
        assertTrue(digest.contains("--- ERROR DETAILS ---"));
        assertTrue(digest.contains("--- LLM CALLS ---"));
        assertTrue(digest.contains("--- SPAN BREAKDOWN ---"));
        assertTrue(digest.contains("--- KEY EVENTS ---"));
    }

    @Test
    void shouldResolveModelFromAlternativeAttributeKeys() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("model_id", "claude-opus");
        TraceSpanRecord llm = TraceSpanRecord.builder()
                .spanId("llm-alt-key")
                .name("chat")
                .kind(TraceSpanKind.LLM)
                .statusCode(TraceStatusCode.OK)
                .attributes(attrs)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(llm))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("model=claude-opus calls=1"));
    }

    @Test
    void shouldHandleNullAttributesOnLlmSpan() {
        TraceSpanRecord llm = TraceSpanRecord.builder()
                .spanId("llm-null-attr")
                .name("chat")
                .kind(TraceSpanKind.LLM)
                .statusCode(TraceStatusCode.OK)
                .attributes(null)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(llm))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("model=unknown calls=1"));
    }

    @Test
    void shouldLimitEventsPerSpanToMax() {
        List<TraceEventRecord> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(TraceEventRecord.builder().name("evt-" + i).build());
        }
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("s-many-evt")
                .name("span")
                .kind(TraceSpanKind.INTERNAL)
                .statusCode(TraceStatusCode.OK)
                .events(events)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        // MAX_EVENTS_PER_SPAN = 3
        assertTrue(digest.contains("- evt-0"));
        assertTrue(digest.contains("- evt-1"));
        assertTrue(digest.contains("- evt-2"));
        assertFalse(digest.contains("- evt-3"));
    }

    @Test
    void shouldHandleToolSpanWithNullTimestamps() {
        TraceSpanRecord span = TraceSpanRecord.builder()
                .spanId("tool-null-ts")
                .name("exec")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.OK)
                .startedAt(null)
                .endedAt(null)
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(span))
                .build();

        String digest = service.buildDigest(null, traceRecord);

        assertTrue(digest.contains("1. exec [OK]"));
        // No duration parentheses when timestamps are null
        assertFalse(digest.contains("("));
    }

    // --- helpers ---

    private TraceSpanRecord toolSpan(String spanId, String name, TraceStatusCode status) {
        return TraceSpanRecord.builder()
                .spanId(spanId)
                .name(name)
                .kind(TraceSpanKind.TOOL)
                .statusCode(status)
                .build();
    }

    private TraceSpanRecord llmSpan(String spanId, String name, TraceStatusCode status,
            Map<String, Object> attributes) {
        return TraceSpanRecord.builder()
                .spanId(spanId)
                .name(name)
                .kind(TraceSpanKind.LLM)
                .statusCode(status)
                .attributes(attributes != null ? attributes : new LinkedHashMap<>())
                .build();
    }
}

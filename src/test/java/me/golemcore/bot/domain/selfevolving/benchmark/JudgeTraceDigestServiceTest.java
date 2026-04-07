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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeTraceDigestServiceTest {

    private JudgeTraceDigestService service;

    @BeforeEach
    void setUp() {
        service = new JudgeTraceDigestService();
    }

    @Test
    void shouldReturnEmptyDigestWhenTraceIsMissingOrHasNoSpans() {
        assertEquals("", service.buildDigest(null, null));
        assertEquals("", service.buildDigest(null, TraceRecord.builder().spans(List.of()).build()));
    }

    @Test
    void shouldBuildStructuredDigestFromRunAndTraceData() {
        Instant runStart = Instant.parse("2026-04-07T10:00:00Z");
        Instant runEnd = Instant.parse("2026-04-07T10:02:05Z");
        RunRecord runRecord = RunRecord.builder()
                .id("run-1")
                .startedAt(runStart)
                .completedAt(runEnd)
                .appliedTacticIds(List.of("tactic-a", "tactic-b"))
                .metrics(Map.of("score", 0.75, "steps", 4))
                .build();

        TraceSpanRecord toolOk = TraceSpanRecord.builder()
                .spanId("tool-1")
                .name("browser.search")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.OK)
                .startedAt(runStart)
                .endedAt(runStart.plusSeconds(3))
                .attributes(new LinkedHashMap<>(Map.of(
                        "tool.name", "browser.search",
                        "tool.input", "latest telemetry docs",
                        "tier.name", "deep")))
                .events(List.of(TraceEventRecord.builder()
                        .name("tool_started")
                        .attributes(new LinkedHashMap<>(Map.of(
                                "command", "search telemetry",
                                "result", "ok")))
                        .build()))
                .build();

        TraceSpanRecord toolError = TraceSpanRecord.builder()
                .spanId("tool-2")
                .name(" ")
                .kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.ERROR)
                .statusMessage("process failed\nexit code 2")
                .startedAt(runStart.plusSeconds(5))
                .endedAt(runStart.plusSeconds(9))
                .attributes(new LinkedHashMap<>(Map.of(
                        "tool.name", "shell.exec",
                        "tool.error", "exit code 2",
                        "http.status_code", 502,
                        "exception.type", "IllegalStateException")))
                .events(List.of(TraceEventRecord.builder()
                        .name("stderr")
                        .attributes(new LinkedHashMap<>(Map.of("line", "permission denied")))
                        .build()))
                .build();

        TraceSpanRecord llmOk = TraceSpanRecord.builder()
                .spanId("llm-1")
                .name("llm.chat")
                .kind(TraceSpanKind.LLM)
                .statusCode(TraceStatusCode.OK)
                .attributes(new LinkedHashMap<>(Map.of(
                        "llm.model", "openai/gpt-5",
                        "llm.total_tokens", 120)))
                .events(List.of(TraceEventRecord.builder()
                        .name("completion_received")
                        .attributes(new LinkedHashMap<>(Map.of("finish_reason", "stop")))
                        .build()))
                .build();

        TraceSpanRecord llmError = TraceSpanRecord.builder()
                .spanId("llm-2")
                .name("judge.call")
                .kind(TraceSpanKind.LLM)
                .statusCode(TraceStatusCode.ERROR)
                .statusMessage("provider unavailable")
                .attributes(new LinkedHashMap<>(Map.of(
                        "llm.provider", "openrouter",
                        "llm.usage.total_tokens", 30)))
                .build();

        TraceSpanRecord internal = TraceSpanRecord.builder()
                .spanId("internal-1")
                .name("post-analysis")
                .kind(TraceSpanKind.INTERNAL)
                .statusCode(TraceStatusCode.OK)
                .build();

        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(toolOk, toolError, llmOk, llmError, internal))
                .truncated(true)
                .build();

        String digest = service.buildDigest(runRecord, traceRecord);

        assertTrue(digest.contains("--- RUN OVERVIEW ---"));
        assertTrue(digest.contains("duration=2m5s"));
        assertTrue(digest.contains("appliedTactics=tactic-a, tactic-b"));
        assertTrue(digest.contains("metric.score=0.75"));
        assertTrue(digest.contains("metric.steps=4"));
        assertTrue(digest.contains("totalSpans=5"));
        assertTrue(digest.contains("errorSpans=2"));
        assertTrue(digest.contains("traceTruncated=true"));

        assertTrue(digest.contains("--- TOOL CALL SEQUENCE ---"));
        assertTrue(digest.contains("1. browser.search [OK] (3s)"));
        assertTrue(digest.contains("2. tool-2 [ERROR] (4s) -> process failed exit code 2"));

        assertTrue(digest.contains("--- ERROR DETAILS ---"));
        assertTrue(digest.contains("spanId=tool-2"));
        assertTrue(digest.contains("name=unknown"));
        assertTrue(digest.contains("tool.name=shell.exec"));
        assertTrue(digest.contains("tool.error=exit code 2"));
        assertTrue(digest.contains("http.status_code=502"));
        assertTrue(digest.contains("exception.type=IllegalStateException"));
        assertTrue(digest.contains("event=stderr"));

        assertTrue(digest.contains("--- LLM CALLS ---"));
        assertTrue(digest.contains("model=openai/gpt-5 calls=1 totalTokens=120"));
        assertTrue(digest.contains("model=openrouter calls=1 errors=1 totalTokens=30"));
        assertTrue(digest.contains("llmError=provider unavailable"));

        assertTrue(digest.contains("--- SPAN BREAKDOWN ---"));
        assertTrue(digest.contains("TOOL: 2 (1 errors)"));
        assertTrue(digest.contains("LLM: 2 (1 errors)"));
        assertTrue(digest.contains("INTERNAL: 1"));

        assertTrue(digest.contains("--- KEY EVENTS ---"));
        assertTrue(digest.contains("- tool_started {"));
        assertTrue(digest.contains("command=search telemetry"));
        assertTrue(digest.contains("result=ok"));
        assertTrue(digest.contains("- completion_received {finish_reason=stop}"));
    }

    @Test
    void shouldLimitLongToolErrorAndEventSections() {
        Instant start = Instant.parse("2026-04-07T10:00:00Z");
        List<TraceSpanRecord> spans = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            boolean error = index < 6;
            spans.add(TraceSpanRecord.builder()
                    .spanId("tool-" + index)
                    .name("tool-" + index)
                    .kind(TraceSpanKind.TOOL)
                    .statusCode(error ? TraceStatusCode.ERROR : TraceStatusCode.OK)
                    .statusMessage(error ? "failed-" + index : null)
                    .startedAt(start.plusSeconds(index))
                    .endedAt(start.plusSeconds(index + 1))
                    .events(List.of(TraceEventRecord.builder()
                            .name("event-" + index)
                            .attributes(Map.of("index", index))
                            .build()))
                    .build());
        }

        String digest = service.buildDigest(null, TraceRecord.builder()
                .traceId("trace-limit")
                .spans(spans)
                .build());

        assertTrue(digest.contains("... and 1 more tool calls"));
        assertTrue(digest.contains("... and 1 more error spans"));
        assertTrue(digest.contains("... and 16 more events"));
    }
}

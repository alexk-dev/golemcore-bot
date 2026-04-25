package me.golemcore.bot.domain.selfevolving.benchmark;

import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DeterministicJudgeServiceTest {

    private DeterministicJudgeService service;

    @BeforeEach
    void setUp() {
        service = new DeterministicJudgeService();
    }

    @Test
    void shouldScoreFailedToolRunAsUnsuccessfulBeforeLlmJudge() {
        RunRecord runRecord = RunRecord.builder()
                .id("run-1")
                .traceId("trace-1")
                .status("FAILED")
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .spans(List.of(
                        TraceSpanRecord.builder()
                                .spanId("tool-1")
                                .name("tool.exec")
                                .kind(TraceSpanKind.TOOL)
                                .statusCode(TraceStatusCode.ERROR)
                                .statusMessage("tool failed")
                                .startedAt(Instant.parse("2026-03-31T14:00:00Z"))
                                .endedAt(Instant.parse("2026-03-31T14:00:02Z"))
                                .build()))
                .truncated(true)
                .build();

        RunVerdict verdict = service.evaluate(runRecord, traceRecord);

        assertEquals("FAILED", verdict.getOutcomeStatus());
        assertFalse(verdict.getProcessFindings().isEmpty());
        assertFalse(verdict.getEvidenceRefs().isEmpty());
    }

    @Test
    void shouldClassifyNonToolErrorsAsSpanErrorsAndFallbackToSpanIdWhenNameIsBlank() {
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-2")
                .spans(List.of(
                        TraceSpanRecord.builder()
                                .spanId("span-2")
                                .name(" ")
                                .kind(TraceSpanKind.LLM)
                                .statusCode(TraceStatusCode.ERROR)
                                .statusMessage("llm failed")
                                .startedAt(Instant.parse("2026-03-31T14:10:00Z"))
                                .endedAt(Instant.parse("2026-03-31T14:10:02Z"))
                                .build()))
                .build();

        RunVerdict verdict = service.evaluate(null, traceRecord);

        assertEquals("FAILED", verdict.getOutcomeStatus());
        assertEquals(List.of("span_error:span-2"), verdict.getProcessFindings());
        assertEquals("llm failed", verdict.getEvidenceRefs().getFirst().getOutputFragment());
        assertNotNull(verdict.getId());
    }

    @Test
    void shouldPreserveRunningOutcomeWhenNoErrorsArePresent() {
        RunRecord runRecord = RunRecord.builder()
                .id("run-running")
                .status("running")
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-running")
                .spans(List.of(
                        TraceSpanRecord.builder()
                                .spanId("span-ok")
                                .name("llm.ok")
                                .kind(TraceSpanKind.LLM)
                                .statusCode(TraceStatusCode.OK)
                                .startedAt(Instant.parse("2026-03-31T14:20:00Z"))
                                .endedAt(Instant.parse("2026-03-31T14:20:01Z"))
                                .build()))
                .build();

        RunVerdict verdict = service.evaluate(runRecord, traceRecord);

        assertEquals("RUNNING", verdict.getOutcomeStatus());
        assertEquals("CLEAN", verdict.getProcessStatus());
        assertEquals("Run is still in progress", verdict.getOutcomeSummary());
        assertEquals("approve_gated", verdict.getPromotionRecommendation());
        assertEquals("No deterministic process issues detected", verdict.getProcessSummary());
    }
}

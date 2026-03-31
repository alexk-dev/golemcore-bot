package me.golemcore.bot.domain.service;

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
}

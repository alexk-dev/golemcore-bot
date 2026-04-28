package me.golemcore.bot.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FailureSummaryTest {

    @Test
    void shouldMapFailureEventWithoutExposingMutableRuntimeState() {
        Instant timestamp = Instant.parse("2026-04-27T12:00:00Z");
        FailureEvent event = new FailureEvent(FailureSource.SYSTEM, "AgentPipelineRunner", FailureKind.EXCEPTION,
                "boom", timestamp);

        FailureSummary summary = FailureSummary.from(event);

        assertEquals(FailureSource.SYSTEM, summary.source());
        assertEquals("AgentPipelineRunner", summary.component());
        assertEquals(FailureKind.EXCEPTION, summary.kind());
        assertNull(summary.errorCode());
        assertEquals("boom", summary.message());
        assertEquals(timestamp, summary.timestamp());
    }

    @Test
    void shouldIgnoreMissingFailureEvent() {
        assertNull(FailureSummary.from(null));
    }
}

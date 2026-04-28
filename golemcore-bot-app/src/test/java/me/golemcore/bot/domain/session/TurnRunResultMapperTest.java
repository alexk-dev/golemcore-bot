package me.golemcore.bot.domain.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.FailureSummary;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.PersistenceOutcome;
import me.golemcore.bot.domain.model.RunStatus;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.TurnRunResult;
import me.golemcore.bot.domain.model.trace.TraceContext;
import org.junit.jupiter.api.Test;

class TurnRunResultMapperTest {

    @Test
    void shouldMapSuccessfulContextToImmutableResult() {
        AgentSession session = AgentSession.builder().id("session-1").messages(new ArrayList<>()).build();
        AgentContext context = AgentContext.builder().session(session).build();
        context.setTraceContext(TraceContext.builder().traceId("trace-1").spanId("span-1").build());
        context.setTurnOutcome(TurnOutcome.builder().outgoingResponse(OutgoingResponse.textOnly("done")).build());
        context.setAttribute(ContextAttributes.TURN_PERSISTENCE_OUTCOME, PersistenceOutcome.saved("session-1"));
        Message inbound = Message.builder().role("user").content("run").channelType("telegram").chatId("1")
                .metadata(Map.of(ContextAttributes.AUTO_RUN_ID, "run-1")).build();

        TurnRunResult result = new TurnRunResultMapper().map(inbound, context);

        assertEquals("session-1", result.sessionId());
        assertEquals("run-1", result.runId());
        assertEquals("trace-1", result.traceId());
        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals("done", result.response().getText());
    }

    @Test
    void shouldMapFailuresAndPersistenceOutcomeToFailedStatus() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-1").messages(new ArrayList<>()).build())
                .build();
        context.addFailure(new FailureEvent(FailureSource.SYSTEM, "component", FailureKind.EXCEPTION, "boom",
                Instant.parse("2026-04-27T00:00:00Z")));
        context.setAttribute(ContextAttributes.TURN_PERSISTENCE_OUTCOME,
                PersistenceOutcome.failed("session-1", "save.failed", "disk full"));

        TurnRunResult result = new TurnRunResultMapper().map(null, context);

        assertEquals(RunStatus.FAILED, result.status());
        assertFalse(result.persistence().saved());
        assertEquals(new FailureSummary(FailureSource.SYSTEM, "component", FailureKind.EXCEPTION, null, "boom",
                Instant.parse("2026-04-27T00:00:00Z")), result.failures().getFirst());
    }

    @Test
    void shouldReturnSkippedResultWhenContextIsMissing() {
        TurnRunResult result = new TurnRunResultMapper().map(null, null);

        assertEquals(RunStatus.SKIPPED, result.status());
        assertEquals("persistence.skipped", result.persistence().errorCode());
    }
}

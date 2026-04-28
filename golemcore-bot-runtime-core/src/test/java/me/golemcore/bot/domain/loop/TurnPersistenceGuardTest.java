package me.golemcore.bot.domain.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.PersistenceOutcome;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.context.hygiene.ContextHygieneService;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnPersistenceGuardTest {

    private static final Instant NOW = Instant.parse("2026-04-27T00:00:00Z");

    @Test
    void shouldRunHygieneAndSaveSessionWhenTracingDisabled() {
        SessionPort sessionPort = mock(SessionPort.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        TraceService traceService = mock(TraceService.class);
        ContextHygieneService hygieneService = mock(ContextHygieneService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TurnPersistenceGuard guard = new TurnPersistenceGuard(sessionPort, runtimeConfigService, traceService,
                hygieneService, clock, new RuntimeEventService(clock));
        AgentSession session = AgentSession.builder().id("session-1").build();
        AgentContext context = AgentContext.builder().session(session).build();

        PersistenceOutcome outcome = guard.persist(context, session,
                TraceContext.builder().traceId("trace").spanId("root").build());

        assertEquals(PersistenceOutcome.saved("session-1"), outcome);
        assertSame(outcome, context.getAttribute(ContextAttributes.TURN_PERSISTENCE_OUTCOME));
        verify(hygieneService).beforePersist(context);
        verify(sessionPort).save(session);
    }

    @Test
    void shouldTraceSessionSaveWhenTracingEnabled() {
        SessionPort sessionPort = mock(SessionPort.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        TraceService traceService = mock(TraceService.class);
        ContextHygieneService hygieneService = mock(ContextHygieneService.class);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        AgentSession session = AgentSession.builder().id("session-1").build();
        TraceContext parent = TraceContext.builder().traceId("trace").spanId("root").build();
        TraceContext saveSpan = TraceContext.builder().traceId("trace").spanId("save").parentSpanId("root").build();
        when(traceService.startSpan(eq(session), eq(parent), eq("session.save"), eq(TraceSpanKind.STORAGE), eq(NOW),
                eq(Map.of("session.id", "session-1")))).thenReturn(saveSpan);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TurnPersistenceGuard guard = new TurnPersistenceGuard(sessionPort, runtimeConfigService, traceService,
                hygieneService, clock, new RuntimeEventService(clock));

        guard.persist(AgentContext.builder().session(session).build(), session, parent);

        verify(traceService).finishSpan(session, saveSpan, TraceStatusCode.OK, null, NOW);
    }

    @Test
    void shouldFinishSaveSpanAsErrorWhenPersistenceFails() {
        SessionPort sessionPort = mock(SessionPort.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        TraceService traceService = mock(TraceService.class);
        ContextHygieneService hygieneService = mock(ContextHygieneService.class);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        AgentSession session = AgentSession.builder().id("session-1").build();
        TraceContext parent = TraceContext.builder().traceId("trace").spanId("root").build();
        TraceContext saveSpan = TraceContext.builder().traceId("trace").spanId("save").parentSpanId("root").build();
        when(traceService.startSpan(eq(session), eq(parent), eq("session.save"), eq(TraceSpanKind.STORAGE), eq(NOW),
                any())).thenReturn(saveSpan);
        org.mockito.Mockito.doThrow(new IllegalStateException("disk full")).when(sessionPort).save(session);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TurnPersistenceGuard guard = new TurnPersistenceGuard(sessionPort, runtimeConfigService, traceService,
                hygieneService, clock, new RuntimeEventService(clock));

        AgentContext context = AgentContext.builder().session(session).build();

        PersistenceOutcome outcome = guard.persist(context, session, parent);

        assertFalse(outcome.saved());
        assertEquals("session.persistence.save_failed", outcome.errorCode());
        assertEquals(RuntimeEventType.SESSION_PERSISTENCE_FAILED,
                ((java.util.List<?>) context.getAttribute(ContextAttributes.RUNTIME_EVENTS)).stream()
                        .map(me.golemcore.bot.domain.model.RuntimeEvent.class::cast).findFirst().orElseThrow().type());
        verify(traceService).finishSpan(session, saveSpan, TraceStatusCode.ERROR, "disk full", NOW);
    }
}

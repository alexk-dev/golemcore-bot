package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.service.ContextHygieneService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        TurnPersistenceGuard guard = new TurnPersistenceGuard(sessionPort, runtimeConfigService, traceService,
                hygieneService, Clock.fixed(NOW, ZoneOffset.UTC));
        AgentSession session = AgentSession.builder().id("session-1").build();
        AgentContext context = AgentContext.builder().session(session).build();

        guard.persist(context, session, TraceContext.builder().traceId("trace").spanId("root").build());

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
        TurnPersistenceGuard guard = new TurnPersistenceGuard(sessionPort, runtimeConfigService, traceService,
                hygieneService, Clock.fixed(NOW, ZoneOffset.UTC));

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
        TurnPersistenceGuard guard = new TurnPersistenceGuard(sessionPort, runtimeConfigService, traceService,
                hygieneService, Clock.fixed(NOW, ZoneOffset.UTC));

        guard.persist(AgentContext.builder().session(session).build(), session, parent);

        verify(traceService).finishSpan(session, saveSpan, TraceStatusCode.ERROR, "disk full", NOW);
    }
}

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
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

@Slf4j
class TurnPersistenceGuard {

    private final SessionPort sessionService;
    private final RuntimeConfigService runtimeConfigService;
    private final TraceService traceService;
    private final ContextHygieneService contextHygieneService;
    private final Clock clock;

    TurnPersistenceGuard(SessionPort sessionService, RuntimeConfigService runtimeConfigService,
            TraceService traceService, ContextHygieneService contextHygieneService, Clock clock) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService must not be null");
        this.runtimeConfigService = Objects.requireNonNull(runtimeConfigService,
                "runtimeConfigService must not be null");
        this.traceService = Objects.requireNonNull(traceService, "traceService must not be null");
        this.contextHygieneService = Objects.requireNonNull(contextHygieneService,
                "contextHygieneService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    void persist(AgentContext context, AgentSession session, TraceContext parentTraceContext) {
        try {
            contextHygieneService.beforePersist(context);
            saveSessionWithTracing(session, parentTraceContext);
        } catch (Exception e) { // NOSONAR - last-resort persistence must not break finally
            log.error("Failed to persist session in finally: {}", session != null ? session.getId() : null, e);
        }
    }

    private void saveSessionWithTracing(AgentSession session, TraceContext parentTraceContext) {
        TraceContext saveSpan = startSessionSaveSpan(session, parentTraceContext);
        try {
            sessionService.save(session);
            finishSessionSaveSpan(session, saveSpan, TraceStatusCode.OK, null);
        } catch (RuntimeException e) {
            finishSessionSaveSpan(session, saveSpan, TraceStatusCode.ERROR, e.getMessage());
            log.error("Failed to save session {} during traced persistence", session != null ? session.getId() : null,
                    e);
        }
    }

    private TraceContext startSessionSaveSpan(AgentSession session, TraceContext parentTraceContext) {
        if (!runtimeConfigService.isTracingEnabled() || session == null || parentTraceContext == null) {
            return null;
        }
        return traceService.startSpan(session, parentTraceContext, "session.save", TraceSpanKind.STORAGE,
                clock.instant(), Map.of("session.id", session.getId()));
    }

    private void finishSessionSaveSpan(AgentSession session, TraceContext spanContext, TraceStatusCode statusCode,
            String statusMessage) {
        if (session == null || spanContext == null) {
            return;
        }
        traceService.finishSpan(session, spanContext, statusCode, statusMessage, clock.instant());
    }
}

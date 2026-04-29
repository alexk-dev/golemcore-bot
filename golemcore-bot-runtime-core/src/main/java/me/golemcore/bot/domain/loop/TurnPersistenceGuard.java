package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.PersistenceOutcome;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.context.hygiene.ContextHygieneService;
import me.golemcore.bot.domain.runtimeconfig.TracingConfigView;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.port.outbound.SessionPort;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
class TurnPersistenceGuard {

    private final SessionPort sessionService;
    private final TracingConfigView tracingConfigView;
    private final TraceService traceService;
    private final ContextHygieneService contextHygieneService;
    private final Clock clock;
    private final RuntimeEventService runtimeEventService;

    TurnPersistenceGuard(SessionPort sessionService, TracingConfigView tracingConfigView, TraceService traceService,
            ContextHygieneService contextHygieneService, Clock clock, RuntimeEventService runtimeEventService) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService must not be null");
        this.tracingConfigView = Objects.requireNonNull(tracingConfigView, "tracingConfigView must not be null");
        this.traceService = Objects.requireNonNull(traceService, "traceService must not be null");
        this.contextHygieneService = Objects.requireNonNull(contextHygieneService,
                "contextHygieneService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.runtimeEventService = Objects.requireNonNull(runtimeEventService, "runtimeEventService must not be null");
    }

    PersistenceOutcome persist(AgentContext context, AgentSession session, TraceContext parentTraceContext) {
        PersistenceOutcome outcome;
        try {
            contextHygieneService.beforePersist(context);
            outcome = saveSessionWithTracing(session, parentTraceContext);
        } catch (Exception e) { // NOSONAR - last-resort persistence must not break finally
            log.error("Failed to persist session in finally: {}", session != null ? session.getId() : null, e);
            outcome = PersistenceOutcome.failed(session != null ? session.getId() : null,
                    "session.persistence.finally_failed", safeMessage(e));
        }
        if (context != null) {
            context.setAttribute(ContextAttributes.TURN_PERSISTENCE_OUTCOME, outcome);
        }
        if (!outcome.saved()) {
            emitPersistenceFailure(context, session, outcome);
        }
        return outcome;
    }

    private PersistenceOutcome saveSessionWithTracing(AgentSession session, TraceContext parentTraceContext) {
        TraceContext saveSpan = startSessionSaveSpan(session, parentTraceContext);
        try {
            sessionService.save(session);
            finishSessionSaveSpan(session, saveSpan, TraceStatusCode.OK, null);
            return PersistenceOutcome.saved(session != null ? session.getId() : null);
        } catch (RuntimeException e) {
            String message = safeMessage(e);
            finishSessionSaveSpan(session, saveSpan, TraceStatusCode.ERROR, message);
            log.error("Failed to save session {} during traced persistence", session != null ? session.getId() : null,
                    e);
            return PersistenceOutcome.failed(session != null ? session.getId() : null,
                    "session.persistence.save_failed", message);
        }
    }

    private void emitPersistenceFailure(AgentContext context, AgentSession session, PersistenceOutcome outcome) {
        if (context == null && session == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "session_id", outcome.sessionId());
        putIfPresent(payload, "error_code", outcome.errorCode());
        putIfPresent(payload, "error_message", outcome.errorMessage());
        try {
            if (context != null) {
                runtimeEventService.emit(context, RuntimeEventType.SESSION_PERSISTENCE_FAILED, payload);
            } else {
                runtimeEventService.emitForSession(session, RuntimeEventType.SESSION_PERSISTENCE_FAILED, payload);
            }
        } catch (RuntimeException eventFailure) { // NOSONAR - event publication must not mask persistence outcome
            log.warn("Failed to emit persistence failure event: {}", eventFailure.getMessage());
        }
    }

    private String safeMessage(Throwable failure) {
        return failure != null && failure.getMessage() != null ? failure.getMessage() : "unknown persistence failure";
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private TraceContext startSessionSaveSpan(AgentSession session, TraceContext parentTraceContext) {
        if (!tracingConfigView.isTracingEnabled() || session == null || parentTraceContext == null) {
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

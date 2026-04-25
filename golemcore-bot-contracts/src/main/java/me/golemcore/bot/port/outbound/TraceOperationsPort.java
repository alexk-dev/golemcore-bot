package me.golemcore.bot.port.outbound;

import java.time.Instant;
import java.util.Map;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;

/**
 * Trace mutation operations exposed to capability modules.
 */
public interface TraceOperationsPort {

    TraceContext startRootTrace(AgentSession session, String traceName, TraceSpanKind kind, Instant startedAt,
            Map<String, Object> attributes);

    TraceContext startRootTrace(AgentSession session, String traceName, TraceSpanKind kind, Instant startedAt,
            Map<String, Object> attributes, Integer maxTracesPerSession);

    TraceContext startRootTrace(AgentSession session, TraceContext rootContext, String traceName,
            TraceSpanKind kind,
            Instant startedAt, Map<String, Object> attributes);

    TraceContext startRootTrace(AgentSession session, TraceContext rootContext, String traceName,
            TraceSpanKind kind,
            Instant startedAt, Map<String, Object> attributes, Integer maxTracesPerSession);

    TraceContext startSpan(AgentSession session, TraceContext parentContext, String spanName, TraceSpanKind kind,
            Instant startedAt, Map<String, Object> attributes);

    void captureSnapshot(AgentSession session, TraceContext spanContext,
            RuntimeConfig.TracingConfig tracingConfig,
            String role, String contentType, byte[] payload);

    void finishSpan(AgentSession session, TraceContext traceContext, TraceStatusCode statusCode,
            String statusMessage, Instant endedAt);

    void appendEvent(AgentSession session, TraceContext spanContext, String eventName, Instant timestamp,
            Map<String, Object> attributes);
}

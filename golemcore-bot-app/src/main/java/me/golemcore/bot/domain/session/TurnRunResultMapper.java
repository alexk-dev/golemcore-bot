package me.golemcore.bot.domain.session;

import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.FailureSummary;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.PersistenceOutcome;
import me.golemcore.bot.domain.model.RunStatus;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.TurnRunResult;
import me.golemcore.bot.domain.model.trace.TraceContext;

final class TurnRunResultMapper {

    private static final String L5_TERMINAL_ERROR_CODE = "resilience.l5.terminal";

    TurnRunResult map(Message inbound, AgentContext context) {
        String sessionId = context != null && context.getSession() != null ? context.getSession().getId() : null;
        if (context == null) {
            return TurnRunResult.skipped(sessionId, "Turn did not produce a runtime context");
        }

        List<FailureSummary> failures = new ArrayList<>();
        for (FailureEvent failure : context.getFailures()) {
            FailureSummary summary = FailureSummary.from(failure);
            if (summary != null) {
                failures.add(summary);
            }
        }
        appendTerminalL5Failure(context, failures);

        TurnOutcome outcome = context.getTurnOutcome();
        OutgoingResponse response = outcome != null && outcome.getOutgoingResponse() != null
                ? outcome.getOutgoingResponse()
                : context.getOutgoingResponse();
        TraceContext traceContext = context.getTraceContext();
        PersistenceOutcome persistence = resolvePersistence(context, sessionId);
        return new TurnRunResult(
                sessionId,
                resolveRunId(inbound, context),
                traceContext != null ? traceContext.getTraceId() : null,
                resolveStatus(context, outcome, failures, persistence),
                response,
                failures,
                Boolean.TRUE.equals(context.getAttribute(ContextAttributes.TURN_INTERRUPT_REQUESTED)),
                false,
                persistence);
    }

    private void appendTerminalL5Failure(AgentContext context, List<FailureSummary> failures) {
        if (!Boolean.TRUE.equals(context.getAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_FAILURE))) {
            return;
        }
        String reason = context.getAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_REASON);
        failures.add(new FailureSummary(FailureSource.SYSTEM, "L5Resilience", FailureKind.EXCEPTION,
                L5_TERMINAL_ERROR_CODE, reason != null && !reason.isBlank() ? reason : "L5 cold retry failed", null));
    }

    private PersistenceOutcome resolvePersistence(AgentContext context, String sessionId) {
        Object outcome = context.getAttribute(ContextAttributes.TURN_PERSISTENCE_OUTCOME);
        if (outcome instanceof PersistenceOutcome persistenceOutcome) {
            return persistenceOutcome;
        }
        return PersistenceOutcome.skipped(sessionId, "Persistence outcome was not recorded");
    }

    private RunStatus resolveStatus(AgentContext context, TurnOutcome outcome, List<FailureSummary> failures,
            PersistenceOutcome persistence) {
        if (outcome != null && outcome.getFinishReason() == FinishReason.ERROR) {
            return RunStatus.FAILED;
        }
        if (!failures.isEmpty()) {
            return RunStatus.FAILED;
        }
        if (persistence != null && !persistence.saved()) {
            return RunStatus.FAILED;
        }
        if (context.getAttribute(ContextAttributes.LLM_ERROR) != null) {
            return RunStatus.FAILED;
        }
        return RunStatus.COMPLETED;
    }

    private String resolveRunId(Message inbound, AgentContext context) {
        String value = readString(inbound, ContextAttributes.HIVE_RUN_ID);
        if (value != null) {
            return value;
        }
        value = readString(inbound, ContextAttributes.AUTO_RUN_ID);
        if (value != null) {
            return value;
        }
        value = context.getAttribute(ContextAttributes.HIVE_RUN_ID);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return context.getAttribute(ContextAttributes.AUTO_RUN_ID);
    }

    private String readString(Message message, String key) {
        if (message == null || message.getMetadata() == null || key == null) {
            return null;
        }
        Object value = message.getMetadata().get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
    }
}

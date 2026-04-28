package me.golemcore.bot.domain.hive;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveInspectionRequestBody;
import me.golemcore.bot.domain.model.HiveInspectionResponse;
import me.golemcore.bot.domain.model.hive.HiveRuntimeContracts;
import me.golemcore.bot.domain.support.StringValueSupport;
import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import me.golemcore.bot.port.outbound.SessionInspectionQueryPort;
import me.golemcore.bot.port.outbound.HiveInspectionPayloadPort;
import org.springframework.stereotype.Service;

@Service
public class HiveInspectionCommandHandler {

    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int DEFAULT_KEEP_LAST = 20;

    private final SessionInspectionQueryPort sessionInspectionPort;
    private final HiveEventPublishPort hiveEventPublishPort;
    private final HiveInspectionPayloadPort hiveInspectionPayloadPort;

    public HiveInspectionCommandHandler(
            SessionInspectionQueryPort sessionInspectionPort,
            HiveEventPublishPort hiveEventPublishPort,
            HiveInspectionPayloadPort hiveInspectionPayloadPort) {
        this.sessionInspectionPort = sessionInspectionPort;
        this.hiveEventPublishPort = hiveEventPublishPort;
        this.hiveInspectionPayloadPort = hiveInspectionPayloadPort;
    }

    public void handle(HiveControlCommandEnvelope envelope) {
        String operation = resolveOperation(envelope);
        HiveInspectionResponse response;
        try {
            response = HiveInspectionResponse.builder()
                    .requestId(requireRequestId(envelope))
                    .threadId(requireThreadId(envelope))
                    .cardId(envelope.getCardId())
                    .runId(envelope.getRunId())
                    .golemId(envelope.getGolemId())
                    .operation(operation)
                    .success(true)
                    .payload(execute(operation, envelope.getInspection()))
                    .createdAt(Instant.now())
                    .build();
        } catch (RuntimeException exception) {
            response = buildErrorResponse(envelope, operation, exception);
        }
        hiveEventPublishPort.publishInspectionResponse(response);
    }

    private Object execute(String operation, HiveInspectionRequestBody inspection) {
        return switch (operation) {
            case "sessions.list" -> hiveInspectionPayloadPort
                    .toSessionListPayload(sessionInspectionPort.listSessions(inspection != null ? inspection.getChannel() : null));
            case "session.detail" -> hiveInspectionPayloadPort
                    .toSessionDetailPayload(sessionInspectionPort.getSessionDetail(requireSessionId(inspection)));
            case "session.messages" -> executeSessionMessagesInspection(inspection);
            case "session.trace.summary" -> hiveInspectionPayloadPort
                    .toSessionTraceSummaryPayload(sessionInspectionPort.getSessionTraceSummary(requireSessionId(inspection)));
            case "session.trace.detail" -> hiveInspectionPayloadPort
                    .toSessionTracePayload(sessionInspectionPort.getSessionTrace(requireSessionId(inspection)));
            case "session.trace.export" -> hiveInspectionPayloadPort
                    .toSessionTraceExportPayload(sessionInspectionPort.getSessionTraceExport(requireSessionId(inspection)));
            case "session.trace.snapshot.payload" -> sessionInspectionPort.getSessionTraceSnapshotPayload(
                    requireSessionId(inspection),
                    requireSnapshotId(inspection));
            case "session.compact" -> executeSessionCompactInspection(inspection);
            case "session.clear" -> {
                sessionInspectionPort.clearSession(requireSessionId(inspection));
                yield Map.of();
            }
            case "session.delete" -> {
                sessionInspectionPort.deleteSession(requireSessionId(inspection));
                yield Map.of();
            }
            default -> throw new IllegalArgumentException("Unsupported inspection operation: " + operation);
        };
    }

    private Object executeSessionMessagesInspection(HiveInspectionRequestBody inspection) {
        String sessionId = requireSessionId(inspection);
        int limit = inspection.getLimit() != null ? inspection.getLimit() : DEFAULT_MESSAGE_LIMIT;
        return hiveInspectionPayloadPort.toSessionMessagesPayload(
                sessionInspectionPort.getSessionMessages(sessionId, limit, inspection.getBeforeMessageId()));
    }

    private Map<String, Integer> executeSessionCompactInspection(HiveInspectionRequestBody inspection) {
        String sessionId = requireSessionId(inspection);
        int keepLast = inspection.getKeepLast() != null ? inspection.getKeepLast() : DEFAULT_KEEP_LAST;
        return Map.of("removed", sessionInspectionPort.compactSession(sessionId, keepLast));
    }

    private HiveInspectionResponse buildErrorResponse(
            HiveControlCommandEnvelope envelope,
            String operation,
            RuntimeException exception) {
        String normalizedOperation = operation != null ? operation : resolveOperationSafely(envelope);
        return HiveInspectionResponse.builder()
                .requestId(resolveRequestId(envelope))
                .threadId(resolveThreadId(envelope))
                .cardId(envelope != null ? envelope.getCardId() : null)
                .runId(envelope != null ? envelope.getRunId() : null)
                .golemId(envelope != null ? envelope.getGolemId() : null)
                .operation(normalizedOperation)
                .success(false)
                .errorCode(resolveErrorCode(exception))
                .errorMessage(resolveErrorMessage(exception))
                .payload(null)
                .createdAt(Instant.now())
                .build();
    }

    private String resolveOperation(HiveControlCommandEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Hive inspection request is required");
        }
        String eventType = envelope.getEventType() != null ? envelope.getEventType().trim().toLowerCase(Locale.ROOT)
                : "";
        if (!HiveRuntimeContracts.CONTROL_EVENT_TYPE_INSPECTION_REQUEST.equals(eventType)) {
            throw new IllegalArgumentException("Unsupported inspection eventType: " + envelope.getEventType());
        }
        if (envelope.getInspection() == null || StringValueSupport.isBlank(envelope.getInspection().getOperation())) {
            throw new IllegalArgumentException("Hive inspection operation is required");
        }
        return envelope.getInspection().getOperation().trim().toLowerCase(Locale.ROOT);
    }

    private String resolveOperationSafely(HiveControlCommandEnvelope envelope) {
        if (envelope == null || envelope.getInspection() == null
                || StringValueSupport.isBlank(envelope.getInspection().getOperation())) {
            return null;
        }
        return envelope.getInspection().getOperation().trim().toLowerCase(Locale.ROOT);
    }

    private String requireRequestId(HiveControlCommandEnvelope envelope) {
        String requestId = resolveRequestId(envelope);
        if (StringValueSupport.isBlank(requestId)) {
            throw new IllegalArgumentException("Hive inspection requestId is required");
        }
        return requestId;
    }

    private String resolveRequestId(HiveControlCommandEnvelope envelope) {
        return envelope != null ? envelope.getRequestId() : null;
    }

    private String requireThreadId(HiveControlCommandEnvelope envelope) {
        String threadId = resolveThreadId(envelope);
        if (StringValueSupport.isBlank(threadId)) {
            throw new IllegalArgumentException("Hive inspection threadId is required");
        }
        return threadId;
    }

    private String resolveThreadId(HiveControlCommandEnvelope envelope) {
        return envelope != null ? envelope.getThreadId() : null;
    }

    private String requireSessionId(HiveInspectionRequestBody inspection) {
        if (inspection == null || StringValueSupport.isBlank(inspection.getSessionId())) {
            throw new IllegalArgumentException("Hive inspection sessionId is required");
        }
        return inspection.getSessionId().trim();
    }

    private String requireSnapshotId(HiveInspectionRequestBody inspection) {
        if (inspection == null || StringValueSupport.isBlank(inspection.getSnapshotId())) {
            throw new IllegalArgumentException("Hive inspection snapshotId is required");
        }
        return inspection.getSnapshotId().trim();
    }

    private String resolveErrorCode(RuntimeException exception) {
        if (exception instanceof NoSuchElementException) {
            return "NOT_FOUND";
        }
        if (exception instanceof IllegalArgumentException) {
            return "INVALID_REQUEST";
        }
        return "INTERNAL_ERROR";
    }

    private String resolveErrorMessage(RuntimeException exception) {
        if (!StringValueSupport.isBlank(exception.getMessage())) {
            return exception.getMessage();
        }
        return "Inspection request failed";
    }
}

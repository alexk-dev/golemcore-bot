package me.golemcore.bot.domain.service;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.SessionDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionMessagesPageDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionSummaryDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSnapshotDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSpanDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceStorageStatsDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.trace.TraceEventRecord;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionInspectionService {

    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final int MAX_PAGE_LIMIT = 100;
    private static final int SNAPSHOT_PREVIEW_MAX_CHARS = 4096;
    private static final int START_WITH_INDEX = 0;

    private final SessionPort sessionPort;
    private final ActiveSessionPointerService pointerService;
    private final TraceSnapshotCompressionService traceSnapshotCompressionService;

    public List<SessionSummaryDto> listSessions(String channel) {
        List<AgentSession> sessions = StringValueSupport.isBlank(channel)
                ? sessionPort.listAll()
                : sessionPort.listByChannelType(channel.trim());
        return sessions.stream()
                .sorted(ConversationKeyValidator.byRecentActivity())
                .map(session -> SessionPresentationSupport.toSummary(session, false))
                .toList();
    }

    public SessionDetailDto getSessionDetail(String sessionId) {
        return toDetail(requireSession(sessionId));
    }

    public SessionMessagesPageDto getSessionMessages(String sessionId, int limit, String beforeMessageId) {
        AgentSession session = requireSession(sessionId);
        int normalizedLimit = Math.clamp(limit, 1, MAX_PAGE_LIMIT);
        List<Message> visibleMessages = SessionPresentationSupport.getVisibleMessages(session);
        int endExclusive = resolvePageEndExclusive(visibleMessages, beforeMessageId);
        int startInclusive = Math.max(START_WITH_INDEX, endExclusive - normalizedLimit);
        List<SessionDetailDto.MessageDto> page = visibleMessages.subList(startInclusive, endExclusive).stream()
                .map(this::toMessageDto)
                .toList();
        String oldestMessageId = page.isEmpty() ? null : page.get(START_WITH_INDEX).getId();

        return SessionMessagesPageDto.builder()
                .sessionId(sessionId)
                .messages(page)
                .hasMore(startInclusive > START_WITH_INDEX)
                .oldestMessageId(oldestMessageId)
                .build();
    }

    public SessionTraceSummaryDto getSessionTraceSummary(String sessionId) {
        return toTraceSummary(requireSession(sessionId));
    }

    public SessionTraceDto getSessionTrace(String sessionId) {
        return toTraceDetail(requireSession(sessionId));
    }

    public Map<String, Object> exportSessionTrace(String sessionId) {
        return toTraceExport(requireSession(sessionId));
    }

    public SnapshotPayloadExport exportSessionTraceSnapshotPayload(String sessionId, String snapshotId) {
        AgentSession session = requireSession(sessionId);
        TraceSnapshot snapshot = findTraceSnapshot(session, snapshotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace snapshot not found"));
        String payloadText = decompressSnapshotPayload(snapshot);
        if (payloadText == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace snapshot payload not found");
        }
        return new SnapshotPayloadExport(
                payloadText,
                resolveSnapshotContentType(snapshot),
                resolveSnapshotFileExtension(snapshot));
    }

    public void deleteSession(String sessionId) {
        Optional<AgentSession> deletedSession = sessionPort.get(sessionId);
        sessionPort.delete(sessionId);
        repairPointersAfterDelete(sessionId, deletedSession.orElse(null));
    }

    public int compactSession(String sessionId, int keepLast) {
        return sessionPort.compactMessages(sessionId, keepLast);
    }

    public void clearSession(String sessionId) {
        sessionPort.clearMessages(sessionId);
    }

    public SessionTraceSnapshotDto toTraceSnapshotDto(TraceSnapshot snapshot, boolean includePayloadPreview) {
        SnapshotPreview preview = includePayloadPreview ? buildSnapshotPreview(snapshot) : SnapshotPreview.empty();
        return SessionTraceSnapshotDto.builder()
                .snapshotId(snapshot.getSnapshotId())
                .role(snapshot.getRole())
                .contentType(snapshot.getContentType())
                .encoding(snapshot.getEncoding())
                .originalSize(snapshot.getOriginalSize())
                .compressedSize(snapshot.getCompressedSize())
                .truncated(snapshot.isTruncated())
                .payloadAvailable(preview.payloadAvailable())
                .payloadPreview(preview.payloadPreview())
                .payloadPreviewTruncated(preview.previewTruncated())
                .build();
    }

    private AgentSession requireSession(String sessionId) {
        return sessionPort.get(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
    }

    private SessionDetailDto toDetail(AgentSession session) {
        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
        String transportChatId = SessionIdentitySupport.resolveTransportChatId(session);
        List<SessionDetailDto.MessageDto> messages = List.of();
        if (session.getMessages() != null) {
            messages = session.getMessages().stream()
                    .filter(SessionPresentationSupport::isHistoryVisibleMessage)
                    .map(this::toMessageDto)
                    .toList();
        }
        return SessionDetailDto.builder()
                .id(session.getId())
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .conversationKey(conversationKey)
                .transportChatId(transportChatId)
                .state(session.getState() != null ? session.getState().name() : "ACTIVE")
                .createdAt(session.getCreatedAt() != null ? session.getCreatedAt().toString() : null)
                .updatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null)
                .messages(messages)
                .build();
    }

    private SessionDetailDto.MessageDto toMessageDto(Message message) {
        MessageMetadataView metadata = resolveMetadataView(message);
        return SessionDetailDto.MessageDto.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(SessionPresentationSupport.resolveMessageContent(message))
                .timestamp(message.getTimestamp() != null ? message.getTimestamp().toString() : null)
                .hasToolCalls(message.hasToolCalls())
                .hasVoice(message.hasVoice())
                .model(metadata.model())
                .modelTier(metadata.modelTier())
                .skill(metadata.skill())
                .reasoning(metadata.reasoning())
                .clientMessageId(metadata.clientMessageId())
                .autoMode(metadata.autoMode())
                .autoRunId(metadata.autoRunId())
                .autoScheduleId(metadata.autoScheduleId())
                .autoGoalId(metadata.autoGoalId())
                .autoTaskId(metadata.autoTaskId())
                .attachments(resolveAttachments(message))
                .build();
    }

    private MessageMetadataView resolveMetadataView(Message message) {
        if (message == null || message.getMetadata() == null) {
            return MessageMetadataView.empty();
        }
        Map<String, Object> metadata = message.getMetadata();
        String skill = firstNonBlank(
                readMetadataString(metadata, ContextAttributes.AUTO_RUN_ACTIVE_SKILL),
                readMetadataString(metadata, ContextAttributes.ACTIVE_SKILL_NAME));
        return new MessageMetadataView(
                readMetadataString(metadata, "model"),
                readMetadataString(metadata, "modelTier"),
                skill,
                readMetadataString(metadata, "reasoning"),
                readMetadataString(metadata, "clientMessageId"),
                AutoRunContextSupport.isAutoMetadata(metadata),
                AutoRunContextSupport.readMetadataString(metadata, ContextAttributes.AUTO_RUN_ID),
                AutoRunContextSupport.readMetadataString(metadata, ContextAttributes.AUTO_SCHEDULE_ID),
                AutoRunContextSupport.readMetadataString(metadata, ContextAttributes.AUTO_GOAL_ID),
                AutoRunContextSupport.readMetadataString(metadata, ContextAttributes.AUTO_TASK_ID));
    }

    private String readMetadataString(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (!(value instanceof String stringValue)) {
            return null;
        }
        String normalized = stringValue.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstNonBlank(String primary, String fallback) {
        return !StringValueSupport.isBlank(primary) ? primary : fallback;
    }

    private SessionTraceSummaryDto toTraceSummary(AgentSession session) {
        List<TraceRecord> traces = getSortedTraces(session);
        int spanCount = traces.stream()
                .mapToInt(trace -> trace.getSpans() != null ? trace.getSpans().size() : 0)
                .sum();
        int snapshotCount = traces.stream()
                .mapToInt(this::countSnapshots)
                .sum();
        List<SessionTraceSummaryDto.TraceSummaryDto> traceSummaries = traces.stream()
                .map(this::toTraceSummaryItem)
                .toList();
        return SessionTraceSummaryDto.builder()
                .sessionId(session.getId())
                .traceCount(traces.size())
                .spanCount(spanCount)
                .snapshotCount(snapshotCount)
                .storageStats(toTraceStorageStatsDto(session.getTraceStorageStats()))
                .traces(traceSummaries)
                .build();
    }

    private SessionTraceDto toTraceDetail(AgentSession session) {
        List<SessionTraceDto.TraceDto> traces = getSortedTraces(session).stream()
                .map(trace -> toTraceDto(trace, true))
                .toList();
        return SessionTraceDto.builder()
                .sessionId(session.getId())
                .storageStats(toTraceStorageStatsDto(session.getTraceStorageStats()))
                .traces(traces)
                .build();
    }

    private Map<String, Object> toTraceExport(AgentSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("storageStats", toTraceStorageStatsMap(session.getTraceStorageStats()));
        List<Map<String, Object>> traces = getSortedTraces(session).stream()
                .map(this::toTraceExportMap)
                .toList();
        result.put("traces", traces);
        return result;
    }

    private SessionTraceSummaryDto.TraceSummaryDto toTraceSummaryItem(TraceRecord trace) {
        TraceSpanRecord rootSpan = findRootSpan(trace);
        return SessionTraceSummaryDto.TraceSummaryDto.builder()
                .traceId(trace.getTraceId())
                .rootSpanId(trace.getRootSpanId())
                .traceName(trace.getTraceName())
                .rootKind(rootSpan != null && rootSpan.getKind() != null ? rootSpan.getKind().name() : null)
                .rootStatusCode(rootSpan != null && rootSpan.getStatusCode() != null
                        ? rootSpan.getStatusCode().name()
                        : null)
                .startedAt(toTimestamp(trace.getStartedAt()))
                .endedAt(toTimestamp(trace.getEndedAt()))
                .durationMs(toDurationMs(trace.getStartedAt(), trace.getEndedAt()))
                .spanCount(trace.getSpans() != null ? trace.getSpans().size() : 0)
                .snapshotCount(countSnapshots(trace))
                .truncated(trace.isTruncated())
                .build();
    }

    private SessionTraceDto.TraceDto toTraceDto(TraceRecord trace, boolean includeSnapshotPreview) {
        List<SessionTraceSpanDto> spans = getSortedSpans(trace).stream()
                .map(span -> toTraceSpanDto(span, includeSnapshotPreview))
                .toList();
        return SessionTraceDto.TraceDto.builder()
                .traceId(trace.getTraceId())
                .rootSpanId(trace.getRootSpanId())
                .traceName(trace.getTraceName())
                .startedAt(toTimestamp(trace.getStartedAt()))
                .endedAt(toTimestamp(trace.getEndedAt()))
                .truncated(trace.isTruncated())
                .compressedSnapshotBytes(trace.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(trace.getUncompressedSnapshotBytes())
                .spans(spans)
                .build();
    }

    private SessionTraceSpanDto toTraceSpanDto(TraceSpanRecord span, boolean includeSnapshotPreview) {
        List<SessionTraceSpanDto.EventDto> events = span.getEvents() == null
                ? List.of()
                : span.getEvents().stream()
                        .map(this::toTraceEventDto)
                        .toList();
        List<SessionTraceSnapshotDto> snapshots = span.getSnapshots() == null
                ? List.of()
                : span.getSnapshots().stream()
                        .map(snapshot -> toTraceSnapshotDto(snapshot, includeSnapshotPreview))
                        .toList();
        return SessionTraceSpanDto.builder()
                .spanId(span.getSpanId())
                .parentSpanId(span.getParentSpanId())
                .name(span.getName())
                .kind(span.getKind() != null ? span.getKind().name() : null)
                .statusCode(span.getStatusCode() != null ? span.getStatusCode().name() : null)
                .statusMessage(span.getStatusMessage())
                .startedAt(toTimestamp(span.getStartedAt()))
                .endedAt(toTimestamp(span.getEndedAt()))
                .durationMs(toDurationMs(span.getStartedAt(), span.getEndedAt()))
                .attributes(copyAttributes(span.getAttributes()))
                .events(events)
                .snapshots(snapshots)
                .build();
    }

    private SessionTraceSpanDto.EventDto toTraceEventDto(TraceEventRecord event) {
        return SessionTraceSpanDto.EventDto.builder()
                .name(event.getName())
                .timestamp(toTimestamp(event.getTimestamp()))
                .attributes(copyAttributes(event.getAttributes()))
                .build();
    }

    private SessionTraceStorageStatsDto toTraceStorageStatsDto(TraceStorageStats stats) {
        TraceStorageStats effective = stats != null ? stats : TraceStorageStats.builder().build();
        return SessionTraceStorageStatsDto.builder()
                .compressedSnapshotBytes(effective.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(effective.getUncompressedSnapshotBytes())
                .evictedSnapshots(effective.getEvictedSnapshots())
                .evictedTraces(effective.getEvictedTraces())
                .truncatedTraces(effective.getTruncatedTraces())
                .build();
    }

    private Map<String, Object> toTraceExportMap(TraceRecord trace) {
        Map<String, Object> traceMap = new LinkedHashMap<>();
        traceMap.put("traceId", trace.getTraceId());
        traceMap.put("rootSpanId", trace.getRootSpanId());
        traceMap.put("traceName", trace.getTraceName());
        traceMap.put("startedAt", toTimestamp(trace.getStartedAt()));
        traceMap.put("endedAt", toTimestamp(trace.getEndedAt()));
        traceMap.put("truncated", trace.isTruncated());
        traceMap.put("compressedSnapshotBytes", trace.getCompressedSnapshotBytes());
        traceMap.put("uncompressedSnapshotBytes", trace.getUncompressedSnapshotBytes());
        List<Map<String, Object>> spans = getSortedSpans(trace).stream()
                .map(this::toTraceSpanExportMap)
                .toList();
        traceMap.put("spans", spans);
        return traceMap;
    }

    private Map<String, Object> toTraceSpanExportMap(TraceSpanRecord span) {
        Map<String, Object> spanMap = new LinkedHashMap<>();
        Map<String, Object> statusMap = new LinkedHashMap<>();
        statusMap.put("code", span.getStatusCode() != null ? span.getStatusCode().name() : null);
        statusMap.put("message", span.getStatusMessage());
        spanMap.put("spanId", span.getSpanId());
        spanMap.put("parentSpanId", span.getParentSpanId());
        spanMap.put("name", span.getName());
        spanMap.put("kind", span.getKind() != null ? span.getKind().name() : null);
        spanMap.put("status", statusMap);
        spanMap.put("startedAt", toTimestamp(span.getStartedAt()));
        spanMap.put("endedAt", toTimestamp(span.getEndedAt()));
        spanMap.put("durationMs", toDurationMs(span.getStartedAt(), span.getEndedAt()));
        spanMap.put("attributes", copyAttributes(span.getAttributes()));
        List<Map<String, Object>> events = span.getEvents() == null
                ? List.of()
                : span.getEvents().stream().map(this::toTraceEventExportMap).toList();
        spanMap.put("events", events);
        List<Map<String, Object>> snapshots = span.getSnapshots() == null
                ? List.of()
                : span.getSnapshots().stream().map(this::toTraceSnapshotExportMap).toList();
        spanMap.put("snapshots", snapshots);
        return spanMap;
    }

    private Map<String, Object> toTraceEventExportMap(TraceEventRecord event) {
        Map<String, Object> eventMap = new LinkedHashMap<>();
        eventMap.put("name", event.getName());
        eventMap.put("timestamp", toTimestamp(event.getTimestamp()));
        eventMap.put("attributes", copyAttributes(event.getAttributes()));
        return eventMap;
    }

    private Map<String, Object> toTraceSnapshotExportMap(TraceSnapshot snapshot) {
        Map<String, Object> snapshotMap = new LinkedHashMap<>();
        snapshotMap.put("snapshotId", snapshot.getSnapshotId());
        snapshotMap.put("role", snapshot.getRole());
        snapshotMap.put("contentType", snapshot.getContentType());
        snapshotMap.put("encoding", snapshot.getEncoding());
        snapshotMap.put("originalSize", snapshot.getOriginalSize());
        snapshotMap.put("compressedSize", snapshot.getCompressedSize());
        snapshotMap.put("truncated", snapshot.isTruncated());
        snapshotMap.put("payloadText", decompressSnapshotPayload(snapshot));
        return snapshotMap;
    }

    private Map<String, Object> toTraceStorageStatsMap(TraceStorageStats stats) {
        SessionTraceStorageStatsDto dto = toTraceStorageStatsDto(stats);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("compressedSnapshotBytes", dto.getCompressedSnapshotBytes());
        result.put("uncompressedSnapshotBytes", dto.getUncompressedSnapshotBytes());
        result.put("evictedSnapshots", dto.getEvictedSnapshots());
        result.put("evictedTraces", dto.getEvictedTraces());
        result.put("truncatedTraces", dto.getTruncatedTraces());
        return result;
    }

    private List<TraceRecord> getSortedTraces(AgentSession session) {
        if (session.getTraces() == null || session.getTraces().isEmpty()) {
            return List.of();
        }
        return session.getTraces().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TraceRecord::getStartedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private List<TraceSpanRecord> getSortedSpans(TraceRecord trace) {
        if (trace == null || trace.getSpans() == null || trace.getSpans().isEmpty()) {
            return List.of();
        }
        Map<String, Integer> spanOrder = new HashMap<>();
        for (int index = 0; index < trace.getSpans().size(); index++) {
            TraceSpanRecord span = trace.getSpans().get(index);
            if (span != null && span.getSpanId() != null) {
                spanOrder.put(span.getSpanId(), index);
            }
        }
        return trace.getSpans().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing((TraceSpanRecord span) -> span.getStartedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(span -> spanOrder.getOrDefault(span.getSpanId(), Integer.MAX_VALUE)))
                .toList();
    }

    private TraceSpanRecord findRootSpan(TraceRecord trace) {
        if (trace == null || trace.getSpans() == null || trace.getSpans().isEmpty()) {
            return null;
        }
        return trace.getSpans().stream()
                .filter(span -> span != null && trace.getRootSpanId() != null
                        && trace.getRootSpanId().equals(span.getSpanId()))
                .findFirst()
                .orElse(trace.getSpans().get(0));
    }

    private int countSnapshots(TraceRecord trace) {
        if (trace == null || trace.getSpans() == null) {
            return 0;
        }
        return trace.getSpans().stream()
                .mapToInt(span -> span != null && span.getSnapshots() != null ? span.getSnapshots().size() : 0)
                .sum();
    }

    private Map<String, Object> copyAttributes(Map<String, Object> attributes) {
        return attributes != null ? new LinkedHashMap<>(attributes) : Map.of();
    }

    private String toTimestamp(Instant timestamp) {
        return timestamp != null ? timestamp.toString() : null;
    }

    private Long toDurationMs(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null) {
            return null;
        }
        return Math.max(0L, endedAt.toEpochMilli() - startedAt.toEpochMilli());
    }

    private SnapshotPreview buildSnapshotPreview(TraceSnapshot snapshot) {
        String payloadText = decompressSnapshotPayload(snapshot);
        if (payloadText == null) {
            return SnapshotPreview.empty();
        }
        boolean previewTruncated = payloadText.length() > SNAPSHOT_PREVIEW_MAX_CHARS;
        String preview = previewTruncated
                ? payloadText.substring(0, SNAPSHOT_PREVIEW_MAX_CHARS)
                : payloadText;
        return new SnapshotPreview(true, preview, previewTruncated);
    }

    private String decompressSnapshotPayload(TraceSnapshot snapshot) {
        if (snapshot == null || snapshot.getCompressedPayload() == null
                || snapshot.getCompressedPayload().length == 0) {
            return null;
        }
        byte[] payload = traceSnapshotCompressionService.decompress(
                snapshot.getEncoding(), snapshot.getCompressedPayload());
        return new String(payload, StandardCharsets.UTF_8);
    }

    private Optional<TraceSnapshot> findTraceSnapshot(AgentSession session, String snapshotId) {
        if (session == null || StringValueSupport.isBlank(snapshotId) || session.getTraces() == null) {
            return Optional.empty();
        }
        return session.getTraces().stream()
                .filter(trace -> trace != null && trace.getSpans() != null)
                .flatMap(trace -> trace.getSpans().stream())
                .filter(span -> span != null && span.getSnapshots() != null)
                .flatMap(span -> span.getSnapshots().stream())
                .filter(snapshot -> snapshot != null && snapshotId.equals(snapshot.getSnapshotId()))
                .findFirst();
    }

    private MediaType resolveSnapshotContentType(TraceSnapshot snapshot) {
        if (snapshot == null || StringValueSupport.isBlank(snapshot.getContentType())) {
            return MediaType.APPLICATION_JSON;
        }
        try {
            return MediaType.parseMediaType(snapshot.getContentType());
        } catch (IllegalArgumentException _) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String resolveSnapshotFileExtension(TraceSnapshot snapshot) {
        MediaType contentType = resolveSnapshotContentType(snapshot);
        return MediaType.APPLICATION_JSON.includes(contentType) ? ".json" : ".txt";
    }

    private int resolvePageEndExclusive(List<Message> messages, String beforeMessageId) {
        if (messages == null || messages.isEmpty()) {
            return START_WITH_INDEX;
        }
        if (StringValueSupport.isBlank(beforeMessageId)) {
            return messages.size();
        }
        String normalizedBeforeMessageId = beforeMessageId.trim();
        for (int index = START_WITH_INDEX; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message != null && normalizedBeforeMessageId.equals(message.getId())) {
                return index;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "beforeMessageId not found");
    }

    @SuppressWarnings("unchecked")
    private List<SessionDetailDto.AttachmentDto> resolveAttachments(Message message) {
        if (message == null || message.getMetadata() == null) {
            return List.of();
        }
        Object attachmentsRaw = message.getMetadata().get("attachments");
        if (!(attachmentsRaw instanceof List<?> attachments) || attachments.isEmpty()) {
            return List.of();
        }

        return attachments.stream()
                .map(this::toAttachmentDto)
                .filter(Objects::nonNull)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private SessionDetailDto.AttachmentDto toAttachmentDto(Object attachmentObj) {
        if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>((Map<String, Object>) attachmentMap);
        String type = readAttachmentString(normalized, "type");
        String name = readAttachmentString(normalized, "name");
        String mimeType = readAttachmentString(normalized, "mimeType");
        String url = readAttachmentUrl(normalized);
        String internalFilePath = readAttachmentString(normalized, "internalFilePath");
        String thumbnailBase64 = readAttachmentString(normalized, "thumbnailBase64");
        if (type == null && name == null && mimeType == null && url == null
                && internalFilePath == null && thumbnailBase64 == null) {
            return null;
        }
        return SessionDetailDto.AttachmentDto.builder()
                .type(type)
                .name(name)
                .mimeType(mimeType)
                .url(url)
                .internalFilePath(internalFilePath)
                .thumbnailBase64(thumbnailBase64)
                .build();
    }

    private String readAttachmentUrl(Map<String, Object> attachment) {
        String directUrl = readAttachmentString(attachment, "url");
        if (directUrl != null) {
            return directUrl;
        }
        String internalFilePath = readAttachmentString(attachment, "internalFilePath");
        if (internalFilePath == null) {
            return null;
        }
        String encoded = URLEncoder.encode(internalFilePath, StandardCharsets.UTF_8).replace("+", "%20");
        return "/api/files/download?path=" + encoded;
    }

    private String readAttachmentString(Map<String, Object> attachment, String key) {
        Object value = attachment.get(key);
        if (!(value instanceof String stringValue)) {
            return null;
        }
        String normalized = stringValue.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void repairPointersAfterDelete(String deletedSessionId, AgentSession deletedSession) {
        String channel = deletedSession != null ? deletedSession.getChannelType()
                : resolveDeletedChannel(deletedSessionId);
        String deletedConversation = deletedSession != null
                ? SessionIdentitySupport.resolveConversationKey(deletedSession)
                : resolveDeletedConversation(deletedSessionId);

        if (StringValueSupport.isBlank(channel) || StringValueSupport.isBlank(deletedConversation)) {
            return;
        }

        Map<String, String> pointers = pointerService.getPointersSnapshot();
        if (pointers.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> entry : pointers.entrySet()) {
            String pointerKey = entry.getKey();
            String pointerConversation = entry.getValue();
            if (!deletedConversation.equals(pointerConversation) || !isPointerForChannel(pointerKey, channel)) {
                continue;
            }

            String pointerTransportChatId = CHANNEL_TELEGRAM.equals(channel)
                    ? extractTelegramTransportChatId(pointerKey)
                    : null;
            String replacement = resolveOrCreateConversationKey(channel, pointerTransportChatId, null);
            pointerService.setActiveConversationKey(pointerKey, replacement);
        }
    }

    private boolean isPointerForChannel(String pointerKey, String channel) {
        if (StringValueSupport.isBlank(pointerKey) || StringValueSupport.isBlank(channel)) {
            return false;
        }
        return pointerKey.startsWith(channel + "|");
    }

    private String extractTelegramTransportChatId(String pointerKey) {
        if (StringValueSupport.isBlank(pointerKey)) {
            return null;
        }
        String prefix = CHANNEL_TELEGRAM + "|";
        if (!pointerKey.startsWith(prefix) || pointerKey.length() <= prefix.length()) {
            return null;
        }
        return pointerKey.substring(prefix.length());
    }

    private String resolveDeletedChannel(String sessionId) {
        if (StringValueSupport.isBlank(sessionId)) {
            return null;
        }
        int separator = sessionId.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        return sessionId.substring(0, separator);
    }

    private String resolveDeletedConversation(String sessionId) {
        if (StringValueSupport.isBlank(sessionId)) {
            return null;
        }
        int separator = sessionId.indexOf(':');
        if (separator < 0 || separator + 1 >= sessionId.length()) {
            return null;
        }
        return sessionId.substring(separator + 1);
    }

    private String resolveOrCreateConversationKey(
            String channel,
            String transportChatId,
            String preferredConversation) {
        if (!StringValueSupport.isBlank(preferredConversation)
                && isConversationResolvable(channel, transportChatId, preferredConversation)) {
            return preferredConversation;
        }

        String fallbackConversation = findLatestConversationKey(channel, transportChatId, preferredConversation)
                .orElseGet(this::generateConversationKey);

        if (!isConversationResolvable(channel, transportChatId, fallbackConversation)) {
            ensureSessionExists(channel, transportChatId, fallbackConversation);
        }
        return fallbackConversation;
    }

    private Optional<String> findLatestConversationKey(
            String channel,
            String transportChatId,
            String excludedConversation) {
        return listRecentSessionsByOwner(channel, transportChatId).stream()
                .sorted(ConversationKeyValidator.byRecentActivity())
                .map(SessionIdentitySupport::resolveConversationKey)
                .filter(value -> !StringValueSupport.isBlank(value) && !value.equals(excludedConversation))
                .findFirst();
    }

    private boolean isConversationResolvable(String channel, String transportChatId, String conversationKey) {
        if (StringValueSupport.isBlank(channel) || StringValueSupport.isBlank(conversationKey)) {
            return false;
        }

        Optional<AgentSession> session = sessionPort.get(channel + ":" + conversationKey);
        if (session.isEmpty()) {
            return false;
        }

        if (!CHANNEL_TELEGRAM.equals(channel) || StringValueSupport.isBlank(transportChatId)) {
            return true;
        }
        return transportChatId.equals(SessionIdentitySupport.resolveTransportChatId(session.get()));
    }

    private void ensureSessionExists(String channel, String transportChatId, String conversationKey) {
        AgentSession session = sessionPort.getOrCreate(channel, conversationKey);
        if (CHANNEL_TELEGRAM.equals(channel)) {
            SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        }
        sessionPort.save(session);
    }

    private List<AgentSession> listRecentSessionsByOwner(String channel, String transportChatId) {
        if (!CHANNEL_TELEGRAM.equals(channel) || StringValueSupport.isBlank(transportChatId)) {
            return sessionPort.listByChannelType(channel);
        }
        return sessionPort.listByChannelTypeAndTransportChatId(channel, transportChatId);
    }

    private String generateConversationKey() {
        return UUID.randomUUID().toString();
    }

    private record SnapshotPreview(boolean payloadAvailable, String payloadPreview, boolean previewTruncated) {
        private static SnapshotPreview empty() {
            return new SnapshotPreview(false, null, false);
        }
    }

    private record MessageMetadataView(
            String model,
            String modelTier,
            String skill,
            String reasoning,
            String clientMessageId,
            boolean autoMode,
            String autoRunId,
            String autoScheduleId,
            String autoGoalId,
            String autoTaskId) {

        private static MessageMetadataView empty() {
            return new MessageMetadataView(null, null, null, null, null, false, null, null, null, null);
        }
    }

    public record SnapshotPayloadExport(String payloadText, MediaType contentType, String fileExtension) {
    }
}

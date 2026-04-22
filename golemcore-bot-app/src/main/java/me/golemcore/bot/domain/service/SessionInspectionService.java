package me.golemcore.bot.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.trace.TraceEventRecord;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import me.golemcore.bot.domain.view.SessionDetailView;
import me.golemcore.bot.domain.view.SessionMessagesPageView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.domain.view.SessionTraceSnapshotView;
import me.golemcore.bot.domain.view.SessionTraceSpanView;
import me.golemcore.bot.domain.view.SessionTraceStorageStatsView;
import me.golemcore.bot.domain.view.SessionTraceSummaryView;
import me.golemcore.bot.domain.view.SessionTraceView;
import me.golemcore.bot.port.outbound.SessionInspectionQueryPort;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionInspectionService implements SessionInspectionQueryPort {

    private static final int MAX_PAGE_LIMIT = 100;
    private static final int SNAPSHOT_PREVIEW_MAX_CHARS = 4096;
    private static final int START_WITH_INDEX = 0;
    private static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile("^[\\w!#$&^_.+-]+/[\\w!#$&^_.+-]+$");
    private static final String CONTENT_TYPE_PARAMETER_NAME_CHARS = "!#$%&'*+.^_`|~-";

    private final SessionPort sessionPort;
    private final ActiveSessionPointerService pointerService;
    private final TraceSnapshotCompressionService traceSnapshotCompressionService;

    public List<SessionSummaryView> listSessions(String channel) {
        List<AgentSession> sessions = StringValueSupport.isBlank(channel)
                ? sessionPort.listAll()
                : sessionPort.listByChannelType(channel.trim());
        return sessions.stream()
                .sorted(ConversationKeyValidator.byRecentActivity())
                .map(session -> summarizeSession(session, false))
                .toList();
    }

    public SessionSummaryView resolveSession(String channel, String conversationKey) {
        if (StringValueSupport.isBlank(channel) || StringValueSupport.isBlank(conversationKey)) {
            throw new IllegalArgumentException("conversationKey is required");
        }
        String normalizedChannel = channel.trim();
        String normalizedConversationKey = conversationKey.trim();
        AgentSession session = sessionPort.listByChannelType(normalizedChannel).stream()
                .filter(candidate -> matchesConversationKey(candidate, normalizedConversationKey))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Session not found"));
        return summarizeSession(session, false);
    }

    public SessionSummaryView summarizeSession(AgentSession session, boolean active) {
        return SessionPresentationSupport.toSummary(session, active);
    }

    public SessionDetailView getSessionDetail(String sessionId) {
        return toDetail(requireSession(sessionId));
    }

    public SessionMessagesPageView getSessionMessages(String sessionId, int limit, String beforeMessageId) {
        AgentSession session = requireSession(sessionId);
        int normalizedLimit = Math.clamp(limit, 1, MAX_PAGE_LIMIT);
        List<Message> visibleMessages = SessionPresentationSupport.getVisibleMessages(session);
        int endExclusive = resolvePageEndExclusive(visibleMessages, beforeMessageId);
        int startInclusive = Math.max(START_WITH_INDEX, endExclusive - normalizedLimit);
        List<SessionDetailView.MessageView> page = visibleMessages.subList(startInclusive, endExclusive).stream()
                .map(this::toMessageDto)
                .toList();
        String oldestMessageId = page.isEmpty() ? null : page.get(START_WITH_INDEX).getId();

        return SessionMessagesPageView.builder()
                .sessionId(sessionId)
                .messages(page)
                .hasMore(startInclusive > START_WITH_INDEX)
                .oldestMessageId(oldestMessageId)
                .build();
    }

    public SessionTraceSummaryView getSessionTraceSummary(String sessionId) {
        return toTraceSummary(requireSession(sessionId));
    }

    public SessionTraceView getSessionTrace(String sessionId) {
        return toTraceDetail(requireSession(sessionId));
    }

    public SessionTraceExportView getSessionTraceExport(String sessionId) {
        return toTraceExportView(requireSession(sessionId));
    }

    public SnapshotPayloadExport exportSessionTraceSnapshotPayload(String sessionId, String snapshotId) {
        AgentSession session = requireSession(sessionId);
        TraceSnapshot snapshot = findTraceSnapshot(session, snapshotId)
                .orElseThrow(() -> new NoSuchElementException("Trace snapshot not found"));
        String payloadText = decompressSnapshotPayload(snapshot);
        if (payloadText == null) {
            throw new NoSuchElementException("Trace snapshot payload not found");
        }
        return new SnapshotPayloadExport(
                payloadText,
                resolveSnapshotContentType(snapshot),
                resolveSnapshotFileExtension(snapshot));
    }

    public Map<String, Object> getSessionTraceSnapshotPayload(String sessionId, String snapshotId) {
        SnapshotPayloadExport export = exportSessionTraceSnapshotPayload(sessionId, snapshotId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payloadText", export.payloadText());
        payload.put("contentType", export.contentType());
        payload.put("fileExtension", export.fileExtension());
        return payload;
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

    public SessionTraceSnapshotView toTraceSnapshotView(TraceSnapshot snapshot, boolean includePayloadPreview) {
        SnapshotPreview preview = includePayloadPreview ? buildSnapshotPreview(snapshot) : SnapshotPreview.empty();
        return SessionTraceSnapshotView.builder()
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
                .orElseThrow(() -> new NoSuchElementException("Session not found"));
    }

    private SessionDetailView toDetail(AgentSession session) {
        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
        String transportChatId = SessionIdentitySupport.resolveTransportChatId(session);
        List<SessionDetailView.MessageView> messages = List.of();
        if (session.getMessages() != null) {
            messages = session.getMessages().stream()
                    .filter(SessionPresentationSupport::isHistoryVisibleMessage)
                    .map(this::toMessageDto)
                    .toList();
        }
        return SessionDetailView.builder()
                .id(session.getId())
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .conversationKey(conversationKey)
                .transportChatId(transportChatId)
                .state(session.getState() != null ? session.getState().name() : "ACTIVE")
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .messages(messages)
                .build();
    }

    private SessionDetailView.MessageView toMessageDto(Message message) {
        MessageMetadataView metadata = resolveMetadataView(message);
        return SessionDetailView.MessageView.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(SessionPresentationSupport.resolveMessageContent(message))
                .timestamp(message.getTimestamp())
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

    private SessionTraceSummaryView toTraceSummary(AgentSession session) {
        List<TraceRecord> traces = getSortedTraces(session);
        int spanCount = traces.stream()
                .mapToInt(trace -> trace.getSpans() != null ? trace.getSpans().size() : 0)
                .sum();
        int snapshotCount = traces.stream()
                .mapToInt(this::countSnapshots)
                .sum();
        List<SessionTraceSummaryView.TraceSummaryView> traceSummaries = traces.stream()
                .map(this::toTraceSummaryItem)
                .toList();
        return SessionTraceSummaryView.builder()
                .sessionId(session.getId())
                .traceCount(traces.size())
                .spanCount(spanCount)
                .snapshotCount(snapshotCount)
                .storageStats(toTraceStorageStatsDto(session.getTraceStorageStats()))
                .traces(traceSummaries)
                .build();
    }

    private SessionTraceView toTraceDetail(AgentSession session) {
        List<SessionTraceView.TraceView> traces = getSortedTraces(session).stream()
                .map(trace -> toTraceDto(trace, true))
                .toList();
        return SessionTraceView.builder()
                .sessionId(session.getId())
                .storageStats(toTraceStorageStatsDto(session.getTraceStorageStats()))
                .traces(traces)
                .build();
    }

    private SessionTraceExportView toTraceExportView(AgentSession session) {
        return SessionTraceExportView.builder()
                .sessionId(session.getId())
                .storageStats(toTraceStorageStatsDto(session.getTraceStorageStats()))
                .traces(getSortedTraces(session).stream().map(this::toTraceExportItem).toList())
                .build();
    }

    private SessionTraceSummaryView.TraceSummaryView toTraceSummaryItem(TraceRecord trace) {
        TraceSpanRecord rootSpan = findRootSpan(trace);
        return SessionTraceSummaryView.TraceSummaryView.builder()
                .traceId(trace.getTraceId())
                .rootSpanId(trace.getRootSpanId())
                .traceName(trace.getTraceName())
                .rootKind(rootSpan != null && rootSpan.getKind() != null ? rootSpan.getKind().name() : null)
                .rootStatusCode(rootSpan != null && rootSpan.getStatusCode() != null
                        ? rootSpan.getStatusCode().name()
                        : null)
                .startedAt(trace.getStartedAt())
                .endedAt(trace.getEndedAt())
                .durationMs(toDurationMs(trace.getStartedAt(), trace.getEndedAt()))
                .spanCount(trace.getSpans() != null ? trace.getSpans().size() : 0)
                .snapshotCount(countSnapshots(trace))
                .truncated(trace.isTruncated())
                .build();
    }

    private SessionTraceView.TraceView toTraceDto(TraceRecord trace, boolean includeSnapshotPreview) {
        List<SessionTraceSpanView> spans = getSortedSpans(trace).stream()
                .map(span -> toTraceSpanDto(span, includeSnapshotPreview))
                .toList();
        return SessionTraceView.TraceView.builder()
                .traceId(trace.getTraceId())
                .rootSpanId(trace.getRootSpanId())
                .traceName(trace.getTraceName())
                .startedAt(trace.getStartedAt())
                .endedAt(trace.getEndedAt())
                .truncated(trace.isTruncated())
                .compressedSnapshotBytes(trace.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(trace.getUncompressedSnapshotBytes())
                .spans(spans)
                .build();
    }

    private SessionTraceSpanView toTraceSpanDto(TraceSpanRecord span, boolean includeSnapshotPreview) {
        List<SessionTraceSpanView.EventView> events = span.getEvents() == null
                ? List.of()
                : span.getEvents().stream()
                        .map(this::toTraceEventDto)
                        .toList();
        List<SessionTraceSnapshotView> snapshots = span.getSnapshots() == null
                ? List.of()
                : span.getSnapshots().stream()
                        .map(snapshot -> toTraceSnapshotView(snapshot, includeSnapshotPreview))
                        .toList();
        return SessionTraceSpanView.builder()
                .spanId(span.getSpanId())
                .parentSpanId(span.getParentSpanId())
                .name(span.getName())
                .kind(span.getKind() != null ? span.getKind().name() : null)
                .statusCode(span.getStatusCode() != null ? span.getStatusCode().name() : null)
                .statusMessage(span.getStatusMessage())
                .startedAt(span.getStartedAt())
                .endedAt(span.getEndedAt())
                .durationMs(toDurationMs(span.getStartedAt(), span.getEndedAt()))
                .attributes(copyAttributes(span.getAttributes()))
                .events(events)
                .snapshots(snapshots)
                .build();
    }

    private SessionTraceSpanView.EventView toTraceEventDto(TraceEventRecord event) {
        return SessionTraceSpanView.EventView.builder()
                .name(event.getName())
                .timestamp(event.getTimestamp())
                .attributes(copyAttributes(event.getAttributes()))
                .build();
    }

    private SessionTraceStorageStatsView toTraceStorageStatsDto(TraceStorageStats stats) {
        TraceStorageStats effective = stats != null ? stats : TraceStorageStats.builder().build();
        return SessionTraceStorageStatsView.builder()
                .compressedSnapshotBytes(effective.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(effective.getUncompressedSnapshotBytes())
                .evictedSnapshots(effective.getEvictedSnapshots())
                .evictedTraces(effective.getEvictedTraces())
                .truncatedTraces(effective.getTruncatedTraces())
                .build();
    }

    private SessionTraceExportView.TraceExportView toTraceExportItem(TraceRecord trace) {
        return SessionTraceExportView.TraceExportView.builder()
                .traceId(trace.getTraceId())
                .rootSpanId(trace.getRootSpanId())
                .traceName(trace.getTraceName())
                .startedAt(trace.getStartedAt())
                .endedAt(trace.getEndedAt())
                .truncated(trace.isTruncated())
                .compressedSnapshotBytes(trace.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(trace.getUncompressedSnapshotBytes())
                .spans(getSortedSpans(trace).stream().map(this::toTraceExportSpan).toList())
                .build();
    }

    private SessionTraceExportView.SpanExportView toTraceExportSpan(TraceSpanRecord span) {
        List<SessionTraceExportView.EventExportView> events = span.getEvents() == null
                ? List.of()
                : span.getEvents().stream().map(this::toTraceExportEvent).toList();
        List<SessionTraceExportView.SnapshotExportView> snapshots = span.getSnapshots() == null
                ? List.of()
                : span.getSnapshots().stream().map(this::toTraceExportSnapshot).toList();
        return SessionTraceExportView.SpanExportView.builder()
                .spanId(span.getSpanId())
                .parentSpanId(span.getParentSpanId())
                .name(span.getName())
                .kind(span.getKind() != null ? span.getKind().name() : null)
                .status(SessionTraceExportView.StatusView.builder()
                        .code(span.getStatusCode() != null ? span.getStatusCode().name() : null)
                        .message(span.getStatusMessage())
                        .build())
                .startedAt(span.getStartedAt())
                .endedAt(span.getEndedAt())
                .durationMs(toDurationMs(span.getStartedAt(), span.getEndedAt()))
                .attributes(copyAttributes(span.getAttributes()))
                .events(events)
                .snapshots(snapshots)
                .build();
    }

    private SessionTraceExportView.EventExportView toTraceExportEvent(TraceEventRecord event) {
        return SessionTraceExportView.EventExportView.builder()
                .name(event.getName())
                .timestamp(event.getTimestamp())
                .attributes(copyAttributes(event.getAttributes()))
                .build();
    }

    private SessionTraceExportView.SnapshotExportView toTraceExportSnapshot(TraceSnapshot snapshot) {
        return SessionTraceExportView.SnapshotExportView.builder()
                .snapshotId(snapshot.getSnapshotId())
                .role(snapshot.getRole())
                .contentType(snapshot.getContentType())
                .encoding(snapshot.getEncoding())
                .originalSize(snapshot.getOriginalSize())
                .compressedSize(snapshot.getCompressedSize())
                .truncated(snapshot.isTruncated())
                .payloadText(decompressSnapshotPayload(snapshot))
                .build();
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

    private String resolveSnapshotContentType(TraceSnapshot snapshot) {
        if (snapshot == null || StringValueSupport.isBlank(snapshot.getContentType())) {
            return "application/json";
        }
        String contentType = snapshot.getContentType().trim();
        String baseType = extractContentTypeBase(contentType);
        if (!CONTENT_TYPE_PATTERN.matcher(baseType).matches() || !hasValidContentTypeParameters(contentType)) {
            return "application/octet-stream";
        }
        return contentType;
    }

    private String resolveSnapshotFileExtension(TraceSnapshot snapshot) {
        String contentType = resolveSnapshotContentType(snapshot);
        return isJsonContentType(contentType) ? ".json" : ".txt";
    }

    private String extractContentTypeBase(String contentType) {
        int separatorIndex = contentType.indexOf(';');
        if (separatorIndex < 0) {
            return contentType;
        }
        return contentType.substring(0, separatorIndex).trim();
    }

    private boolean hasValidContentTypeParameters(String contentType) {
        String[] parts = contentType.split(";");
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].trim();
            if (!isValidContentTypeParameter(parameter)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidContentTypeParameter(String parameter) {
        if (parameter == null || parameter.isEmpty()) {
            return false;
        }

        int separatorIndex = parameter.indexOf('=');
        if (separatorIndex <= 0 || separatorIndex == parameter.length() - 1) {
            return false;
        }

        String name = parameter.substring(0, separatorIndex).trim();
        String value = parameter.substring(separatorIndex + 1).trim();
        if (name.isEmpty() || value.isEmpty()) {
            return false;
        }

        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!Character.isLetterOrDigit(character)
                    && CONTENT_TYPE_PARAMETER_NAME_CHARS.indexOf(character) < 0) {
                return false;
            }
        }
        return value.indexOf(';') < 0;
    }

    private boolean isJsonContentType(String contentType) {
        String baseType = extractContentTypeBase(contentType);
        return "application/json".equalsIgnoreCase(baseType) || baseType.endsWith("+json");
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
        throw new IllegalArgumentException("beforeMessageId not found");
    }

    @SuppressWarnings("unchecked")
    private List<SessionDetailView.AttachmentView> resolveAttachments(Message message) {
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
    private SessionDetailView.AttachmentView toAttachmentDto(Object attachmentObj) {
        if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>((Map<String, Object>) attachmentMap);
        String type = readAttachmentString(normalized, "type");
        String name = readAttachmentString(normalized, "name");
        String mimeType = readAttachmentString(normalized, "mimeType");
        String directUrl = readAttachmentString(normalized, "url");
        String internalFilePath = readAttachmentString(normalized, "internalFilePath");
        String thumbnailBase64 = readAttachmentString(normalized, "thumbnailBase64");
        if (type == null && name == null && mimeType == null && directUrl == null
                && internalFilePath == null && thumbnailBase64 == null) {
            return null;
        }
        return SessionDetailView.AttachmentView.builder()
                .type(type)
                .name(name)
                .mimeType(mimeType)
                .directUrl(directUrl)
                .internalFilePath(internalFilePath)
                .thumbnailBase64(thumbnailBase64)
                .build();
    }

    private String readAttachmentString(Map<String, Object> attachment, String key) {
        Object value = attachment.get(key);
        if (!(value instanceof String stringValue)) {
            return null;
        }
        String normalized = stringValue.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean matchesConversationKey(AgentSession session, String conversationKey) {
        if (session == null || StringValueSupport.isBlank(conversationKey)) {
            return false;
        }
        String resolvedConversationKey = SessionIdentitySupport.resolveConversationKey(session);
        return conversationKey.equals(resolvedConversationKey) || conversationKey.equals(session.getChatId());
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

            String pointerTransportChatId = ChannelTypes.TELEGRAM.equals(channel)
                    ? extractTelegramTransportChatId(pointerKey)
                    : null;
            String replacement = SessionConversationSupport.resolveOrCreateConversationKey(
                    sessionPort, channel, pointerTransportChatId, null);
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
        String prefix = ChannelTypes.TELEGRAM + "|";
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

    public record SnapshotPayloadExport(String payloadText, String contentType, String fileExtension) {
    }
}

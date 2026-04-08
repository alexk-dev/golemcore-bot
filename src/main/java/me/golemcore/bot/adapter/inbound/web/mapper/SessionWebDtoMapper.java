package me.golemcore.bot.adapter.inbound.web.mapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.adapter.inbound.web.dto.SessionDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionMessagesPageDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionSummaryDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSnapshotDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSpanDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceStorageStatsDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSummaryDto;
import me.golemcore.bot.domain.view.SessionDetailView;
import me.golemcore.bot.domain.view.SessionMessagesPageView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.domain.view.SessionTraceSnapshotView;
import me.golemcore.bot.domain.view.SessionTraceSpanView;
import me.golemcore.bot.domain.view.SessionTraceStorageStatsView;
import me.golemcore.bot.domain.view.SessionTraceSummaryView;
import me.golemcore.bot.domain.view.SessionTraceView;
import org.springframework.stereotype.Component;

@Component
public class SessionWebDtoMapper {

    public List<SessionSummaryDto> toSummaryDtos(List<SessionSummaryView> views) {
        return views.stream().map(this::toSummaryDto).toList();
    }

    public SessionSummaryDto toSummaryDto(SessionSummaryView view) {
        return SessionSummaryDto.builder()
                .id(view.getId())
                .channelType(view.getChannelType())
                .chatId(view.getChatId())
                .conversationKey(view.getConversationKey())
                .transportChatId(view.getTransportChatId())
                .messageCount(view.getMessageCount())
                .state(view.getState())
                .createdAt(toTimestamp(view.getCreatedAt()))
                .updatedAt(toTimestamp(view.getUpdatedAt()))
                .title(view.getTitle())
                .preview(view.getPreview())
                .active(view.isActive())
                .build();
    }

    public SessionDetailDto toDetailDto(SessionDetailView view) {
        return SessionDetailDto.builder()
                .id(view.getId())
                .channelType(view.getChannelType())
                .chatId(view.getChatId())
                .conversationKey(view.getConversationKey())
                .transportChatId(view.getTransportChatId())
                .state(view.getState())
                .createdAt(toTimestamp(view.getCreatedAt()))
                .updatedAt(toTimestamp(view.getUpdatedAt()))
                .messages(view.getMessages().stream().map(this::toMessageDto).toList())
                .build();
    }

    public SessionMessagesPageDto toMessagesPageDto(SessionMessagesPageView view) {
        return SessionMessagesPageDto.builder()
                .sessionId(view.getSessionId())
                .messages(view.getMessages().stream().map(this::toMessageDto).toList())
                .hasMore(view.isHasMore())
                .oldestMessageId(view.getOldestMessageId())
                .build();
    }

    public SessionTraceSummaryDto toTraceSummaryDto(SessionTraceSummaryView view) {
        return SessionTraceSummaryDto.builder()
                .sessionId(view.getSessionId())
                .traceCount(view.getTraceCount())
                .spanCount(view.getSpanCount())
                .snapshotCount(view.getSnapshotCount())
                .storageStats(toTraceStorageStatsDto(view.getStorageStats()))
                .traces(view.getTraces().stream().map(this::toTraceSummaryDto).toList())
                .build();
    }

    public SessionTraceDto toTraceDto(SessionTraceView view) {
        return SessionTraceDto.builder()
                .sessionId(view.getSessionId())
                .storageStats(toTraceStorageStatsDto(view.getStorageStats()))
                .traces(view.getTraces().stream().map(this::toTraceDto).toList())
                .build();
    }

    public Map<String, Object> toTraceExportPayload(SessionTraceExportView view) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", view.getSessionId());
        payload.put("storageStats", toTraceStorageStatsPayload(view.getStorageStats()));
        payload.put("traces", view.getTraces().stream().map(this::toTraceExportPayload).toList());
        return payload;
    }

    public SessionTraceSnapshotDto toTraceSnapshotDto(SessionTraceSnapshotView view) {
        return SessionTraceSnapshotDto.builder()
                .snapshotId(view.getSnapshotId())
                .role(view.getRole())
                .contentType(view.getContentType())
                .encoding(view.getEncoding())
                .originalSize(view.getOriginalSize())
                .compressedSize(view.getCompressedSize())
                .truncated(view.isTruncated())
                .payloadAvailable(view.isPayloadAvailable())
                .payloadPreview(view.getPayloadPreview())
                .payloadPreviewTruncated(view.isPayloadPreviewTruncated())
                .build();
    }

    private SessionDetailDto.MessageDto toMessageDto(SessionDetailView.MessageView view) {
        return SessionDetailDto.MessageDto.builder()
                .id(view.getId())
                .role(view.getRole())
                .content(view.getContent())
                .timestamp(toTimestamp(view.getTimestamp()))
                .hasToolCalls(view.isHasToolCalls())
                .hasVoice(view.isHasVoice())
                .model(view.getModel())
                .modelTier(view.getModelTier())
                .skill(view.getSkill())
                .reasoning(view.getReasoning())
                .clientMessageId(view.getClientMessageId())
                .autoMode(view.isAutoMode())
                .autoRunId(view.getAutoRunId())
                .autoScheduleId(view.getAutoScheduleId())
                .autoGoalId(view.getAutoGoalId())
                .autoTaskId(view.getAutoTaskId())
                .attachments(view.getAttachments().stream().map(this::toAttachmentDto).toList())
                .build();
    }

    private SessionDetailDto.AttachmentDto toAttachmentDto(SessionDetailView.AttachmentView view) {
        return SessionDetailDto.AttachmentDto.builder()
                .type(view.getType())
                .name(view.getName())
                .mimeType(view.getMimeType())
                .url(resolveAttachmentUrl(view))
                .internalFilePath(view.getInternalFilePath())
                .thumbnailBase64(view.getThumbnailBase64())
                .build();
    }

    private SessionTraceSummaryDto.TraceSummaryDto toTraceSummaryDto(SessionTraceSummaryView.TraceSummaryView view) {
        return SessionTraceSummaryDto.TraceSummaryDto.builder()
                .traceId(view.getTraceId())
                .rootSpanId(view.getRootSpanId())
                .traceName(view.getTraceName())
                .rootKind(view.getRootKind())
                .rootStatusCode(view.getRootStatusCode())
                .startedAt(toTimestamp(view.getStartedAt()))
                .endedAt(toTimestamp(view.getEndedAt()))
                .durationMs(view.getDurationMs())
                .spanCount(view.getSpanCount())
                .snapshotCount(view.getSnapshotCount())
                .truncated(view.isTruncated())
                .build();
    }

    private SessionTraceDto.TraceDto toTraceDto(SessionTraceView.TraceView view) {
        return SessionTraceDto.TraceDto.builder()
                .traceId(view.getTraceId())
                .rootSpanId(view.getRootSpanId())
                .traceName(view.getTraceName())
                .startedAt(toTimestamp(view.getStartedAt()))
                .endedAt(toTimestamp(view.getEndedAt()))
                .truncated(view.isTruncated())
                .compressedSnapshotBytes(view.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(view.getUncompressedSnapshotBytes())
                .spans(view.getSpans().stream().map(this::toTraceSpanDto).toList())
                .build();
    }

    private SessionTraceSpanDto toTraceSpanDto(SessionTraceSpanView view) {
        return SessionTraceSpanDto.builder()
                .spanId(view.getSpanId())
                .parentSpanId(view.getParentSpanId())
                .name(view.getName())
                .kind(view.getKind())
                .statusCode(view.getStatusCode())
                .statusMessage(view.getStatusMessage())
                .startedAt(toTimestamp(view.getStartedAt()))
                .endedAt(toTimestamp(view.getEndedAt()))
                .durationMs(view.getDurationMs())
                .attributes(view.getAttributes())
                .events(view.getEvents().stream().map(this::toTraceEventDto).toList())
                .snapshots(view.getSnapshots().stream().map(this::toTraceSnapshotDto).toList())
                .build();
    }

    private SessionTraceSpanDto.EventDto toTraceEventDto(SessionTraceSpanView.EventView view) {
        return SessionTraceSpanDto.EventDto.builder()
                .name(view.getName())
                .timestamp(toTimestamp(view.getTimestamp()))
                .attributes(view.getAttributes())
                .build();
    }

    private SessionTraceStorageStatsDto toTraceStorageStatsDto(SessionTraceStorageStatsView view) {
        return SessionTraceStorageStatsDto.builder()
                .compressedSnapshotBytes(view.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(view.getUncompressedSnapshotBytes())
                .evictedSnapshots(view.getEvictedSnapshots())
                .evictedTraces(view.getEvictedTraces())
                .truncatedTraces(view.getTruncatedTraces())
                .build();
    }

    private Map<String, Object> toTraceExportPayload(SessionTraceExportView.TraceExportView view) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", view.getTraceId());
        payload.put("rootSpanId", view.getRootSpanId());
        payload.put("traceName", view.getTraceName());
        payload.put("startedAt", toTimestamp(view.getStartedAt()));
        payload.put("endedAt", toTimestamp(view.getEndedAt()));
        payload.put("truncated", view.isTruncated());
        payload.put("compressedSnapshotBytes", view.getCompressedSnapshotBytes());
        payload.put("uncompressedSnapshotBytes", view.getUncompressedSnapshotBytes());
        payload.put("spans", view.getSpans().stream().map(this::toTraceExportSpanPayload).toList());
        return payload;
    }

    private Map<String, Object> toTraceExportSpanPayload(SessionTraceExportView.SpanExportView view) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("spanId", view.getSpanId());
        payload.put("parentSpanId", view.getParentSpanId());
        payload.put("name", view.getName());
        payload.put("kind", view.getKind());
        payload.put("status", toTraceExportStatusPayload(view.getStatus()));
        payload.put("startedAt", toTimestamp(view.getStartedAt()));
        payload.put("endedAt", toTimestamp(view.getEndedAt()));
        payload.put("durationMs", view.getDurationMs());
        payload.put("attributes", view.getAttributes());
        payload.put("events", view.getEvents().stream().map(this::toTraceExportEventPayload).toList());
        payload.put("snapshots", view.getSnapshots().stream().map(this::toTraceExportSnapshotPayload).toList());
        return payload;
    }

    private Map<String, Object> toTraceExportStatusPayload(SessionTraceExportView.StatusView view) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (view == null) {
            payload.put("code", null);
            payload.put("message", null);
            return payload;
        }
        payload.put("code", view.getCode());
        payload.put("message", view.getMessage());
        return payload;
    }

    private Map<String, Object> toTraceExportEventPayload(SessionTraceExportView.EventExportView view) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", view.getName());
        payload.put("timestamp", toTimestamp(view.getTimestamp()));
        payload.put("attributes", view.getAttributes());
        return payload;
    }

    private Map<String, Object> toTraceExportSnapshotPayload(SessionTraceExportView.SnapshotExportView view) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotId", view.getSnapshotId());
        payload.put("role", view.getRole());
        payload.put("contentType", view.getContentType());
        payload.put("encoding", view.getEncoding());
        payload.put("originalSize", view.getOriginalSize());
        payload.put("compressedSize", view.getCompressedSize());
        payload.put("truncated", view.isTruncated());
        payload.put("payloadText", view.getPayloadText());
        return payload;
    }

    private Map<String, Object> toTraceStorageStatsPayload(SessionTraceStorageStatsView view) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("compressedSnapshotBytes", view.getCompressedSnapshotBytes());
        payload.put("uncompressedSnapshotBytes", view.getUncompressedSnapshotBytes());
        payload.put("evictedSnapshots", view.getEvictedSnapshots());
        payload.put("evictedTraces", view.getEvictedTraces());
        payload.put("truncatedTraces", view.getTruncatedTraces());
        return payload;
    }

    private String resolveAttachmentUrl(SessionDetailView.AttachmentView view) {
        if (view.getDirectUrl() != null) {
            return view.getDirectUrl();
        }
        if (view.getInternalFilePath() == null) {
            return null;
        }
        String encoded = URLEncoder.encode(view.getInternalFilePath(), StandardCharsets.UTF_8).replace("+", "%20");
        return "/api/files/download?path=" + encoded;
    }

    private String toTimestamp(Instant timestamp) {
        return timestamp != null ? timestamp.toString() : null;
    }
}

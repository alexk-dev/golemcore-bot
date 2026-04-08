package me.golemcore.bot.adapter.outbound.hive;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.domain.view.SessionDetailView;
import me.golemcore.bot.domain.view.SessionMessagesPageView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.domain.view.SessionTraceSnapshotView;
import me.golemcore.bot.domain.view.SessionTraceSpanView;
import me.golemcore.bot.domain.view.SessionTraceStorageStatsView;
import me.golemcore.bot.domain.view.SessionTraceSummaryView;
import me.golemcore.bot.domain.view.SessionTraceView;
import me.golemcore.bot.port.outbound.HiveInspectionPayloadPort;
import org.springframework.stereotype.Component;

@Component
public class HiveInspectionPayloadMapper implements HiveInspectionPayloadPort {

    @Override
    public Object toSessionListPayload(List<SessionSummaryView> sessions) {
        return sessions.stream().map(this::toSummaryPayload).toList();
    }

    @Override
    public Object toSessionDetailPayload(SessionDetailView sessionDetail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", sessionDetail.getId());
        payload.put("channelType", sessionDetail.getChannelType());
        payload.put("chatId", sessionDetail.getChatId());
        payload.put("conversationKey", sessionDetail.getConversationKey());
        payload.put("transportChatId", sessionDetail.getTransportChatId());
        payload.put("state", sessionDetail.getState());
        payload.put("createdAt", toTimestamp(sessionDetail.getCreatedAt()));
        payload.put("updatedAt", toTimestamp(sessionDetail.getUpdatedAt()));
        payload.put("messages", sessionDetail.getMessages().stream().map(this::toMessagePayload).toList());
        return payload;
    }

    @Override
    public Object toSessionMessagesPayload(SessionMessagesPageView messagesPage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", messagesPage.getSessionId());
        payload.put("messages", messagesPage.getMessages().stream().map(this::toMessagePayload).toList());
        payload.put("hasMore", messagesPage.isHasMore());
        payload.put("oldestMessageId", messagesPage.getOldestMessageId());
        return payload;
    }

    @Override
    public Object toSessionTraceSummaryPayload(SessionTraceSummaryView traceSummary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", traceSummary.getSessionId());
        payload.put("traceCount", traceSummary.getTraceCount());
        payload.put("spanCount", traceSummary.getSpanCount());
        payload.put("snapshotCount", traceSummary.getSnapshotCount());
        payload.put("storageStats", toTraceStorageStatsPayload(traceSummary.getStorageStats()));
        payload.put("traces", traceSummary.getTraces().stream().map(this::toTraceSummaryPayload).toList());
        return payload;
    }

    @Override
    public Object toSessionTracePayload(SessionTraceView trace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", trace.getSessionId());
        payload.put("storageStats", toTraceStorageStatsPayload(trace.getStorageStats()));
        payload.put("traces", trace.getTraces().stream().map(this::toTracePayload).toList());
        return payload;
    }

    @Override
    public Object toSessionTraceExportPayload(SessionTraceExportView traceExport) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", traceExport.getSessionId());
        payload.put("storageStats", toTraceStorageStatsPayload(traceExport.getStorageStats()));
        payload.put("traces", traceExport.getTraces().stream().map(this::toTraceExportPayload).toList());
        return payload;
    }

    private Map<String, Object> toSummaryPayload(SessionSummaryView session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", session.getId());
        payload.put("channelType", session.getChannelType());
        payload.put("chatId", session.getChatId());
        payload.put("conversationKey", session.getConversationKey());
        payload.put("transportChatId", session.getTransportChatId());
        payload.put("messageCount", session.getMessageCount());
        payload.put("state", session.getState());
        payload.put("createdAt", toTimestamp(session.getCreatedAt()));
        payload.put("updatedAt", toTimestamp(session.getUpdatedAt()));
        payload.put("title", session.getTitle());
        payload.put("preview", session.getPreview());
        payload.put("active", session.isActive());
        return payload;
    }

    private Map<String, Object> toMessagePayload(SessionDetailView.MessageView message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", message.getId());
        payload.put("role", message.getRole());
        payload.put("content", message.getContent());
        payload.put("timestamp", toTimestamp(message.getTimestamp()));
        payload.put("hasToolCalls", message.isHasToolCalls());
        payload.put("hasVoice", message.isHasVoice());
        payload.put("model", message.getModel());
        payload.put("modelTier", message.getModelTier());
        payload.put("skill", message.getSkill());
        payload.put("reasoning", message.getReasoning());
        payload.put("clientMessageId", message.getClientMessageId());
        payload.put("autoMode", message.isAutoMode());
        payload.put("autoRunId", message.getAutoRunId());
        payload.put("autoScheduleId", message.getAutoScheduleId());
        payload.put("autoGoalId", message.getAutoGoalId());
        payload.put("autoTaskId", message.getAutoTaskId());
        payload.put("attachments", message.getAttachments().stream().map(this::toAttachmentPayload).toList());
        return payload;
    }

    private Map<String, Object> toAttachmentPayload(SessionDetailView.AttachmentView attachment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", attachment.getType());
        payload.put("name", attachment.getName());
        payload.put("mimeType", attachment.getMimeType());
        putIfNotNull(payload, "directUrl", attachment.getDirectUrl());
        putIfNotNull(payload, "internalFilePath", attachment.getInternalFilePath());
        payload.put("thumbnailBase64", attachment.getThumbnailBase64());
        return payload;
    }

    private Map<String, Object> toTraceSummaryPayload(SessionTraceSummaryView.TraceSummaryView trace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", trace.getTraceId());
        payload.put("rootSpanId", trace.getRootSpanId());
        payload.put("traceName", trace.getTraceName());
        payload.put("rootKind", trace.getRootKind());
        payload.put("rootStatusCode", trace.getRootStatusCode());
        payload.put("startedAt", toTimestamp(trace.getStartedAt()));
        payload.put("endedAt", toTimestamp(trace.getEndedAt()));
        payload.put("durationMs", trace.getDurationMs());
        payload.put("spanCount", trace.getSpanCount());
        payload.put("snapshotCount", trace.getSnapshotCount());
        payload.put("truncated", trace.isTruncated());
        return payload;
    }

    private Map<String, Object> toTracePayload(SessionTraceView.TraceView trace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", trace.getTraceId());
        payload.put("rootSpanId", trace.getRootSpanId());
        payload.put("traceName", trace.getTraceName());
        payload.put("startedAt", toTimestamp(trace.getStartedAt()));
        payload.put("endedAt", toTimestamp(trace.getEndedAt()));
        payload.put("truncated", trace.isTruncated());
        payload.put("compressedSnapshotBytes", trace.getCompressedSnapshotBytes());
        payload.put("uncompressedSnapshotBytes", trace.getUncompressedSnapshotBytes());
        payload.put("spans", trace.getSpans().stream().map(this::toTraceSpanPayload).toList());
        return payload;
    }

    private Map<String, Object> toTraceExportPayload(SessionTraceExportView.TraceExportView trace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", trace.getTraceId());
        payload.put("rootSpanId", trace.getRootSpanId());
        payload.put("traceName", trace.getTraceName());
        payload.put("startedAt", toTimestamp(trace.getStartedAt()));
        payload.put("endedAt", toTimestamp(trace.getEndedAt()));
        payload.put("truncated", trace.isTruncated());
        payload.put("compressedSnapshotBytes", trace.getCompressedSnapshotBytes());
        payload.put("uncompressedSnapshotBytes", trace.getUncompressedSnapshotBytes());
        payload.put("spans", trace.getSpans().stream().map(this::toTraceExportSpanPayload).toList());
        return payload;
    }

    private Map<String, Object> toTraceSpanPayload(SessionTraceSpanView span) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("spanId", span.getSpanId());
        payload.put("parentSpanId", span.getParentSpanId());
        payload.put("name", span.getName());
        payload.put("kind", span.getKind());
        payload.put("statusCode", span.getStatusCode());
        payload.put("statusMessage", span.getStatusMessage());
        payload.put("startedAt", toTimestamp(span.getStartedAt()));
        payload.put("endedAt", toTimestamp(span.getEndedAt()));
        payload.put("durationMs", span.getDurationMs());
        payload.put("attributes", span.getAttributes());
        payload.put("events", span.getEvents().stream().map(this::toTraceEventPayload).toList());
        payload.put("snapshots", span.getSnapshots().stream().map(this::toTraceSnapshotPayload).toList());
        return payload;
    }

    private Map<String, Object> toTraceEventPayload(SessionTraceSpanView.EventView event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", event.getName());
        payload.put("timestamp", toTimestamp(event.getTimestamp()));
        payload.put("attributes", event.getAttributes());
        return payload;
    }

    private Map<String, Object> toTraceExportSpanPayload(SessionTraceExportView.SpanExportView span) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("spanId", span.getSpanId());
        payload.put("parentSpanId", span.getParentSpanId());
        payload.put("name", span.getName());
        payload.put("kind", span.getKind());
        payload.put("status", toTraceExportStatusPayload(span.getStatus()));
        payload.put("startedAt", toTimestamp(span.getStartedAt()));
        payload.put("endedAt", toTimestamp(span.getEndedAt()));
        payload.put("durationMs", span.getDurationMs());
        payload.put("attributes", span.getAttributes());
        payload.put("events", span.getEvents().stream().map(this::toTraceExportEventPayload).toList());
        payload.put("snapshots", span.getSnapshots().stream().map(this::toTraceExportSnapshotPayload).toList());
        return payload;
    }

    private Map<String, Object> toTraceExportStatusPayload(SessionTraceExportView.StatusView status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (status == null) {
            payload.put("code", null);
            payload.put("message", null);
            return payload;
        }
        payload.put("code", status.getCode());
        payload.put("message", status.getMessage());
        return payload;
    }

    private Map<String, Object> toTraceExportEventPayload(SessionTraceExportView.EventExportView event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", event.getName());
        payload.put("timestamp", toTimestamp(event.getTimestamp()));
        payload.put("attributes", event.getAttributes());
        return payload;
    }

    private Map<String, Object> toTraceExportSnapshotPayload(SessionTraceExportView.SnapshotExportView snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotId", snapshot.getSnapshotId());
        payload.put("role", snapshot.getRole());
        payload.put("contentType", snapshot.getContentType());
        payload.put("encoding", snapshot.getEncoding());
        payload.put("originalSize", snapshot.getOriginalSize());
        payload.put("compressedSize", snapshot.getCompressedSize());
        payload.put("truncated", snapshot.isTruncated());
        payload.put("payloadText", snapshot.getPayloadText());
        return payload;
    }

    private Map<String, Object> toTraceSnapshotPayload(SessionTraceSnapshotView snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotId", snapshot.getSnapshotId());
        payload.put("role", snapshot.getRole());
        payload.put("contentType", snapshot.getContentType());
        payload.put("encoding", snapshot.getEncoding());
        payload.put("originalSize", snapshot.getOriginalSize());
        payload.put("compressedSize", snapshot.getCompressedSize());
        payload.put("truncated", snapshot.isTruncated());
        payload.put("payloadAvailable", snapshot.isPayloadAvailable());
        payload.put("payloadPreview", snapshot.getPayloadPreview());
        payload.put("payloadPreviewTruncated", snapshot.isPayloadPreviewTruncated());
        return payload;
    }

    private Map<String, Object> toTraceStorageStatsPayload(SessionTraceStorageStatsView stats) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("compressedSnapshotBytes", stats.getCompressedSnapshotBytes());
        payload.put("uncompressedSnapshotBytes", stats.getUncompressedSnapshotBytes());
        payload.put("evictedSnapshots", stats.getEvictedSnapshots());
        payload.put("evictedTraces", stats.getEvictedTraces());
        payload.put("truncatedTraces", stats.getTruncatedTraces());
        return payload;
    }

    private String toTimestamp(Instant timestamp) {
        return timestamp != null ? timestamp.toString() : null;
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}

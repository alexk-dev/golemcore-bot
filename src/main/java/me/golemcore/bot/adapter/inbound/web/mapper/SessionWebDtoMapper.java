package me.golemcore.bot.adapter.inbound.web.mapper;

import java.util.List;
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
                .createdAt(view.getCreatedAt())
                .updatedAt(view.getUpdatedAt())
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
                .createdAt(view.getCreatedAt())
                .updatedAt(view.getUpdatedAt())
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
                .timestamp(view.getTimestamp())
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
                .url(view.getUrl())
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
                .startedAt(view.getStartedAt())
                .endedAt(view.getEndedAt())
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
                .startedAt(view.getStartedAt())
                .endedAt(view.getEndedAt())
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
                .startedAt(view.getStartedAt())
                .endedAt(view.getEndedAt())
                .durationMs(view.getDurationMs())
                .attributes(view.getAttributes())
                .events(view.getEvents().stream().map(this::toTraceEventDto).toList())
                .snapshots(view.getSnapshots().stream().map(this::toTraceSnapshotDto).toList())
                .build();
    }

    private SessionTraceSpanDto.EventDto toTraceEventDto(SessionTraceSpanView.EventView view) {
        return SessionTraceSpanDto.EventDto.builder()
                .name(view.getName())
                .timestamp(view.getTimestamp())
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
}

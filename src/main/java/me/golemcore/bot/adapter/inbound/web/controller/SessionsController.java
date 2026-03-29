package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.ActiveSessionRequest;
import me.golemcore.bot.adapter.inbound.web.dto.ActiveSessionResponse;
import me.golemcore.bot.adapter.inbound.web.dto.CreateSessionRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SessionDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionMessagesPageDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSnapshotDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSpanDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceStorageStatsDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSummaryDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ActiveSessionPointerService;
import me.golemcore.bot.domain.service.AutoRunContextSupport;
import me.golemcore.bot.domain.service.ConversationKeyValidator;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.domain.service.TelemetrySupport;
import me.golemcore.bot.domain.service.TraceSnapshotCompressionService;
import me.golemcore.bot.domain.model.trace.TraceEventRecord;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Session browser and management endpoints.
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Slf4j
public class SessionsController {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String CHANNEL_WEB = "web";
    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final int MAX_RECENT_LIMIT = 20;
    private static final int MAX_PAGE_LIMIT = 100;
    private static final int TITLE_MAX_LEN = 64;
    private static final int PREVIEW_MAX_LEN = 160;
    private static final int START_WITH_INDEX = 0;
    private static final String POINTER_SOURCE = "pointer";
    private static final String DEFAULT_SOURCE = "default";
    private static final String REPAIRED_SOURCE = "repaired";
    private static final String DEFAULT_SESSION_TITLE = "New session";
    private static final int SNAPSHOT_PREVIEW_MAX_CHARS = 4096;

    private final SessionPort sessionPort;
    private final ActiveSessionPointerService pointerService;
    private final TraceSnapshotCompressionService traceSnapshotCompressionService;

    @GetMapping
    public Mono<ResponseEntity<List<SessionSummaryDto>>> listSessions(
            @RequestParam(required = false) String channel) {
        List<AgentSession> sessions = StringValueSupport.isBlank(channel)
                ? sessionPort.listAll()
                : sessionPort.listByChannelType(channel.trim());
        List<SessionSummaryDto> dtos = sessions.stream()
                .sorted(ConversationKeyValidator.byRecentActivity())
                .map(session -> toSummary(session, false))
                .toList();
        return Mono.just(ResponseEntity.ok(dtos));
    }

    @GetMapping("/resolve")
    public Mono<ResponseEntity<SessionSummaryDto>> resolveSession(
            @RequestParam(defaultValue = CHANNEL_WEB) String channel,
            @RequestParam String conversationKey) {
        String normalizedChannel = defaultIfBlank(channel, CHANNEL_WEB);
        if (StringValueSupport.isBlank(conversationKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationKey is required");
        }

        String normalizedConversationKey = conversationKey.trim();
        Optional<AgentSession> match = sessionPort.listByChannelType(normalizedChannel).stream()
                .filter(session -> matchesConversationKey(session, normalizedConversationKey))
                .findFirst();
        if (match.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        return Mono.just(ResponseEntity.ok(toSummary(match.get(), false)));
    }

    @GetMapping("/recent")
    public Mono<ResponseEntity<List<SessionSummaryDto>>> listRecentSessions(
            @RequestParam(defaultValue = CHANNEL_WEB) String channel,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(required = false) String clientInstanceId,
            @RequestParam(required = false) String transportChatId,
            Principal principal) {
        String pointerKey = resolvePointerKey(channel, clientInstanceId, transportChatId, principal);
        Optional<String> activeConversation = pointerService.getActiveConversationKey(pointerKey);
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_RECENT_LIMIT));

        List<SessionSummaryDto> dtos = listRecentSessionsByOwner(channel, transportChatId).stream()
                .sorted(ConversationKeyValidator.byRecentActivity())
                .map(session -> toSummary(session, isActiveSession(session, activeConversation.orElse(null))))
                .limit(normalizedLimit)
                .toList();

        return Mono.just(ResponseEntity.ok(dtos));
    }

    @GetMapping("/active")
    public Mono<ResponseEntity<ActiveSessionResponse>> getActiveSession(
            @RequestParam(defaultValue = CHANNEL_WEB) String channel,
            @RequestParam(required = false) String clientInstanceId,
            @RequestParam(required = false) String transportChatId,
            Principal principal) {
        String pointerKey = resolvePointerKey(channel, clientInstanceId, transportChatId, principal);
        Optional<String> activeConversation = pointerService.getActiveConversationKey(pointerKey);

        if (activeConversation.isPresent()) {
            String currentConversation = activeConversation.get();
            if (isConversationResolvable(channel, transportChatId, currentConversation)) {
                return Mono.just(ResponseEntity.ok(toActiveResponse(
                        channel,
                        clientInstanceId,
                        transportChatId,
                        currentConversation,
                        POINTER_SOURCE)));
            }

            log.info(
                    "[SessionMetrics] metric=sessions.active.pointer.stale.count channel={} transportHash={} staleConversation={}",
                    channel,
                    TelemetrySupport.shortHash(resolveTelemetryTransport(channel, clientInstanceId, transportChatId)),
                    currentConversation);
            String repairedConversation = resolveOrCreateConversationKey(channel, transportChatId, currentConversation);
            pointerService.setActiveConversationKey(pointerKey, repairedConversation);
            if (CHANNEL_TELEGRAM.equals(channel)) {
                ensureTelegramSessionBinding(transportChatId, repairedConversation);
            }
            return Mono.just(ResponseEntity.ok(toActiveResponse(
                    channel,
                    clientInstanceId,
                    transportChatId,
                    repairedConversation,
                    REPAIRED_SOURCE)));
        }

        log.info("[SessionMetrics] metric=sessions.active.pointer.miss.count channel={} transportHash={}", channel,
                TelemetrySupport.shortHash(resolveTelemetryTransport(channel, clientInstanceId, transportChatId)));
        String fallbackConversation = resolveOrCreateConversationKey(channel, transportChatId, null);
        pointerService.setActiveConversationKey(pointerKey, fallbackConversation);
        if (CHANNEL_TELEGRAM.equals(channel)) {
            ensureTelegramSessionBinding(transportChatId, fallbackConversation);
        }

        return Mono.just(ResponseEntity.ok(toActiveResponse(
                channel,
                clientInstanceId,
                transportChatId,
                fallbackConversation,
                DEFAULT_SOURCE)));
    }

    @PostMapping("/active")
    public Mono<ResponseEntity<ActiveSessionResponse>> setActiveSession(
            @RequestBody ActiveSessionRequest request,
            Principal principal) {
        if (request == null || StringValueSupport.isBlank(request.getConversationKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationKey is required");
        }
        String channel = defaultIfBlank(request.getChannelType(), CHANNEL_WEB);
        String conversationKey = normalizeConversationKeyForActivationOrThrow(channel, request.getConversationKey());

        String pointerKey = resolvePointerKey(channel, request.getClientInstanceId(), request.getTransportChatId(),
                principal);
        pointerService.setActiveConversationKey(pointerKey, conversationKey);
        log.info("[SessionMetrics] metric=sessions.switch.count channel={} transportHash={} conversationKey={}",
                channel,
                TelemetrySupport.shortHash(resolveTelemetryTransport(
                        channel,
                        request.getClientInstanceId(),
                        request.getTransportChatId())),
                conversationKey);
        if (CHANNEL_TELEGRAM.equals(channel)) {
            ensureTelegramSessionBinding(request.getTransportChatId(), conversationKey);
        }
        return Mono.just(ResponseEntity.ok(toActiveResponse(
                channel,
                request.getClientInstanceId(),
                request.getTransportChatId(),
                conversationKey,
                POINTER_SOURCE)));
    }

    @PostMapping
    public Mono<ResponseEntity<SessionSummaryDto>> createSession(
            @RequestBody(required = false) CreateSessionRequest request,
            Principal principal) {
        CreateSessionRequest normalizedRequest = request != null ? request : CreateSessionRequest.builder().build();
        String channel = defaultIfBlank(normalizedRequest.getChannelType(), CHANNEL_WEB);
        if (!CHANNEL_WEB.equals(channel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only web channel session creation is supported");
        }

        String conversationKey = StringValueSupport.isBlank(normalizedRequest.getConversationKey())
                ? generateConversationKey()
                : normalizeConversationKeyForCreation(normalizedRequest.getConversationKey());

        AgentSession session = sessionPort.getOrCreate(channel, conversationKey);
        sessionPort.save(session);
        log.info("[SessionMetrics] metric=sessions.create.count channel={} transportHash={} conversationKey={}",
                channel,
                TelemetrySupport
                        .shortHash(resolveTelemetryTransport(channel, normalizedRequest.getClientInstanceId(), null)),
                conversationKey);

        boolean shouldActivate = normalizedRequest.getActivate() == null || normalizedRequest.getActivate();
        if (shouldActivate) {
            String pointerKey = resolvePointerKey(
                    channel,
                    normalizedRequest.getClientInstanceId(),
                    null,
                    principal);
            pointerService.setActiveConversationKey(pointerKey, conversationKey);
        }

        return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(toSummary(session, shouldActivate)));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<SessionDetailDto>> getSession(@PathVariable String id) {
        Optional<AgentSession> session = sessionPort.get(id);
        if (session.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        return Mono.just(ResponseEntity.ok(toDetail(session.get())));
    }

    @GetMapping("/{id}/messages")
    public Mono<ResponseEntity<SessionMessagesPageDto>> getSessionMessages(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String beforeMessageId) {
        Optional<AgentSession> session = sessionPort.get(id);
        if (session.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }

        int normalizedLimit = Math.max(1, Math.min(limit, MAX_PAGE_LIMIT));
        List<Message> visibleMessages = getVisibleMessages(session.get());
        int endExclusive = resolvePageEndExclusive(visibleMessages, beforeMessageId);
        int startInclusive = Math.max(START_WITH_INDEX, endExclusive - normalizedLimit);
        List<SessionDetailDto.MessageDto> page = visibleMessages.subList(startInclusive, endExclusive).stream()
                .map(this::toMessageDto)
                .toList();
        String oldestMessageId = page.isEmpty() ? null : page.get(START_WITH_INDEX).getId();

        return Mono.just(ResponseEntity.ok(SessionMessagesPageDto.builder()
                .sessionId(id)
                .messages(page)
                .hasMore(startInclusive > START_WITH_INDEX)
                .oldestMessageId(oldestMessageId)
                .build()));
    }

    @GetMapping("/{id}/trace/summary")
    public Mono<ResponseEntity<SessionTraceSummaryDto>> getSessionTraceSummary(@PathVariable String id) {
        AgentSession session = requireSession(id);
        return Mono.just(ResponseEntity.ok(toTraceSummary(session)));
    }

    @GetMapping("/{id}/trace")
    public Mono<ResponseEntity<SessionTraceDto>> getSessionTrace(@PathVariable String id) {
        AgentSession session = requireSession(id);
        return Mono.just(ResponseEntity.ok(toTraceDetail(session)));
    }

    @GetMapping("/{id}/trace/export")
    public Mono<ResponseEntity<Map<String, Object>>> exportSessionTrace(@PathVariable String id) {
        AgentSession session = requireSession(id);
        String fileName = "session-trace-" + sanitizeExportName(id) + ".json";
        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(toTraceExport(session)));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteSession(@PathVariable String id) {
        Optional<AgentSession> deletedSession = sessionPort.get(id);
        sessionPort.delete(id);
        repairPointersAfterDelete(id, deletedSession.orElse(null));
        return Mono.just(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/compact")
    public Mono<ResponseEntity<Map<String, Object>>> compactSession(
            @PathVariable String id, @RequestParam(defaultValue = "20") int keepLast) {
        int removed = sessionPort.compactMessages(id, keepLast);
        return Mono.just(ResponseEntity.ok(Map.of("removed", removed)));
    }

    @PostMapping("/{id}/clear")
    public Mono<ResponseEntity<Void>> clearSession(@PathVariable String id) {
        sessionPort.clearMessages(id);
        return Mono.just(ResponseEntity.noContent().build());
    }

    private SessionSummaryDto toSummary(AgentSession session, boolean active) {
        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
        String transportChatId = SessionIdentitySupport.resolveTransportChatId(session);
        String preview = buildPreview(session);
        String title = buildTitle(session, conversationKey);
        return SessionSummaryDto.builder()
                .id(session.getId())
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .conversationKey(conversationKey)
                .transportChatId(transportChatId)
                .messageCount(getVisibleMessages(session).size())
                .state(session.getState() != null ? session.getState().name() : "ACTIVE")
                .createdAt(session.getCreatedAt() != null ? session.getCreatedAt().toString() : null)
                .updatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null)
                .title(title)
                .preview(preview)
                .active(active)
                .build();
    }

    private AgentSession requireSession(String id) {
        return sessionPort.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
    }

    private SessionDetailDto toDetail(AgentSession session) {
        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
        String transportChatId = SessionIdentitySupport.resolveTransportChatId(session);
        List<SessionDetailDto.MessageDto> messages = List.of();
        if (session.getMessages() != null) {
            messages = session.getMessages().stream()
                    .filter(this::isHistoryVisibleMessage)
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

    private SessionDetailDto.MessageDto toMessageDto(Message msg) {
        String model = null;
        String modelTier = null;
        String skill = null;
        String reasoning = null;
        String clientMessageId = null;
        boolean autoMode = false;
        String autoRunId = null;
        String autoScheduleId = null;
        String autoGoalId = null;
        String autoTaskId = null;
        if (msg.getMetadata() != null) {
            Object modelValue = msg.getMetadata().get("model");
            if (modelValue instanceof String) {
                model = (String) modelValue;
            }
            Object tierValue = msg.getMetadata().get("modelTier");
            if (tierValue instanceof String) {
                modelTier = (String) tierValue;
            }
            Object reasoningValue = msg.getMetadata().get("reasoning");
            if (reasoningValue instanceof String) {
                reasoning = (String) reasoningValue;
            }
            Object skillValue = msg.getMetadata().get(ContextAttributes.AUTO_RUN_ACTIVE_SKILL);
            if (skillValue instanceof String) {
                skill = (String) skillValue;
            }
            if (skill == null || skill.isBlank()) {
                Object activeSkillValue = msg.getMetadata().get(ContextAttributes.ACTIVE_SKILL_NAME);
                if (activeSkillValue instanceof String) {
                    skill = (String) activeSkillValue;
                }
            }
            Object clientMessageIdValue = msg.getMetadata().get("clientMessageId");
            if (clientMessageIdValue instanceof String) {
                clientMessageId = (String) clientMessageIdValue;
            }
            autoMode = AutoRunContextSupport.isAutoMetadata(msg.getMetadata());
            autoRunId = AutoRunContextSupport.readMetadataString(msg.getMetadata(), ContextAttributes.AUTO_RUN_ID);
            autoScheduleId = AutoRunContextSupport.readMetadataString(
                    msg.getMetadata(), ContextAttributes.AUTO_SCHEDULE_ID);
            autoGoalId = AutoRunContextSupport.readMetadataString(msg.getMetadata(), ContextAttributes.AUTO_GOAL_ID);
            autoTaskId = AutoRunContextSupport.readMetadataString(msg.getMetadata(), ContextAttributes.AUTO_TASK_ID);
        }
        return SessionDetailDto.MessageDto.builder()
                .id(msg.getId())
                .role(msg.getRole())
                .content(resolveMessageContent(msg))
                .timestamp(msg.getTimestamp() != null ? msg.getTimestamp().toString() : null)
                .hasToolCalls(msg.hasToolCalls())
                .hasVoice(msg.hasVoice())
                .model(model)
                .modelTier(modelTier)
                .skill(skill)
                .reasoning(reasoning)
                .clientMessageId(clientMessageId)
                .autoMode(autoMode)
                .autoRunId(autoRunId)
                .autoScheduleId(autoScheduleId)
                .autoGoalId(autoGoalId)
                .autoTaskId(autoTaskId)
                .attachments(resolveAttachments(msg))
                .build();
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

    private SessionTraceSnapshotDto toTraceSnapshotDto(TraceSnapshot snapshot, boolean includePayloadPreview) {
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
                .filter(trace -> trace != null)
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
                .filter(span -> span != null)
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

    private String toTimestamp(java.time.Instant timestamp) {
        return timestamp != null ? timestamp.toString() : null;
    }

    private Long toDurationMs(java.time.Instant startedAt, java.time.Instant endedAt) {
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

    private String sanitizeExportName(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record SnapshotPreview(boolean payloadAvailable, String payloadPreview, boolean previewTruncated) {
        private static SnapshotPreview empty() {
            return new SnapshotPreview(false, null, false);
        }
    }

    private ActiveSessionResponse toActiveResponse(
            String channel,
            String clientInstanceId,
            String transportChatId,
            String conversationKey,
            String source) {
        String effectiveTransportChatId = transportChatId;
        if (CHANNEL_WEB.equals(channel)) {
            effectiveTransportChatId = clientInstanceId;
        }
        return ActiveSessionResponse.builder()
                .channelType(channel)
                .clientInstanceId(clientInstanceId)
                .transportChatId(effectiveTransportChatId)
                .conversationKey(conversationKey)
                .sessionId(channel + ":" + conversationKey)
                .source(source)
                .build();
    }

    private boolean matchesConversationKey(AgentSession session, String conversationKey) {
        if (session == null || StringValueSupport.isBlank(conversationKey)) {
            return false;
        }
        String resolvedConversationKey = SessionIdentitySupport.resolveConversationKey(session);
        return conversationKey.equals(resolvedConversationKey) || conversationKey.equals(session.getChatId());
    }

    private List<Message> getVisibleMessages(AgentSession session) {
        if (session == null || session.getMessages() == null) {
            return List.of();
        }
        return session.getMessages().stream()
                .filter(message -> message != null
                        && ("user".equals(message.getRole()) || ROLE_ASSISTANT.equals(message.getRole()))
                        && isHistoryVisibleMessage(message))
                .toList();
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

    private boolean isHistoryVisibleMessage(Message message) {
        if (message == null || message.isInternalMessage()) {
            return false;
        }
        return hasVisibleContent(message.getContent()) || resolveAttachmentCount(message) > START_WITH_INDEX;
    }

    private boolean hasVisibleContent(String content) {
        return content != null && !content.trim().isEmpty();
    }

    private String resolveMessageContent(Message message) {
        if (message == null) {
            return null;
        }
        if (hasVisibleContent(message.getContent())) {
            return message.getContent();
        }

        int attachmentCount = resolveAttachmentCount(message);
        if (attachmentCount <= START_WITH_INDEX) {
            return message.getContent();
        }

        return attachmentCount == 1
                ? "[1 attachment]"
                : "[" + attachmentCount + " attachments]";
    }

    private int resolveAttachmentCount(Message message) {
        if (message == null || message.getMetadata() == null) {
            return START_WITH_INDEX;
        }
        Object attachmentsValue = message.getMetadata().get("attachments");
        if (!(attachmentsValue instanceof List<?> attachments)) {
            return START_WITH_INDEX;
        }
        return attachments.size();
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

        List<SessionDetailDto.AttachmentDto> result = new ArrayList<>();
        for (Object attachmentObj : attachments) {
            if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
                continue;
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
                continue;
            }
            result.add(SessionDetailDto.AttachmentDto.builder()
                    .type(type)
                    .name(name)
                    .mimeType(mimeType)
                    .url(url)
                    .internalFilePath(internalFilePath)
                    .thumbnailBase64(thumbnailBase64)
                    .build());
        }
        return result;
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

    private String resolvePointerKey(
            String channel,
            String clientInstanceId,
            String transportChatId,
            Principal principal) {
        if (CHANNEL_WEB.equals(channel)) {
            String username = resolvePrincipalName(principal);
            if (StringValueSupport.isBlank(clientInstanceId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientInstanceId is required for web");
            }
            return pointerService.buildWebPointerKey(username, clientInstanceId);
        }
        if (CHANNEL_TELEGRAM.equals(channel)) {
            if (StringValueSupport.isBlank(transportChatId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transportChatId is required for telegram");
            }
            return pointerService.buildTelegramPointerKey(transportChatId);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported channel: " + channel);
    }

    private String resolvePrincipalName(Principal principal) {
        if (principal == null || StringValueSupport.isBlank(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return principal.getName();
    }

    private boolean isActiveSession(AgentSession session, String activeConversation) {
        if (StringValueSupport.isBlank(activeConversation)) {
            return false;
        }
        return activeConversation.equals(SessionIdentitySupport.resolveConversationKey(session));
    }

    private String generateConversationKey() {
        return UUID.randomUUID().toString();
    }

    private String resolveOrCreateConversationKey(String channel, String transportChatId,
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

    private Optional<String> findLatestConversationKey(String channel, String transportChatId,
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

    private void ensureTelegramSessionBinding(String transportChatId, String conversationKey) {
        if (StringValueSupport.isBlank(transportChatId) || StringValueSupport.isBlank(conversationKey)) {
            return;
        }
        AgentSession session = sessionPort.getOrCreate(CHANNEL_TELEGRAM, conversationKey);
        SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        sessionPort.save(session);
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

    private String buildTitle(AgentSession session, String conversationKey) {
        if (session == null || session.getMessages() == null || session.getMessages().isEmpty()) {
            return DEFAULT_SESSION_TITLE;
        }

        for (Message message : session.getMessages()) {
            if (message == null || !"user".equals(message.getRole()) || message.isInternalMessage()) {
                continue;
            }
            String content = message.getContent();
            if (!StringValueSupport.isBlank(content)) {
                return truncate(content.trim(), TITLE_MAX_LEN);
            }
        }

        if (!StringValueSupport.isBlank(conversationKey)) {
            return "Session " + truncate(conversationKey, 12);
        }
        return DEFAULT_SESSION_TITLE;
    }

    private String buildPreview(AgentSession session) {
        if (session == null || session.getMessages() == null || session.getMessages().isEmpty()) {
            return null;
        }

        String preview = null;
        for (int index = START_WITH_INDEX; index < session.getMessages().size(); index++) {
            Message message = session.getMessages().get(index);
            if (message == null || message.isInternalMessage() || StringValueSupport.isBlank(message.getContent())) {
                continue;
            }
            preview = truncate(message.getContent().trim(), PREVIEW_MAX_LEN);
        }

        return preview;
    }

    private String normalizeConversationKeyForCreation(String value) {
        try {
            return ConversationKeyValidator.normalizeStrictOrThrow(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private String normalizeConversationKeyForActivationOrThrow(String channel, String value) {
        try {
            return ConversationKeyValidator.normalizeForActivationOrThrow(
                    value,
                    candidate -> sessionPort.get(channel + ":" + candidate).isPresent());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        if (StringValueSupport.isBlank(value)) {
            return defaultValue;
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private String resolveTelemetryTransport(String channel, String clientInstanceId, String transportChatId) {
        if (CHANNEL_TELEGRAM.equals(channel)) {
            return transportChatId;
        }
        return clientInstanceId;
    }
}

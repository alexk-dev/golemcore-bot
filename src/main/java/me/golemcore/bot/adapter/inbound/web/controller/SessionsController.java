package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.mapper.SessionWebDtoMapper;
import me.golemcore.bot.adapter.inbound.web.dto.ActiveSessionRequest;
import me.golemcore.bot.adapter.inbound.web.dto.ActiveSessionResponse;
import me.golemcore.bot.adapter.inbound.web.dto.CreateSessionRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SessionDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionMessagesPageDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionSummaryDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSnapshotDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.service.ActiveSessionPointerService;
import me.golemcore.bot.domain.service.ConversationKeyValidator;
import me.golemcore.bot.domain.service.SessionConversationSupport;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.SessionInspectionService;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.domain.service.TelemetrySupport;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.security.Principal;
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

    private static final String CHANNEL_WEB = "web";
    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final int MAX_RECENT_LIMIT = 20;
    private static final String POINTER_SOURCE = "pointer";
    private static final String DEFAULT_SOURCE = "default";
    private static final String REPAIRED_SOURCE = "repaired";

    private final SessionPort sessionPort;
    private final ActiveSessionPointerService pointerService;
    private final SessionInspectionService sessionInspectionService;
    private final SessionWebDtoMapper sessionWebDtoMapper;

    @GetMapping
    public Mono<ResponseEntity<List<SessionSummaryDto>>> listSessions(
            @RequestParam(required = false) String channel) {
        return Mono.just(
                ResponseEntity.ok(sessionWebDtoMapper.toSummaryDtos(sessionInspectionService.listSessions(channel))));
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
        return Mono.just(
                ResponseEntity.ok(
                        sessionWebDtoMapper.toSummaryDto(
                                sessionInspectionService.resolveSession(normalizedChannel,
                                        normalizedConversationKey))));
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

        return Mono.just(ResponseEntity.ok(sessionWebDtoMapper.toSummaryDtos(
                sessionInspectionService.listRecentSessions(
                        channel,
                        transportChatId,
                        activeConversation.orElse(null),
                        normalizedLimit))));
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
            if (SessionConversationSupport.isConversationResolvable(
                    sessionPort, channel, transportChatId, currentConversation)) {
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
            String repairedConversation = SessionConversationSupport.resolveOrCreateConversationKey(
                    sessionPort, channel, transportChatId, currentConversation);
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
        String fallbackConversation = SessionConversationSupport.resolveOrCreateConversationKey(
                sessionPort, channel, transportChatId, null);
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

        return Mono.just(ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionWebDtoMapper
                        .toSummaryDto(sessionInspectionService.summarizeSession(session, shouldActivate))));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<SessionDetailDto>> getSession(@PathVariable String id) {
        return Mono.just(
                ResponseEntity.ok(sessionWebDtoMapper.toDetailDto(sessionInspectionService.getSessionDetail(id))));
    }

    @GetMapping("/{id}/messages")
    public Mono<ResponseEntity<SessionMessagesPageDto>> getSessionMessages(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String beforeMessageId) {
        return Mono.just(ResponseEntity.ok(
                sessionWebDtoMapper
                        .toMessagesPageDto(sessionInspectionService.getSessionMessages(id, limit, beforeMessageId))));
    }

    @GetMapping("/{id}/trace/summary")
    public Mono<ResponseEntity<SessionTraceSummaryDto>> getSessionTraceSummary(@PathVariable String id) {
        return Mono.just(
                ResponseEntity.ok(
                        sessionWebDtoMapper.toTraceSummaryDto(sessionInspectionService.getSessionTraceSummary(id))));
    }

    @GetMapping("/{id}/trace")
    public Mono<ResponseEntity<SessionTraceDto>> getSessionTrace(@PathVariable String id) {
        return Mono
                .just(ResponseEntity.ok(sessionWebDtoMapper.toTraceDto(sessionInspectionService.getSessionTrace(id))));
    }

    @GetMapping("/{id}/trace/export")
    public Mono<ResponseEntity<Map<String, Object>>> exportSessionTrace(@PathVariable String id) {
        String fileName = "session-trace-" + sanitizeExportName(id) + ".json";
        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(sessionInspectionService.exportSessionTrace(id)));
    }

    @GetMapping("/{id}/trace/snapshots/{snapshotId}/payload")
    public Mono<ResponseEntity<String>> exportSessionTraceSnapshotPayload(
            @PathVariable String id,
            @PathVariable String snapshotId) {
        SessionInspectionService.SnapshotPayloadExport payload = sessionInspectionService
                .exportSessionTraceSnapshotPayload(id, snapshotId);
        String fileName = "session-trace-" + sanitizeExportName(id) + "-snapshot-"
                + sanitizeExportName(snapshotId) + payload.fileExtension();
        return Mono.just(ResponseEntity.ok()
                .contentType(payload.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(payload.payloadText()));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteSession(@PathVariable String id) {
        sessionInspectionService.deleteSession(id);
        return Mono.just(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/compact")
    public Mono<ResponseEntity<Map<String, Object>>> compactSession(
            @PathVariable String id, @RequestParam(defaultValue = "20") int keepLast) {
        return Mono.just(ResponseEntity.ok(Map.of("removed", sessionInspectionService.compactSession(id, keepLast))));
    }

    @PostMapping("/{id}/clear")
    public Mono<ResponseEntity<Void>> clearSession(@PathVariable String id) {
        sessionInspectionService.clearSession(id);
        return Mono.just(ResponseEntity.noContent().build());
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

    private String generateConversationKey() {
        return UUID.randomUUID().toString();
    }

    private void ensureTelegramSessionBinding(String transportChatId, String conversationKey) {
        if (StringValueSupport.isBlank(transportChatId) || StringValueSupport.isBlank(conversationKey)) {
            return;
        }
        AgentSession session = sessionPort.getOrCreate(CHANNEL_TELEGRAM, conversationKey);
        SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        sessionPort.save(session);
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

    private String resolveTelemetryTransport(String channel, String clientInstanceId, String transportChatId) {
        if (CHANNEL_TELEGRAM.equals(channel)) {
            return transportChatId;
        }
        return clientInstanceId;
    }

    private String sanitizeExportName(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @SuppressWarnings("unused")
    private SessionTraceSnapshotDto toTraceSnapshotDto(TraceSnapshot snapshot, boolean includePayloadPreview) {
        return sessionWebDtoMapper
                .toTraceSnapshotDto(sessionInspectionService.toTraceSnapshotView(snapshot, includePayloadPreview));
    }
}

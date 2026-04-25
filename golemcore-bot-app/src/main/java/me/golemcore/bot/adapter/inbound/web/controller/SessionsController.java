package me.golemcore.bot.adapter.inbound.web.controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.client.dto.ActiveSessionRequest;
import me.golemcore.bot.client.dto.ActiveSessionResponse;
import me.golemcore.bot.client.dto.CreateSessionRequest;
import me.golemcore.bot.client.dto.SessionDetailDto;
import me.golemcore.bot.client.dto.SessionMessagesPageDto;
import me.golemcore.bot.client.dto.SessionSummaryDto;
import me.golemcore.bot.client.dto.SessionTraceDto;
import me.golemcore.bot.client.dto.SessionTraceSummaryDto;
import me.golemcore.bot.adapter.inbound.web.mapper.SessionWebDtoMapper;
import me.golemcore.bot.client.dto.SessionTraceExportPayload;
import me.golemcore.bot.domain.view.ActiveSessionSelectionView;
import me.golemcore.bot.domain.service.SessionInspectionService;
import me.golemcore.bot.domain.service.SessionSelectionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

/**
 * Session browser and management endpoints.
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionsController {

    private final SessionInspectionService sessionInspectionService;
    private final SessionSelectionService sessionSelectionService;
    private final SessionWebDtoMapper sessionWebDtoMapper;

    @GetMapping
    public Mono<ResponseEntity<List<SessionSummaryDto>>> listSessions(
            @RequestParam(required = false) String channel) {
        return execute(() -> ResponseEntity.ok(
                sessionWebDtoMapper.toSummaryDtos(sessionInspectionService.listSessions(channel))));
    }

    @GetMapping("/resolve")
    public Mono<ResponseEntity<SessionSummaryDto>> resolveSession(
            @RequestParam(defaultValue = "web") String channel,
            @RequestParam String conversationKey) {
        return execute(() -> ResponseEntity.ok(sessionWebDtoMapper.toSummaryDto(
                sessionInspectionService.resolveSession(channel, conversationKey))));
    }

    @GetMapping("/recent")
    public Mono<ResponseEntity<List<SessionSummaryDto>>> listRecentSessions(
            @RequestParam(defaultValue = "web") String channel,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(required = false) String clientInstanceId,
            @RequestParam(required = false) String transportChatId,
            Principal principal) {
        return execute(() -> ResponseEntity.ok(sessionWebDtoMapper.toSummaryDtos(
                sessionSelectionService.listRecentSessions(
                        channel,
                        clientInstanceId,
                        transportChatId,
                        principalName(principal),
                        limit))));
    }

    @GetMapping("/active")
    public Mono<ResponseEntity<ActiveSessionResponse>> getActiveSession(
            @RequestParam(defaultValue = "web") String channel,
            @RequestParam(required = false) String clientInstanceId,
            @RequestParam(required = false) String transportChatId,
            Principal principal) {
        return execute(() -> ResponseEntity.ok(toActiveResponse(sessionSelectionService.getActiveSession(
                channel,
                clientInstanceId,
                transportChatId,
                principalName(principal)))));
    }

    @PostMapping("/active")
    public Mono<ResponseEntity<ActiveSessionResponse>> setActiveSession(
            @RequestBody ActiveSessionRequest request,
            Principal principal) {
        ActiveSessionRequest normalizedRequest = request != null ? request : ActiveSessionRequest.builder().build();
        return execute(() -> ResponseEntity.ok(toActiveResponse(sessionSelectionService.setActiveSession(
                normalizedRequest.getChannelType(),
                normalizedRequest.getClientInstanceId(),
                normalizedRequest.getTransportChatId(),
                principalName(principal),
                normalizedRequest.getConversationKey()))));
    }

    @PostMapping
    public Mono<ResponseEntity<SessionSummaryDto>> createSession(
            @RequestBody(required = false) CreateSessionRequest request,
            Principal principal) {
        CreateSessionRequest normalizedRequest = request != null ? request : CreateSessionRequest.builder().build();
        return execute(() -> ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionWebDtoMapper.toSummaryDto(sessionSelectionService.createSession(
                        normalizedRequest.getChannelType(),
                        normalizedRequest.getClientInstanceId(),
                        principalName(principal),
                        normalizedRequest.getConversationKey(),
                        normalizedRequest.getActivate()))));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<SessionDetailDto>> getSession(@PathVariable String id) {
        return execute(() -> ResponseEntity.ok(
                sessionWebDtoMapper.toDetailDto(sessionInspectionService.getSessionDetail(id))));
    }

    @GetMapping("/{id}/messages")
    public Mono<ResponseEntity<SessionMessagesPageDto>> getSessionMessages(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String beforeMessageId) {
        return execute(() -> ResponseEntity.ok(
                sessionWebDtoMapper.toMessagesPageDto(
                        sessionInspectionService.getSessionMessages(id, limit, beforeMessageId))));
    }

    @GetMapping("/{id}/trace/summary")
    public Mono<ResponseEntity<SessionTraceSummaryDto>> getSessionTraceSummary(@PathVariable String id) {
        return execute(() -> ResponseEntity.ok(
                sessionWebDtoMapper.toTraceSummaryDto(sessionInspectionService.getSessionTraceSummary(id))));
    }

    @GetMapping("/{id}/trace")
    public Mono<ResponseEntity<SessionTraceDto>> getSessionTrace(@PathVariable String id) {
        return execute(() -> ResponseEntity.ok(
                sessionWebDtoMapper.toTraceDto(sessionInspectionService.getSessionTrace(id))));
    }

    @GetMapping("/{id}/trace/export")
    public Mono<ResponseEntity<SessionTraceExportPayload>> exportSessionTrace(@PathVariable String id) {
        return execute(() -> {
            String fileName = "session-trace-" + sanitizeExportName(id) + ".json";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .body(sessionWebDtoMapper.toTraceExportPayload(sessionInspectionService.getSessionTraceExport(id)));
        });
    }

    @GetMapping("/{id}/trace/snapshots/{snapshotId}/payload")
    public Mono<ResponseEntity<String>> exportSessionTraceSnapshotPayload(
            @PathVariable String id,
            @PathVariable String snapshotId) {
        return execute(() -> {
            SessionInspectionService.SnapshotPayloadExport payload = sessionInspectionService
                    .exportSessionTraceSnapshotPayload(id, snapshotId);
            String fileName = "session-trace-" + sanitizeExportName(id) + "-snapshot-"
                    + sanitizeExportName(snapshotId) + payload.fileExtension();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(payload.contentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .body(payload.payloadText());
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteSession(@PathVariable String id) {
        return execute(() -> {
            sessionInspectionService.deleteSession(id);
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/{id}/compact")
    public Mono<ResponseEntity<Map<String, Object>>> compactSession(
            @PathVariable String id, @RequestParam(defaultValue = "20") int keepLast) {
        return execute(
                () -> ResponseEntity.ok(Map.of("removed", sessionInspectionService.compactSession(id, keepLast))));
    }

    @PostMapping("/{id}/clear")
    public Mono<ResponseEntity<Void>> clearSession(@PathVariable String id) {
        return execute(() -> {
            sessionInspectionService.clearSession(id);
            return ResponseEntity.noContent().build();
        });
    }

    private ActiveSessionResponse toActiveResponse(ActiveSessionSelectionView view) {
        return ActiveSessionResponse.builder()
                .channelType(view.getChannelType())
                .clientInstanceId(view.getClientInstanceId())
                .transportChatId(view.getTransportChatId())
                .conversationKey(view.getConversationKey())
                .sessionId(view.getSessionId())
                .source(view.getSource())
                .build();
    }

    private String principalName(Principal principal) {
        return principal != null ? principal.getName() : null;
    }

    private String sanitizeExportName(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private <T> Mono<ResponseEntity<T>> execute(Supplier<ResponseEntity<T>> supplier) {
        try {
            return Mono.just(supplier.get());
        } catch (RuntimeException exception) {
            throw translateException(exception);
        }
    }

    private ResponseStatusException translateException(RuntimeException exception) {
        if (exception instanceof ResponseStatusException responseStatusException) {
            return responseStatusException;
        }
        if (exception instanceof IllegalArgumentException) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        if (exception instanceof NoSuchElementException) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
        if (exception instanceof SecurityException) {
            return new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage(), exception);
        }
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
    }
}

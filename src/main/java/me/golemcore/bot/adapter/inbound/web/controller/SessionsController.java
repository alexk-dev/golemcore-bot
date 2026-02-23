package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.ActiveSessionRequest;
import me.golemcore.bot.adapter.inbound.web.dto.ActiveSessionResponse;
import me.golemcore.bot.adapter.inbound.web.dto.CreateSessionRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SessionDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ActiveSessionPointerService;
import me.golemcore.bot.domain.service.ConversationKeyValidator;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.port.outbound.SessionPort;
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

import java.security.Principal;
import java.util.Comparator;
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
    private static final int TITLE_MAX_LEN = 64;
    private static final int PREVIEW_MAX_LEN = 160;
    private static final int START_WITH_INDEX = 0;
    private static final String POINTER_SOURCE = "pointer";
    private static final String DEFAULT_SOURCE = "default";
    private static final String REPAIRED_SOURCE = "repaired";
    private static final String DEFAULT_SESSION_TITLE = "New session";

    private final SessionPort sessionPort;
    private final ActiveSessionPointerService pointerService;

    @GetMapping
    public Mono<ResponseEntity<List<SessionSummaryDto>>> listSessions(
            @RequestParam(required = false) String channel) {
        List<AgentSession> sessions = sessionPort.listAll();
        List<SessionSummaryDto> dtos = sessions.stream()
                .filter(s -> channel == null || channel.equals(s.getChannelType()))
                .sorted(sessionComparator())
                .map(session -> toSummary(session, false))
                .toList();
        return Mono.just(ResponseEntity.ok(dtos));
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

        List<SessionSummaryDto> dtos = sessionPort.listAll().stream()
                .filter(session -> isSessionVisibleForRecent(session, channel, transportChatId))
                .sorted(sessionComparator())
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
        if (request == null || isBlank(request.getConversationKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationKey is required");
        }
        String channel = defaultIfBlank(request.getChannelType(), CHANNEL_WEB);
        String conversationKey = normalizeConversationKeyForActivation(channel, request.getConversationKey());

        String pointerKey = resolvePointerKey(channel, request.getClientInstanceId(), request.getTransportChatId(), principal);
        pointerService.setActiveConversationKey(pointerKey, conversationKey);
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

        String conversationKey = isBlank(normalizedRequest.getConversationKey())
                ? generateConversationKey()
                : normalizeConversationKeyForCreation(normalizedRequest.getConversationKey());

        AgentSession session = sessionPort.getOrCreate(channel, conversationKey);
        sessionPort.save(session);

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
                .messageCount(session.getMessages() != null ? session.getMessages().size() : 0)
                .state(session.getState() != null ? session.getState().name() : "ACTIVE")
                .createdAt(session.getCreatedAt() != null ? session.getCreatedAt().toString() : null)
                .updatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null)
                .title(title)
                .preview(preview)
                .active(active)
                .build();
    }

    private SessionDetailDto toDetail(AgentSession session) {
        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
        String transportChatId = SessionIdentitySupport.resolveTransportChatId(session);
        List<SessionDetailDto.MessageDto> messages = List.of();
        if (session.getMessages() != null) {
            messages = session.getMessages().stream()
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
        if (msg.getMetadata() != null) {
            Object modelValue = msg.getMetadata().get("model");
            if (modelValue instanceof String) {
                model = (String) modelValue;
            }
            Object tierValue = msg.getMetadata().get("modelTier");
            if (tierValue instanceof String) {
                modelTier = (String) tierValue;
            }
        }

        return SessionDetailDto.MessageDto.builder()
                .id(msg.getId())
                .role(msg.getRole())
                .content(msg.getContent())
                .timestamp(msg.getTimestamp() != null ? msg.getTimestamp().toString() : null)
                .hasToolCalls(msg.hasToolCalls())
                .hasVoice(msg.hasVoice())
                .model(model)
                .modelTier(modelTier)
                .build();
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
            if (isBlank(clientInstanceId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientInstanceId is required for web");
            }
            return pointerService.buildWebPointerKey(username, clientInstanceId);
        }
        if (CHANNEL_TELEGRAM.equals(channel)) {
            if (isBlank(transportChatId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transportChatId is required for telegram");
            }
            return pointerService.buildTelegramPointerKey(transportChatId);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported channel: " + channel);
    }

    private String resolvePrincipalName(Principal principal) {
        if (principal == null || isBlank(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return principal.getName();
    }

    private boolean isActiveSession(AgentSession session, String activeConversation) {
        if (isBlank(activeConversation)) {
            return false;
        }
        return activeConversation.equals(SessionIdentitySupport.resolveConversationKey(session));
    }

    private String generateConversationKey() {
        return UUID.randomUUID().toString();
    }

    private String resolveOrCreateConversationKey(String channel, String transportChatId, String preferredConversation) {
        if (!isBlank(preferredConversation) && isConversationResolvable(channel, transportChatId, preferredConversation)) {
            return preferredConversation;
        }

        String fallbackConversation = findLatestConversationKey(channel, transportChatId, preferredConversation)
                .orElseGet(this::generateConversationKey);

        if (!isConversationResolvable(channel, transportChatId, fallbackConversation)) {
            ensureSessionExists(channel, transportChatId, fallbackConversation);
        }
        return fallbackConversation;
    }

    private Optional<String> findLatestConversationKey(String channel, String transportChatId, String excludedConversation) {
        return sessionPort.listAll().stream()
                .filter(session -> isSessionVisibleForRecent(session, channel, transportChatId))
                .sorted(sessionComparator())
                .map(SessionIdentitySupport::resolveConversationKey)
                .filter(value -> !isBlank(value) && !value.equals(excludedConversation))
                .findFirst();
    }

    private boolean isConversationResolvable(String channel, String transportChatId, String conversationKey) {
        if (isBlank(channel) || isBlank(conversationKey)) {
            return false;
        }

        Optional<AgentSession> session = sessionPort.get(channel + ":" + conversationKey);
        if (session.isEmpty()) {
            return false;
        }

        if (!CHANNEL_TELEGRAM.equals(channel) || isBlank(transportChatId)) {
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

    private boolean isSessionVisibleForRecent(AgentSession session, String channel, String transportChatId) {
        if (!channel.equals(session.getChannelType())) {
            return false;
        }
        if (!CHANNEL_TELEGRAM.equals(channel)) {
            return true;
        }
        if (isBlank(transportChatId)) {
            return true;
        }
        return transportChatId.equals(SessionIdentitySupport.resolveTransportChatId(session));
    }

    private void ensureTelegramSessionBinding(String transportChatId, String conversationKey) {
        if (isBlank(transportChatId) || isBlank(conversationKey)) {
            return;
        }
        AgentSession session = sessionPort.getOrCreate(CHANNEL_TELEGRAM, conversationKey);
        SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        sessionPort.save(session);
    }

    private void repairPointersAfterDelete(String deletedSessionId, AgentSession deletedSession) {
        String channel = deletedSession != null ? deletedSession.getChannelType() : resolveDeletedChannel(deletedSessionId);
        String deletedConversation = deletedSession != null
                ? SessionIdentitySupport.resolveConversationKey(deletedSession)
                : resolveDeletedConversation(deletedSessionId);

        if (isBlank(channel) || isBlank(deletedConversation)) {
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
        if (isBlank(pointerKey) || isBlank(channel)) {
            return false;
        }
        return pointerKey.startsWith(channel + "|");
    }

    private String extractTelegramTransportChatId(String pointerKey) {
        if (isBlank(pointerKey)) {
            return null;
        }
        String prefix = CHANNEL_TELEGRAM + "|";
        if (!pointerKey.startsWith(prefix) || pointerKey.length() <= prefix.length()) {
            return null;
        }
        return pointerKey.substring(prefix.length());
    }

    private String resolveDeletedChannel(String sessionId) {
        if (isBlank(sessionId)) {
            return null;
        }
        int separator = sessionId.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        return sessionId.substring(0, separator);
    }

    private String resolveDeletedConversation(String sessionId) {
        if (isBlank(sessionId)) {
            return null;
        }
        int separator = sessionId.indexOf(':');
        if (separator < 0 || separator + 1 >= sessionId.length()) {
            return null;
        }
        return sessionId.substring(separator + 1);
    }

    private Comparator<AgentSession> sessionComparator() {
        return Comparator.comparing(
                (AgentSession session) -> session.getUpdatedAt() != null ? session.getUpdatedAt() : session.getCreatedAt(),
                Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed();
    }

    private String buildTitle(AgentSession session, String conversationKey) {
        if (session == null || session.getMessages() == null || session.getMessages().isEmpty()) {
            return DEFAULT_SESSION_TITLE;
        }

        for (Message message : session.getMessages()) {
            if (message == null || !"user".equals(message.getRole())) {
                continue;
            }
            String content = message.getContent();
            if (!isBlank(content)) {
                return truncate(content.trim(), TITLE_MAX_LEN);
            }
        }

        if (!isBlank(conversationKey)) {
            return "Session " + truncate(conversationKey, 12);
        }
        return DEFAULT_SESSION_TITLE;
    }

    private String buildPreview(AgentSession session) {
        if (session == null || session.getMessages() == null || session.getMessages().isEmpty()) {
            return null;
        }

        for (int index = session.getMessages().size() - 1; index >= START_WITH_INDEX; index--) {
            Message message = session.getMessages().get(index);
            if (message == null || isBlank(message.getContent())) {
                continue;
            }
            return truncate(message.getContent().trim(), PREVIEW_MAX_LEN);
        }

        return null;
    }

    private String normalizeConversationKeyForCreation(String value) {
        try {
            return ConversationKeyValidator.normalizeStrictOrThrow(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private String normalizeConversationKeyForActivation(String channel, String value) {
        if (ConversationKeyValidator.isStrictConversationKey(value)) {
            return ConversationKeyValidator.normalizeStrictOrThrow(value);
        }

        String candidate;
        try {
            candidate = ConversationKeyValidator.normalizeLegacyCompatibleOrThrow(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        if (sessionPort.get(channel + ":" + candidate).isPresent()) {
            return candidate;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationKey must match ^[a-zA-Z0-9_-]{8,64}$");
    }

    private String defaultIfBlank(String value, String defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
}

package me.golemcore.bot.adapter.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.command.CommandOutcomePresenter;
import me.golemcore.bot.adapter.inbound.command.ParsedSlashCommand;
import me.golemcore.bot.adapter.inbound.command.SlashCommandParser;
import me.golemcore.bot.domain.command.CommandExecutionContext;
import me.golemcore.bot.domain.command.CommandInvocation;
import me.golemcore.bot.domain.command.CommandOutcome;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.service.ActiveSessionPointerService;
import me.golemcore.bot.domain.service.ConversationKeyValidator;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.domain.service.TraceContextSupport;
import me.golemcore.bot.domain.service.TraceNamingSupport;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.security.JwtTokenProvider;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Reactive WebSocket handler for dashboard chat. Handles JSON messages: {
 * "text": "...", "sessionId": "...", "memoryPreset": "..." } Delegates to
 * WebChannelAdapter for message routing.
 *
 * <p>
 * The {@code memoryPreset} field is an optional per-message override from a
 * workspace chat. When it is omitted or invalid, the handler falls back to the
 * global user preference so individual chats can override memory behavior
 * without mutating shared settings.
 */
@Component
@Slf4j
public class WebSocketChatHandler implements WebSocketHandler {

    private static final int MAX_IMAGE_ATTACHMENTS = 6;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final String CHANNEL_TYPE = "web";
    private static final String MESSAGE_TYPE_BIND = "bind";

    private final JwtTokenProvider jwtTokenProvider;
    private final WebChannelAdapter webChannelAdapter;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<CommandPort> commandRouter;
    private final ActiveSessionPointerService pointerService;
    private final UserPreferencesService preferencesService;
    private final MemoryPresetService memoryPresetService;
    private final SlashCommandParser slashCommandParser;
    private final CommandOutcomePresenter commandOutcomePresenter;

    public WebSocketChatHandler(
            JwtTokenProvider jwtTokenProvider,
            WebChannelAdapter webChannelAdapter,
            ObjectMapper objectMapper,
            ObjectProvider<CommandPort> commandRouter,
            ActiveSessionPointerService pointerService,
            UserPreferencesService preferencesService,
            MemoryPresetService memoryPresetService,
            SlashCommandParser slashCommandParser,
            CommandOutcomePresenter commandOutcomePresenter) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.webChannelAdapter = webChannelAdapter;
        this.objectMapper = objectMapper;
        this.commandRouter = commandRouter;
        this.pointerService = pointerService;
        this.preferencesService = preferencesService;
        this.memoryPresetService = memoryPresetService;
        this.slashCommandParser = slashCommandParser;
        this.commandOutcomePresenter = commandOutcomePresenter;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String token = extractToken(session);
        if (token == null || !jwtTokenProvider.validateToken(token) || !jwtTokenProvider.isAccessToken(token)) {
            log.debug("[WebSocket] Connection rejected: invalid or missing JWT");
            return session.close();
        }

        String username = jwtTokenProvider.getUsernameFromToken(token);
        String connectionId = UUID.randomUUID().toString();
        log.info("[WebSocket] Connection established: user={}, connectionId={}", username, connectionId);

        webChannelAdapter.registerSession(connectionId, session);

        return session.receive()
                .doOnNext(wsMessage -> handleIncoming(wsMessage, connectionId, username))
                .doOnError(error -> log.warn(
                        "[WebSocket] Connection error: connectionId={}, message={}",
                        connectionId,
                        error.getMessage()))
                .doFinally(signal -> {
                    log.info("[WebSocket] Connection closed: connectionId={}, signal={}", connectionId, signal);
                    webChannelAdapter.deregisterSession(connectionId);
                })
                .then();
    }

    private void handleIncoming(WebSocketMessage wsMessage, String connectionId, String username) {
        try {
            String payload = wsMessage.getPayloadAsText();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = objectMapper.readValue(payload, Map.class);

            String messageType = asString(json.get("type"));
            String text = (String) json.get("text");
            String sessionId = normalizeSessionId((String) json.get("sessionId"), connectionId);
            String clientInstanceId = normalizeClientInstanceId((String) json.get("clientInstanceId"));
            String clientMessageId = normalizeClientMessageId(asString(json.get("clientMessageId")));
            String requestedMemoryPreset = asString(json.get("memoryPreset"));
            String activePath = normalizeActivePath(asString(json.get("activePath")));
            String selectionText = asString(json.get("selectionText"));
            Integer selectionFrom = asInteger(json.get("selectionFrom"));
            Integer selectionTo = asInteger(json.get("selectionTo"));
            List<Map<String, Object>> attachments = extractImageAttachments(json.get("attachments"));
            List<Map<String, Object>> openedTabs = extractOpenedTabs(json.get("openedTabs"));
            webChannelAdapter.bindConnectionToChatId(connectionId, sessionId);
            bindWebPointer(username, clientInstanceId, sessionId);

            if (MESSAGE_TYPE_BIND.equals(messageType)) {
                return;
            }

            if ((text == null || text.isBlank()) && attachments.isEmpty()) {
                return;
            }

            // Intercept slash commands before routing to agent loop
            if (text != null && text.startsWith("/") && attachments.isEmpty()
                    && tryExecuteCommand(text.trim(), sessionId, connectionId)) {
                return;
            }

            String memoryPreset = resolveEffectiveMemoryPreset(requestedMemoryPreset);
            Map<String, Object> metadata = null;
            if (!attachments.isEmpty()) {
                metadata = new LinkedHashMap<>();
                metadata.put("attachments", attachments);
            }
            if (clientMessageId != null) {
                if (metadata == null) {
                    metadata = new LinkedHashMap<>();
                }
                metadata.put("clientMessageId", clientMessageId);
            }
            if (clientInstanceId != null) {
                if (metadata == null) {
                    metadata = new LinkedHashMap<>();
                }
                metadata.put(ContextAttributes.WEB_CLIENT_INSTANCE_ID, clientInstanceId);
            }
            if (memoryPreset != null) {
                if (metadata == null) {
                    metadata = new LinkedHashMap<>();
                }
                metadata.put(ContextAttributes.MEMORY_PRESET_ID, memoryPreset);
            }
            if (!openedTabs.isEmpty()) {
                if (metadata == null) {
                    metadata = new LinkedHashMap<>();
                }
                metadata.put(ContextAttributes.WEB_OPENED_TABS, openedTabs);
            }
            if (activePath != null) {
                if (metadata == null) {
                    metadata = new LinkedHashMap<>();
                }
                metadata.put(ContextAttributes.WEB_ACTIVE_PATH, activePath);
            }
            if (selectionText != null && !selectionText.isBlank()) {
                if (metadata == null) {
                    metadata = new LinkedHashMap<>();
                }
                metadata.put(ContextAttributes.WEB_SELECTION_TEXT, selectionText);
                if (selectionFrom != null) {
                    metadata.put(ContextAttributes.WEB_SELECTION_FROM, selectionFrom);
                }
                if (selectionTo != null) {
                    metadata.put(ContextAttributes.WEB_SELECTION_TO, selectionTo);
                }
            }
            metadata = TraceContextSupport.ensureRootMetadata(
                    metadata,
                    TraceSpanKind.INGRESS,
                    TraceNamingSupport.WEBSOCKET_MESSAGE);

            Message message = Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role("user")
                    .content(text != null ? text : "")
                    .channelType(CHANNEL_TYPE)
                    .chatId(sessionId)
                    .senderId(username)
                    .metadata(metadata)
                    .timestamp(Instant.now())
                    .build();

            webChannelAdapter.handleIncomingMessage(message, connectionId);
        } catch (IOException | RuntimeException e) { // NOSONAR
            log.warn("[WebSocket] Failed to process incoming message: {}", e.getMessage());
        }
    }

    private boolean tryExecuteCommand(String text, String sessionId, String connectionId) {
        java.util.Optional<ParsedSlashCommand> parsedCommand = slashCommandParser.parse(text);
        if (parsedCommand.isEmpty()) {
            return false;
        }
        ParsedSlashCommand parsed = parsedCommand.get();
        webChannelAdapter.bindConnectionToChatId(connectionId, sessionId);

        CommandPort router = commandRouter.getIfAvailable();
        if (router == null || !router.hasCommand(parsed.command())) {
            return false;
        }

        String fullSessionId = CHANNEL_TYPE + ":" + sessionId;
        CommandExecutionContext context = CommandExecutionContext.builder()
                .sessionId(fullSessionId)
                .chatId(sessionId)
                .sessionChatId(sessionId)
                .transportChatId(sessionId)
                .conversationKey(sessionId)
                .channelType(CHANNEL_TYPE)
                .build();
        CommandInvocation invocation = CommandInvocation.of(
                parsed.command(),
                parsed.args(),
                parsed.rawInput(),
                context);

        try {
            CommandOutcome result = router.execute(invocation).join();
            webChannelAdapter.sendMessage(sessionId, commandOutcomePresenter.present(result));
            log.debug("[WebSocket] Executed command: /{} -> {}", parsed.command(), result.success() ? "ok" : "fail");
        } catch (Exception e) { // NOSONAR
            log.error("[WebSocket] Command execution failed: /{}", parsed.command(), e);
            webChannelAdapter.sendMessage(sessionId, "Command failed: " + e.getMessage());
        }
        return true;
    }

    private String extractToken(WebSocketSession session) {
        URI uri = session.getHandshakeInfo().getUri();
        String query = uri.getQuery();
        if (query != null) {
            return UriComponentsBuilder.newInstance()
                    .query(query)
                    .build()
                    .getQueryParams()
                    .getFirst("token");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractImageAttachments(Object rawAttachments) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (!(rawAttachments instanceof List<?> attachments)) {
            return result;
        }

        for (Object attachmentObj : attachments) {
            if (result.size() >= MAX_IMAGE_ATTACHMENTS) {
                break;
            }
            if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
                continue;
            }

            String type = asString(attachmentMap.get("type"));
            String mimeType = asString(attachmentMap.get("mimeType"));
            String dataBase64 = asString(attachmentMap.get("dataBase64"));
            String name = asString(attachmentMap.get("name"));

            if (!"image".equals(type) || mimeType == null || !mimeType.startsWith("image/")
                    || dataBase64 == null || dataBase64.isBlank()) {
                continue;
            }

            try {
                byte[] decoded = Base64.getDecoder().decode(dataBase64);
                if (decoded.length == 0 || decoded.length > MAX_IMAGE_BYTES) {
                    continue;
                }
            } catch (IllegalArgumentException ex) {
                continue;
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("type", "image");
            normalized.put("mimeType", mimeType);
            normalized.put("dataBase64", dataBase64);
            normalized.put("name", name != null ? name : "image");
            result.add(normalized);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractOpenedTabs(Object rawOpenedTabs) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(rawOpenedTabs instanceof List<?> openedTabs)) {
            return result;
        }
        for (Object tabObj : openedTabs) {
            if (!(tabObj instanceof Map<?, ?> tabMap)) {
                continue;
            }
            String path = asString(tabMap.get("path"));
            String title = asString(tabMap.get("title"));
            Object isDirtyValue = tabMap.get("isDirty");
            if (path == null || path.isBlank() || title == null || title.isBlank()
                    || !(isDirtyValue instanceof Boolean isDirty)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("path", path);
            normalized.put("title", title);
            normalized.put("isDirty", isDirty);
            result.add(normalized);
        }
        return result;
    }

    private String asString(Object value) {
        if (value instanceof String stringValue) {
            return stringValue;
        }
        return null;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private String normalizeSessionId(String sessionId, String fallback) {
        if (StringValueSupport.isBlank(sessionId)) {
            return fallback;
        }
        String candidate = sessionId.trim();
        if (candidate.isEmpty()) {
            return fallback;
        }
        return candidate;
    }

    private String normalizeClientInstanceId(String clientInstanceId) {
        if (StringValueSupport.isBlank(clientInstanceId)) {
            return null;
        }
        String candidate = clientInstanceId.trim();
        return candidate.isEmpty() ? null : candidate;
    }

    private String normalizeClientMessageId(String clientMessageId) {
        if (StringValueSupport.isBlank(clientMessageId)) {
            return null;
        }
        String candidate = clientMessageId.trim();
        return candidate.isEmpty() ? null : candidate;
    }

    private String normalizeMemoryPreset(String memoryPreset) {
        if (StringValueSupport.isBlank(memoryPreset)) {
            return null;
        }
        String candidate = memoryPreset.trim().toLowerCase(Locale.ROOT);
        return candidate;
    }

    private String normalizeActivePath(String activePath) {
        if (StringValueSupport.isBlank(activePath)) {
            return null;
        }
        String candidate = activePath.trim();
        return candidate.isEmpty() ? null : candidate;
    }

    /**
     * Resolves the memory preset for a websocket turn.
     *
     * <p>
     * Valid client overrides win for that turn only. Unknown override ids are
     * ignored instead of being forwarded to the agent context, because unknown ids
     * would otherwise bypass a global {@code disabled} preset in the memory layer.
     */
    private String resolveEffectiveMemoryPreset(String requestedMemoryPreset) {
        String normalizedOverride = normalizeMemoryPreset(requestedMemoryPreset);
        if (isKnownMemoryPreset(normalizedOverride)) {
            return normalizedOverride;
        }
        String persistedPreset = resolvePersistedMemoryPreset();
        return isKnownMemoryPreset(persistedPreset) ? persistedPreset : null;
    }

    private String resolvePersistedMemoryPreset() {
        try {
            return normalizeMemoryPreset(preferencesService.getPreferences().getMemoryPreset());
        } catch (RuntimeException e) { // NOSONAR - memory preference lookup must not block chat delivery
            log.debug("[WebSocket] Failed to resolve persisted memory preset: {}", e.getMessage());
            return null;
        }
    }

    private boolean isKnownMemoryPreset(String memoryPreset) {
        return memoryPreset != null && memoryPresetService.findById(memoryPreset).isPresent();
    }

    private void bindWebPointer(String username, String clientInstanceId, String sessionId) {
        if (StringValueSupport.isBlank(username) || StringValueSupport.isBlank(clientInstanceId)) {
            return;
        }
        if (!ConversationKeyValidator.isLegacyCompatibleConversationKey(sessionId)) {
            return;
        }
        try {
            String pointerKey = pointerService.buildWebPointerKey(username, clientInstanceId);
            pointerService.setActiveConversationKey(pointerKey, sessionId);
        } catch (RuntimeException e) { // NOSONAR - pointer persistence should not block chat delivery
            log.debug("[WebSocket] Failed to persist active pointer: {}", e.getMessage());
        }
    }
}

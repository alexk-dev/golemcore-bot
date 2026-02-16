/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.domain.model.ConfirmationCallbackEvent;
import me.golemcore.bot.domain.model.PlanApprovalCallbackEvent;
import me.golemcore.bot.domain.model.TelegramRestartEvent;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.security.AllowlistValidator;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Telegram channel adapter using long polling.
 *
 * <p>
 * This adapter implements both {@link ChannelPort} for outbound messaging and
 * {@link LongPollingSingleThreadUpdateConsumer} for inbound updates.
 *
 * <p>
 * Features:
 * <ul>
 * <li>Long polling for incoming messages via Telegram Bot API
 * <li>User authorization via allowlist/blocklist
 * <li>Command routing (slash commands) before AgentLoop
 * <li>Message splitting for Telegram's 4096 character limit
 * <li>Markdown to HTML formatting via {@link TelegramHtmlFormatter}
 * <li>Voice message download and processing
 * <li>Settings menu with inline keyboard for language selection
 * <li>Tool confirmation callbacks via inline keyboards
 * <li>Photo and document sending
 * </ul>
 *
 * <p>
 * The adapter is always available as a Spring bean but only starts polling if
 * {@code bot.channels.telegram.enabled=true} and a valid token is configured.
 *
 * @see me.golemcore.bot.port.inbound.ChannelPort
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramAdapter implements ChannelPort, LongPollingSingleThreadUpdateConsumer {

    private static final String CHANNEL_TYPE = "telegram";
    private static final String SETTINGS_COMMAND = "settings";
    private static final int CALLBACK_DATA_PARTS_COUNT = 3;
    private static final int TELEGRAM_MAX_MESSAGE_LENGTH = 4096;
    private static final int TELEGRAM_MAX_CAPTION_LENGTH = 1024;
    private static final int INVITE_MAX_FAILED_ATTEMPTS = 3;
    private static final int INVITE_COOLDOWN_SECONDS = 30;

    private final RuntimeConfigService runtimeConfigService;
    private final AllowlistValidator allowlistValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final TelegramBotsLongPollingApplication botsApplication;
    private final UserPreferencesService preferencesService;
    private final MessageService messageService;
    private final ObjectProvider<CommandPort> commandRouter;
    private final TelegramVoiceHandler voiceHandler;
    private final TelegramMenuHandler menuHandler;

    private TelegramClient telegramClient;
    private volatile Consumer<Message> messageHandler;
    private volatile boolean running = false;
    private volatile boolean initialized = false;
    private final Object lifecycleLock = new Object();
    private final Map<String, InviteAttemptState> inviteAttemptStates = new ConcurrentHashMap<>();

    /**
     * Package-private setter for testing — allows injecting a mock TelegramClient.
     */
    void setTelegramClient(TelegramClient client) {
        this.telegramClient = client;
        this.initialized = true;
    }

    /**
     * Package-private getter for testing.
     */
    TelegramClient getTelegramClient() {
        return this.telegramClient;
    }

    private boolean isEnabled() {
        return runtimeConfigService.isTelegramEnabled();
    }

    private synchronized void ensureInitialized() {
        if (initialized || !isEnabled())
            return;

        String token = runtimeConfigService.getTelegramToken();
        if (token == null || token.isBlank()) {
            log.warn("Telegram token not configured, adapter will not start");
            return;
        }
        this.telegramClient = new OkHttpTelegramClient(token);
        initialized = true;
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                log.debug("Telegram adapter already running");
                return;
            }
            if (!isEnabled()) {
                log.info("Telegram channel disabled");
                return;
            }
            ensureInitialized();
            if (telegramClient == null) {
                log.warn("Telegram client not initialized, cannot start");
                return;
            }

            try {
                String token = runtimeConfigService.getTelegramToken();
                botsApplication.registerBot(token, this);
                running = true;
                log.info("Telegram adapter started");
            } catch (TelegramApiException e) {
                if (e.getMessage() != null && e.getMessage().contains("already registered")) {
                    running = true;
                    log.warn("Telegram bot already registered; keeping existing polling session active");
                    return;
                }
                log.error("Failed to start Telegram adapter", e);
            } catch (Exception e) {
                log.error("Failed to start Telegram adapter", e);
            }
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            running = false;
            try {
                botsApplication.close();
                log.info("Telegram adapter stopped");
            } catch (Exception e) {
                log.error("Error stopping Telegram adapter", e);
            }
        }
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    /**
     * Handle restart request from dashboard settings UI.
     */
    @EventListener
    public void onTelegramRestart(TelegramRestartEvent event) {
        synchronized (lifecycleLock) {
            log.info("[Telegram] Restart requested from dashboard");
            if (running) {
                stop();
            }
            initialized = false;
            start();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void consume(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallback(update);
        } else if (update.hasMessage()) {
            handleMessage(update);
        }
    }

    private void handleCallback(Update update) {
        var callback = update.getCallbackQuery();
        if (callback.getMessage() == null) {
            log.warn("Callback query without associated message, ignoring");
            return;
        }
        String chatId = callback.getMessage().getChatId().toString();
        String data = callback.getData();
        Integer messageId = callback.getMessage().getMessageId();

        log.debug("Callback: {}", data);

        if (data.startsWith("confirm:")) {
            handleConfirmationCallback(chatId, messageId, data);
        } else if (data.startsWith("plan:")) {
            handlePlanCallback(chatId, messageId, data);
        } else if (data.startsWith("menu:")) {
            menuHandler.handleCallback(chatId, messageId, data);
        }
    }

    private void handleConfirmationCallback(String chatId, Integer messageId, String data) {
        // Format: confirm:<id>:yes or confirm:<id>:no
        String[] parts = data.split(":");
        if (parts.length != CALLBACK_DATA_PARTS_COUNT) {
            log.warn("Invalid confirmation callback data: {}", data);
            return;
        }

        String confirmationId = parts[1];
        boolean approved = "yes".equals(parts[2]);

        eventPublisher.publishEvent(new ConfirmationCallbackEvent(
                confirmationId, approved, chatId, messageId.toString()));
    }

    private void handlePlanCallback(String chatId, Integer messageId, String data) {
        // Format: plan:<planId>:<action> (approve or cancel)
        String[] parts = data.split(":");
        if (parts.length != CALLBACK_DATA_PARTS_COUNT) {
            log.warn("Invalid plan callback data: {}", data);
            return;
        }

        String planId = parts[1];
        String action = parts[2];

        eventPublisher.publishEvent(new PlanApprovalCallbackEvent(
                planId, action, chatId, messageId.toString()));
    }

    private void handleMessage(Update update) {
        org.telegram.telegrambots.meta.api.objects.message.Message telegramMessage = update.getMessage();
        String chatId = telegramMessage.getChatId().toString();
        String userId = telegramMessage.getFrom().getId().toString();

        // Check authorization
        if (!isAuthorized(userId)) {
            String authMode = runtimeConfigService.getRuntimeConfig().getTelegram().getAuthMode();
            if ("invite_only".equals(authMode)) {
                String text = telegramMessage.hasText() ? telegramMessage.getText().trim() : "";
                if (isInviteCooldownActive(userId)) {
                    long secondsLeft = getInviteCooldownSecondsLeft(userId);
                    sendMessage(chatId, messageService.getMessage("telegram.invite.cooldown", secondsLeft));
                    return;
                }
                if (!text.isEmpty() && runtimeConfigService.redeemInviteCode(text, userId)) {
                    clearInviteFailures(userId);
                    log.info("Invite code redeemed by user {} in chat {}", userId, chatId);
                    sendMessage(chatId, messageService.getMessage("telegram.invite.success"));
                    return;
                }
                if (!text.isEmpty()) {
                    long secondsLeft = recordInviteFailureAndGetCooldown(userId);
                    if (secondsLeft > 0) {
                        sendMessage(chatId, messageService.getMessage("telegram.invite.cooldown", secondsLeft));
                    } else {
                        sendMessage(chatId, messageService.getMessage("telegram.invite.invalid"));
                    }
                } else {
                    sendMessage(chatId, messageService.getMessage("telegram.invite.prompt"));
                }
            } else {
                log.warn("Unauthorized user: {} in chat: {}", userId, chatId);
                sendMessage(chatId, messageService.getMessage("security.unauthorized"));
            }
            return;
        }

        Message.MessageBuilder messageBuilder = Message.builder()
                .id(telegramMessage.getMessageId().toString())
                .channelType(CHANNEL_TYPE)
                .chatId(chatId)
                .senderId(userId)
                .role("user")
                .timestamp(Instant.now());

        // Handle text messages
        if (telegramMessage.hasText()) {
            String text = telegramMessage.getText();

            // Handle commands
            if (text.startsWith("/")) {
                String[] parts = text.split("\\s+", 2);
                String cmd = parts[0].substring(1).split("@")[0]; // strip / and @botname

                // /menu and /settings open the centralized inline-keyboard menu
                if ("menu".equals(cmd) || SETTINGS_COMMAND.equals(cmd)) {
                    menuHandler.sendMainMenu(chatId);
                    return;
                }

                // Route to CommandRouter
                CommandPort router = commandRouter.getIfAvailable();
                if (router != null && router.hasCommand(cmd)) {
                    List<String> args = parts.length > 1
                            ? Arrays.asList(parts[1].split("\s+"))
                            : List.of();
                    String sessionId = CHANNEL_TYPE + ":" + chatId;
                    Map<String, Object> ctx = Map.<String, Object>of(
                            "sessionId", sessionId,
                            "chatId", chatId,
                            "channelType", CHANNEL_TYPE);
                    try {
                        var result = router.execute(cmd, args, ctx).join();
                        sendMessage(chatId, result.output());
                    } catch (Exception e) {
                        log.error("Command execution failed: /{}", cmd, e);
                        sendMessage(chatId, "Command failed: " + e.getMessage());
                    }
                    return;
                }
            }

            messageBuilder.content(text);
        }

        // Handle voice messages
        if (telegramMessage.hasVoice()) {
            if (runtimeConfigService.isTelegramTranscribeIncomingEnabled()) {
                processVoiceMessage(telegramMessage, messageBuilder);
            } else {
                messageBuilder.content("[Voice message]");
            }
        }

        Message message = messageBuilder.build();

        // Notify handlers (local copy to avoid race on volatile field)
        Consumer<Message> handler = this.messageHandler;
        if (handler != null) {
            handler.accept(message);
        }

        // Publish event
        eventPublisher.publishEvent(new AgentLoop.InboundMessageEvent(message));
    }

    private boolean isInviteCooldownActive(String userId) {
        InviteAttemptState state = inviteAttemptStates.get(userId);
        return state != null && state.cooldownUntil != null && state.cooldownUntil.isAfter(Instant.now());
    }

    private long getInviteCooldownSecondsLeft(String userId) {
        InviteAttemptState state = inviteAttemptStates.get(userId);
        if (state == null || state.cooldownUntil == null) {
            return 0;
        }
        long seconds = java.time.Duration.between(Instant.now(), state.cooldownUntil).getSeconds();
        return Math.max(seconds, 0);
    }

    private long recordInviteFailureAndGetCooldown(String userId) {
        InviteAttemptState state = inviteAttemptStates.computeIfAbsent(userId, key -> new InviteAttemptState());
        if (state.cooldownUntil != null && state.cooldownUntil.isAfter(Instant.now())) {
            return getInviteCooldownSecondsLeft(userId);
        }

        state.failedAttempts = state.failedAttempts + 1;
        if (state.failedAttempts >= INVITE_MAX_FAILED_ATTEMPTS) {
            state.failedAttempts = 0;
            state.cooldownUntil = Instant.now().plusSeconds(INVITE_COOLDOWN_SECONDS);
            return INVITE_COOLDOWN_SECONDS;
        }
        return 0;
    }

    private void clearInviteFailures(String userId) {
        inviteAttemptStates.remove(userId);
    }

    private static final class InviteAttemptState {
        private int failedAttempts = 0;
        private Instant cooldownUntil;
    }

    private void processVoiceMessage(
            org.telegram.telegrambots.meta.api.objects.message.Message telegramMessage,
            Message.MessageBuilder messageBuilder) {
        try {
            org.telegram.telegrambots.meta.api.objects.Voice voice = telegramMessage.getVoice();
            byte[] voiceData = downloadVoice(voice.getFileId());
            int duration = voice.getDuration() != null ? voice.getDuration() : 0;
            log.info("[Voice] Received voice message: {} bytes, {}s duration", voiceData.length, duration);

            messageBuilder
                    .voiceData(voiceData)
                    .audioFormat(AudioFormat.OGG_OPUS);

            String transcription = voiceHandler.handleIncomingVoice(voiceData).get();
            if (transcription != null && !transcription.startsWith("[")) {
                log.info("[Voice] Decoded: \"{}\" ({} chars, {} bytes audio)",
                        truncateForLog(transcription, 100), transcription.length(), voiceData.length);
                messageBuilder.content(transcription).voiceTranscription(transcription);
            } else {
                log.warn("[Voice] Transcription unavailable, using placeholder: {}", transcription);
                messageBuilder.content(transcription != null ? transcription : "[Voice message]");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Voice] Voice processing interrupted", e);
            messageBuilder.content("[Failed to process voice message]");
        } catch (Exception e) {
            log.error("[Voice] Failed to process voice message", e);
            messageBuilder.content("[Failed to process voice message]");
        }
    }

    private byte[] downloadVoice(String fileId) throws TelegramApiException, java.io.IOException {
        GetFile getFile = new GetFile(fileId);
        org.telegram.telegrambots.meta.api.objects.File file = telegramClient.execute(getFile);

        String filePath = file.getFilePath();
        String token = runtimeConfigService.getTelegramToken();
        String fileUrl = "https://api.telegram.org/file/bot" + token + "/" + filePath;

        try (InputStream is = URI.create(fileUrl).toURL().openStream()) {
            return is.readAllBytes();
        }
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Split long messages at paragraph/line boundaries (Telegram limit is 4096
                // chars)
                List<String> chunks = splitAtNewlines(content, 3800);

                for (String chunk : chunks) {
                    String formatted = TelegramHtmlFormatter.format(chunk);

                    // Safety: if formatted exceeds Telegram limit, truncate
                    if (formatted.length() > TELEGRAM_MAX_MESSAGE_LENGTH) {
                        formatted = formatted.substring(0, TELEGRAM_MAX_MESSAGE_LENGTH - 3) + "...";
                    }

                    SendMessage sendMessage = SendMessage.builder()
                            .chatId(chatId)
                            .text(formatted)
                            .parseMode("HTML")
                            .build();

                    try {
                        telegramClient.execute(sendMessage);
                    } catch (Exception htmlEx) {
                        // Fallback: retry without formatting if HTML parsing fails
                        log.debug("HTML parse failed, retrying as plain text: {}", htmlEx.getMessage());
                        SendMessage plain = SendMessage.builder()
                                .chatId(chatId)
                                .text(chunk)
                                .build();
                        telegramClient.execute(plain);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to send message to chat: {}", chatId, e);
                throw new RuntimeException("Failed to send message", e);
            }
        });
    }

    /**
     * Split text at paragraph (\n\n) or line (\n) boundaries to keep chunks under
     * maxLength. This prevents splitting in the middle of markdown formatting like
     * **bold** or `code`.
     */
    static List<String> splitAtNewlines(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            if (start + maxLength >= text.length()) {
                chunks.add(text.substring(start));
                break;
            }

            String segment = text.substring(start, start + maxLength);

            // Try paragraph break
            int splitAt = segment.lastIndexOf("\n\n");
            if (splitAt > maxLength / 4) {
                chunks.add(text.substring(start, start + splitAt));
                start += splitAt + 2;
                continue;
            }

            // Try line break
            splitAt = segment.lastIndexOf('\n');
            if (splitAt > maxLength / 4) {
                chunks.add(text.substring(start, start + splitAt));
                start += splitAt + 1;
                continue;
            }

            // Hard split as last resort
            chunks.add(text.substring(start, start + maxLength));
            start += maxLength;
        }

        return chunks;
    }

    @Override
    public CompletableFuture<Void> sendMessage(Message message) {
        return sendMessage(message.getChatId(), message.getContent());
    }

    @Override
    public CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData) {
        return CompletableFuture.runAsync(() -> {
            try {
                SendVoice sendVoice = SendVoice.builder()
                        .chatId(chatId)
                        .voice(new InputFile(new ByteArrayInputStream(voiceData), "voice.ogg"))
                        .build();

                telegramClient.execute(sendVoice);
            } catch (Exception e) {
                if (isVoiceForbidden(e)) {
                    log.info("Voice messages forbidden in chat {}, falling back to audio", chatId);
                    sendAudioFallback(chatId, voiceData);
                    return;
                }
                log.error("Failed to send voice to chat: {}", chatId, e);
                throw new RuntimeException("Failed to send voice", e);
            }
        });
    }

    private void sendAudioFallback(String chatId, byte[] audioData) {
        try {
            SendAudio sendAudio = SendAudio.builder()
                    .chatId(chatId)
                    .audio(new InputFile(new ByteArrayInputStream(audioData), "voice.mp3"))
                    .build();
            telegramClient.execute(sendAudio);
        } catch (Exception e) {
            log.error("Failed to send audio fallback to chat: {}", chatId, e);
            throw new RuntimeException("Failed to send audio", e);
        }
    }

    private boolean isVoiceForbidden(Exception e) {
        if (e instanceof TelegramApiRequestException reqEx) {
            return reqEx.getApiResponse() != null
                    && reqEx.getApiResponse().contains("VOICE_MESSAGES_FORBIDDEN");
        }
        if (e.getCause() instanceof TelegramApiRequestException reqEx) {
            return reqEx.getApiResponse() != null
                    && reqEx.getApiResponse().contains("VOICE_MESSAGES_FORBIDDEN");
        }
        return false;
    }

    @Override
    public CompletableFuture<Void> sendPhoto(String chatId, byte[] imageData,
            String filename, String caption) {
        return CompletableFuture.runAsync(() -> {
            try {
                SendPhoto.SendPhotoBuilder<?, ?> builder = SendPhoto.builder()
                        .chatId(chatId)
                        .photo(new InputFile(new ByteArrayInputStream(imageData), filename));

                if (caption != null && !caption.isBlank()) {
                    builder.caption(truncateCaption(caption));
                }

                telegramClient.execute(builder.build());
                log.debug("Sent photo '{}' ({} bytes) to chat: {}", filename, imageData.length, chatId);
            } catch (Exception e) {
                log.error("Failed to send photo '{}' to chat: {}", filename, chatId, e);
                throw new RuntimeException("Failed to send photo", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> sendDocument(String chatId, byte[] fileData,
            String filename, String caption) {
        return CompletableFuture.runAsync(() -> {
            try {
                SendDocument.SendDocumentBuilder<?, ?> builder = SendDocument.builder()
                        .chatId(chatId)
                        .document(new InputFile(new ByteArrayInputStream(fileData), filename));

                if (caption != null && !caption.isBlank()) {
                    builder.caption(truncateCaption(caption));
                }

                telegramClient.execute(builder.build());
                log.debug("Sent document '{}' ({} bytes) to chat: {}", filename, fileData.length, chatId);
            } catch (Exception e) {
                log.error("Failed to send document '{}' to chat: {}", filename, chatId, e);
                throw new RuntimeException("Failed to send document", e);
            }
        });
    }

    private String truncateCaption(String caption) {
        if (caption.length() <= TELEGRAM_MAX_CAPTION_LENGTH)
            return caption;
        return caption.substring(0, TELEGRAM_MAX_CAPTION_LENGTH - 3) + "...";
    }

    private static String truncateForLog(String text, int maxLen) {
        if (text == null || text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    @Override
    public boolean isAuthorized(String senderId) {
        return allowlistValidator.isAllowed(CHANNEL_TYPE, senderId) &&
                !allowlistValidator.isBlocked(senderId);
    }

    @Override
    public void onMessage(Consumer<Message> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void showTyping(String chatId) {
        try {
            SendChatAction action = SendChatAction.builder()
                    .chatId(chatId)
                    .action(ActionType.TYPING.toString())
                    .build();
            telegramClient.execute(action);
        } catch (Exception e) {
            log.debug("Failed to send typing indicator", e);
        }
    }

}

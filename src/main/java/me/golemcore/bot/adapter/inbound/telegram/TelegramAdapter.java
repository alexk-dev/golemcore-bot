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

import me.golemcore.bot.adapter.outbound.confirmation.TelegramConfirmationAdapter;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.security.AllowlistValidator;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
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

    private final BotProperties properties;
    private final AllowlistValidator allowlistValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final TelegramBotsLongPollingApplication botsApplication;
    private final UserPreferencesService preferencesService;
    private final MessageService messageService;
    private final CommandPort commandRouter;
    private final TelegramConfirmationAdapter telegramConfirmationAdapter;

    private TelegramClient telegramClient;
    private volatile Consumer<Message> messageHandler;
    private volatile boolean running = false;
    private volatile boolean initialized = false;

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
        BotProperties.ChannelProperties channelProps = properties.getChannels().get("telegram");
        return channelProps != null && channelProps.isEnabled();
    }

    private synchronized void ensureInitialized() {
        if (initialized || !isEnabled())
            return;

        String token = properties.getChannels().get("telegram").getToken();
        if (token == null || token.isBlank()) {
            log.warn("Telegram token not configured, adapter will not start");
            return;
        }
        this.telegramClient = new OkHttpTelegramClient(token);
        telegramConfirmationAdapter.setTelegramClient(this.telegramClient);
        initialized = true;
    }

    @Override
    public String getChannelType() {
        return "telegram";
    }

    @Override
    public void start() {
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
            String token = properties.getChannels().get("telegram").getToken();
            botsApplication.registerBot(token, this);
            running = true;
            log.info("Telegram adapter started");
        } catch (Exception e) {
            log.error("Failed to start Telegram adapter", e);
        }
    }

    @Override
    public void stop() {
        running = false;
        try {
            botsApplication.close();
            log.info("Telegram adapter stopped");
        } catch (Exception e) {
            log.error("Error stopping Telegram adapter", e);
        }
    }

    @PreDestroy
    public void destroy() {
        stop();
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
        } else if (data.startsWith("lang:")) {
            String lang = data.substring(5);
            preferencesService.setLanguage(lang);
            String langName = messageService.getLanguageDisplayName(lang);
            String message = preferencesService.getMessage("settings.language.changed", langName);
            updateSettingsMessage(chatId, messageId, message);
        }
    }

    private void handleConfirmationCallback(String chatId, Integer messageId, String data) {
        // Format: confirm:<id>:yes or confirm:<id>:no
        String[] parts = data.split(":");
        if (parts.length != 3) {
            log.warn("Invalid confirmation callback data: {}", data);
            return;
        }

        String confirmationId = parts[1];
        boolean approved = "yes".equals(parts[2]);

        boolean resolved = telegramConfirmationAdapter.resolve(confirmationId, approved);

        if (resolved) {
            String statusText = approved ? "\u2705 Confirmed" : "\u274c Cancelled";
            try {
                EditMessageText edit = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(statusText)
                        .build();
                telegramClient.execute(edit);
            } catch (Exception e) {
                log.error("Failed to update confirmation message", e);
            }
        }
    }

    private void updateSettingsMessage(String chatId, Integer messageId, String statusMessage) {
        try {
            String title = preferencesService.getMessage("settings.title");
            String currentLang = preferencesService.getLanguage();
            String langName = messageService.getLanguageDisplayName(currentLang);
            String langLabel = preferencesService.getMessage("settings.language.current", langName);

            String text = "**" + title + "**\n\n" + langLabel;
            if (statusMessage != null) {
                text += "\n\n" + statusMessage;
            }

            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(TelegramHtmlFormatter.format(text))
                    .parseMode("HTML")
                    .replyMarkup(buildLanguageKeyboard())
                    .build();

            telegramClient.execute(edit);
        } catch (Exception e) {
            log.error("Failed to update settings message", e);
        }
    }

    private InlineKeyboardMarkup buildLanguageKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text(preferencesService.getMessage("button.language.en"))
                                .callbackData("lang:en")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text(preferencesService.getMessage("button.language.ru"))
                                .callbackData("lang:ru")
                                .build()))
                .build();
    }

    private void handleMessage(Update update) {
        org.telegram.telegrambots.meta.api.objects.message.Message telegramMessage = update.getMessage();
        String chatId = telegramMessage.getChatId().toString();
        String userId = telegramMessage.getFrom().getId().toString();

        // Check authorization
        if (!isAuthorized(userId)) {
            log.warn("Unauthorized user: {} in chat: {}", userId, chatId);
            return;
        }

        Message.MessageBuilder messageBuilder = Message.builder()
                .id(telegramMessage.getMessageId().toString())
                .channelType("telegram")
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

                // /settings is a special case (uses Telegram inline keyboards)
                if ("settings".equals(cmd)) {
                    sendSettingsMenu(chatId);
                    return;
                }

                // Route to CommandRouter
                if (commandRouter.hasCommand(cmd)) {
                    List<String> args = parts.length > 1
                            ? Arrays.asList(parts[1].split("\\s+"))
                            : List.of();
                    String sessionId = "telegram:" + chatId;
                    Map<String, Object> ctx = Map.<String, Object>of(
                            "sessionId", sessionId,
                            "chatId", chatId,
                            "channelType", "telegram");
                    try {
                        var result = commandRouter.execute(cmd, args, ctx).join();
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
            try {
                byte[] voiceData = downloadVoice(telegramMessage.getVoice().getFileId());
                messageBuilder
                        .voiceData(voiceData)
                        .audioFormat(AudioFormat.OGG_OPUS)
                        .content("[Voice message]");
            } catch (Exception e) {
                log.error("Failed to download voice message", e);
                messageBuilder.content("[Failed to process voice message]");
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

    private byte[] downloadVoice(String fileId) throws Exception {
        GetFile getFile = new GetFile(fileId);
        org.telegram.telegrambots.meta.api.objects.File file = telegramClient.execute(getFile);

        String filePath = file.getFilePath();
        String token = properties.getChannels().get("telegram").getToken();
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
                    if (formatted.length() > 4096) {
                        formatted = formatted.substring(0, 4093) + "...";
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
                log.error("Failed to send voice to chat: {}", chatId, e);
                throw new RuntimeException("Failed to send voice", e);
            }
        });
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
        if (caption.length() <= 1024)
            return caption;
        return caption.substring(0, 1021) + "...";
    }

    @Override
    public boolean isAuthorized(String senderId) {
        return allowlistValidator.isAllowed("telegram", senderId) &&
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

    private void sendSettingsMenu(String chatId) {
        try {
            String title = preferencesService.getMessage("settings.title");
            String currentLang = preferencesService.getLanguage();
            String langName = messageService.getLanguageDisplayName(currentLang);
            String langLabel = preferencesService.getMessage("settings.language.current", langName);
            String selectLabel = preferencesService.getMessage("settings.language.select");

            String text = "**" + title + "**\n\n" + langLabel + "\n\n" + selectLabel;

            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(TelegramHtmlFormatter.format(text))
                    .parseMode("HTML")
                    .replyMarkup(buildLanguageKeyboard())
                    .build();

            telegramClient.execute(message);
            log.debug("Sent settings menu to chat: {}", chatId);
        } catch (Exception e) {
            log.error("Failed to send settings menu", e);
        }
    }
}

package me.golemcore.bot.adapter.outbound.confirmation;

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

import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Telegram-based implementation of ConfirmationPort.
 *
 * <p>
 * This adapter sends inline keyboard prompts to Telegram users asking for
 * confirmation before executing certain tool calls. Used by
 * {@link me.golemcore.bot.domain.service.ToolConfirmationPolicy} to protect
 * high-risk operations.
 *
 * <p>
 * Features:
 * <ul>
 * <li>Inline keyboard with "Confirm" / "Cancel" buttons
 * <li>Configurable timeout (default 60s)
 * <li>Fail-open: auto-approves if Telegram client unavailable
 * <li>Scheduled cleanup of stale pending confirmations
 * </ul>
 *
 * <p>
 * Integration:
 * <ul>
 * <li>{@link me.golemcore.bot.adapter.inbound.telegram.TelegramAdapter} calls
 * {@link #setTelegramClient} after initialization
 * <li>{@link me.golemcore.bot.domain.system.ToolExecutionSystem} calls
 * {@link #requestConfirmation} before executing tools
 * <li>TelegramAdapter routes callbacks to {@link #resolve}
 * </ul>
 *
 * <p>
 * Configuration:
 * <ul>
 * <li>{@code bot.security.tool-confirmation.enabled} - Enable/disable
 * <li>{@code bot.security.tool-confirmation.timeout-seconds} - Timeout
 * </ul>
 *
 * @see me.golemcore.bot.port.outbound.ConfirmationPort
 * @see me.golemcore.bot.domain.service.ToolConfirmationPolicy
 */
@Component
@Slf4j
public class TelegramConfirmationAdapter implements ConfirmationPort {

    private final Map<String, PendingConfirmation> pending = new ConcurrentHashMap<>();
    private final int timeoutSeconds;
    private final boolean enabled;

    private volatile TelegramClient telegramClient;
    private ScheduledExecutorService cleanupExecutor;

    public TelegramConfirmationAdapter(BotProperties properties) {
        var config = properties.getSecurity().getToolConfirmation();
        this.enabled = config.isEnabled();
        this.timeoutSeconds = config.getTimeoutSeconds();
        log.info("TelegramConfirmationAdapter enabled: {}, timeout: {}s", enabled, timeoutSeconds);
    }

    @PostConstruct
    public void init() {
        // Periodically clean up stale pending confirmations that were never resolved
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "confirmation-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(() -> {
            long cutoff = System.currentTimeMillis() - (timeoutSeconds + 30) * 1000L;
            pending.entrySet().removeIf(e -> {
                if (e.getValue().createdAt() < cutoff) {
                    e.getValue().future().completeExceptionally(new TimeoutException("Stale confirmation cleaned up"));
                    return true;
                }
                return false;
            });
        }, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void destroy() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
            try {
                cleanupExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Set the TelegramClient instance. Called by TelegramAdapter after
     * initialization.
     */
    public void setTelegramClient(TelegramClient client) {
        this.telegramClient = client;
        log.debug("TelegramClient set for confirmation adapter");
    }

    @Override
    public boolean isAvailable() {
        return enabled && telegramClient != null;
    }

    @Override
    public CompletableFuture<Boolean> requestConfirmation(String chatId, String toolName, String description) {
        if (!isAvailable()) {
            log.debug("Confirmation not available, auto-approving");
            return CompletableFuture.completedFuture(true);
        }

        String confirmationId = UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        pending.put(confirmationId, new PendingConfirmation(future, System.currentTimeMillis()));

        try {
            sendConfirmationMessage(chatId, confirmationId, toolName, description);
        } catch (Exception e) {
            log.error("Failed to send confirmation message, auto-approving", e);
            pending.remove(confirmationId);
            return CompletableFuture.completedFuture(true);
        }

        return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.info("Confirmation {} timed out or failed, denying", confirmationId);
                    pending.remove(confirmationId);
                    return false;
                });
    }

    /**
     * Resolve a pending confirmation. Called by TelegramAdapter when a callback is
     * received.
     *
     * @param confirmationId
     *            the confirmation ID from the callback data
     * @param approved
     *            true if confirmed, false if cancelled
     * @return true if the confirmation was found and resolved
     */
    public boolean resolve(String confirmationId, boolean approved) {
        PendingConfirmation confirmation = pending.remove(confirmationId);
        if (confirmation == null) {
            log.debug("No pending confirmation found for id: {}", confirmationId);
            return false;
        }

        confirmation.future.complete(approved);
        log.info("Confirmation {} resolved: approved={}", confirmationId, approved);
        return true;
    }

    private void sendConfirmationMessage(String chatId, String confirmationId, String toolName, String description)
            throws Exception {
        TelegramClient client = this.telegramClient;
        if (client == null) {
            log.warn("TelegramClient not initialized, cannot send confirmation message");
            return;
        }

        String text = "\u26a0\ufe0f <b>Confirm action</b>\n\n"
                + "<b>Tool:</b> " + escapeHtml(toolName) + "\n"
                + "<b>Action:</b> " + escapeHtml(description);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("\u2705 Confirm")
                                .callbackData("confirm:" + confirmationId + ":yes")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("\u274c Cancel")
                                .callbackData("confirm:" + confirmationId + ":no")
                                .build()))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
                .replyMarkup(keyboard)
                .build();

        client.execute(message);
        log.debug("Sent confirmation message for id: {}", confirmationId);
    }

    private String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private record PendingConfirmation(CompletableFuture<Boolean> future, long createdAt) {
    }
}

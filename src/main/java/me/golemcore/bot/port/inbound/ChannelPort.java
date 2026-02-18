package me.golemcore.bot.port.inbound;

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

import me.golemcore.bot.domain.model.Message;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Bidirectional port for communication channels (Telegram, Discord, etc.).
 * Handles both incoming messages from users and outgoing responses from the
 * agent. Implementations manage connection lifecycle, authorization, and
 * message formatting.
 */
public interface ChannelPort {

    /**
     * Returns the channel type identifier (e.g., "telegram", "discord").
     */
    String getChannelType();

    /**
     * Starts listening for incoming messages from the channel.
     */
    void start();

    /**
     * Stops listening for messages and disconnects from the channel.
     */
    void stop();

    /**
     * Checks if the channel is currently active and listening.
     */
    boolean isRunning();

    /**
     * Sends a text message to the specified chat.
     */
    CompletableFuture<Void> sendMessage(String chatId, String content);

    /**
     * Sends a text message with protocol-level metadata hints. Default
     * implementation ignores hints — channels override to include metadata.
     */
    default CompletableFuture<Void> sendMessage(String chatId, String content, Map<String, Object> hints) {
        return sendMessage(chatId, content);
    }

    /**
     * Sends a complete message object to the channel.
     */
    CompletableFuture<Void> sendMessage(Message message);

    /**
     * Sends an audio voice message to the specified chat.
     */
    CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData);

    /**
     * Checks if a user is authorized to interact with the bot based on allowlist.
     */
    boolean isAuthorized(String senderId);

    /**
     * Registers a callback handler that is invoked when new messages arrive.
     */
    void onMessage(Consumer<Message> handler);

    /**
     * Sends a photo/image to the chat with optional filename and caption. Default
     * implementation delegates to sendDocument.
     */
    default CompletableFuture<Void> sendPhoto(String chatId, byte[] imageData,
            String filename, String caption) {
        return sendDocument(chatId, imageData, filename, caption);
    }

    /**
     * Sends a document/file to the chat with optional filename and caption. Default
     * implementation is a no-op; channels should override to provide actual
     * functionality.
     */
    default CompletableFuture<Void> sendDocument(String chatId, byte[] fileData,
            String filename, String caption) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Displays a typing indicator to the user while processing. Default
     * implementation does nothing; channels can override to show activity.
     */
    default void showTyping(String chatId) {
        // Default no-op implementation
    }
}

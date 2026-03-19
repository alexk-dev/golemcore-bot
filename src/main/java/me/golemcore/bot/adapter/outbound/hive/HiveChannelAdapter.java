package me.golemcore.bot.adapter.outbound.hive;

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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HiveChannelAdapter implements ChannelPort {

    private final HiveEventBatchPublisher hiveEventBatchPublisher;

    private volatile boolean running = true;

    @Override
    public String getChannelType() {
        return "hive";
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content) {
        return sendMessage(chatId, content, Map.of());
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content, Map<String, Object> hints) {
        try {
            hiveEventBatchPublisher.publishThreadMessage(chatId, content, hints);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletableFuture<Void> sendMessage(Message message) {
        if (message == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            hiveEventBatchPublisher.publishThreadMessage(message.getChatId(), message.getContent(),
                    message.getMetadata());
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendRuntimeEvent(String chatId, RuntimeEvent event) {
        try {
            hiveEventBatchPublisher.publishRuntimeEvents(
                    event != null ? java.util.List.of(event) : java.util.List.of(),
                    event != null ? event.payload() : Map.of());
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletableFuture<Void> sendProgressUpdate(String chatId, ProgressUpdate update) {
        try {
            hiveEventBatchPublisher.publishProgressUpdate(chatId, update);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public boolean isAuthorized(String senderId) {
        return true;
    }

    @Override
    public void onMessage(Consumer<Message> handler) {
        // Hive messages arrive through the outbound control channel client.
    }
}

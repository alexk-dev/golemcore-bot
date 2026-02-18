package me.golemcore.bot.adapter.inbound.telegram;

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

import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.VoicePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Telegram-specific handler for incoming voice messages (STT).
 *
 * <p>
 * Transcribes OGG Opus voice messages via {@link VoicePort} (ElevenLabs STT).
 * Gracefully degrades when voice processing is disabled or unavailable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramVoiceHandler {

    private final VoicePort voicePort;
    private final RuntimeConfigService runtimeConfigService;

    /**
     * Transcribe incoming OGG Opus voice message to text.
     */
    public CompletableFuture<String> handleIncomingVoice(byte[] voiceData) {
        if (!runtimeConfigService.isVoiceEnabled()) {
            log.debug("[Voice] Incoming voice skipped: voice feature disabled");
            return CompletableFuture.completedFuture("[Voice messages disabled]");
        }

        if (!voicePort.isAvailable()) {
            log.warn("[Voice] Incoming voice skipped: voice service unavailable (API key missing?)");
            return CompletableFuture.completedFuture("[Voice processing unavailable]");
        }

        log.info("[Voice] Transcribing incoming voice: {} bytes, format=OGG_OPUS", voiceData.length);

        return voicePort.transcribe(voiceData, AudioFormat.OGG_OPUS)
                .thenApply(result -> {
                    String preview = result.text() != null && result.text().length() > 200
                            ? result.text().substring(0, 200) + "..."
                            : result.text();
                    log.info("[Voice] Transcription result: \"{}\" ({} chars, language={})",
                            preview, result.text() != null ? result.text().length() : 0, result.language());
                    return result.text();
                })
                .exceptionally(e -> {
                    log.error("[Voice] Transcription failed: {}", e.getMessage(), e);
                    return "[Failed to transcribe voice message]";
                });
    }

    /**
     * Process voice message and return a Message with transcription.
     */
    public CompletableFuture<Message> processVoiceMessage(String chatId, byte[] voiceData) {
        log.info("[Voice] Processing voice message: chatId={}, {} bytes", chatId, voiceData.length);

        return handleIncomingVoice(voiceData)
                .thenApply(transcription -> Message.builder()
                        .channelType("telegram")
                        .chatId(chatId)
                        .role("user")
                        .content(transcription)
                        .voiceData(voiceData)
                        .voiceTranscription(transcription)
                        .audioFormat(AudioFormat.OGG_OPUS)
                        .timestamp(Instant.now())
                        .build());
    }
}

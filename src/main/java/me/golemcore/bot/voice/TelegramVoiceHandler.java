package me.golemcore.bot.voice;

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
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.VoicePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Telegram-specific handler for voice message processing.
 *
 * <p>
 * Provides two main operations:
 * <ul>
 * <li><b>Incoming voice</b> — Transcribe OGG Opus voice messages via ElevenLabs
 * STT</li>
 * <li><b>Outgoing voice</b> — Synthesize text to MP3 via ElevenLabs TTS</li>
 * </ul>
 *
 * <p>
 * ElevenLabs accepts OGG natively and returns MP3, so no FFmpeg conversion is
 * needed. Telegram {@code sendVoice()} accepts MP3.
 *
 * <p>
 * Gracefully degrades when voice processing is disabled or unavailable,
 * returning placeholder text instead of failing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramVoiceHandler {

    private final VoicePort voicePort;
    private final BotProperties properties;

    /**
     * Transcribe incoming OGG Opus voice message to text.
     */
    public CompletableFuture<String> handleIncomingVoice(byte[] voiceData) {
        if (!properties.getVoice().isEnabled()) {
            return CompletableFuture.completedFuture("[Voice messages disabled]");
        }

        if (!voicePort.isAvailable()) {
            return CompletableFuture.completedFuture("[Voice processing unavailable]");
        }

        return voicePort.transcribe(voiceData, AudioFormat.OGG_OPUS)
                .thenApply(result -> {
                    log.debug("Transcribed voice message: {} chars, language: {}",
                            result.text().length(), result.language());
                    return result.text();
                })
                .exceptionally(e -> {
                    log.error("Voice transcription failed", e);
                    return "[Failed to transcribe voice message]";
                });
    }

    /**
     * Synthesize text to MP3 audio for sending via Telegram.
     */
    public CompletableFuture<byte[]> synthesizeForTelegram(String text) {
        if (!properties.getVoice().isEnabled()) {
            return CompletableFuture.failedFuture(new RuntimeException("Voice disabled"));
        }

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(
                properties.getVoice().getVoiceId(),
                properties.getVoice().getTtsModelId(),
                properties.getVoice().getSpeed(),
                AudioFormat.MP3);

        return voicePort.synthesize(text, config);
    }

    /**
     * Process voice message and return a Message with transcription.
     */
    public CompletableFuture<Message> processVoiceMessage(
            String chatId,
            byte[] voiceData,
            boolean respondWithVoice) {
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

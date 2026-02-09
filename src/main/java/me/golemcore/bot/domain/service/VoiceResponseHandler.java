package me.golemcore.bot.domain.service;

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
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.VoicePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Unified voice response handler. Synthesizes text via {@link VoicePort} and
 * sends via {@link ChannelPort}. Falls back to text on any failure.
 *
 * <p>
 * Returns {@code true} if voice was successfully sent, {@code false} if caller
 * should fall back to text delivery.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceResponseHandler {

    private final VoicePort voicePort;
    private final BotProperties properties;

    /**
     * Try to synthesize and send voice. Returns true on success.
     *
     * @param channel
     *            the channel to send through
     * @param chatId
     *            target chat
     * @param text
     *            text to synthesize
     * @return true if voice was sent successfully
     */
    public boolean trySendVoice(ChannelPort channel, String chatId, String text) {
        if (!voicePort.isAvailable()) {
            log.debug("[Voice] Not available, skipping synthesis");
            return false;
        }
        try {
            BotProperties.VoiceProperties voice = properties.getVoice();
            VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(
                    voice.getVoiceId(),
                    voice.getTtsModelId(),
                    voice.getSpeed(),
                    AudioFormat.MP3);
            byte[] audioData = voicePort.synthesize(text, config).get(60, TimeUnit.SECONDS);
            channel.sendVoice(chatId, audioData).get(30, TimeUnit.SECONDS);
            log.info("[Voice] Sent: {} chars → {} bytes audio, chatId={}", text.length(), audioData.length, chatId);
            return true;
        } catch (Exception e) {
            log.error("[Voice] TTS/send failed, caller should fall back to text: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send voice with text fallback. Tries voice first; on failure sends text.
     *
     * @param channel
     *            the channel to send through
     * @param chatId
     *            target chat
     * @param text
     *            text to synthesize / send as fallback
     * @return true if something was sent (voice or text), false if both failed
     */
    public boolean sendVoiceWithFallback(ChannelPort channel, String chatId, String text) {
        if (trySendVoice(channel, chatId, text)) {
            return true;
        }
        // Fallback to text
        try {
            channel.sendMessage(chatId, text).get(30, TimeUnit.SECONDS);
            log.info("[Voice] Fallback text sent: {} chars, chatId={}", text.length(), chatId);
            return true;
        } catch (Exception e) {
            log.error("[Voice] Fallback text also failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isAvailable() {
        return voicePort.isAvailable();
    }
}

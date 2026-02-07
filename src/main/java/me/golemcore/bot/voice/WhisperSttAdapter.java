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
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.VoicePort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI Whisper speech-to-text adapter for voice message transcription.
 *
 * <p>
 * Implements {@link VoicePort} for STT operations using OpenAI's Whisper API.
 * Supports multiple audio formats (OGG Opus, MP3, WAV) with automatic
 * conversion to Whisper-compatible format via {@link VoiceDecoder}.
 *
 * <p>
 * Features:
 * <ul>
 * <li>Automatic audio preprocessing (16kHz mono conversion)</li>
 * <li>Language detection in transcription results</li>
 * <li>Configurable model (default: whisper-1)</li>
 * <li>Verbose JSON response parsing for metadata</li>
 * </ul>
 *
 * <p>
 * Always available as a Spring bean, but operations check
 * {@code bot.voice.enabled} before executing.
 *
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WhisperSttAdapter implements VoicePort {

    private final OkHttpClient okHttpClient;
    private final BotProperties properties;
    private final VoiceDecoder voiceDecoder;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private static final String WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions";

    @Override
    public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, AudioFormat format) {
        return CompletableFuture.supplyAsync(() -> {
            if (!properties.getVoice().isEnabled()) {
                throw new RuntimeException("Voice processing is disabled");
            }
            try {
                // Convert to format Whisper expects (16kHz mono PCM wrapped in WAV/MP3)
                byte[] processedAudio = prepareAudioForWhisper(audioData, format);

                String apiKey = getOpenAiApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    throw new RuntimeException("OpenAI API key not configured");
                }

                RequestBody fileBody = RequestBody.create(processedAudio, MediaType.parse("audio/mpeg"));

                MultipartBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "audio.mp3", fileBody)
                        .addFormDataPart("model", properties.getVoice().getStt().getModel())
                        .addFormDataPart("response_format", "verbose_json")
                        .build();

                Request request = new Request.Builder()
                        .url(WHISPER_API_URL)
                        .header("Authorization", "Bearer " + apiKey)
                        .post(requestBody)
                        .build();

                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String error = response.body() != null ? response.body().string() : "Unknown error";
                        throw new RuntimeException("Whisper API error: " + error);
                    }

                    String responseBody = response.body().string();
                    WhisperResponse whisperResponse = objectMapper.readValue(responseBody, WhisperResponse.class);

                    return new TranscriptionResult(
                            whisperResponse.getText(),
                            whisperResponse.getLanguage(),
                            1.0f,
                            Duration.ofMillis((long) (whisperResponse.getDuration() * 1000)),
                            Collections.emptyList());
                }
            } catch (Exception e) {
                log.error("Whisper transcription failed", e);
                throw new RuntimeException("Transcription failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, VoiceConfig config) {
        // TTS not implemented in this adapter - use ElevenLabs or another TTS provider
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Use TTS provider"));
    }

    @Override
    public CompletableFuture<byte[]> convert(byte[] audioData, AudioFormat from, AudioFormat to) {
        return CompletableFuture.supplyAsync(() -> {
            // Use JaffreeVoiceProcessor for conversion
            if (to == AudioFormat.PCM_16K) {
                return voiceDecoder.decodeToMono16k(audioData, from);
            }
            throw new UnsupportedOperationException("Conversion to " + to + " not supported");
        });
    }

    @Override
    public boolean isAvailable() {
        String apiKey = getOpenAiApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    private String getOpenAiApiKey() {
        var config = properties.getLlm().getLangchain4j().getProviders().get("openai");
        return config != null ? config.getApiKey() : null;
    }

    private byte[] prepareAudioForWhisper(byte[] audioData, AudioFormat format) {
        // For OGG Opus (Telegram), we can send directly - Whisper supports it
        // For other formats, convert to PCM first
        if (format == AudioFormat.OGG_OPUS) {
            return audioData;
        }
        return voiceDecoder.decodeToMono16k(audioData, format);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class WhisperResponse {
        private String text;
        private String language;
        private double duration;
        @JsonProperty("segments")
        private java.util.List<Object> segments;
    }
}

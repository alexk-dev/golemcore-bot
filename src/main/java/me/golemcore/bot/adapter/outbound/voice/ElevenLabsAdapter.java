package me.golemcore.bot.adapter.outbound.voice;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * ElevenLabs adapter for both STT and TTS via ElevenLabs API.
 *
 * <p>
 * STT uses the speech-to-text endpoint with the Scribe model. TTS uses the
 * text-to-speech endpoint, returning MP3 audio bytes. ElevenLabs accepts OGG
 * natively, so no FFmpeg conversion is needed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ElevenLabsAdapter implements VoicePort {

    private static final String STT_URL = "https://api.elevenlabs.io/v1/speech-to-text";
    private static final String TTS_URL_TEMPLATE = "https://api.elevenlabs.io/v1/text-to-speech/%s";

    private final OkHttpClient okHttpClient;
    private final BotProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, AudioFormat format) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BotProperties.VoiceProperties voice = properties.getVoice();
                String apiKey = voice.getApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    log.warn("[ElevenLabs] STT request rejected: API key not configured");
                    throw new RuntimeException("ElevenLabs API key not configured");
                }

                String mimeType = format != null ? format.getMimeType() : "audio/ogg";
                String extension = format != null ? format.getExtension() : "ogg";

                log.info("[ElevenLabs] STT request: {} bytes, format={}, model={}",
                        audioData.length, mimeType, voice.getSttModelId());

                RequestBody fileBody = RequestBody.create(audioData, MediaType.parse(mimeType));

                MultipartBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "audio." + extension, fileBody)
                        .addFormDataPart("model_id", voice.getSttModelId())
                        .build();

                Request request = new Request.Builder()
                        .url(STT_URL)
                        .header("xi-api-key", apiKey)
                        .post(requestBody)
                        .build();

                long startTime = System.currentTimeMillis();
                try (Response response = okHttpClient.newCall(request).execute()) {
                    long elapsed = System.currentTimeMillis() - startTime;

                    if (!response.isSuccessful()) {
                        String error = response.body() != null ? response.body().string() : "Unknown error";
                        log.warn("[ElevenLabs] STT failed: HTTP {} in {}ms — {}",
                                response.code(), elapsed, error);
                        throw new RuntimeException("ElevenLabs STT error (" + response.code() + "): " + error);
                    }

                    String responseBody = response.body().string();
                    SttResponse sttResponse = objectMapper.readValue(responseBody, SttResponse.class);

                    String language = sttResponse.getLanguageCode() != null
                            ? sttResponse.getLanguageCode()
                            : "unknown";
                    int textLength = sttResponse.getText() != null ? sttResponse.getText().length() : 0;
                    log.info("[ElevenLabs] STT success: {} chars, language={}, {}ms",
                            textLength, language, elapsed);

                    return new TranscriptionResult(
                            sttResponse.getText(),
                            language,
                            1.0f,
                            Duration.ZERO,
                            Collections.emptyList());
                }
            } catch (IOException e) {
                log.error("[ElevenLabs] STT network error: {}", e.getMessage(), e);
                throw new RuntimeException("Transcription failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, VoiceConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BotProperties.VoiceProperties voice = properties.getVoice();
                String apiKey = voice.getApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    log.warn("[ElevenLabs] TTS request rejected: API key not configured");
                    throw new RuntimeException("ElevenLabs API key not configured");
                }

                String voiceId = config.voiceId() != null ? config.voiceId() : voice.getVoiceId();
                String modelId = config.modelId() != null ? config.modelId() : voice.getTtsModelId();
                float speed = config.speed() > 0 ? config.speed() : voice.getSpeed();

                log.info("[ElevenLabs] TTS request: {} chars, voice={}, model={}, speed={}",
                        text.length(), voiceId, modelId, speed);

                String url = String.format(TTS_URL_TEMPLATE, voiceId) + "?output_format=mp3_44100_128";

                String jsonBody = objectMapper.writeValueAsString(new TtsRequest(text, modelId, speed));

                Request request = new Request.Builder()
                        .url(url)
                        .header("xi-api-key", apiKey)
                        .header("Accept", "audio/mpeg")
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                        .build();

                long startTime = System.currentTimeMillis();
                try (Response response = okHttpClient.newCall(request).execute()) {
                    long elapsed = System.currentTimeMillis() - startTime;

                    if (!response.isSuccessful()) {
                        String error = response.body() != null ? response.body().string() : "Unknown error";
                        log.warn("[ElevenLabs] TTS failed: HTTP {} in {}ms — {}",
                                response.code(), elapsed, error);
                        throw new RuntimeException("ElevenLabs TTS error (" + response.code() + "): " + error);
                    }

                    byte[] audioBytes = response.body().bytes();
                    log.info("[ElevenLabs] TTS success: {} chars → {} bytes MP3, {}ms",
                            text.length(), audioBytes.length, elapsed);
                    return audioBytes;
                }
            } catch (IOException e) {
                log.error("[ElevenLabs] TTS network error: {}", e.getMessage(), e);
                throw new RuntimeException("Synthesis failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public boolean isAvailable() {
        String apiKey = properties.getVoice().getApiKey();
        return properties.getVoice().isEnabled() && apiKey != null && !apiKey.isBlank();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SttResponse {
        private String text;
        @JsonProperty("language_code")
        private String languageCode;
    }

    record TtsRequest(
            String text,
            @JsonProperty("model_id") String modelId,
            float speed) {
    }
}

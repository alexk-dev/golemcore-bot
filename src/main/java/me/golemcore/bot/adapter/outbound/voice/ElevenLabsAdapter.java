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
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private static final String DEFAULT_STT_URL = "https://api.elevenlabs.io/v1/speech-to-text";
    private static final String DEFAULT_TTS_URL_TEMPLATE = "https://api.elevenlabs.io/v1/text-to-speech/%s";

    private static final ExecutorService VOICE_EXECUTOR = Executors.newFixedThreadPool(2,
            r -> {
                Thread t = new Thread(r, "elevenlabs-voice");
                t.setDaemon(true);
                return t;
            });

    private final OkHttpClient okHttpClient;
    private final BotProperties properties;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        BotProperties.VoiceProperties voice = properties.getVoice();
        boolean hasApiKey = voice.getApiKey() != null && !voice.getApiKey().isBlank();
        if (voice.isEnabled() && !hasApiKey) {
            log.warn("[ElevenLabs] Voice is ENABLED but API key is NOT configured — "
                    + "STT/TTS will not work. Set ELEVENLABS_API_KEY env var.");
        }
        log.info("[ElevenLabs] Adapter initialized: enabled={}, apiKeyConfigured={}, voiceId={}, "
                + "sttModel={}, ttsModel={}",
                voice.isEnabled(), hasApiKey, voice.getVoiceId(),
                voice.getSttModelId(), voice.getTtsModelId());
    }

    @Override
    public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, AudioFormat format) {
        return CompletableFuture.supplyAsync(() -> doTranscribe(audioData, format), VOICE_EXECUTOR);
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, VoiceConfig config) {
        return CompletableFuture.supplyAsync(() -> doSynthesize(text, config), VOICE_EXECUTOR);
    }

    @SuppressWarnings("PMD.CloseResource") // ResponseBody is closed when Response is closed in try-with-resources
    private TranscriptionResult doTranscribe(byte[] audioData, AudioFormat format) {
        try {
            BotProperties.VoiceProperties voice = properties.getVoice();
            String apiKey = requireApiKey(voice);

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
                    .url(getSttUrl())
                    .header("xi-api-key", apiKey)
                    .post(requestBody)
                    .build();

            long startTime = System.currentTimeMillis();
            int maxRetries = 3;
            int attempt = 0;

            while (attempt < maxRetries) {
                try (Response response = okHttpClient.newCall(request).execute()) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    ResponseBody body = response.body();

                    if (!response.isSuccessful()) {
                        if (isRetryableError(response.code()) && attempt < maxRetries - 1) {
                            attempt++;
                            long backoffMs = (long) Math.pow(2, attempt) * 1000;
                            log.info("[ElevenLabs] STT retrying after {} (attempt {}/{}), backoff={}ms",
                                    response.code(), attempt, maxRetries, backoffMs);
                            Thread.sleep(backoffMs);
                            continue;
                        }
                        handleErrorResponse(response, body, elapsed, "STT");
                    }

                    if (body == null) {
                        throw new IllegalStateException("ElevenLabs STT returned empty body");
                    }
                    return parseSttResponse(body.string(), elapsed);
                }
            }
            throw new IllegalStateException("ElevenLabs STT failed after " + maxRetries + " attempts");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ElevenLabs STT interrupted", e);
        } catch (IOException e) {
            log.error("[ElevenLabs] STT network error: {}", e.getMessage(), e);
            throw new UncheckedIOException("Transcription failed: " + e.getMessage(), e);
        }
    }

    private TranscriptionResult parseSttResponse(String responseBody, long elapsed) throws IOException {
        SttResponse sttResponse = objectMapper.readValue(responseBody, SttResponse.class);

        String language = sttResponse.getLanguageCode() != null
                ? sttResponse.getLanguageCode()
                : "unknown";
        String text = sttResponse.getText();
        int textLength = text != null ? text.length() : 0;
        String preview = text != null && text.length() > 200
                ? text.substring(0, 200) + "..."
                : text;
        log.info("[ElevenLabs] STT success: \"{}\" ({} chars, language={}, {}ms)",
                preview, textLength, language, elapsed);

        return new TranscriptionResult(
                sttResponse.getText(),
                language,
                1.0f,
                Duration.ZERO,
                Collections.emptyList());
    }

    @SuppressWarnings("PMD.CloseResource") // ResponseBody is closed when Response is closed in try-with-resources
    private byte[] doSynthesize(String text, VoiceConfig config) {
        try {
            BotProperties.VoiceProperties voice = properties.getVoice();
            String apiKey = requireApiKey(voice);

            String voiceId = config.voiceId() != null ? config.voiceId() : voice.getVoiceId();
            String modelId = config.modelId() != null ? config.modelId() : voice.getTtsModelId();
            float speed = config.speed() > 0 ? config.speed() : voice.getSpeed();

            log.info("[ElevenLabs] TTS request: {} chars, voice={}, model={}, speed={}",
                    text.length(), voiceId, modelId, speed);

            String url = getTtsUrl(voiceId) + "?output_format=mp3_44100_128";

            String jsonBody = objectMapper.writeValueAsString(new TtsRequest(text, modelId, speed));

            Request request = new Request.Builder()
                    .url(url)
                    .header("xi-api-key", apiKey)
                    .header("Accept", "audio/mpeg")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            long startTime = System.currentTimeMillis();
            int maxRetries = 3;
            int attempt = 0;

            while (attempt < maxRetries) {
                try (Response response = okHttpClient.newCall(request).execute()) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    ResponseBody body = response.body();

                    if (!response.isSuccessful()) {
                        if (isRetryableError(response.code()) && attempt < maxRetries - 1) {
                            attempt++;
                            long backoffMs = (long) Math.pow(2, attempt) * 1000;
                            log.info("[ElevenLabs] TTS retrying after {} (attempt {}/{}), backoff={}ms",
                                    response.code(), attempt, maxRetries, backoffMs);
                            Thread.sleep(backoffMs);
                            continue;
                        }
                        handleErrorResponse(response, body, elapsed, "TTS");
                    }

                    if (body == null) {
                        throw new IllegalStateException("ElevenLabs TTS returned empty body");
                    }
                    byte[] audioBytes = body.bytes();
                    log.info("[ElevenLabs] TTS success: {} chars → {} bytes MP3, {}ms",
                            text.length(), audioBytes.length, elapsed);
                    return audioBytes;
                }
            }
            throw new IllegalStateException("ElevenLabs TTS failed after " + maxRetries + " attempts");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ElevenLabs TTS interrupted", e);
        } catch (IOException e) {
            log.error("[ElevenLabs] TTS network error: {}", e.getMessage(), e);
            throw new UncheckedIOException("Synthesis failed: " + e.getMessage(), e);
        }
    }

    private String requireApiKey(BotProperties.VoiceProperties voice) {
        String apiKey = voice.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[ElevenLabs] Request rejected: API key not configured");
            throw new IllegalStateException("ElevenLabs API key not configured");
        }
        return apiKey;
    }

    @Override
    public boolean isAvailable() {
        BotProperties.VoiceProperties voice = properties.getVoice();
        String apiKey = voice.getApiKey();
        return voice.isEnabled() && apiKey != null && !apiKey.isBlank();
    }

    protected String getSttUrl() {
        return DEFAULT_STT_URL;
    }

    protected String getTtsUrl(String voiceId) {
        return String.format(DEFAULT_TTS_URL_TEMPLATE, voiceId);
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

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ErrorResponse {
        private ErrorDetail detail;
        private String message; // Sometimes error is at root level
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ErrorDetail {
        private String status;
        private String message;
    }

    /**
     * Exception for quota/payment issues that should be shown to the user.
     */
    public static class QuotaExceededException extends IllegalStateException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }

    private ErrorResponse parseErrorResponse(String errorBody) {
        try {
            return objectMapper.readValue(errorBody, ErrorResponse.class);
        } catch (IOException e) {
            log.debug("[ElevenLabs] Could not parse error response: {}", errorBody);
            ErrorResponse fallback = new ErrorResponse();
            fallback.setMessage(errorBody);
            return fallback;
        }
    }

    private String extractErrorMessage(ErrorResponse errorResponse) {
        if (errorResponse.getDetail() != null && errorResponse.getDetail().getMessage() != null) {
            return errorResponse.getDetail().getMessage();
        }
        if (errorResponse.getMessage() != null) {
            return errorResponse.getMessage();
        }
        return "Unknown error";
    }

    private String getErrorContext(int code, String status) {
        return switch (code) {
        case 400 -> "Bad request. Check text length limits (max 10,000-40,000 chars depending on model)";
        case 401 -> "Authentication failed. Check your ElevenLabs API key";
        case 402 -> "⚠️ Quota exceeded. Please enable usage-based billing or upgrade your plan";
        case 422 -> "Invalid request format. Check parameters";
        case 429 -> "Rate limit exceeded. Please retry in a few seconds";
        case 500, 503 -> "ElevenLabs service temporarily unavailable. Will retry automatically";
        case 504 -> "Request timeout. Try breaking text into smaller chunks";
        default -> status != null ? "Error: " + status : "Service error";
        };
    }

    private boolean isRetryableError(int code) {
        return code == 429 || code == 500 || code == 503 || code == 504;
    }

    private void handleErrorResponse(Response response, ResponseBody body, long elapsed, String operation)
            throws IOException {
        int code = response.code();
        String errorBody = body != null ? body.string() : "";
        ErrorResponse errorResponse = parseErrorResponse(errorBody);
        String errorMessage = extractErrorMessage(errorResponse);
        String errorStatus = errorResponse.getDetail() != null
                ? errorResponse.getDetail().getStatus()
                : null;
        String context = getErrorContext(code, errorStatus);

        log.warn("[ElevenLabs] {} failed: HTTP {} in {}ms — {} ({})",
                operation, code, elapsed, errorMessage, context);

        // Special handling for quota exceeded - throw custom exception
        if (code == 402) {
            throw new QuotaExceededException(
                    String.format("ElevenLabs quota exceeded: %s. %s", errorMessage, context));
        }

        throw new IllegalStateException(
                String.format("ElevenLabs %s error (HTTP %d): %s. %s",
                        operation, code, errorMessage, context));
    }
}

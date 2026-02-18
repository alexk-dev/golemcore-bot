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
import me.golemcore.bot.domain.service.RuntimeConfigService;
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
import java.util.function.Function;

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
    private static final int HTTP_PAYMENT_REQUIRED = 402;

    private static final ExecutorService VOICE_EXECUTOR = Executors.newFixedThreadPool(2,
            r -> {
                Thread t = new Thread(r, "elevenlabs-voice");
                t.setDaemon(true);
                return t;
            });

    private final OkHttpClient okHttpClient;
    private final BotProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        boolean voiceEnabled = runtimeConfigService.isVoiceEnabled();
        String apiKey = runtimeConfigService.getVoiceApiKey();
        boolean hasApiKey = apiKey != null && !apiKey.isBlank();
        if (voiceEnabled && !hasApiKey) {
            log.warn("[ElevenLabs] Voice is ENABLED but API key is NOT configured — "
                    + "STT/TTS will not work. Set ELEVENLABS_API_KEY env var.");
        }
        log.info("[ElevenLabs] Adapter initialized: enabled={}, apiKeyConfigured={}, voiceId={}, "
                + "sttModel={}, ttsModel={}",
                voiceEnabled, hasApiKey, runtimeConfigService.getVoiceId(),
                runtimeConfigService.getSttModelId(), runtimeConfigService.getTtsModelId());
    }

    @Override
    public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, AudioFormat format) {
        return CompletableFuture.supplyAsync(() -> doTranscribe(audioData, format), VOICE_EXECUTOR);
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, VoiceConfig config) {
        return CompletableFuture.supplyAsync(() -> doSynthesize(text, config), VOICE_EXECUTOR);
    }

    /**
     * Executes HTTP request with retry logic for transient errors.
     *
     * @param request
     *            the HTTP request to execute
     * @param operationName
     *            operation name for logging (e.g., "STT", "TTS")
     * @param processor
     *            function to process successful response
     * @param <T>
     *            return type
     * @return processed result
     * @throws IOException
     *             on network errors
     * @throws InterruptedException
     *             if interrupted during retry backoff
     */
    @SuppressWarnings("PMD.CloseResource") // ResponseBody is closed when Response is closed in try-with-resources
    private <T> T executeWithRetry(Request request, String operationName, Function<Response, T> processor)
            throws IOException, InterruptedException {
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
                        log.info("[ElevenLabs] {} retrying after {} (attempt {}/{}), backoff={}ms",
                                operationName, response.code(), attempt, maxRetries, backoffMs);
                        sleepBeforeRetry(backoffMs);
                        continue;
                    }
                    handleErrorResponse(response, body, elapsed, operationName);
                }

                if (body == null) {
                    throw new IllegalStateException(
                            "ElevenLabs " + operationName + " returned empty body");
                }
                return processor.apply(response);
            }
        }
        throw new IllegalStateException(
                "ElevenLabs " + operationName + " failed after " + maxRetries + " attempts");
    }

    @SuppressWarnings("PMD.CloseResource") // ResponseBody is closed when Response is closed in try-with-resources
    private TranscriptionResult doTranscribe(byte[] audioData, AudioFormat format) {
        try {
            String apiKey = requireApiKey();
            String sttModelId = runtimeConfigService.getSttModelId();

            String mimeType = format != null ? format.getMimeType() : "audio/ogg";
            String extension = format != null ? format.getExtension() : "ogg";

            log.info("[ElevenLabs] STT request: {} bytes, format={}, model={}",
                    audioData.length, mimeType, sttModelId);

            RequestBody fileBody = RequestBody.create(audioData, MediaType.parse(mimeType));

            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio." + extension, fileBody)
                    .addFormDataPart("model_id", sttModelId)
                    .build();

            Request request = new Request.Builder()
                    .url(getSttUrl())
                    .header("xi-api-key", apiKey)
                    .post(requestBody)
                    .build();

            long startTime = System.currentTimeMillis();
            return executeWithRetry(request, "STT", response -> {
                try {
                    long elapsed = System.currentTimeMillis() - startTime;
                    return parseSttResponse(response.body().string(), elapsed);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ElevenLabs STT interrupted", e);
        } catch (UncheckedIOException e) {
            log.error("[ElevenLabs] STT network error: {}", e.getMessage(), e);
            throw new UncheckedIOException("Transcription failed: " + e.getMessage(), e.getCause());
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
            String apiKey = requireApiKey();

            String voiceId = config.voiceId() != null ? config.voiceId() : runtimeConfigService.getVoiceId();
            String modelId = config.modelId() != null ? config.modelId() : runtimeConfigService.getTtsModelId();
            float speed = config.speed() > 0 ? config.speed() : runtimeConfigService.getVoiceSpeed();

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
            return executeWithRetry(request, "TTS", response -> {
                try {
                    byte[] audioBytes = response.body().bytes();
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[ElevenLabs] TTS success: {} chars → {} bytes MP3, {}ms",
                            text.length(), audioBytes.length, elapsed);
                    return audioBytes;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ElevenLabs TTS interrupted", e);
        } catch (UncheckedIOException e) {
            log.error("[ElevenLabs] TTS network error: {}", e.getMessage(), e);
            throw new UncheckedIOException("Synthesis failed: " + e.getMessage(), e.getCause());
        } catch (IOException e) {
            log.error("[ElevenLabs] TTS network error: {}", e.getMessage(), e);
            throw new UncheckedIOException("Synthesis failed: " + e.getMessage(), e);
        }
    }

    private String requireApiKey() {
        String apiKey = runtimeConfigService.getVoiceApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[ElevenLabs] Request rejected: API key not configured");
            throw new IllegalStateException("ElevenLabs API key not configured");
        }
        return apiKey;
    }

    @Override
    public boolean isAvailable() {
        String apiKey = runtimeConfigService.getVoiceApiKey();
        return runtimeConfigService.isVoiceEnabled() && apiKey != null && !apiKey.isBlank();
    }

    protected String getSttUrl() {
        return DEFAULT_STT_URL;
    }

    protected String getTtsUrl(String voiceId) {
        return String.format(DEFAULT_TTS_URL_TEMPLATE, voiceId);
    }

    protected void sleepBeforeRetry(long backoffMs) throws InterruptedException {
        Thread.sleep(backoffMs);
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

    private boolean isQuotaExceededMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("exceeds your quota") || lower.contains("quota exceeded")
                || lower.contains("credits remaining");
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
        // ElevenLabs may return 402 or 401 with "exceeds your quota" for quota issues
        if (code == HTTP_PAYMENT_REQUIRED || isQuotaExceededMessage(errorMessage)) {
            throw new VoicePort.QuotaExceededException(
                    String.format("ElevenLabs quota exceeded: %s. %s", errorMessage, context));
        }

        throw new IllegalStateException(
                String.format("ElevenLabs %s error (HTTP %d): %s. %s",
                        operation, code, errorMessage, context));
    }
}

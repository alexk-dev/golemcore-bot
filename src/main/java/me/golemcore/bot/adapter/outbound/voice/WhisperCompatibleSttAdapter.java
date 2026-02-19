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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.VoicePort;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collections;

/**
 * Adapter for any Whisper-compatible STT server (faster-whisper, Parakeet,
 * Whisper.cpp, OpenAI). All use the same API:
 * {@code POST /v1/audio/transcriptions}.
 *
 * <p>
 * This is an adapter-layer component, not a {@link VoicePort} implementation.
 * Routing from the domain is handled by {@link ElevenLabsAdapter}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WhisperCompatibleSttAdapter {

    private static final String TRANSCRIPTION_PATH = "/v1/audio/transcriptions";
    private static final int MAX_RETRIES = 3;

    private final OkHttpClient okHttpClient;
    private final RuntimeConfigService runtimeConfigService;
    private final ObjectMapper objectMapper;

    /**
     * Transcribe audio using a Whisper-compatible endpoint.
     *
     * @param audioData
     *            raw audio bytes
     * @param format
     *            audio format (nullable, defaults to OGG)
     * @return transcription result
     */
    public VoicePort.TranscriptionResult transcribe(byte[] audioData, AudioFormat format) {
        String baseUrl = runtimeConfigService.getWhisperSttUrl();
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("Whisper STT URL is not configured");
        }

        String mimeType = format != null ? format.getMimeType() : "audio/ogg";
        String extension = format != null ? format.getExtension() : "ogg";

        log.info("[WhisperSTT] STT request: {} bytes, format={}, url={}",
                audioData.length, mimeType, baseUrl);

        RequestBody fileBody = RequestBody.create(audioData, MediaType.parse(mimeType));

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio." + extension, fileBody)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("response_format", "verbose_json")
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(getTranscriptionUrl(baseUrl))
                .post(requestBody);

        String apiKey = runtimeConfigService.getWhisperSttApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        Request request = requestBuilder.build();

        try {
            long startTime = System.currentTimeMillis();
            return executeWithRetry(request, startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Whisper STT interrupted", e);
        } catch (UncheckedIOException e) {
            log.error("[WhisperSTT] STT network error: {}", e.getMessage(), e);
            throw new UncheckedIOException("Whisper transcription failed: " + e.getMessage(), e.getCause());
        } catch (IOException e) {
            log.error("[WhisperSTT] STT network error: {}", e.getMessage(), e);
            throw new UncheckedIOException("Whisper transcription failed: " + e.getMessage(), e);
        }
    }

    /**
     * Health check against the Whisper-compatible server.
     *
     * @return true if the server responds with 2xx on GET /health
     */
    public boolean isHealthy() {
        String baseUrl = runtimeConfigService.getWhisperSttUrl();
        if (baseUrl.isBlank()) {
            return false;
        }
        try {
            Request request = new Request.Builder()
                    .url(normalizeBaseUrl(baseUrl) + "/health")
                    .get()
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) { // NOSONAR - best-effort health check
            log.debug("[WhisperSTT] Health check failed: {}", e.getMessage());
            return false;
        }
    }

    protected String getTranscriptionUrl(String baseUrl) {
        return normalizeBaseUrl(baseUrl) + TRANSCRIPTION_PATH;
    }

    protected void sleepBeforeRetry(long backoffMs) throws InterruptedException {
        Thread.sleep(backoffMs);
    }

    @SuppressWarnings("PMD.CloseResource") // ResponseBody is closed when Response is closed in try-with-resources
    private VoicePort.TranscriptionResult executeWithRetry(Request request, long startTime)
            throws IOException, InterruptedException {
        int attempt = 0;

        while (attempt < MAX_RETRIES) {
            try (Response response = okHttpClient.newCall(request).execute()) {
                ResponseBody body = response.body();

                if (!response.isSuccessful()) {
                    if (isRetryableError(response.code()) && attempt < MAX_RETRIES - 1) {
                        attempt++;
                        long backoffMs = (long) Math.pow(2, attempt) * 1000;
                        log.info("[WhisperSTT] Retrying after {} (attempt {}/{}), backoff={}ms",
                                response.code(), attempt, MAX_RETRIES, backoffMs);
                        sleepBeforeRetry(backoffMs);
                        continue;
                    }
                    String errorBody = body != null ? body.string() : "";
                    throw new IllegalStateException(
                            String.format("Whisper STT error (HTTP %d): %s", response.code(), errorBody));
                }

                if (body == null) {
                    throw new IllegalStateException("Whisper STT returned empty body");
                }

                String responseBody = body.string();
                long elapsed = System.currentTimeMillis() - startTime;
                return parseResponse(responseBody, elapsed);
            }
        }
        throw new IllegalStateException("Whisper STT failed after " + MAX_RETRIES + " attempts");
    }

    private VoicePort.TranscriptionResult parseResponse(String responseBody, long elapsed)
            throws IOException {
        WhisperResponse whisperResponse = objectMapper.readValue(responseBody, WhisperResponse.class);

        String text = whisperResponse.getText();
        String language = whisperResponse.getLanguage() != null
                ? whisperResponse.getLanguage()
                : "unknown";

        int textLength = text != null ? text.length() : 0;
        String preview = text != null && text.length() > 200
                ? text.substring(0, 200) + "..."
                : text;
        log.info("[WhisperSTT] STT success: \"{}\" ({} chars, language={}, {}ms)",
                preview, textLength, language, elapsed);

        return new VoicePort.TranscriptionResult(
                text,
                language,
                1.0f,
                Duration.ZERO,
                Collections.emptyList());
    }

    private boolean isRetryableError(int code) {
        return code == 429 || code == 500 || code == 503 || code == 504;
    }

    private String normalizeBaseUrl(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class WhisperResponse {
        private String text;
        private String language;
    }
}

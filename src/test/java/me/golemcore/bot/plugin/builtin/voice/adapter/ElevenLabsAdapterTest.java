package me.golemcore.bot.plugin.builtin.voice.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.builtin.whisper.adapter.WhisperCompatibleSttAdapter;
import me.golemcore.bot.port.outbound.VoicePort;
import me.golemcore.bot.testsupport.http.OkHttpMockEngine;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElevenLabsAdapterTest {

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String AUDIO_MPEG = "audio/mpeg";
    private static final String APPLICATION_JSON = "application/json";
    private static final String OPERATION_SYNTHESIZE = "synthesize";
    private static final String XI_API_KEY = "xi-api-key";
    private static final String TTS_TEXT = "Test";
    private static final String STT_TEXT = "Hello";
    private static final String BASE_URL = "http://mock.elevenlabs.local/";

    private OkHttpMockEngine httpEngine;
    private ElevenLabsAdapter adapter;
    private BotProperties properties;
    private RuntimeConfigService runtimeConfigService;
    private WhisperCompatibleSttAdapter whisperSttAdapter;

    @BeforeEach
    void setUp() {
        httpEngine = new OkHttpMockEngine();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .addInterceptor(httpEngine)
                .build();

        properties = new BotProperties();

        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(true);
        when(runtimeConfigService.getVoiceApiKey()).thenReturn("test-api-key");
        when(runtimeConfigService.getVoiceId()).thenReturn("test-voice-id");
        when(runtimeConfigService.getTtsModelId()).thenReturn("eleven_multilingual_v2");
        when(runtimeConfigService.getSttModelId()).thenReturn("scribe_v1");
        when(runtimeConfigService.getTtsProvider()).thenReturn("elevenlabs");
        when(runtimeConfigService.getVoiceSpeed()).thenReturn(1.0f);
        when(runtimeConfigService.isWhisperSttConfigured()).thenReturn(false);

        whisperSttAdapter = mock(WhisperCompatibleSttAdapter.class);
        adapter = new ElevenLabsAdapter(client, properties, runtimeConfigService, new ObjectMapper(),
                whisperSttAdapter) {
            @Override
            protected String getSttUrl() {
                return BASE_URL + "v1/speech-to-text";
            }

            @Override
            protected String getTtsUrl(String voiceId) {
                return BASE_URL + "v1/text-to-speech/" + voiceId;
            }

            @Override
            protected void sleepBeforeRetry(long backoffMs) {
                // No-op in tests to avoid real backoff delays.
            }
        };
    }

    // Helper methods to reduce duplication
    private void enqueueErrorResponse(int code, String json) {
        httpEngine.enqueueJson(code, json);
    }

    private void enqueueErrorResponseMultiple(int code, String json, int times) {
        for (int i = 0; i < times; i++) {
            enqueueErrorResponse(code, json);
        }
    }

    private void assertTranscribeThrows(int expectedCode) {
        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(15, TimeUnit.SECONDS));
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        assertTrue(message != null && message.contains(String.valueOf(expectedCode)));
    }

    private void assertSynthesizeThrows(int expectedCode) {
        CompletableFuture<byte[]> future = adapter.synthesize(TTS_TEXT, VoicePort.VoiceConfig.defaultConfig());
        Exception ex = assertThrows(Exception.class, () -> future.get(15, TimeUnit.SECONDS));
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        assertTrue(message != null && message.contains(String.valueOf(expectedCode)));
    }

    private void assertTranscribeThrowsAny() {
        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    private void assertSynthesizeThrowsAny() {
        CompletableFuture<byte[]> future = adapter.synthesize(TTS_TEXT, VoicePort.VoiceConfig.defaultConfig());
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void transcribeSuccess() throws Exception {
        httpEngine.enqueueJson(200, "{\"text\":\"Hello world\",\"language_code\":\"en\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1, 2, 3 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals("Hello world", result.text());
        assertEquals("en", result.language());

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertEquals("test-api-key", request.headers().get(XI_API_KEY));
        assertTrue(request.headers().get(CONTENT_TYPE).contains("multipart/form-data"));
    }

    @Test
    void transcribeApiError() {
        httpEngine.enqueueJson(401, "Unauthorized");

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Transcription failed") || ex.getCause().getMessage().contains("401"));
    }

    @Test
    void transcribeApiErrorWithoutBody() {
        enqueueErrorResponseMultiple(500, "", 3);
        assertTranscribeThrowsAny();
    }

    @Test
    void synthesizeSuccess() throws Exception {
        byte[] mp3Bytes = new byte[] { 0x49, 0x44, 0x33, 1, 2, 3 }; // fake MP3
        httpEngine.enqueueBytes(200, mp3Bytes, AUDIO_MPEG);

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(
                "custom-voice", "eleven_multilingual_v2", 1.0f, AudioFormat.MP3);

        byte[] result = adapter.synthesize(STT_TEXT, config).get(5, TimeUnit.SECONDS);
        assertArrayEquals(mp3Bytes, result);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertEquals("test-api-key", request.headers().get(XI_API_KEY));
        assertEquals(AUDIO_MPEG, request.headers().get("Accept"));
        assertTrue(request.target().contains("custom-voice"));
        assertTrue(request.target().contains("output_format=mp3_44100_128"));
    }

    @Test
    void synthesizeUsesDefaultVoiceId() throws Exception {
        httpEngine.enqueueBytes(200, new byte[] { 1 }, AUDIO_MPEG);

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(null, null, 0f, AudioFormat.MP3);

        adapter.synthesize(TTS_TEXT, config).get(5, TimeUnit.SECONDS);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertTrue(request.target().contains("test-voice-id"));
    }

    @Test
    void synthesizeApiError() {
        enqueueErrorResponseMultiple(500, "Server error", 3);
        assertSynthesizeThrowsAny();
    }

    @Test
    void synthesizeApiErrorWithoutBody() {
        enqueueErrorResponseMultiple(503, "", 3);
        assertSynthesizeThrowsAny();
    }

    @Test
    void synthesizeNetworkError() throws IOException {
        httpEngine.enqueueFailure(new IOException("Simulated network failure"));

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize(TTS_TEXT, config);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Synthesis failed") || ex.getCause() instanceof IOException);
    }

    @Test
    void isAvailableWithApiKey() {
        assertTrue(adapter.isAvailable());
    }

    @Test
    void isNotAvailableWithoutApiKey() {
        when(runtimeConfigService.getVoiceApiKey()).thenReturn("");
        assertFalse(adapter.isAvailable());
    }

    @Test
    void isNotAvailableWhenDisabled() {
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        assertFalse(adapter.isAvailable());
    }

    @Test
    void isNotAvailableWithNullApiKey() {
        when(runtimeConfigService.getVoiceApiKey()).thenReturn(null);
        assertFalse(adapter.isAvailable());
    }

    @Test
    void transcribeFailsWithoutApiKey() {
        when(runtimeConfigService.getVoiceApiKey()).thenReturn("");
        assertTranscribeThrowsAny();
    }

    @Test
    void synthesizeFailsWithoutApiKey() {
        when(runtimeConfigService.getVoiceApiKey()).thenReturn("");
        CompletableFuture<byte[]> future = adapter.synthesize(STT_TEXT, VoicePort.VoiceConfig.defaultConfig());
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    // ===== Edge cases =====

    @Test
    void transcribeEmptyResponseText() throws Exception {
        httpEngine.enqueueJson(200, "{\"text\":\"\",\"language_code\":\"en\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals("", result.text());
        assertEquals("en", result.language());
    }

    @Test
    void transcribeNullLanguageCode() throws Exception {
        httpEngine.enqueueJson(200, "{\"text\":\"Hello\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals(STT_TEXT, result.text());
        assertEquals("unknown", result.language());
    }

    @Test
    void transcribeMalformedJson() {
        httpEngine.enqueueText(200, "not valid json at all", APPLICATION_JSON);
        assertTranscribeThrowsAny();
    }

    @Test
    void transcribeNullFormat() throws Exception {
        httpEngine.enqueueJson(200, "{\"text\":\"Hello\",\"language_code\":\"en\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, null).get(5, TimeUnit.SECONDS);

        assertEquals(STT_TEXT, result.text());

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        // Should use "audio/ogg" fallback
        String body = request.body();
        assertTrue(body.contains("audio.ogg"));
    }

    @Test
    void synthesizeEmptyAudioResponse() throws Exception {
        httpEngine.enqueueBytes(200, new byte[0], AUDIO_MPEG);

        byte[] result = adapter.synthesize(TTS_TEXT,
                VoicePort.VoiceConfig.defaultConfig()).get(5, TimeUnit.SECONDS);

        assertEquals(0, result.length);
    }

    @Test
    void synthesizeRateLimited() {
        enqueueErrorResponseMultiple(429, "{\"detail\":\"Rate limit exceeded\"}", 3);
        assertSynthesizeThrowsAny();
    }

    @Test
    void synthesizeCustomSpeed() throws Exception {
        httpEngine.enqueueBytes(200, new byte[] { 1 }, AUDIO_MPEG);

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(
                "voice1", "model1", 1.5f, AudioFormat.MP3);

        adapter.synthesize(TTS_TEXT, config).get(5, TimeUnit.SECONDS);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        String body = request.body();
        assertTrue(body.contains("1.5"));
    }

    @Test
    void transcribeWithDifferentFormats() throws Exception {
        httpEngine.enqueueJson(200, "{\"text\":\"Hello\",\"language_code\":\"en\"}");

        adapter.transcribe(new byte[] { 1 }, AudioFormat.MP3).get(5, TimeUnit.SECONDS);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        String body = request.body();
        assertTrue(body.contains("audio.mp3"));
    }

    @Test
    void transcribeUsesConfiguredSttModelId() throws Exception {
        when(runtimeConfigService.getSttModelId()).thenReturn("scribe_v2");
        httpEngine.enqueueJson(200, "{\"text\":\"Hello\",\"language_code\":\"en\"}");

        adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        String body = request.body();
        assertTrue(body.contains("scribe_v2"));
    }

    @Test
    void transcribeNullText() throws Exception {
        httpEngine.enqueueJson(200, "{\"text\":null,\"language_code\":\"en\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertNull(result.text());
        assertEquals("en", result.language());
    }

    @Test
    void transcribeLongTextPreview() throws Exception {
        String longText = "A".repeat(300);
        httpEngine.enqueueJson(200, "{\"text\":\"" + longText + "\",\"language_code\":\"en\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals(300, result.text().length());
    }

    @Test
    void synthesizeNullApiKeyOnCall() {
        when(runtimeConfigService.getVoiceApiKey()).thenReturn(null);
        assertSynthesizeThrowsAny();
    }

    @Test
    void synthesizeUsesDefaultModelAndSpeed() throws Exception {
        httpEngine.enqueueBytes(200, new byte[] { 1 }, AUDIO_MPEG);

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(null, null, 0f, AudioFormat.MP3);

        adapter.synthesize(TTS_TEXT, config).get(5, TimeUnit.SECONDS);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        String body = request.body();
        // Should use default model from properties
        assertTrue(body.contains("eleven_multilingual_v2"));
        // Should use default voice from properties
        assertTrue(request.target().contains("test-voice-id"));
    }

    @Test
    void initLogsWarnWhenEnabledButNoKey() {
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(true);
        when(runtimeConfigService.getVoiceApiKey()).thenReturn("");
        // init() should not throw, just log warn
        assertDoesNotThrow(() -> adapter.init());
    }

    @Test
    void initLogsWhenDisabled() {
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        when(runtimeConfigService.getVoiceApiKey()).thenReturn("");
        // init() should not throw even when disabled
        assertDoesNotThrow(() -> adapter.init());
    }

    // ===== Error Handling Tests (Consolidated) =====

    @ParameterizedTest
    @CsvSource({ "400,max_character_limit_exceeded,Text too long",
            "422,value_error,Invalid parameter" })
    void transcribeHttpErrors(int code, String status, String errorMessage) {
        String json = String.format("{\"detail\":{\"status\":\"%s\",\"message\":\"%s\"}}", status, errorMessage);
        enqueueErrorResponse(code, json);
        assertTranscribeThrows(code);
    }

    @ParameterizedTest
    @CsvSource({ "400,max_character_limit_exceeded,Text too long",
            "422,value_error,Invalid parameter" })
    void synthesizeHttpErrors(int code, String status, String errorMessage) {
        String json = String.format("{\"detail\":{\"status\":\"%s\",\"message\":\"%s\"}}", status, errorMessage);
        enqueueErrorResponse(code, json);
        assertSynthesizeThrows(code);
    }

    @Test
    void transcribe402QuotaExceeded() {
        enqueueErrorResponse(402, "{\"detail\":{\"status\":\"quota_exceeded\",\"message\":\"Quota exceeded\"}}");
        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof VoicePort.QuotaExceededException
                || (cause != null && cause.getMessage().contains("quota")));
    }

    @Test
    void synthesize402QuotaExceeded() {
        enqueueErrorResponse(402, "{\"detail\":{\"status\":\"quota_exceeded\",\"message\":\"Quota exceeded\"}}");
        CompletableFuture<byte[]> future = adapter.synthesize(TTS_TEXT, VoicePort.VoiceConfig.defaultConfig());
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof VoicePort.QuotaExceededException
                || (cause != null && cause.getMessage().contains("quota")));
    }

    @Test
    void synthesize401QuotaExceededInMessage() {
        enqueueErrorResponse(401,
                "{\"detail\":{\"status\":\"quota_error\",\"message\":\"This request exceeds your quota of 10000. You have 463 credits remaining, while 593 credits are required.\"}}");
        CompletableFuture<byte[]> future = adapter.synthesize(TTS_TEXT, VoicePort.VoiceConfig.defaultConfig());
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof VoicePort.QuotaExceededException,
                "Expected QuotaExceededException but got: " + cause);
    }

    @Test
    void transcribe401QuotaExceededInMessage() {
        enqueueErrorResponse(401,
                "{\"detail\":{\"message\":\"You have 0 credits remaining\"}}");
        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof VoicePort.QuotaExceededException,
                "Expected QuotaExceededException but got: " + cause);
    }

    @Test
    void synthesize401WithoutQuotaMessageIsNotQuotaException() {
        enqueueErrorResponse(401, "{\"detail\":{\"message\":\"Invalid API key\"}}");
        CompletableFuture<byte[]> future = adapter.synthesize(TTS_TEXT, VoicePort.VoiceConfig.defaultConfig());
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        Throwable cause = ex.getCause();
        assertFalse(cause instanceof VoicePort.QuotaExceededException,
                "Expected regular exception for auth error, not QuotaExceededException");
    }

    @Test
    void transcribe504Timeout() {
        enqueueErrorResponseMultiple(504, "{\"message\":\"Gateway timeout\"}", 3);
        assertTranscribeThrows(504);
    }

    @Test
    void synthesize504Timeout() {
        enqueueErrorResponseMultiple(504, "{\"message\":\"Gateway timeout\"}", 3);
        assertSynthesizeThrows(504);
    }

    @ParameterizedTest
    @CsvSource({
            "synthesize,429,1",
            "synthesize,500,2",
            "synthesize,503,3",
            "transcribe,429,1"
    })
    void retriesOnRetryableErrors(String operation, int errorCode, int expectedBytes) throws Exception {
        String errorBody = errorCode == 429
                ? "{\"detail\":{\"status\":\"rate_limited\",\"message\":\"Rate limited\"}}"
                : "{\"message\":\"Server error\"}";
        httpEngine.enqueueJson(errorCode, errorBody);

        if (OPERATION_SYNTHESIZE.equals(operation)) {
            byte[] response = new byte[expectedBytes];
            for (int i = 0; i < expectedBytes; i++) {
                response[i] = (byte) (i + 1);
            }
            httpEngine.enqueueBytes(200, response, AUDIO_MPEG);
            byte[] result = adapter.synthesize(TTS_TEXT, VoicePort.VoiceConfig.defaultConfig()).get(10,
                    TimeUnit.SECONDS);
            assertEquals(expectedBytes, result.length);
        } else {
            httpEngine.enqueueJson(200, "{\"text\":\"Hello\",\"language_code\":\"en\"}");
            VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 },
                    AudioFormat.OGG_OPUS).get(10, TimeUnit.SECONDS);
            assertEquals(STT_TEXT, result.text());
        }
        assertEquals(2, httpEngine.getRequestCount());
    }

    @ParameterizedTest
    @CsvSource({ "synthesize,429", "transcribe,500" })
    void failsAfterMaxRetries(String operation, int errorCode) {
        String errorBody = "{\"detail\":{\"message\":\"Error\"}}";
        enqueueErrorResponseMultiple(errorCode, errorBody, 3);

        if (OPERATION_SYNTHESIZE.equals(operation)) {
            CompletableFuture<byte[]> future = adapter.synthesize(TTS_TEXT, VoicePort.VoiceConfig.defaultConfig());
            Exception ex = assertThrows(Exception.class, () -> future.get(15, TimeUnit.SECONDS));
            String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            assertTrue(message != null && message.contains(String.valueOf(errorCode)));
        } else {
            CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                    AudioFormat.OGG_OPUS);
            Exception ex = assertThrows(Exception.class, () -> future.get(15, TimeUnit.SECONDS));
            String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            assertTrue(message != null && message.contains(String.valueOf(errorCode)));
        }
        assertEquals(3, httpEngine.getRequestCount());
    }

    static Stream<Arguments> errorParsingCases() {
        return Stream.of(
                Arguments.of(400, "{\"detail\":{\"status\":\"test_error\",\"message\":\"Test message\"}}",
                        "Test message"),
                Arguments.of(400, "{\"message\":\"Root level error\"}", "Root level"),
                Arguments.of(418, "Not valid JSON at all", ""));
    }

    @ParameterizedTest
    @MethodSource("errorParsingCases")
    void errorResponseParsing(int code, String errorBody, String expectedSubstring) {
        httpEngine.enqueueJson(code, errorBody);
        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        if (!expectedSubstring.isEmpty()) {
            String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            assertTrue(message != null && message.contains(expectedSubstring));
        }
    }

    // ===== Whisper STT delegation tests =====

    @Test
    void shouldDelegateToWhisperWhenWhisperSttConfigured() throws Exception {
        when(runtimeConfigService.isWhisperSttConfigured()).thenReturn(true);
        VoicePort.TranscriptionResult whisperResult = new VoicePort.TranscriptionResult(
                "Whisper result", "en", 1.0f, java.time.Duration.ZERO, java.util.Collections.emptyList());
        when(whisperSttAdapter.transcribe(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.any(AudioFormat.class))).thenReturn(whisperResult);

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals("Whisper result", result.text());
        assertEquals(0, httpEngine.getRequestCount());
    }

    @Test
    void shouldBeAvailableWithWhisperSttAndNoElevenLabsApiKey() {
        when(runtimeConfigService.isWhisperSttConfigured()).thenReturn(true);
        when(runtimeConfigService.getVoiceApiKey()).thenReturn("");

        assertTrue(adapter.isAvailable());
    }

    @Test
    void shouldDelegateToWhisperWithoutElevenLabsApiKey() throws Exception {
        when(runtimeConfigService.isWhisperSttConfigured()).thenReturn(true);
        when(runtimeConfigService.getVoiceApiKey()).thenReturn("");
        VoicePort.TranscriptionResult whisperResult = new VoicePort.TranscriptionResult(
                "Delegated", "en", 1.0f, java.time.Duration.ZERO, java.util.Collections.emptyList());
        when(whisperSttAdapter.transcribe(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.any(AudioFormat.class))).thenReturn(whisperResult);

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS)
                .get(5, TimeUnit.SECONDS);

        assertEquals("Delegated", result.text());
        assertEquals(0, httpEngine.getRequestCount());
    }

    @Test
    void shouldPropagateWhisperErrorWithoutElevenLabsFallback() {
        when(runtimeConfigService.isWhisperSttConfigured()).thenReturn(true);
        when(whisperSttAdapter.transcribe(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.any(AudioFormat.class)))
                .thenThrow(new IllegalStateException("Whisper offline"));

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        assertTrue(message != null && message.contains("Whisper offline"));
        assertEquals(0, httpEngine.getRequestCount());
    }

    @Test
    void shouldRejectSynthesizeWhenTtsProviderIsNotElevenLabs() {
        when(runtimeConfigService.getTtsProvider()).thenReturn("whisper");

        CompletableFuture<byte[]> future = adapter.synthesize(TTS_TEXT, VoicePort.VoiceConfig.defaultConfig());
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        assertTrue(message != null && message.contains("Unsupported TTS provider"));
        assertEquals(0, httpEngine.getRequestCount());
    }

    @Test
    void shouldNotBeAvailableWhenVoiceDisabledEvenWithWhisperConfigured() {
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        when(runtimeConfigService.isWhisperSttConfigured()).thenReturn(true);

        assertFalse(adapter.isAvailable());
    }

}

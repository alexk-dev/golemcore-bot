package me.golemcore.bot.adapter.outbound.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.VoicePort;
import okhttp3.OkHttpClient;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ElevenLabsAdapterTest {

    private MockWebServer mockServer;
    private ElevenLabsAdapter adapter;
    private BotProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();

        properties = new BotProperties();
        properties.getVoice().setEnabled(true);
        properties.getVoice().setApiKey("test-api-key");
        properties.getVoice().setVoiceId("test-voice-id");
        properties.getVoice().setTtsModelId("eleven_multilingual_v2");
        properties.getVoice().setSttModelId("scribe_v1");

        String baseUrl = mockServer.url("/").toString();
        adapter = new ElevenLabsAdapter(client, properties, new ObjectMapper()) {
            @Override
            protected String getSttUrl() {
                return baseUrl + "v1/speech-to-text";
            }

            @Override
            protected String getTtsUrl(String voiceId) {
                return baseUrl + "v1/text-to-speech/" + voiceId;
            }
        };
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.close();
    }

    @Test
    void transcribeSuccess() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"Hello world\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json").build());

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1, 2, 3 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals("Hello world", result.text());
        assertEquals("en", result.language());

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("test-api-key", request.getHeaders().get("xi-api-key"));
        assertTrue(request.getHeaders().get("Content-Type").contains("multipart/form-data"));
    }

    @Test
    void transcribeApiError() {
        mockServer.enqueue(new MockResponse.Builder().code(401).body("Unauthorized").build());

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Transcription failed") || ex.getCause().getMessage().contains("401"));
    }

    @Test
    void transcribeApiErrorWithoutBody() {
        mockServer.enqueue(new MockResponse.Builder().code(500).build());

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void synthesizeSuccess() throws Exception {
        byte[] mp3Bytes = new byte[] { 0x49, 0x44, 0x33, 1, 2, 3 }; // fake MP3
        mockServer.enqueue(new MockResponse.Builder()
                .body(new okio.Buffer().write(mp3Bytes))
                .addHeader("Content-Type", "audio/mpeg").build());

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(
                "custom-voice", "eleven_multilingual_v2", 1.0f, AudioFormat.MP3);

        byte[] result = adapter.synthesize("Hello", config).get(5, TimeUnit.SECONDS);
        assertArrayEquals(mp3Bytes, result);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("test-api-key", request.getHeaders().get("xi-api-key"));
        assertEquals("audio/mpeg", request.getHeaders().get("Accept"));
        assertTrue(request.getTarget() != null && request.getTarget().contains("custom-voice"));
        assertTrue(request.getTarget() != null && request.getTarget().contains("output_format=mp3_44100_128"));
    }

    @Test
    void synthesizeUsesDefaultVoiceId() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body(new okio.Buffer().write(new byte[] { 1 }))
                .addHeader("Content-Type", "audio/mpeg")
                .build());

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(null, null, 0f, AudioFormat.MP3);

        adapter.synthesize("Test", config).get(5, TimeUnit.SECONDS);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(request.getTarget() != null && request.getTarget().contains("test-voice-id"));
    }

    @Test
    void synthesizeApiError() {
        mockServer.enqueue(new MockResponse.Builder().code(500).body("Server error").build());

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize("Test", config);
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void synthesizeApiErrorWithoutBody() {
        mockServer.enqueue(new MockResponse.Builder().code(503).build());

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize("Test", config);
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void synthesizeNetworkError() throws IOException {
        mockServer.close();

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize("Test", config);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Synthesis failed") || ex.getCause() instanceof IOException);
    }

    @Test
    void isAvailableWithApiKey() {
        assertTrue(adapter.isAvailable());
    }

    @Test
    void isNotAvailableWithoutApiKey() {
        properties.getVoice().setApiKey("");
        assertFalse(adapter.isAvailable());
    }

    @Test
    void isNotAvailableWhenDisabled() {
        properties.getVoice().setEnabled(false);
        assertFalse(adapter.isAvailable());
    }

    @Test
    void isNotAvailableWithNullApiKey() {
        properties.getVoice().setApiKey(null);
        assertFalse(adapter.isAvailable());
    }

    @Test
    void transcribeFailsWithoutApiKey() {
        properties.getVoice().setApiKey("");
        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void synthesizeFailsWithoutApiKey() {
        properties.getVoice().setApiKey("");
        CompletableFuture<byte[]> future = adapter.synthesize("Hello", VoicePort.VoiceConfig.defaultConfig());
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    // ===== Edge cases =====

    @Test
    void transcribeEmptyResponseText() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json").build());

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals("", result.text());
        assertEquals("en", result.language());
    }

    @Test
    void transcribeNullLanguageCode() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"Hello\"}")
                .addHeader("Content-Type", "application/json").build());

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals("Hello", result.text());
        assertEquals("unknown", result.language());
    }

    @Test
    void transcribeMalformedJson() {
        mockServer.enqueue(new MockResponse.Builder()
                .body("not valid json at all")
                .addHeader("Content-Type", "application/json").build());

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Transcription failed")
                || ex.getCause().getMessage().contains("Transcription failed"));
    }

    @Test
    void transcribeNullFormat() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"Hello\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json").build());

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, null).get(5, TimeUnit.SECONDS);

        assertEquals("Hello", result.text());

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        // Should use "audio/ogg" fallback
        String body = request.getBody().utf8();
        assertTrue(body.contains("audio.ogg"));
    }

    @Test
    void synthesizeEmptyAudioResponse() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body(new okio.Buffer())
                .addHeader("Content-Type", "audio/mpeg").build());

        byte[] result = adapter.synthesize("Test",
                VoicePort.VoiceConfig.defaultConfig()).get(5, TimeUnit.SECONDS);

        assertEquals(0, result.length);
    }

    @Test
    void synthesizeRateLimited() {
        mockServer.enqueue(new MockResponse.Builder()
                .code(429)
                .body("{\"detail\":\"Rate limit exceeded\"}").build());

        CompletableFuture<byte[]> future = adapter.synthesize("Test", VoicePort.VoiceConfig.defaultConfig());
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void synthesizeCustomSpeed() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body(new okio.Buffer().write(new byte[] { 1 }))
                .addHeader("Content-Type", "audio/mpeg")
                .build());

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(
                "voice1", "model1", 1.5f, AudioFormat.MP3);

        adapter.synthesize("Test", config).get(5, TimeUnit.SECONDS);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().utf8();
        assertTrue(body.contains("1.5"));
    }

    @Test
    void transcribeWithDifferentFormats() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"Hello\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json").build());

        adapter.transcribe(new byte[] { 1 }, AudioFormat.MP3).get(5, TimeUnit.SECONDS);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().utf8();
        assertTrue(body.contains("audio.mp3"));
    }

    @Test
    void transcribeNullText() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":null,\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json")
                .build());

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertNull(result.text());
        assertEquals("en", result.language());
    }

    @Test
    void transcribeLongTextPreview() throws Exception {
        String longText = "A".repeat(300);
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"" + longText + "\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json")
                .build());

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals(300, result.text().length());
    }

    @Test
    void synthesizeNullApiKeyOnCall() {
        properties.getVoice().setApiKey(null);
        CompletableFuture<byte[]> future = adapter.synthesize("Hello", VoicePort.VoiceConfig.defaultConfig());
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void synthesizeUsesDefaultModelAndSpeed() throws Exception {
        mockServer.enqueue(new MockResponse.Builder()
                .body(new okio.Buffer().write(new byte[] { 1 }))
                .addHeader("Content-Type", "audio/mpeg")
                .build());

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(null, null, 0f, AudioFormat.MP3);

        adapter.synthesize("Test", config).get(5, TimeUnit.SECONDS);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().utf8();
        // Should use default model from properties
        assertTrue(body.contains("eleven_multilingual_v2"));
        // Should use default voice from properties
        assertTrue(request.getTarget() != null && request.getTarget().contains("test-voice-id"));
    }

    @Test
    void initLogsWarnWhenEnabledButNoKey() {
        properties.getVoice().setEnabled(true);
        properties.getVoice().setApiKey("");
        // init() should not throw, just log warn
        assertDoesNotThrow(() -> adapter.init());
    }

    @Test
    void initLogsWhenDisabled() {
        properties.getVoice().setEnabled(false);
        properties.getVoice().setApiKey("");
        // init() should not throw even when disabled
        assertDoesNotThrow(() -> adapter.init());
    }

    // ===== New Error Handling Tests =====

    @Test
    void transcribe400BadRequest() {
        String errorJson = "{\"detail\":{\"status\":\"max_character_limit_exceeded\",\"message\":\"Text has 50000 characters and exceeds the limit of 10000\"}}";
        mockServer.enqueue(new MockResponse.Builder().code(400).body(errorJson).build());

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        assertTrue(message != null && (message.contains("400") || message.contains("Bad request")));
    }

    @Test
    void transcribe402QuotaExceeded() {
        String errorJson = "{\"detail\":{\"status\":\"quota_exceeded\",\"message\":\"Quota exceeded for current billing period\"}}";
        mockServer.enqueue(new MockResponse.Builder().code(402).body(errorJson).build());

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof ElevenLabsAdapter.QuotaExceededException
                || (cause != null && cause.getMessage().contains("quota")));
    }

    @Test
    void transcribe422ValidationError() {
        String errorJson = "{\"detail\":{\"status\":\"value_error\",\"message\":\"Invalid model_id parameter\"}}";
        mockServer.enqueue(new MockResponse.Builder().code(422).body(errorJson).build());

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        assertTrue(message != null && message.contains("422"));
    }

    @Test
    void transcribe504Timeout() {
        String errorJson = "{\"message\":\"Gateway timeout\"}";
        // Enqueue 3 times because 504 is retryable
        for (int i = 0; i < 3; i++) {
            mockServer.enqueue(new MockResponse.Builder().code(504).body(errorJson).build());
        }

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(15, TimeUnit.SECONDS));
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        assertTrue(message != null && message.contains("504"));
    }

    @Test
    void synthesize400BadRequest() {
        String errorJson = "{\"detail\":{\"status\":\"max_character_limit_exceeded\",\"message\":\"Text too long\"}}";
        mockServer.enqueue(new MockResponse.Builder().code(400).body(errorJson).build());

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize("Test", config);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("400") || ex.getCause().getMessage().contains("400"));
    }

    @Test
    void synthesize402QuotaExceeded() {
        String errorJson = "{\"detail\":{\"status\":\"quota_exceeded\",\"message\":\"Quota exceeded\"}}";
        mockServer.enqueue(new MockResponse.Builder().code(402).body(errorJson).build());

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize("Test", config);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof ElevenLabsAdapter.QuotaExceededException
                || (cause != null && cause.getMessage().contains("quota")));
    }

    @Test
    void synthesize422ValidationError() {
        String errorJson = "{\"detail\":{\"status\":\"value_error\",\"message\":\"Invalid voice_id\"}}";
        mockServer.enqueue(new MockResponse.Builder().code(422).body(errorJson).build());

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize("Test", config);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        assertTrue(message != null && message.contains("422"));
    }

    @Test
    void synthesize504Timeout() {
        String errorJson = "{\"message\":\"Gateway timeout\"}";
        // Enqueue 3 times because 504 is retryable
        for (int i = 0; i < 3; i++) {
            mockServer.enqueue(new MockResponse.Builder().code(504).body(errorJson).build());
        }

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize("Test", config);
        Exception ex = assertThrows(Exception.class, () -> future.get(15, TimeUnit.SECONDS));
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        assertTrue(message != null && message.contains("504"));
    }

    @Test
    void synthesizeRetriesOn429() throws Exception {
        // First call: 429, second call: success
        mockServer.enqueue(new MockResponse.Builder().code(429)
                .body("{\"detail\":{\"status\":\"too_many_concurrent_requests\",\"message\":\"Rate limited\"}}")
                .build());
        mockServer.enqueue(new MockResponse.Builder()
                .body(new okio.Buffer().write(new byte[] { 1, 2, 3 }))
                .addHeader("Content-Type", "audio/mpeg").build());

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        byte[] result = adapter.synthesize("Test", config).get(10, TimeUnit.SECONDS);

        assertEquals(3, result.length);
        assertEquals(2, mockServer.getRequestCount());
    }

    @Test
    void synthesizeRetriesOn500() throws Exception {
        // First call: 500, second call: success
        mockServer.enqueue(new MockResponse.Builder().code(500)
                .body("{\"detail\":{\"message\":\"Internal server error\"}}").build());
        mockServer.enqueue(new MockResponse.Builder()
                .body(new okio.Buffer().write(new byte[] { 1, 2 }))
                .addHeader("Content-Type", "audio/mpeg").build());

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        byte[] result = adapter.synthesize("Test", config).get(10, TimeUnit.SECONDS);

        assertEquals(2, result.length);
        assertEquals(2, mockServer.getRequestCount());
    }

    @Test
    void synthesizeRetriesOn503() throws Exception {
        // First call: 503, second call: success
        mockServer.enqueue(new MockResponse.Builder().code(503)
                .body("{\"message\":\"Service unavailable\"}}").build());
        mockServer.enqueue(new MockResponse.Builder()
                .body(new okio.Buffer().write(new byte[] { 1 }))
                .addHeader("Content-Type", "audio/mpeg").build());

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        byte[] result = adapter.synthesize("Test", config).get(10, TimeUnit.SECONDS);

        assertEquals(1, result.length);
        assertEquals(2, mockServer.getRequestCount());
    }

    @Test
    void synthesizeFailsAfterMaxRetries() {
        // All 3 attempts fail with 429
        for (int i = 0; i < 3; i++) {
            mockServer.enqueue(new MockResponse.Builder().code(429)
                    .body("{\"detail\":{\"status\":\"rate_limited\",\"message\":\"Too many requests\"}}").build());
        }

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize("Test", config);
        Exception ex = assertThrows(Exception.class, () -> future.get(15, TimeUnit.SECONDS));
        assertTrue((ex.getMessage() != null && ex.getMessage().contains("429"))
                || (ex.getCause() != null && ex.getCause().getMessage().contains("429")));
        assertEquals(3, mockServer.getRequestCount());
    }

    @Test
    void transcribeRetriesOn429() throws Exception {
        // First call: 429, second call: success
        mockServer.enqueue(new MockResponse.Builder().code(429)
                .body("{\"detail\":{\"status\":\"rate_limited\",\"message\":\"Rate limited\"}}").build());
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"Hello\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json").build());

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS).get(10, TimeUnit.SECONDS);

        assertEquals("Hello", result.text());
        assertEquals(2, mockServer.getRequestCount());
    }

    @Test
    void transcribeFailsAfterMaxRetries() {
        // All 3 attempts fail with 500
        for (int i = 0; i < 3; i++) {
            mockServer.enqueue(new MockResponse.Builder().code(500)
                    .body("{\"message\":\"Internal error\"}").build());
        }

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(15, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("500") || ex.getCause().getMessage().contains("500"));
        assertEquals(3, mockServer.getRequestCount());
    }

    @Test
    void errorResponseParsing() {
        // Test structured error parsing
        String structuredError = "{\"detail\":{\"status\":\"test_error\",\"message\":\"Test message\"}}";
        mockServer.enqueue(new MockResponse.Builder().code(400).body(structuredError).build());

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Test message") || ex.getCause().getMessage().contains("Test message"));
    }

    @Test
    void errorResponseParsingRootLevel() {
        // Test error at root level (no detail)
        String rootError = "{\"message\":\"Root level error\"}";
        mockServer.enqueue(new MockResponse.Builder().code(400).body(rootError).build());

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Root level error") || ex.getCause().getMessage().contains("Root level"));
    }

    @Test
    void errorResponseParsingMalformed() {
        // Test malformed JSON error (fallback)
        String malformedError = "Not valid JSON at all";
        mockServer.enqueue(new MockResponse.Builder().code(500).body(malformedError).build());

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }
}

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
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Transcription failed") || ex.getCause().getMessage().contains("500"));
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
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Synthesis failed") || ex.getCause().getMessage().contains("500"));
    }

    @Test
    void synthesizeApiErrorWithoutBody() {
        mockServer.enqueue(new MockResponse.Builder().code(503).build());

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize("Test", config);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Synthesis failed") || ex.getCause().getMessage().contains("503"));
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
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("429") || ex.getCause().getMessage().contains("429"));
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
}

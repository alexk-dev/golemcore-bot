package me.golemcore.bot.adapter.outbound.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.VoicePort;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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
        mockServer.shutdown();
    }

    @Test
    void transcribeSuccess() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"text\":\"Hello world\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json"));

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1, 2, 3 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals("Hello world", result.text());
        assertEquals("en", result.language());

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("test-api-key", request.getHeader("xi-api-key"));
        assertTrue(request.getHeader("Content-Type").contains("multipart/form-data"));
    }

    @Test
    void transcribeApiError() {
        mockServer.enqueue(new MockResponse().setResponseCode(401).setBody("Unauthorized"));

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Transcription failed") || ex.getCause().getMessage().contains("401"));
    }

    @Test
    void synthesizeSuccess() throws Exception {
        byte[] mp3Bytes = new byte[] { 0x49, 0x44, 0x33, 1, 2, 3 }; // fake MP3
        mockServer.enqueue(new MockResponse()
                .setBody(new okio.Buffer().write(mp3Bytes))
                .addHeader("Content-Type", "audio/mpeg"));

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(
                "custom-voice", "eleven_multilingual_v2", 1.0f, AudioFormat.MP3);

        byte[] result = adapter.synthesize("Hello", config).get(5, TimeUnit.SECONDS);
        assertArrayEquals(mp3Bytes, result);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("test-api-key", request.getHeader("xi-api-key"));
        assertEquals("audio/mpeg", request.getHeader("Accept"));
        assertTrue(request.getPath().contains("custom-voice"));
        assertTrue(request.getPath().contains("output_format=mp3_44100_128"));
    }

    @Test
    void synthesizeUsesDefaultVoiceId() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody(new okio.Buffer().write(new byte[] { 1 }))
                .addHeader("Content-Type", "audio/mpeg"));

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(null, null, 0f, AudioFormat.MP3);

        adapter.synthesize("Test", config).get(5, TimeUnit.SECONDS);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(request.getPath().contains("test-voice-id"));
    }

    @Test
    void synthesizeApiError() {
        mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("Server error"));

        VoicePort.VoiceConfig config = VoicePort.VoiceConfig.defaultConfig();
        CompletableFuture<byte[]> future = adapter.synthesize("Test", config);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Synthesis failed") || ex.getCause().getMessage().contains("500"));
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
        mockServer.enqueue(new MockResponse()
                .setBody("{\"text\":\"\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json"));

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals("", result.text());
        assertEquals("en", result.language());
    }

    @Test
    void transcribeNullLanguageCode() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"text\":\"Hello\"}")
                .addHeader("Content-Type", "application/json"));

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertEquals("Hello", result.text());
        assertEquals("unknown", result.language());
    }

    @Test
    void transcribeMalformedJson() {
        mockServer.enqueue(new MockResponse()
                .setBody("not valid json at all")
                .addHeader("Content-Type", "application/json"));

        CompletableFuture<VoicePort.TranscriptionResult> future = adapter.transcribe(new byte[] { 1 },
                AudioFormat.OGG_OPUS);
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Transcription failed")
                || ex.getCause().getMessage().contains("Transcription failed"));
    }

    @Test
    void transcribeNullFormat() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"text\":\"Hello\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json"));

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, null).get(5, TimeUnit.SECONDS);

        assertEquals("Hello", result.text());

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        // Should use "audio/ogg" fallback
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("audio.ogg"));
    }

    @Test
    void synthesizeEmptyAudioResponse() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody(new okio.Buffer())
                .addHeader("Content-Type", "audio/mpeg"));

        byte[] result = adapter.synthesize("Test",
                VoicePort.VoiceConfig.defaultConfig()).get(5, TimeUnit.SECONDS);

        assertEquals(0, result.length);
    }

    @Test
    void synthesizeRateLimited() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("{\"detail\":\"Rate limit exceeded\"}"));

        CompletableFuture<byte[]> future = adapter.synthesize("Test", VoicePort.VoiceConfig.defaultConfig());
        Exception ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("429") || ex.getCause().getMessage().contains("429"));
    }

    @Test
    void synthesizeCustomSpeed() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody(new okio.Buffer().write(new byte[] { 1 }))
                .addHeader("Content-Type", "audio/mpeg"));

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(
                "voice1", "model1", 1.5f, AudioFormat.MP3);

        adapter.synthesize("Test", config).get(5, TimeUnit.SECONDS);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("1.5"));
    }

    @Test
    void transcribeWithDifferentFormats() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"text\":\"Hello\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json"));

        adapter.transcribe(new byte[] { 1 }, AudioFormat.MP3).get(5, TimeUnit.SECONDS);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("audio.mp3"));
    }

    @Test
    void transcribeNullText() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"text\":null,\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json"));

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1 }, AudioFormat.OGG_OPUS).get(5, TimeUnit.SECONDS);

        assertNull(result.text());
        assertEquals("en", result.language());
    }

    @Test
    void transcribeLongTextPreview() throws Exception {
        String longText = "A".repeat(300);
        mockServer.enqueue(new MockResponse()
                .setBody("{\"text\":\"" + longText + "\",\"language_code\":\"en\"}")
                .addHeader("Content-Type", "application/json"));

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
        mockServer.enqueue(new MockResponse()
                .setBody(new okio.Buffer().write(new byte[] { 1 }))
                .addHeader("Content-Type", "audio/mpeg"));

        VoicePort.VoiceConfig config = new VoicePort.VoiceConfig(null, null, 0f, AudioFormat.MP3);

        adapter.synthesize("Test", config).get(5, TimeUnit.SECONDS);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().readUtf8();
        // Should use default model from properties
        assertTrue(body.contains("eleven_multilingual_v2"));
        // Should use default voice from properties
        assertTrue(request.getPath().contains("test-voice-id"));
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

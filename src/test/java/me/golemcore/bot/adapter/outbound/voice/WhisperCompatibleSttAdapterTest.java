package me.golemcore.bot.adapter.outbound.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.VoicePort;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WhisperCompatibleSttAdapterTest {

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private MockWebServer mockServer;
    private WhisperCompatibleSttAdapter adapter;
    private RuntimeConfigService runtimeConfigService;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build();

        runtimeConfigService = mock(RuntimeConfigService.class);
        String baseUrl = mockServer.url("/").toString();
        // Remove trailing slash for consistent URL building
        String normalizedUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        when(runtimeConfigService.getWhisperSttUrl()).thenReturn(normalizedUrl);
        when(runtimeConfigService.getWhisperSttApiKey()).thenReturn("");

        adapter = new WhisperCompatibleSttAdapter(client, runtimeConfigService, new ObjectMapper()) {
            @Override
            protected void sleepBeforeRetry(long backoffMs) {
                // No-op in tests to avoid real backoff delays.
            }
        };
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.close();
    }

    @Test
    void shouldTranscribeSuccessfully() {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"Hello world\",\"language\":\"en\"}")
                .addHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1, 2, 3 }, AudioFormat.OGG_OPUS);

        assertEquals("Hello world", result.text());
        assertEquals("en", result.language());
        assertEquals(1.0f, result.confidence());
    }

    @Test
    void shouldParseLanguageField() {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"Hola\",\"language\":\"es\"}")
                .addHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        assertEquals("Hola", result.text());
        assertEquals("es", result.language());
    }

    @Test
    void shouldSendMultipartWithCorrectFields() throws InterruptedException {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"test\",\"language\":\"en\"}")
                .addHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        adapter.transcribe(new byte[] { 1 }, AudioFormat.MP3);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(request.getTarget() != null && request.getTarget().contains("/v1/audio/transcriptions"));
        String contentType = request.getHeaders().get(CONTENT_TYPE);
        assertNotNull(contentType);
        assertTrue(contentType.contains("multipart/form-data"));
        String body = request.getBody().utf8();
        assertTrue(body.contains("audio.mp3"));
        assertTrue(body.contains("whisper-1"));
        assertTrue(body.contains("verbose_json"));
    }

    @Test
    void shouldSendAuthHeaderWhenApiKeyConfigured() throws InterruptedException {
        when(runtimeConfigService.getWhisperSttApiKey()).thenReturn("sk-test-key");
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"test\",\"language\":\"en\"}")
                .addHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("Bearer sk-test-key", request.getHeaders().get("Authorization"));
    }

    @Test
    void shouldNotSendAuthHeaderWhenApiKeyEmpty() throws InterruptedException {
        when(runtimeConfigService.getWhisperSttApiKey()).thenReturn("");
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"test\",\"language\":\"en\"}")
                .addHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertNull(request.getHeaders().get("Authorization"));
    }

    @Test
    void shouldRetryOnServerError() {
        mockServer.enqueue(new MockResponse.Builder().code(500).body("Server error").build());
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"recovered\",\"language\":\"en\"}")
                .addHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        assertEquals("recovered", result.text());
        assertEquals(2, mockServer.getRequestCount());
    }

    @Test
    void shouldFailAfterMaxRetries() {
        mockServer.enqueue(new MockResponse.Builder().code(503).body("Unavailable").build());
        mockServer.enqueue(new MockResponse.Builder().code(503).body("Unavailable").build());
        mockServer.enqueue(new MockResponse.Builder().code(503).body("Unavailable").build());

        Exception ex = assertThrows(Exception.class,
                () -> adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS));
        assertTrue(ex.getMessage().contains("503") || ex.getMessage().contains("failed"));
        assertEquals(3, mockServer.getRequestCount());
    }

    @Test
    void shouldThrowWhenUrlNotConfigured() {
        when(runtimeConfigService.getWhisperSttUrl()).thenReturn("");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS));
        assertTrue(ex.getMessage().contains("not configured"));
    }

    @Test
    void shouldReportHealthy() {
        mockServer.enqueue(new MockResponse.Builder().code(200).body("OK").build());

        assertTrue(adapter.isHealthy());
    }

    @Test
    void shouldReportUnhealthy() {
        mockServer.enqueue(new MockResponse.Builder().code(503).body("Down").build());

        assertFalse(adapter.isHealthy());
    }

    @Test
    void shouldReportUnhealthyWhenUrlEmpty() {
        when(runtimeConfigService.getWhisperSttUrl()).thenReturn("");

        assertFalse(adapter.isHealthy());
    }

    @Test
    void shouldHandleNullFormat() throws InterruptedException {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"test\",\"language\":\"en\"}")
                .addHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, null);

        assertEquals("test", result.text());
        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().utf8();
        assertTrue(body.contains("audio.ogg"));
    }

    @Test
    void shouldHandleNullLanguageInResponse() {
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"Hello\"}")
                .addHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        assertEquals("Hello", result.text());
        assertEquals("unknown", result.language());
    }

    @Test
    void shouldRetryOn429RateLimit() {
        mockServer.enqueue(new MockResponse.Builder().code(429).body("Rate limited").build());
        mockServer.enqueue(new MockResponse.Builder()
                .body("{\"text\":\"ok\",\"language\":\"en\"}")
                .addHeader(CONTENT_TYPE, APPLICATION_JSON).build());

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        assertEquals("ok", result.text());
        assertEquals(2, mockServer.getRequestCount());
    }

    @Test
    void shouldNotRetryOnClientError() {
        mockServer.enqueue(new MockResponse.Builder().code(400).body("Bad request").build());

        Exception ex = assertThrows(Exception.class,
                () -> adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS));
        assertTrue(ex.getMessage().contains("400"));
        assertEquals(1, mockServer.getRequestCount());
    }
}

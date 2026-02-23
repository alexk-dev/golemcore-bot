package me.golemcore.bot.plugin.builtin.whisper.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.VoicePort;
import me.golemcore.bot.testsupport.http.OkHttpMockEngine;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WhisperCompatibleSttAdapterTest {

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String BASE_URL = "http://mock.whisper.local";

    private OkHttpMockEngine httpEngine;
    private WhisperCompatibleSttAdapter adapter;
    private RuntimeConfigService runtimeConfigService;

    @BeforeEach
    void setUp() {
        httpEngine = new OkHttpMockEngine();

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(httpEngine)
                .build();

        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getWhisperSttUrl()).thenReturn(BASE_URL);
        when(runtimeConfigService.getWhisperSttApiKey()).thenReturn("");

        adapter = new WhisperCompatibleSttAdapter(client, runtimeConfigService, new ObjectMapper()) {
            @Override
            protected void sleepBeforeRetry(long backoffMs) {
                // No-op in tests to avoid real backoff delays.
            }
        };
    }

    @Test
    void shouldTranscribeSuccessfully() {
        httpEngine.enqueueJson(200, "{\"text\":\"Hello world\",\"language\":\"en\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(
                new byte[] { 1, 2, 3 }, AudioFormat.OGG_OPUS);

        assertEquals("Hello world", result.text());
        assertEquals("en", result.language());
        assertEquals(1.0f, result.confidence());
    }

    @Test
    void shouldParseLanguageField() {
        httpEngine.enqueueJson(200, "{\"text\":\"Hola\",\"language\":\"es\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        assertEquals("Hola", result.text());
        assertEquals("es", result.language());
    }

    @Test
    void shouldSendMultipartWithCorrectFields() {
        httpEngine.enqueueJson(200, "{\"text\":\"test\",\"language\":\"en\"}");

        adapter.transcribe(new byte[] { 1 }, AudioFormat.MP3);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertTrue(request.target().contains("/v1/audio/transcriptions"));
        String contentType = request.headers().get(CONTENT_TYPE);
        assertNotNull(contentType);
        assertTrue(contentType.contains("multipart/form-data"));
        String body = request.body();
        assertTrue(body.contains("audio.mp3"));
        assertTrue(body.contains("whisper-1"));
        assertTrue(body.contains("verbose_json"));
    }

    @Test
    void shouldSendAuthHeaderWhenApiKeyConfigured() {
        when(runtimeConfigService.getWhisperSttApiKey()).thenReturn("sk-test-key");
        httpEngine.enqueueJson(200, "{\"text\":\"test\",\"language\":\"en\"}");

        adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertEquals("Bearer sk-test-key", request.headers().get("Authorization"));
    }

    @Test
    void shouldNotSendAuthHeaderWhenApiKeyEmpty() {
        when(runtimeConfigService.getWhisperSttApiKey()).thenReturn("");
        httpEngine.enqueueJson(200, "{\"text\":\"test\",\"language\":\"en\"}");

        adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertNull(request.headers().get("Authorization"));
    }

    @Test
    void shouldNotSendAuthHeaderWhenApiKeyBlank() {
        when(runtimeConfigService.getWhisperSttApiKey()).thenReturn("   ");
        httpEngine.enqueueJson(200, "{\"text\":\"test\",\"language\":\"en\"}");

        adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertNull(request.headers().get("Authorization"));
    }

    @Test
    void shouldRetryOnServerError() {
        httpEngine.enqueueJson(500, "Server error");
        httpEngine.enqueueJson(200, "{\"text\":\"recovered\",\"language\":\"en\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        assertEquals("recovered", result.text());
        assertEquals(2, httpEngine.getRequestCount());
    }

    @Test
    void shouldFailAfterMaxRetries() {
        httpEngine.enqueueJson(503, "Unavailable");
        httpEngine.enqueueJson(503, "Unavailable");
        httpEngine.enqueueJson(503, "Unavailable");

        Exception ex = assertThrows(Exception.class,
                () -> adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS));
        assertTrue(ex.getMessage().contains("503") || ex.getMessage().contains("failed"));
        assertEquals(3, httpEngine.getRequestCount());
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
        httpEngine.enqueueJson(200, "OK");

        assertTrue(adapter.isHealthy());
    }

    @Test
    void shouldReportUnhealthy() {
        httpEngine.enqueueJson(503, "Down");

        assertFalse(adapter.isHealthy());
    }

    @Test
    void shouldReportUnhealthyWhenUrlEmpty() {
        when(runtimeConfigService.getWhisperSttUrl()).thenReturn("");

        assertFalse(adapter.isHealthy());
    }

    @Test
    void shouldHandleNullFormat() {
        httpEngine.enqueueJson(200, "{\"text\":\"test\",\"language\":\"en\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, null);

        assertEquals("test", result.text());
        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        String body = request.body();
        assertTrue(body.contains("audio.ogg"));
    }

    @Test
    void shouldHandleNullLanguageInResponse() {
        httpEngine.enqueueJson(200, "{\"text\":\"Hello\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        assertEquals("Hello", result.text());
        assertEquals("unknown", result.language());
    }

    @Test
    void shouldRetryOn429RateLimit() {
        httpEngine.enqueueJson(429, "Rate limited");
        httpEngine.enqueueJson(200, "{\"text\":\"ok\",\"language\":\"en\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        assertEquals("ok", result.text());
        assertEquals(2, httpEngine.getRequestCount());
    }

    @Test
    void shouldNotRetryOnClientError() {
        httpEngine.enqueueJson(400, "Bad request");

        Exception ex = assertThrows(Exception.class,
                () -> adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS));
        assertTrue(ex.getMessage().contains("400"));
        assertEquals(1, httpEngine.getRequestCount());
    }

    @Test
    void shouldRetryOn504Timeout() {
        httpEngine.enqueueJson(504, "Gateway timeout");
        httpEngine.enqueueJson(200, "{\"text\":\"ok\",\"language\":\"en\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        assertEquals("ok", result.text());
        assertEquals(2, httpEngine.getRequestCount());
    }

    @Test
    void shouldNotRetryOnUnauthorized() {
        httpEngine.enqueueJson(401, "Unauthorized");

        Exception ex = assertThrows(Exception.class,
                () -> adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS));
        assertTrue(ex.getMessage().contains("401"));
        assertEquals(1, httpEngine.getRequestCount());
    }

    @Test
    void shouldNormalizeBaseUrlWithTrailingSlash() {
        when(runtimeConfigService.getWhisperSttUrl()).thenReturn(BASE_URL + "/");
        httpEngine.enqueueJson(200, "{\"text\":\"ok\",\"language\":\"en\"}");

        adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        OkHttpMockEngine.CapturedRequest request = httpEngine.takeRequest();
        assertNotNull(request);
        assertTrue(request.target().startsWith("/v1/audio/transcriptions"));
        assertFalse(request.target().contains("//v1"));
    }

    @Test
    void shouldFailWhenSuccessfulResponseHasEmptyBody() {
        httpEngine.enqueueText(200, "", APPLICATION_JSON);

        Exception ex = assertThrows(Exception.class,
                () -> adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS));
        assertTrue(ex.getMessage().contains("No content to map")
                || ex.getMessage().contains("MismatchedInputException"));
    }

    @Test
    void shouldFailWhenResponseIsMalformedJson() {
        httpEngine.enqueueText(200, "this is not json", APPLICATION_JSON);

        Exception ex = assertThrows(Exception.class,
                () -> adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS));
        assertTrue(ex.getMessage().contains("Unrecognized token")
                || ex.getMessage().contains("Unexpected character"));
    }

    @Test
    void shouldDefaultToUnknownLanguageWhenLanguageIsEmptyString() {
        httpEngine.enqueueJson(200, "{\"text\":\"Hello\",\"language\":\"\"}");

        VoicePort.TranscriptionResult result = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);

        assertEquals("Hello", result.text());
        assertEquals("", result.language());
    }
}

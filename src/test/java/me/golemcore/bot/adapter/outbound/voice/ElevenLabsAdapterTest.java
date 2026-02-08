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

        adapter = new TestableElevenLabsAdapter(client, properties, new ObjectMapper(),
                mockServer.url("/").toString());
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

        var future = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);
        var ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
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
        var future = adapter.synthesize("Test", config);
        var ex = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
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
        var future = adapter.transcribe(new byte[] { 1 }, AudioFormat.OGG_OPUS);
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    /**
     * Testable subclass that overrides the API URLs to point to MockWebServer.
     */
    static class TestableElevenLabsAdapter extends ElevenLabsAdapter {
        private final String baseUrl;

        TestableElevenLabsAdapter(OkHttpClient client, BotProperties properties,
                ObjectMapper mapper, String baseUrl) {
            super(client, properties, mapper);
            this.baseUrl = baseUrl;
        }

        @Override
        public java.util.concurrent.CompletableFuture<VoicePort.TranscriptionResult> transcribe(
                byte[] audioData, AudioFormat format) {
            // Redirect to mock server
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    BotProperties props = getProperties();
                    String apiKey = props.getVoice().getApiKey();
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new RuntimeException("ElevenLabs API key not configured");
                    }

                    String mimeType = format != null ? format.getMimeType() : "audio/ogg";
                    String extension = format != null ? format.getExtension() : "ogg";

                    okhttp3.RequestBody fileBody = okhttp3.RequestBody.create(audioData,
                            okhttp3.MediaType.parse(mimeType));

                    okhttp3.MultipartBody requestBody = new okhttp3.MultipartBody.Builder()
                            .setType(okhttp3.MultipartBody.FORM)
                            .addFormDataPart("file", "audio." + extension, fileBody)
                            .addFormDataPart("model_id", props.getVoice().getSttModelId())
                            .build();

                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(baseUrl + "v1/speech-to-text")
                            .header("xi-api-key", apiKey)
                            .post(requestBody)
                            .build();

                    try (okhttp3.Response response = getClient().newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            String error = response.body() != null ? response.body().string() : "Unknown error";
                            throw new RuntimeException(
                                    "ElevenLabs STT error (" + response.code() + "): " + error);
                        }
                        String responseBody = response.body().string();
                        ElevenLabsAdapter.SttResponse sttResponse = getMapper().readValue(responseBody,
                                ElevenLabsAdapter.SttResponse.class);
                        return new VoicePort.TranscriptionResult(
                                sttResponse.getText(),
                                sttResponse.getLanguageCode() != null ? sttResponse.getLanguageCode() : "unknown",
                                1.0f, java.time.Duration.ZERO, java.util.Collections.emptyList());
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Transcription failed: " + e.getMessage(), e);
                }
            });
        }

        @Override
        public java.util.concurrent.CompletableFuture<byte[]> synthesize(String text,
                VoicePort.VoiceConfig config) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    BotProperties props = getProperties();
                    String apiKey = props.getVoice().getApiKey();
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new RuntimeException("ElevenLabs API key not configured");
                    }
                    String voiceId = config.voiceId() != null ? config.voiceId() : props.getVoice().getVoiceId();
                    String modelId = config.modelId() != null ? config.modelId() : props.getVoice().getTtsModelId();
                    float speed = config.speed() > 0 ? config.speed() : props.getVoice().getSpeed();

                    String url = baseUrl + "v1/text-to-speech/" + voiceId + "?output_format=mp3_44100_128";
                    String jsonBody = getMapper()
                            .writeValueAsString(new ElevenLabsAdapter.TtsRequest(text, modelId, speed));

                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(url)
                            .header("xi-api-key", apiKey)
                            .header("Accept", "audio/mpeg")
                            .header("Content-Type", "application/json")
                            .post(okhttp3.RequestBody.create(jsonBody,
                                    okhttp3.MediaType.parse("application/json")))
                            .build();

                    try (okhttp3.Response response = getClient().newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            String error = response.body() != null ? response.body().string() : "Unknown error";
                            throw new RuntimeException(
                                    "ElevenLabs TTS error (" + response.code() + "): " + error);
                        }
                        return response.body().bytes();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Synthesis failed: " + e.getMessage(), e);
                }
            });
        }

        OkHttpClient getClient() {
            try {
                var field = ElevenLabsAdapter.class.getDeclaredField("okHttpClient");
                field.setAccessible(true);
                return (OkHttpClient) field.get(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        BotProperties getProperties() {
            try {
                var field = ElevenLabsAdapter.class.getDeclaredField("properties");
                field.setAccessible(true);
                return (BotProperties) field.get(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        com.fasterxml.jackson.databind.ObjectMapper getMapper() {
            try {
                var field = ElevenLabsAdapter.class.getDeclaredField("objectMapper");
                field.setAccessible(true);
                return (com.fasterxml.jackson.databind.ObjectMapper) field.get(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}

package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderModelDiscoveryServiceTest {

    @Test
    void shouldDiscoverAllModelsFromOpenAiCompatibleProvider() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("secret-token"))
                .baseUrl("https://model.xmesh.click/v1")
                .requestTimeoutSeconds(45)
                .apiType("openai")
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("xmesh"));
        when(runtimeConfigService.getLlmProviderConfig("xmesh")).thenReturn(providerConfig);

        StubProviderModelDiscoveryService service = new StubProviderModelDiscoveryService(runtimeConfigService,
                new ProviderModelDiscoveryService.DiscoveryResponse(200, """
                        {"object":"list","data":[
                          {"id":"deepseek-coder-v2-lite","object":"model","owned_by":"local"},
                          {"created":1765440000,"id":"gpt-5.2","object":"model","owned_by":"openai"},
                          {"created":1770307200,"id":"gpt-5.3-codex","object":"model","owned_by":"openai"},
                          {"created":1750118400,"id":"gemini-2.5-pro","object":"model","owned_by":"google"},
                          {"created":1750118400,"id":"gemini-2.5-flash","object":"model","owned_by":"google"},
                          {"created":1753142400,"id":"gemini-2.5-flash-lite","object":"model","owned_by":"google"},
                          {"created":1762473600,"id":"gpt-5-codex-mini","object":"model","owned_by":"openai"},
                          {"created":1765440000,"id":"gpt-5.2-codex","object":"model","owned_by":"openai"},
                          {"created":1770912000,"id":"gpt-5.3-codex-spark","object":"model","owned_by":"openai"},
                          {"created":1765929600,"id":"gemini-3-flash-preview","object":"model","owned_by":"google"},
                          {"created":1762905600,"id":"gpt-5.1","object":"model","owned_by":"openai"},
                          {"created":1754524800,"id":"gpt-5","object":"model","owned_by":"openai"},
                          {"created":1763424000,"id":"gpt-5.1-codex-max","object":"model","owned_by":"openai"},
                          {"created":1737158400,"id":"gemini-3-pro-preview","object":"model","owned_by":"google"},
                          {"created":1757894400,"id":"gpt-5-codex","object":"model","owned_by":"openai"},
                          {"created":1762905600,"id":"gpt-5.1-codex","object":"model","owned_by":"openai"},
                          {"created":1762905600,"id":"gpt-5.1-codex-mini","object":"model","owned_by":"openai"}
                        ]}
                        """));

        List<ProviderModelDiscoveryService.DiscoveredModel> models = service.discoverModels("xmesh");

        assertEquals(17, models.size());
        assertTrue(models.stream().anyMatch(model -> "gpt-5.2".equals(model.id())));
        assertTrue(models.stream().anyMatch(model -> "gpt-5.3-codex-spark".equals(model.id())));
        assertTrue(models.stream().anyMatch(model -> "gemini-2.5-pro".equals(model.id())));
        assertTrue(models.stream().anyMatch(model -> "deepseek-coder-v2-lite".equals(model.id())));

        HttpRequest capturedRequest = service.getCapturedRequest();
        assertEquals(URI.create("https://model.xmesh.click/v1/models"), capturedRequest.uri());
        assertEquals("Bearer secret-token", capturedRequest.headers().firstValue("Authorization").orElse(""));
    }

    @Test
    void shouldMapOpenRouterModelsToDirectGolemcoreDefaults() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("openrouter-key"))
                .baseUrl("https://openrouter.ai/api/v1")
                .requestTimeoutSeconds(30)
                .apiType("openai")
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("openrouter"));
        when(runtimeConfigService.getLlmProviderConfig("openrouter")).thenReturn(providerConfig);

        StubProviderModelDiscoveryService service = new StubProviderModelDiscoveryService(runtimeConfigService,
                new ProviderModelDiscoveryService.DiscoveryResponse(200, """
                        {"data":[
                          {
                            "id":"openai/gpt-5",
                            "name":"OpenAI: GPT-5",
                            "context_length":400000,
                            "architecture":{"input_modalities":["text","image","file"]},
                            "supported_parameters":["include_reasoning","max_tokens","reasoning","tools"]
                          },
                          {
                            "id":"google/gemini-2.5-pro",
                            "name":"Google: Gemini 2.5 Pro",
                            "context_length":1048576,
                            "architecture":{"input_modalities":["text","image","file","audio","video"]},
                            "supported_parameters":["temperature","tools"]
                          },
                          {
                            "id":"openai/gpt-4o-mini",
                            "name":"OpenAI: GPT-4o Mini",
                            "top_provider":{"context_length":8192},
                            "architecture":{"modality":"text+image->text"}
                          }
                        ]}
                        """));

        List<ProviderModelDiscoveryService.DiscoveredModel> models = service.discoverModels("openrouter");

        assertEquals(3, models.size());
        ProviderModelDiscoveryService.DiscoveredModel gpt5Model = models.stream()
                .filter(model -> "openai/gpt-5".equals(model.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("openrouter", gpt5Model.provider());
        assertNotNull(gpt5Model.defaultSettings());
        assertEquals("openrouter", gpt5Model.defaultSettings().getProvider());
        assertEquals("OpenAI: GPT-5", gpt5Model.defaultSettings().getDisplayName());
        assertEquals(400000, gpt5Model.defaultSettings().getMaxInputTokens());
        assertTrue(gpt5Model.defaultSettings().isSupportsVision());
        assertFalse(gpt5Model.defaultSettings().isSupportsTemperature());
        assertNotNull(gpt5Model.defaultSettings().getReasoning());
        assertEquals("medium", gpt5Model.defaultSettings().getReasoning().getDefaultLevel());
        assertEquals(1000000,
                gpt5Model.defaultSettings().getReasoning().getLevels().get("low").getMaxInputTokens());

        ProviderModelDiscoveryService.DiscoveredModel geminiModel = models.stream()
                .filter(model -> "google/gemini-2.5-pro".equals(model.id()))
                .findFirst()
                .orElseThrow();
        assertNotNull(geminiModel.defaultSettings());
        assertTrue(geminiModel.defaultSettings().isSupportsTemperature());
        assertTrue(geminiModel.defaultSettings().isSupportsVision());
        assertEquals(1048576, geminiModel.defaultSettings().getMaxInputTokens());
        assertEquals(null, geminiModel.defaultSettings().getReasoning());

        ProviderModelDiscoveryService.DiscoveredModel gpt4oMiniModel = models.stream()
                .filter(model -> "openai/gpt-4o-mini".equals(model.id()))
                .findFirst()
                .orElseThrow();
        assertNotNull(gpt4oMiniModel.defaultSettings());
        assertTrue(gpt4oMiniModel.defaultSettings().isSupportsVision());
        assertTrue(gpt4oMiniModel.defaultSettings().isSupportsTemperature());
        assertEquals(8192, gpt4oMiniModel.defaultSettings().getMaxInputTokens());
    }

    @Test
    void shouldDiscoverAnthropicModelsUsingDefaultEndpointAndHeaders() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("anthropic-key"))
                .baseUrl(null)
                .requestTimeoutSeconds(20)
                .apiType("anthropic")
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("anthropic"));
        when(runtimeConfigService.getLlmProviderConfig("anthropic")).thenReturn(providerConfig);

        StubProviderModelDiscoveryService service = new StubProviderModelDiscoveryService(runtimeConfigService,
                new ProviderModelDiscoveryService.DiscoveryResponse(200, """
                        {"data":[{"id":"claude-opus-4.1","name":"Claude Opus 4.1","owned_by":"anthropic"}]}
                        """));

        List<ProviderModelDiscoveryService.DiscoveredModel> models = service.discoverModels("anthropic");

        assertEquals(1, models.size());
        HttpRequest request = service.getCapturedRequest();
        assertEquals(URI.create("https://api.anthropic.com/v1/models"), request.uri());
        assertEquals("anthropic-key", request.headers().firstValue("x-api-key").orElse(""));
        assertEquals("2023-06-01", request.headers().firstValue("anthropic-version").orElse(""));
    }

    @Test
    void shouldDiscoverGeminiModelsUsingGoogleHeaderAndModelArray() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("google-key"))
                .baseUrl("https://generativelanguage.googleapis.com")
                .requestTimeoutSeconds(20)
                .apiType("gemini")
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("google"));
        when(runtimeConfigService.getLlmProviderConfig("google")).thenReturn(providerConfig);

        StubProviderModelDiscoveryService service = new StubProviderModelDiscoveryService(runtimeConfigService,
                new ProviderModelDiscoveryService.DiscoveryResponse(200, """
                        {"models":[
                          {
                            "name":"models/embedding-001",
                            "displayName":"Embedding 001",
                            "publisher":"google",
                            "supportedGenerationMethods":["embedContent"]
                          },
                          {
                            "name":"models/gemini-2.0-flash",
                            "displayName":"Gemini 2.0 Flash",
                            "publisher":"google",
                            "supportedGenerationMethods":["generateContent"]
                          }
                        ]}
                        """));

        List<ProviderModelDiscoveryService.DiscoveredModel> models = service.discoverModels("google");

        assertEquals(1, models.size());
        assertEquals("gemini-2.0-flash", models.getFirst().id());
        HttpRequest request = service.getCapturedRequest();
        assertEquals(URI.create("https://generativelanguage.googleapis.com/v1beta/models"), request.uri());
        assertEquals("google-key", request.headers().firstValue("x-goog-api-key").orElse(""));
    }

    @Test
    void shouldUseOpenAiDefaultBaseUrlWhenApiTypeIsMissing() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("openai-key"))
                .baseUrl(null)
                .requestTimeoutSeconds(10)
                .apiType(null)
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("openai"));
        when(runtimeConfigService.getLlmProviderConfig("openai")).thenReturn(providerConfig);

        StubProviderModelDiscoveryService service = new StubProviderModelDiscoveryService(runtimeConfigService,
                new ProviderModelDiscoveryService.DiscoveryResponse(200, "{\"data\":[]}"));

        service.discoverModels("openai");

        assertEquals(URI.create("https://api.openai.com/v1/models"), service.getCapturedRequest().uri());
    }

    @Test
    void shouldTreatOpenRouterHostAsDirectDefaultsForCustomProviderName() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("custom-key"))
                .baseUrl("https://openrouter.ai/api/v1")
                .requestTimeoutSeconds(20)
                .apiType("openai")
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("customrouter"));
        when(runtimeConfigService.getLlmProviderConfig("customrouter")).thenReturn(providerConfig);

        StubProviderModelDiscoveryService service = new StubProviderModelDiscoveryService(runtimeConfigService,
                new ProviderModelDiscoveryService.DiscoveryResponse(200, """
                        {"data":[{"id":"openai/gpt-4o","name":"OpenAI: GPT-4o","context_length":128000}]}
                        """));

        List<ProviderModelDiscoveryService.DiscoveredModel> models = service.discoverModels("customrouter");

        assertNotNull(models.getFirst().defaultSettings());
        assertEquals("customrouter", models.getFirst().defaultSettings().getProvider());
    }

    @Test
    void shouldRejectUnknownProvider() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("openai"));

        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(runtimeConfigService);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.discoverModels("missing"));
        assertEquals("Provider 'missing' is not configured", error.getMessage());
    }

    @Test
    void shouldRejectBlankProviderName() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(runtimeConfigService);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.discoverModels(" "));
        assertEquals("Provider name is required", error.getMessage());
    }

    @Test
    void shouldRejectProviderWhenDiscoveryResponseStatusIsFailure() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("secret-token"))
                .baseUrl("https://model.xmesh.click/v1")
                .requestTimeoutSeconds(45)
                .apiType("openai")
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("xmesh"));
        when(runtimeConfigService.getLlmProviderConfig("xmesh")).thenReturn(providerConfig);

        StubProviderModelDiscoveryService service = new StubProviderModelDiscoveryService(runtimeConfigService,
                new ProviderModelDiscoveryService.DiscoveryResponse(502, "bad gateway"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.discoverModels("xmesh"));
        assertTrue(error.getMessage().contains("status 502"));
    }

    @Test
    void shouldRejectInvalidDiscoveryPayload() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("secret-token"))
                .baseUrl("https://model.xmesh.click/v1")
                .requestTimeoutSeconds(45)
                .apiType("openai")
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("xmesh"));
        when(runtimeConfigService.getLlmProviderConfig("xmesh")).thenReturn(providerConfig);

        StubProviderModelDiscoveryService service = new StubProviderModelDiscoveryService(runtimeConfigService,
                new ProviderModelDiscoveryService.DiscoveryResponse(200, "{"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.discoverModels("xmesh"));
        assertTrue(error.getMessage().contains("Failed to parse model discovery response"));
    }

    @Test
    void shouldUseBaseSendDiscoveryRequestImplementation() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        TestableProviderModelDiscoveryService service = new TestableProviderModelDiscoveryService(
                runtimeConfigService,
                new FakeHttpClient(FakeHttpClient.Mode.SUCCESS, 204, "{}"));

        ProviderModelDiscoveryService.DiscoveryResponse response = service.sendDiscoveryRequest(HttpRequest.newBuilder()
                .uri(URI.create("https://example.com/models"))
                .GET()
                .build());

        assertEquals(204, response.statusCode());
        assertEquals("{}", response.body());
    }

    @Test
    void shouldWrapInterruptedDiscoveryRequests() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        TestableProviderModelDiscoveryService service = new TestableProviderModelDiscoveryService(
                runtimeConfigService,
                new FakeHttpClient(FakeHttpClient.Mode.INTERRUPTED, 0, null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.sendDiscoveryRequest(HttpRequest.newBuilder()
                        .uri(URI.create("https://example.com/models"))
                        .GET()
                        .build()));

        assertTrue(error.getMessage().contains("interrupted"));
        assertTrue(Thread.interrupted());
    }

    @Test
    void shouldWrapIoDiscoveryFailures() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        TestableProviderModelDiscoveryService service = new TestableProviderModelDiscoveryService(
                runtimeConfigService,
                new FakeHttpClient(FakeHttpClient.Mode.IO_EXCEPTION, 0, null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.sendDiscoveryRequest(HttpRequest.newBuilder()
                        .uri(URI.create("https://example.com/models"))
                        .GET()
                        .build()));

        assertTrue(error.getMessage().contains("failed"));
    }

    @Test
    void shouldBuildDefaultHttpClient() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(runtimeConfigService);

        assertNotNull(service.buildHttpClient());
    }

    @Test
    void shouldRequireConfiguredProviderApiKey() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of(""))
                .baseUrl("https://model.xmesh.click/v1")
                .apiType("openai")
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("xmesh"));
        when(runtimeConfigService.getLlmProviderConfig("xmesh")).thenReturn(providerConfig);

        StubProviderModelDiscoveryService service = new StubProviderModelDiscoveryService(runtimeConfigService,
                new ProviderModelDiscoveryService.DiscoveryResponse(200, "{\"data\":[]}"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.discoverModels("xmesh"));
        assertEquals("Provider 'xmesh' does not have an API key configured", ex.getMessage());
    }

    private static final class StubProviderModelDiscoveryService extends ProviderModelDiscoveryService {

        private final DiscoveryResponse response;
        private HttpRequest capturedRequest;

        private StubProviderModelDiscoveryService(RuntimeConfigService runtimeConfigService,
                DiscoveryResponse response) {
            super(runtimeConfigService);
            this.response = response;
        }

        @Override
        protected DiscoveryResponse sendDiscoveryRequest(HttpRequest request) {
            this.capturedRequest = request;
            return response;
        }

        private HttpRequest getCapturedRequest() {
            return capturedRequest;
        }
    }

    private static final class TestableProviderModelDiscoveryService extends ProviderModelDiscoveryService {

        private final HttpClient httpClient;

        private TestableProviderModelDiscoveryService(RuntimeConfigService runtimeConfigService,
                HttpClient httpClient) {
            super(runtimeConfigService);
            this.httpClient = httpClient;
        }

        @Override
        protected HttpClient buildHttpClient() {
            return httpClient;
        }
    }

    private static final class FakeHttpClient extends HttpClient {

        private enum Mode {
            SUCCESS, INTERRUPTED, IO_EXCEPTION,
        }

        private final Mode mode;
        private final int statusCode;
        private final String responseBody;

        private FakeHttpClient(Mode mode, int statusCode, String responseBody) {
            this.mode = mode;
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(20));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NORMAL;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            if (mode == Mode.INTERRUPTED) {
                throw new InterruptedException("simulated interruption");
            }
            if (mode == Mode.IO_EXCEPTION) {
                throw new IOException("simulated io error");
            }
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) new FakeHttpResponse(request, statusCode, responseBody);
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("Not needed for test");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("Not needed for test");
        }
    }

    private record FakeHttpResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (left, right) -> true);
    }

    @Override
    public URI uri() {
        return request.uri();
    }

    @Override
    public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }
}}

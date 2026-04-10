package me.golemcore.bot.adapter.outbound.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import me.golemcore.bot.port.outbound.ProviderModelDiscoveryPort;
import org.junit.jupiter.api.Test;

class HttpProviderModelDiscoveryAdapterTest {

    @Test
    void shouldUseBearerHeaderAndParseOpenAiLikePayload() {
        FakeHttpClient httpClient = new FakeHttpClient(FakeHttpClient.Mode.SUCCESS, 200, """
                {"data":[{"id":"gpt-5","name":"GPT-5","owned_by":"openai","context_length":128000}]}
                """);
        TestHttpProviderModelDiscoveryAdapter adapter = new TestHttpProviderModelDiscoveryAdapter(httpClient);

        ProviderModelDiscoveryPort.DiscoveryResponse response = adapter
                .discover(new ProviderModelDiscoveryPort.DiscoveryRequest(
                        URI.create("https://api.openai.com/v1/models"),
                        Duration.ofSeconds(10),
                        "openai-key",
                        "golemcore-model-discovery",
                        ProviderModelDiscoveryPort.AuthMode.BEARER));

        assertEquals(200, response.statusCode());
        assertEquals(1, response.documents().size());
        assertEquals("gpt-5", response.documents().get(0).id());
        assertEquals("Bearer openai-key",
                httpClient.getCapturedRequest().headers().firstValue("Authorization").orElse(""));
    }

    @Test
    void shouldUseAnthropicHeaders() {
        FakeHttpClient httpClient = new FakeHttpClient(FakeHttpClient.Mode.SUCCESS, 200, "{\"data\":[]}");
        TestHttpProviderModelDiscoveryAdapter adapter = new TestHttpProviderModelDiscoveryAdapter(httpClient);

        adapter.discover(new ProviderModelDiscoveryPort.DiscoveryRequest(
                URI.create("https://api.anthropic.com/v1/models"),
                Duration.ofSeconds(20),
                "anthropic-key",
                "golemcore-model-discovery",
                ProviderModelDiscoveryPort.AuthMode.ANTHROPIC));

        assertEquals("anthropic-key", httpClient.getCapturedRequest().headers().firstValue("x-api-key").orElse(""));
        assertEquals("2023-06-01",
                httpClient.getCapturedRequest().headers().firstValue("anthropic-version").orElse(""));
    }

    @Test
    void shouldUseGoogleHeaderAndParseGeminiPayload() {
        FakeHttpClient httpClient = new FakeHttpClient(FakeHttpClient.Mode.SUCCESS, 200,
                """
                        {"models":[{"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash","publisher":"google","supportedGenerationMethods":["generateContent"]}]}
                        """);
        TestHttpProviderModelDiscoveryAdapter adapter = new TestHttpProviderModelDiscoveryAdapter(httpClient);

        ProviderModelDiscoveryPort.DiscoveryResponse response = adapter
                .discover(new ProviderModelDiscoveryPort.DiscoveryRequest(
                        URI.create("https://generativelanguage.googleapis.com/v1beta/models"),
                        Duration.ofSeconds(20),
                        "google-key",
                        "golemcore-model-discovery",
                        ProviderModelDiscoveryPort.AuthMode.GOOGLE));

        assertEquals(1, response.documents().size());
        assertEquals(ProviderModelDiscoveryPort.DocumentKind.GEMINI, response.documents().get(0).kind());
        assertEquals("google-key",
                httpClient.getCapturedRequest().headers().firstValue("x-goog-api-key").orElse(""));
    }

    @Test
    void shouldWrapInterruptedRequests() {
        TestHttpProviderModelDiscoveryAdapter adapter = new TestHttpProviderModelDiscoveryAdapter(
                new FakeHttpClient(FakeHttpClient.Mode.INTERRUPTED, 0, null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.discover(new ProviderModelDiscoveryPort.DiscoveryRequest(
                        URI.create("https://example.com/models"),
                        Duration.ofSeconds(20),
                        "key",
                        "agent",
                        ProviderModelDiscoveryPort.AuthMode.BEARER)));

        assertEquals(true, error.getMessage().contains("interrupted"));
        assertEquals(true, Thread.interrupted());
    }

    @Test
    void shouldWrapIoFailures() {
        TestHttpProviderModelDiscoveryAdapter adapter = new TestHttpProviderModelDiscoveryAdapter(
                new FakeHttpClient(FakeHttpClient.Mode.IO_EXCEPTION, 0, null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.discover(new ProviderModelDiscoveryPort.DiscoveryRequest(
                        URI.create("https://example.com/models"),
                        Duration.ofSeconds(20),
                        "key",
                        "agent",
                        ProviderModelDiscoveryPort.AuthMode.BEARER)));

        assertEquals(true, error.getMessage().contains("failed"));
    }

    @Test
    void shouldRejectInvalidDiscoveryPayload() {
        TestHttpProviderModelDiscoveryAdapter adapter = new TestHttpProviderModelDiscoveryAdapter(
                new FakeHttpClient(FakeHttpClient.Mode.SUCCESS, 200, "{"));

        assertThrows(IllegalStateException.class,
                () -> adapter.discover(new ProviderModelDiscoveryPort.DiscoveryRequest(
                        URI.create("https://example.com/models"),
                        Duration.ofSeconds(20),
                        "key",
                        "agent",
                        ProviderModelDiscoveryPort.AuthMode.BEARER)));
    }

    private static final class TestHttpProviderModelDiscoveryAdapter extends HttpProviderModelDiscoveryAdapter {

        private final HttpClient httpClient;

        private TestHttpProviderModelDiscoveryAdapter(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        protected HttpClient buildHttpClient(ProviderModelDiscoveryPort.DiscoveryRequest request) {
            return httpClient;
        }
    }

    private static final class FakeHttpClient extends HttpClient {

        private enum Mode {
            SUCCESS, INTERRUPTED, IO_EXCEPTION
        }

        private final Mode mode;
        private final int statusCode;
        private final String responseBody;
        private HttpRequest capturedRequest;

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
            this.capturedRequest = request;
            if (mode == Mode.INTERRUPTED) {
                throw new InterruptedException("interrupted");
            }
            if (mode == Mode.IO_EXCEPTION) {
                throw new IOException("io failure");
            }
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) new FakeHttpResponse(request, statusCode, responseBody);
            return response;
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        private HttpRequest getCapturedRequest() {
            return capturedRequest;
        }
    }

    private record FakeHttpResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (name, value) -> true);
    }

    @Override
    public String body() {
        return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public URI uri() {
        return request.uri();
    }

    @Override
    public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
    }
}}

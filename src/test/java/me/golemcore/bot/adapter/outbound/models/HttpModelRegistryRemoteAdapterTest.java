package me.golemcore.bot.adapter.outbound.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

@SuppressWarnings({ "PMD.CloseResource", "PMD.TestClassWithoutTestCases" })
class HttpModelRegistryRemoteAdapterTest {

    @Test
    void shouldReturnBodyOnSuccess() {
        FakeHttpClient httpClient = new FakeHttpClient(FakeHttpClient.Mode.SUCCESS, 200, "{\"ok\":true}");
        TestHttpModelRegistryRemoteAdapter adapter = new TestHttpModelRegistryRemoteAdapter(httpClient);

        String body = adapter.fetchText(URI.create("https://example.com/models/gpt-5.1.json"));

        assertEquals("{\"ok\":true}", body);
        assertEquals("golemcore-bot-model-registry",
                httpClient.getCapturedRequest().headers().firstValue("User-Agent").orElse(""));
    }

    @Test
    void shouldReturnNullOnNotFound() {
        TestHttpModelRegistryRemoteAdapter adapter = new TestHttpModelRegistryRemoteAdapter(
                new FakeHttpClient(FakeHttpClient.Mode.SUCCESS, 404, ""));

        assertNull(adapter.fetchText(URI.create("https://example.com/models/missing.json")));
    }

    @Test
    void shouldWrapInterruptedRequests() {
        TestHttpModelRegistryRemoteAdapter adapter = new TestHttpModelRegistryRemoteAdapter(
                new FakeHttpClient(FakeHttpClient.Mode.INTERRUPTED, 0, null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.fetchText(URI.create("https://example.com/models/gpt-5.1.json")));

        assertEquals(true, error.getMessage().contains("Interrupted"));
        assertEquals(true, Thread.interrupted());
    }

    @Test
    void shouldWrapIoFailures() {
        TestHttpModelRegistryRemoteAdapter adapter = new TestHttpModelRegistryRemoteAdapter(
                new FakeHttpClient(FakeHttpClient.Mode.IO_EXCEPTION, 0, null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.fetchText(URI.create("https://example.com/models/gpt-5.1.json")));

        assertEquals(true, error.getMessage().contains("Failed to fetch"));
    }

    private static final class TestHttpModelRegistryRemoteAdapter extends HttpModelRegistryRemoteAdapter {

        private final HttpClient httpClient;

        private TestHttpModelRegistryRemoteAdapter(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        protected HttpClient buildHttpClient() {
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
            return Optional.of(Duration.ofSeconds(30));
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

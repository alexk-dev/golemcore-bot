package me.golemcore.bot.adapter.outbound.gonka;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.Test;

class GonkaEndpointResolutionAdapterTest {

    @Test
    void shouldUseExplicitConfiguredEndpointBeforeSourceDiscovery() {
        GonkaEndpointResolutionAdapter adapter = new GonkaEndpointResolutionAdapter();

        GonkaEndpointResolver.GonkaResolvedEndpoint endpoint = adapter.resolve(
                new GonkaEndpointResolver.GonkaEndpointResolutionRequest(
                        URI.create("https://node3.gonka.ai"),
                        List.of(new GonkaEndpointResolver.GonkaConfiguredEndpoint(
                                "https://host.example", "gonka1provider")),
                        Duration.ofSeconds(5)));

        assertEquals("https://host.example/v1", endpoint.url());
        assertEquals("gonka1provider", endpoint.transferAddress());
    }

    @Test
    void shouldResolveEndpointFromParticipantsAndAllowedTransferAgents() {
        FakeHttpClient httpClient = new FakeHttpClient(Map.of(
                "https://node3.gonka.ai/chain-api/productscience/inference/inference/params",
                """
                        {"params":{"transfer_agent_access_params":{"allowed_transfer_addresses":["gonka1allowed"]}}}
                        """,
                "https://node3.gonka.ai/v1/epochs/current/participants",
                """
                        {"excluded_participants":[],"active_participants":{"participants":[
                          {"index":"gonka1blocked","inference_url":"https://blocked.example"},
                          {"index":"gonka1allowed","inference_url":"https://allowed.example"}
                        ]}}
                        """,
                "https://allowed.example/v1/identity",
                """
                        {"data":{"delegate_ta":{"https://delegate.example":"gonka1delegate"}}}
                        """));
        TestGonkaEndpointResolutionAdapter adapter = new TestGonkaEndpointResolutionAdapter(httpClient);

        GonkaEndpointResolver.GonkaResolvedEndpoint endpoint = adapter.resolve(
                new GonkaEndpointResolver.GonkaEndpointResolutionRequest(
                        URI.create("https://node3.gonka.ai"),
                        List.of(),
                        Duration.ofSeconds(5)));

        assertEquals("https://delegate.example/v1", endpoint.url());
        assertEquals("gonka1allowed", endpoint.transferAddress());
    }

    private static final class TestGonkaEndpointResolutionAdapter extends GonkaEndpointResolutionAdapter {

        private final HttpClient httpClient;

        private TestGonkaEndpointResolutionAdapter(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        protected HttpClient buildHttpClient(Duration timeout) {
            return httpClient;
        }
    }

    private static final class FakeHttpClient extends HttpClient {

        private final Map<String, String> responses;

        private FakeHttpClient(Map<String, String> responses) {
            this.responses = responses;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
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
            String body = responses.get(request.uri().toString());
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) new FakeHttpResponse(request, body != null ? 200 : 404,
                    body != null ? body : "{}");
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
    }

    private record FakeHttpResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {

    @Override
    public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (name, value) -> true);
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
        return Optional.empty();
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

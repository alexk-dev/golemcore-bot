package me.golemcore.bot.adapter.outbound.gonka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class GonkaSigningHttpClientBuilderTest {

    private static final String PRIVATE_KEY = "0000000000000000000000000000000000000000000000000000000000000001";

    @Test
    void shouldReplaceBearerHeaderWithGonkaSignatureHeaders() {
        CapturingHttpClient delegateClient = new CapturingHttpClient();
        HttpClientBuilder delegateBuilder = new StubHttpClientBuilder(delegateClient);
        GonkaRequestSigner signer = new GonkaRequestSigner(
                Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC));
        GonkaSigningHttpClientBuilder builder = new GonkaSigningHttpClientBuilder(
                delegateBuilder, signer, PRIVATE_KEY, "gonka1requester", "gonka1provideraddress");

        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://node3.gonka.ai/v1/chat/completions")
                .addHeader("Authorization", "Bearer mock-api-key")
                .addHeader("Content-Type", "application/json")
                .body("{\"messages\":[]}")
                .build();

        builder.build().execute(request);

        assertEquals("gonka1requester",
                delegateClient.lastRequest.headers().get("X-Requester-Address").getFirst());
        assertEquals("1700000000000000000",
                delegateClient.lastRequest.headers().get("X-Timestamp").getFirst());
        String authorization = delegateClient.lastRequest.headers().get("Authorization").getFirst();
        assertEquals(false, authorization.startsWith("Bearer "));
        assertEquals(List.of("application/json"), delegateClient.lastRequest.headers().get("Content-Type"));
    }

    private static final class StubHttpClientBuilder implements HttpClientBuilder {

        private final HttpClient httpClient;
        private Duration connectTimeout;
        private Duration readTimeout;

        private StubHttpClientBuilder(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public Duration connectTimeout() {
            return connectTimeout;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            connectTimeout = timeout;
            return this;
        }

        @Override
        public Duration readTimeout() {
            return readTimeout;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            readTimeout = timeout;
            return this;
        }

        @Override
        public HttpClient build() {
            return httpClient;
        }
    }

    private static final class CapturingHttpClient implements HttpClient {

        private HttpRequest lastRequest;

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            lastRequest = request;
            return SuccessfulHttpResponse.builder().statusCode(200).body("{}").build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            lastRequest = request;
        }
    }
}

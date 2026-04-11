package me.golemcore.bot.adapter.outbound.gonka;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GonkaSigningHttpClientBuilder implements HttpClientBuilder {

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_REQUESTER_ADDRESS = "X-Requester-Address";
    private static final String HEADER_TIMESTAMP = "X-Timestamp";

    private final HttpClientBuilder delegate;
    private final GonkaRequestSigner signer;
    private final String privateKey;
    private final String requesterAddress;
    private final String transferAddress;

    public GonkaSigningHttpClientBuilder(HttpClientBuilder delegate, GonkaRequestSigner signer,
            String privateKey, String requesterAddress, String transferAddress) {
        this.delegate = delegate;
        this.signer = signer;
        this.privateKey = privateKey;
        this.requesterAddress = requesterAddress;
        this.transferAddress = transferAddress;
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        delegate.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        delegate.readTimeout(timeout);
        return this;
    }

    @Override
    public HttpClient build() {
        return new GonkaSigningHttpClient(delegate.build(), signer, privateKey, requesterAddress, transferAddress);
    }

    static final class GonkaSigningHttpClient implements HttpClient {

        private final HttpClient delegate;
        private final GonkaRequestSigner signer;
        private final String privateKey;
        private final String requesterAddress;
        private final String transferAddress;

        private GonkaSigningHttpClient(HttpClient delegate, GonkaRequestSigner signer,
                String privateKey, String requesterAddress, String transferAddress) {
            this.delegate = delegate;
            this.signer = signer;
            this.privateKey = privateKey;
            this.requesterAddress = requesterAddress;
            this.transferAddress = transferAddress;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            return delegate.execute(sign(request));
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            delegate.execute(sign(request), parser, listener);
        }

        private HttpRequest sign(HttpRequest request) {
            GonkaRequestSigner.SignedRequest signedRequest = signer.sign(
                    request.body(),
                    privateKey,
                    requesterAddress,
                    transferAddress);
            return HttpRequest.builder()
                    .method(request.method())
                    .url(request.url())
                    .headers(withGonkaHeaders(request.headers(), signedRequest))
                    .formDataFields(request.formDataFields())
                    .formDataFiles(request.formDataFiles())
                    .body(request.body())
                    .build();
        }

        private Map<String, List<String>> withGonkaHeaders(Map<String, List<String>> originalHeaders,
                GonkaRequestSigner.SignedRequest signedRequest) {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            if (originalHeaders != null) {
                for (Map.Entry<String, List<String>> entry : originalHeaders.entrySet()) {
                    headers.put(entry.getKey(),
                            entry.getValue() != null ? new ArrayList<>(entry.getValue()) : List.of());
                }
            }
            headers.put(HEADER_AUTHORIZATION, List.of(signedRequest.authorization()));
            headers.put(HEADER_REQUESTER_ADDRESS, List.of(signedRequest.requesterAddress()));
            headers.put(HEADER_TIMESTAMP, List.of(signedRequest.timestamp()));
            return headers;
        }
    }
}

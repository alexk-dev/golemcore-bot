package me.golemcore.bot.adapter.outbound.gonka;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import java.time.Duration;
import java.util.List;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import org.springframework.stereotype.Component;

@Component
public class GonkaHttpClientBuilderFactory {

    private final GonkaEndpointResolver endpointResolutionPort;
    private final GonkaRequestSigner signer;

    public GonkaHttpClientBuilderFactory(GonkaEndpointResolver endpointResolutionPort,
            GonkaRequestSigner signer) {
        this.endpointResolutionPort = endpointResolutionPort;
        this.signer = signer;
    }

    public ResolvedGonkaHttpClientBuilder create(RuntimeConfig.LlmProviderConfig config, Duration timeout) {
        String privateKey = Secret.valueOrEmpty(config.getApiKey());
        if (privateKey.isBlank()) {
            throw new IllegalStateException("Missing apiKey for Gonka provider in runtime config");
        }
        GonkaEndpointResolver.GonkaResolvedEndpoint endpoint = endpointResolutionPort.resolve(
                new GonkaEndpointResolver.GonkaEndpointResolutionRequest(
                        config.getSourceUri(),
                        toConfiguredEndpoints(config.getEndpoints()),
                        timeout));
        HttpClientBuilder baseBuilder = HttpClientBuilderLoader.loadHttpClientBuilder();
        if (baseBuilder == null) {
            baseBuilder = instantiateJdkHttpClientBuilder();
        }
        baseBuilder.connectTimeout(timeout);
        baseBuilder.readTimeout(timeout);
        HttpClientBuilder signingBuilder = new GonkaSigningHttpClientBuilder(
                baseBuilder,
                signer,
                privateKey,
                config.getGonkaAddress(),
                endpoint.transferAddress());
        return new ResolvedGonkaHttpClientBuilder(signingBuilder, endpoint.url());
    }

    private List<GonkaEndpointResolver.GonkaConfiguredEndpoint> toConfiguredEndpoints(
            List<RuntimeConfig.GonkaEndpointConfig> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return List.of();
        }
        return endpoints.stream()
                .map(endpoint -> new GonkaEndpointResolver.GonkaConfiguredEndpoint(
                        endpoint.getUrl(), endpoint.getTransferAddress()))
                .toList();
    }

    private HttpClientBuilder instantiateJdkHttpClientBuilder() {
        try {
            Class<?> builderClass = Class.forName("dev.langchain4j.http.client.jdk.JdkHttpClientBuilder");
            return (HttpClientBuilder) builderClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("No HttpClientBuilder implementation available for Gonka provider",
                    exception);
        }
    }

    public record ResolvedGonkaHttpClientBuilder(HttpClientBuilder httpClientBuilder, String baseUrl) {
    }
}

package me.golemcore.bot.adapter.outbound.models;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import me.golemcore.bot.port.outbound.ModelRegistryRemotePort;
import org.springframework.stereotype.Component;

@Component
public class HttpModelRegistryRemoteAdapter implements ModelRegistryRemotePort {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String GITHUB_USER_AGENT = "golemcore-bot-model-registry";

    @Override
    public String fetchText(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", GITHUB_USER_AGENT)
                .build();
        try {
            HttpResponse<String> response = buildHttpClient().send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            throw new IllegalStateException(
                    "Model registry request failed with status " + response.statusCode() + " for " + uri);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching model registry config: " + uri, exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to fetch model registry config: " + uri, exception);
        }
    }

    protected HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}

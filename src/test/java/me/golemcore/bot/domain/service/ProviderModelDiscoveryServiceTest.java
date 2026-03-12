package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

package me.golemcore.bot.application.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.ProviderModelDiscoveryPort;
import org.junit.jupiter.api.Test;

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

        StubProviderModelDiscoveryPort discoveryPort = new StubProviderModelDiscoveryPort(
                new ProviderModelDiscoveryPort.DiscoveryResponse(
                        200,
                        List.of(
                                openAiDocument("deepseek-coder-v2-lite", "deepseek-coder-v2-lite", "local", null,
                                        null,
                                        null, 0, 0),
                                openAiDocument("gpt-5.2", "gpt-5.2", "openai", List.of("text"), null,
                                        List.of("reasoning"),
                                        0, 0),
                                openAiDocument("gpt-5.3-codex-spark", "gpt-5.3-codex-spark", "openai",
                                        List.of("text"), null,
                                        List.of("reasoning"), 0, 0),
                                openAiDocument("gemini-2.5-pro", "gemini-2.5-pro", "google", List.of("text", "image"),
                                        null,
                                        List.of("temperature"), 0, 0),
                                openAiDocument("gemini-2.5-flash", "gemini-2.5-flash", "google",
                                        List.of("text", "image"), null, List.of("temperature"), 0, 0),
                                openAiDocument("gemini-2.5-flash-lite", "gemini-2.5-flash-lite", "google",
                                        List.of("text", "image"), null, List.of("temperature"), 0, 0),
                                openAiDocument("gpt-5-codex-mini", "gpt-5-codex-mini", "openai", List.of("text"),
                                        null,
                                        List.of("reasoning"), 0, 0),
                                openAiDocument("gpt-5.2-codex", "gpt-5.2-codex", "openai", List.of("text"), null,
                                        List.of("reasoning"), 0, 0),
                                openAiDocument("gpt-5.1", "gpt-5.1", "openai", List.of("text"), null,
                                        List.of("reasoning"),
                                        0, 0),
                                openAiDocument("gpt-5", "gpt-5", "openai", List.of("text"), null,
                                        List.of("reasoning"), 0,
                                        0),
                                openAiDocument("gpt-5.1-codex-max", "gpt-5.1-codex-max", "openai", List.of("text"),
                                        null,
                                        List.of("reasoning"), 0, 0),
                                openAiDocument("gemini-3-pro-preview", "gemini-3-pro-preview", "google",
                                        List.of("text", "image"), null, List.of("temperature"), 0, 0),
                                openAiDocument("gpt-5-codex", "gpt-5-codex", "openai", List.of("text"), null,
                                        List.of("reasoning"), 0, 0),
                                openAiDocument("gpt-5.1-codex", "gpt-5.1-codex", "openai", List.of("text"), null,
                                        List.of("reasoning"), 0, 0),
                                openAiDocument("gpt-5.1-codex-mini", "gpt-5.1-codex-mini", "openai",
                                        List.of("text"), null, List.of("reasoning"), 0, 0),
                                openAiDocument("gemini-3-flash-preview", "gemini-3-flash-preview", "google",
                                        List.of("text", "image"), null, List.of("temperature"), 0, 0),
                                openAiDocument("gpt-5.3-codex", "gpt-5.3-codex", "openai", List.of("text"), null,
                                        List.of("reasoning"), 0, 0))));
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(runtimeConfigService, discoveryPort);

        List<ProviderModelDiscoveryService.DiscoveredModel> models = service.discoverModels("xmesh");

        assertEquals(17, models.size());
        assertTrue(models.stream().anyMatch(model -> "gpt-5.2".equals(model.id())));
        assertTrue(models.stream().anyMatch(model -> "gpt-5.3-codex-spark".equals(model.id())));
        assertTrue(models.stream().anyMatch(model -> "gemini-2.5-pro".equals(model.id())));
        assertTrue(models.stream().anyMatch(model -> "deepseek-coder-v2-lite".equals(model.id())));
        assertEquals("https://model.xmesh.click/v1/models", discoveryPort.capturedRequest().uri().toString());
        assertEquals("secret-token", discoveryPort.capturedRequest().apiKey());
        assertEquals(ProviderModelDiscoveryPort.AuthMode.BEARER, discoveryPort.capturedRequest().authMode());
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

        StubProviderModelDiscoveryPort discoveryPort = new StubProviderModelDiscoveryPort(
                new ProviderModelDiscoveryPort.DiscoveryResponse(
                        200,
                        List.of(
                                openAiDocument("openai/gpt-5", "OpenAI: GPT-5", null,
                                        List.of("text", "image", "file"), null,
                                        List.of("include_reasoning", "max_tokens",
                                                "reasoning", "tools"),
                                        400000, 0),
                                openAiDocument("google/gemini-2.5-pro", "Google: Gemini 2.5 Pro", null,
                                        List.of("text", "image", "file", "audio", "video"),
                                        null,
                                        List.of("temperature", "tools"), 1048576, 0),
                                openAiDocument("openai/gpt-4o-mini", "OpenAI: GPT-4o Mini", null, List.of(),
                                        "text+image->text",
                                        List.of(), 0, 8192))));
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(runtimeConfigService, discoveryPort);

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
    void shouldDiscoverAnthropicModelsUsingDefaultEndpointAndAuthMode() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("anthropic-key"))
                .baseUrl(null)
                .requestTimeoutSeconds(20)
                .apiType("anthropic")
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("anthropic"));
        when(runtimeConfigService.getLlmProviderConfig("anthropic")).thenReturn(providerConfig);

        StubProviderModelDiscoveryPort discoveryPort = new StubProviderModelDiscoveryPort(
                new ProviderModelDiscoveryPort.DiscoveryResponse(200,
                        List.of(openAiDocument("claude-opus-4.1", "Claude Opus 4.1", "anthropic",
                                List.of("text"), null, List.of("temperature"), 0, 0))));
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(runtimeConfigService, discoveryPort);

        List<ProviderModelDiscoveryService.DiscoveredModel> models = service.discoverModels("anthropic");

        assertEquals(1, models.size());
        assertEquals("https://api.anthropic.com/v1/models", discoveryPort.capturedRequest().uri().toString());
        assertEquals("anthropic-key", discoveryPort.capturedRequest().apiKey());
        assertEquals(ProviderModelDiscoveryPort.AuthMode.ANTHROPIC, discoveryPort.capturedRequest().authMode());
    }

    @Test
    void shouldDiscoverGeminiModelsUsingGoogleAuthMode() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("google-key"))
                .baseUrl("https://generativelanguage.googleapis.com")
                .requestTimeoutSeconds(20)
                .apiType("gemini")
                .build();
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("google"));
        when(runtimeConfigService.getLlmProviderConfig("google")).thenReturn(providerConfig);

        StubProviderModelDiscoveryPort discoveryPort = new StubProviderModelDiscoveryPort(
                new ProviderModelDiscoveryPort.DiscoveryResponse(
                        200,
                        List.of(
                                geminiDocument("models/embedding-001", "Embedding 001", "google",
                                        List.of("embedContent")),
                                geminiDocument("models/gemini-2.0-flash", "Gemini 2.0 Flash", "google",
                                        List.of("generateContent")))));
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(runtimeConfigService, discoveryPort);

        List<ProviderModelDiscoveryService.DiscoveredModel> models = service.discoverModels("google");

        assertEquals(1, models.size());
        assertEquals("gemini-2.0-flash", models.get(0).id());
        assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models",
                discoveryPort.capturedRequest().uri().toString());
        assertEquals("google-key", discoveryPort.capturedRequest().apiKey());
        assertEquals(ProviderModelDiscoveryPort.AuthMode.GOOGLE, discoveryPort.capturedRequest().authMode());
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

        StubProviderModelDiscoveryPort discoveryPort = new StubProviderModelDiscoveryPort(
                new ProviderModelDiscoveryPort.DiscoveryResponse(200, List.of()));
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(runtimeConfigService, discoveryPort);

        service.discoverModels("openai");

        assertEquals("https://api.openai.com/v1/models", discoveryPort.capturedRequest().uri().toString());
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

        StubProviderModelDiscoveryPort discoveryPort = new StubProviderModelDiscoveryPort(
                new ProviderModelDiscoveryPort.DiscoveryResponse(200,
                        List.of(openAiDocument("openai/gpt-4o", "OpenAI: GPT-4o", null,
                                List.of("text", "image"), null, List.of("temperature"), 128000, 0))));
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(runtimeConfigService, discoveryPort);

        List<ProviderModelDiscoveryService.DiscoveredModel> models = service.discoverModels("customrouter");

        assertNotNull(models.get(0).defaultSettings());
        assertEquals("customrouter", models.get(0).defaultSettings().getProvider());
    }

    @Test
    void shouldRejectUnknownProvider() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getConfiguredLlmProviders()).thenReturn(List.of("openai"));

        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(
                runtimeConfigService,
                new StubProviderModelDiscoveryPort(new ProviderModelDiscoveryPort.DiscoveryResponse(200, List.of())));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.discoverModels("missing"));
        assertEquals("Provider 'missing' is not configured", error.getMessage());
    }

    @Test
    void shouldRejectBlankProviderName() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(
                runtimeConfigService,
                new StubProviderModelDiscoveryPort(new ProviderModelDiscoveryPort.DiscoveryResponse(200, List.of())));

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

        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(
                runtimeConfigService,
                new StubProviderModelDiscoveryPort(new ProviderModelDiscoveryPort.DiscoveryResponse(502, List.of())));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.discoverModels("xmesh"));
        assertTrue(error.getMessage().contains("status 502"));
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

        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(
                runtimeConfigService,
                new StubProviderModelDiscoveryPort(new ProviderModelDiscoveryPort.DiscoveryResponse(200, List.of())));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.discoverModels("xmesh"));
        assertEquals("Provider 'xmesh' does not have an API key configured", error.getMessage());
    }

    @Test
    void shouldTreatInvalidOpenRouterBaseUrlAsNonOpenRouterAndUseDefaultMaxTokensFallback() throws Exception {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.LlmProviderConfig invalidProviderConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("custom-key"))
                .baseUrl("://not-a-uri")
                .requestTimeoutSeconds(20)
                .apiType("openai")
                .build();
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(
                runtimeConfigService,
                new StubProviderModelDiscoveryPort(new ProviderModelDiscoveryPort.DiscoveryResponse(200, List.of())));

        java.lang.reflect.Method shouldAttachMethod = ProviderModelDiscoveryService.class.getDeclaredMethod(
                "shouldAttachOpenRouterDefaults",
                String.class,
                RuntimeConfig.LlmProviderConfig.class);
        shouldAttachMethod.setAccessible(true);
        boolean shouldAttach = (boolean) shouldAttachMethod.invoke(service, "customrouter", invalidProviderConfig);
        assertFalse(shouldAttach);

        java.lang.reflect.Method resolveMaxInputTokensMethod = ProviderModelDiscoveryService.class.getDeclaredMethod(
                "resolveMaxInputTokens",
                ProviderModelDiscoveryPort.DiscoveryDocument.class);
        resolveMaxInputTokensMethod.setAccessible(true);
        ProviderModelDiscoveryPort.DiscoveryDocument emptyDocument = openAiDocument(
                "gpt-5",
                "GPT-5",
                "openai",
                List.of("text"),
                null,
                List.of("temperature"),
                0,
                0);
        int maxTokens = (int) resolveMaxInputTokensMethod.invoke(service, emptyDocument);
        assertEquals(128000, maxTokens);
    }

    private static ProviderModelDiscoveryPort.DiscoveryDocument openAiDocument(
            String id,
            String displayName,
            String ownedBy,
            List<String> inputModalities,
            String modality,
            List<String> supportedParameters,
            int contextLength,
            int topProviderContextLength) {
        return new ProviderModelDiscoveryPort.DiscoveryDocument(
                ProviderModelDiscoveryPort.DocumentKind.OPENAI_LIKE,
                id,
                displayName,
                ownedBy,
                null,
                null,
                inputModalities != null ? inputModalities : List.of(),
                modality,
                supportedParameters != null ? supportedParameters : List.of(),
                contextLength > 0 ? contextLength : null,
                topProviderContextLength > 0 ? topProviderContextLength : null,
                null,
                List.of());
    }

    private static ProviderModelDiscoveryPort.DiscoveryDocument geminiDocument(
            String id,
            String displayName,
            String publisher,
            List<String> supportedGenerationMethods) {
        return new ProviderModelDiscoveryPort.DiscoveryDocument(
                ProviderModelDiscoveryPort.DocumentKind.GEMINI,
                id,
                displayName,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                publisher,
                supportedGenerationMethods);
    }

    private static final class StubProviderModelDiscoveryPort implements ProviderModelDiscoveryPort {

        private final DiscoveryResponse response;
        private DiscoveryRequest capturedRequest;

        private StubProviderModelDiscoveryPort(DiscoveryResponse response) {
            this.response = response;
        }

        @Override
        public DiscoveryResponse discover(DiscoveryRequest request) {
            this.capturedRequest = request;
            return response;
        }

        private DiscoveryRequest capturedRequest() {
            return capturedRequest;
        }
    }
}

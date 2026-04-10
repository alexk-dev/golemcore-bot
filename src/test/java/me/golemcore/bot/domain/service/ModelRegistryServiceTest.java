package me.golemcore.bot.application.models;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.ModelRegistryCachePort;
import me.golemcore.bot.port.outbound.ModelRegistryDocumentPort;
import me.golemcore.bot.port.outbound.ModelRegistryRemotePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRegistryServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private InMemoryModelRegistryCachePort cachePort;
    private ModelRegistryDocumentPort documentPort;
    private StubModelRegistryService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        cachePort = new InMemoryModelRegistryCachePort();
        documentPort = new me.golemcore.bot.adapter.outbound.models.JacksonModelRegistryDocumentAdapter();

        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRegistry(RuntimeConfig.ModelRegistryConfig.builder()
                        .repositoryUrl("https://github.com/alexk-dev/golemcore-models")
                        .branch("main")
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        service = new StubModelRegistryService(runtimeConfigService, cachePort, documentPort);
        service.setNow(Instant.parse("2026-03-23T12:00:00Z"));
    }

    @Test
    void shouldResolveProviderSpecificRemoteConfigBeforeSharedFallback() {
        service.putRemoteFile("providers/openrouter/openai/gpt-4o.json", """
                {
                  "displayName": "OpenRouter GPT-4o",
                  "supportsVision": true,
                  "supportsTemperature": false,
                  "maxInputTokens": 200000
                }
                """);
        service.putRemoteFile("models/openai/gpt-4o.json", """
                {
                  "displayName": "Shared GPT-4o",
                  "supportsVision": true,
                  "supportsTemperature": true,
                  "maxInputTokens": 128000
                }
                """);

        ModelRegistryService.ResolveResult result = service.resolveDefaults("openrouter", "openai/gpt-4o");

        assertNotNull(result.defaultSettings());
        assertEquals("provider", result.configSource());
        assertEquals("remote-hit", result.cacheStatus());
        assertEquals("openrouter", result.defaultSettings().getProvider());
        assertEquals("OpenRouter GPT-4o", result.defaultSettings().getDisplayName());
        assertFalse(result.defaultSettings().isSupportsTemperature());
    }

    @Test
    void shouldResolveSharedFallbackWhenProviderSpecificConfigIsMissing() {
        service.putRemoteFile("models/gpt-5.1.json", """
                {
                  "displayName": "GPT-5.1",
                  "supportsVision": true,
                  "supportsTemperature": false,
                  "maxInputTokens": 1000000
                }
                """);

        ModelRegistryService.ResolveResult result = service.resolveDefaults("openai", "gpt-5.1");

        assertNotNull(result.defaultSettings());
        assertEquals("shared", result.configSource());
        assertEquals("remote-hit", result.cacheStatus());
        assertEquals("GPT-5.1", result.defaultSettings().getDisplayName());
    }

    @Test
    void shouldUseDefaultOfficialRegistrySourceWhenConfigIsMissing() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        service.putRemoteFile("models/gemini-3.1-pro-preview.json", """
                {
                  "displayName": "Gemini 3.1 Pro Preview",
                  "supportsVision": true,
                  "supportsTemperature": true,
                  "maxInputTokens": 1048576
                }
                """);

        ModelRegistryService.ResolveResult result = service.resolveDefaults("dev", "gemini-3.1-pro-preview");

        assertNotNull(result.defaultSettings());
        assertEquals("shared", result.configSource());
        assertEquals("remote-hit", result.cacheStatus());
        assertEquals("Gemini 3.1 Pro Preview", result.defaultSettings().getDisplayName());
        assertTrue(service.requestedUris().stream().anyMatch(
                uri -> "https://raw.githubusercontent.com/alexk-dev/golemcore-models/main/models/gemini-3.1-pro-preview.json"
                        .equals(uri)));
    }

    @Test
    void shouldUseDefaultOfficialRegistrySourceWhenModelRegistrySectionIsNull() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRegistry(null)
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        service.putRemoteFile("models/gpt-5.1.json", """
                {
                  "displayName": "GPT-5.1",
                  "supportsVision": true,
                  "supportsTemperature": false,
                  "maxInputTokens": 1000000
                }
                """);

        ModelRegistryService.ResolveResult result = service.resolveDefaults("openai", "gpt-5.1");

        assertNotNull(result.defaultSettings());
        assertEquals("shared", result.configSource());
        assertEquals("remote-hit", result.cacheStatus());
        assertTrue(service.requestedUris().stream().anyMatch(
                uri -> "https://raw.githubusercontent.com/alexk-dev/golemcore-models/main/models/gpt-5.1.json"
                        .equals(uri)));
    }

    @Test
    void shouldUseDefaultBranchWhenConfiguredBranchIsBlank() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRegistry(RuntimeConfig.ModelRegistryConfig.builder()
                        .repositoryUrl("https://github.com/example/custom-models")
                        .branch("   ")
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        service.putRemoteFile("models/gpt-5.1.json", """
                {
                  "displayName": "GPT-5.1",
                  "supportsVision": true,
                  "supportsTemperature": false,
                  "maxInputTokens": 1000000
                }
                """);

        ModelRegistryService.ResolveResult result = service.resolveDefaults("openai", "gpt-5.1");

        assertNotNull(result.defaultSettings());
        assertEquals("shared", result.configSource());
        assertEquals("remote-hit", result.cacheStatus());
        assertTrue(service.requestedUris().stream().anyMatch(
                uri -> "https://raw.githubusercontent.com/example/custom-models/main/models/gpt-5.1.json"
                        .equals(uri)));
    }

    @Test
    void shouldUseFreshCacheWithoutRefetchingRemoteConfig() {
        service.putRemoteFile("models/gpt-5.1.json", """
                {
                  "displayName": "GPT-5.1",
                  "supportsVision": true,
                  "supportsTemperature": false,
                  "maxInputTokens": 1000000
                }
                """);

        ModelRegistryService.ResolveResult initial = service.resolveDefaults("openai", "gpt-5.1");
        int requestsAfterInitialResolve = service.requestedUris().size();

        service.clearRemoteFiles();
        service.setNow(Instant.parse("2026-03-23T13:00:00Z"));

        ModelRegistryService.ResolveResult cached = service.resolveDefaults("openai", "gpt-5.1");

        assertEquals("remote-hit", initial.cacheStatus());
        assertEquals("fresh-hit", cached.cacheStatus());
        assertEquals(requestsAfterInitialResolve, service.requestedUris().size());
        assertEquals("GPT-5.1", cached.defaultSettings().getDisplayName());
    }

    @Test
    void shouldRefreshFreshMissWhenConfigAppearsInRegistry() {
        ModelRegistryService.ResolveResult initial = service.resolveDefaults("google", "gemini-3.1-pro-preview");

        assertNull(initial.defaultSettings());
        assertEquals("miss", initial.cacheStatus());

        service.putRemoteFile("models/gemini-3.1-pro-preview.json", """
                {
                  "displayName": "Gemini 3.1 Pro Preview",
                  "supportsVision": true,
                  "supportsTemperature": true,
                  "maxInputTokens": 1048576
                }
                """);
        service.setNow(Instant.parse("2026-03-23T12:30:00Z"));

        ModelRegistryService.ResolveResult resolved = service.resolveDefaults("google", "gemini-3.1-pro-preview");

        assertNotNull(resolved.defaultSettings());
        assertEquals("shared", resolved.configSource());
        assertEquals("remote-hit", resolved.cacheStatus());
        assertEquals("Gemini 3.1 Pro Preview", resolved.defaultSettings().getDisplayName());
    }

    @Test
    void shouldStripMonthYearSuffixFromModelIdWhenResolvingSharedConfig() {
        service.putRemoteFile("models/gemini-3-preview.json", """
                {
                  "displayName": "Gemini 3 Preview",
                  "supportsVision": true,
                  "supportsTemperature": true,
                  "maxInputTokens": 1048576
                }
                """);

        ModelRegistryService.ResolveResult result = service.resolveDefaults("google", "gemini-3-preview-09-2025");

        assertNotNull(result.defaultSettings());
        assertEquals("shared", result.configSource());
        assertEquals("remote-hit", result.cacheStatus());
        assertEquals("Gemini 3 Preview", result.defaultSettings().getDisplayName());
        assertTrue(service.requestedUris().stream().anyMatch(uri -> uri.endsWith("/models/gemini-3-preview.json")));
    }

    @Test
    void shouldRefreshStaleCacheFromRemote() {
        service.putRemoteFile("models/gpt-5.1.json", """
                {
                  "displayName": "GPT-5.1",
                  "supportsVision": true,
                  "supportsTemperature": false,
                  "maxInputTokens": 1000000
                }
                """);
        service.resolveDefaults("openai", "gpt-5.1");

        service.setNow(Instant.parse("2026-03-24T13:00:01Z"));
        service.putRemoteFile("models/gpt-5.1.json", """
                {
                  "displayName": "GPT-5.1 refreshed",
                  "supportsVision": true,
                  "supportsTemperature": false,
                  "maxInputTokens": 1000000
                }
                """);

        ModelRegistryService.ResolveResult result = service.resolveDefaults("openai", "gpt-5.1");

        assertEquals("remote-hit", result.cacheStatus());
        assertEquals("GPT-5.1 refreshed", result.defaultSettings().getDisplayName());
    }

    @Test
    void shouldUseStaleCachedHitWhenRemoteRefreshFails() {
        service.putRemoteFile("models/gpt-5.1.json", """
                {
                  "displayName": "GPT-5.1",
                  "supportsVision": true,
                  "supportsTemperature": false,
                  "maxInputTokens": 1000000
                }
                """);
        service.resolveDefaults("openai", "gpt-5.1");

        service.setNow(Instant.parse("2026-03-24T13:00:01Z"));
        service.clearRemoteFiles();
        service.setRemoteFailure("registry offline");

        ModelRegistryService.ResolveResult result = service.resolveDefaults("openai", "gpt-5.1");

        assertNotNull(result.defaultSettings());
        assertEquals("stale-hit", result.cacheStatus());
        assertEquals("GPT-5.1", result.defaultSettings().getDisplayName());
    }

    @Test
    void shouldReturnMissWhenOnlyStaleMissExistsAndRemoteRefreshFails() {
        ModelRegistryService.ResolveResult initial = service.resolveDefaults("openai", "missing-model");
        assertNull(initial.defaultSettings());
        assertEquals("miss", initial.cacheStatus());

        service.setNow(Instant.parse("2026-03-24T13:00:01Z"));
        service.setRemoteFailure("registry offline");

        ModelRegistryService.ResolveResult result = service.resolveDefaults("openai", "missing-model");

        assertNull(result.defaultSettings());
        assertNull(result.configSource());
        assertEquals("miss", result.cacheStatus());
    }

    @Test
    void shouldIgnoreInvalidRegistryJsonAndFallBackToStaleCache() {
        service.putRemoteFile("models/gpt-5.1.json", """
                {
                  "displayName": "GPT-5.1",
                  "supportsVision": true,
                  "supportsTemperature": false,
                  "maxInputTokens": 1000000
                }
                """);
        service.resolveDefaults("openai", "gpt-5.1");

        service.setNow(Instant.parse("2026-03-24T13:00:01Z"));
        service.putRemoteFile("models/gpt-5.1.json", "{ invalid json");

        ModelRegistryService.ResolveResult result = service.resolveDefaults("openai", "gpt-5.1");

        assertNotNull(result.defaultSettings());
        assertEquals("stale-hit", result.cacheStatus());
        assertEquals("GPT-5.1", result.defaultSettings().getDisplayName());
    }

    @SuppressWarnings("PMD.NullAssignment")
    private static final class StubModelRegistryService extends ModelRegistryService {

        private final Map<String, String> remoteFiles = new ConcurrentHashMap<>();
        private final List<String> requestedUriHistory = new ArrayList<>();
        private Instant currentTime;
        private RuntimeException remoteFailure;

        private StubModelRegistryService(
                RuntimeConfigService runtimeConfigService,
                ModelRegistryCachePort cachePort,
                ModelRegistryDocumentPort documentPort) {
            super(runtimeConfigService, mock(ModelRegistryRemotePort.class), cachePort, documentPort);
        }

        private void setNow(Instant now) {
            this.currentTime = now;
        }

        private void putRemoteFile(String relativePath, String content) {
            remoteFiles.put(relativePath, content);
            remoteFailure = null;
        }

        private void clearRemoteFiles() {
            remoteFiles.clear();
        }

        private void setRemoteFailure(String message) {
            remoteFailure = new IllegalStateException(message);
        }

        private List<String> requestedUris() {
            return requestedUriHistory;
        }

        @Override
        protected Instant now() {
            return currentTime;
        }

        @Override
        protected String fetchRemoteText(URI uri) {
            requestedUriHistory.add(uri.toString());
            if (remoteFailure != null) {
                throw remoteFailure;
            }
            for (Map.Entry<String, String> entry : remoteFiles.entrySet()) {
                if (uri.toString().endsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    private static final class InMemoryModelRegistryCachePort implements ModelRegistryCachePort {

        private final Map<String, CachedRegistryEntry> entries = new ConcurrentHashMap<>();

        @Override
        public CachedRegistryEntry read(String repositoryUrl, String branch, String relativePath) {
            return entries.get(cacheKey(repositoryUrl, branch, relativePath));
        }

        @Override
        public void write(String repositoryUrl, String branch, String relativePath, CachedRegistryEntry entry) {
            entries.put(cacheKey(repositoryUrl, branch, relativePath), entry);
        }

        private String cacheKey(String repositoryUrl, String branch, String relativePath) {
            return repositoryUrl + "|" + branch + "|" + relativePath;
        }
    }
}

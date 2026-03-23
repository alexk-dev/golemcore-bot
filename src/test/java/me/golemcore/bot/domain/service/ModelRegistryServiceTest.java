package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRegistryServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private StoragePort storagePort;
    private Map<String, String> persistedText;
    private TestableModelRegistryService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        storagePort = mock(StoragePort.class);
        persistedText = new ConcurrentHashMap<>();

        when(storagePort.getText(anyString(), anyString())).thenAnswer(invocation -> CompletableFuture.completedFuture(
                persistedText.get(storageKey(invocation.getArgument(0), invocation.getArgument(1)))));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> {
                    String directory = invocation.getArgument(0);
                    String path = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedText.put(storageKey(directory, path), content);
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.exists(anyString(), anyString())).thenAnswer(invocation -> CompletableFuture.completedFuture(
                persistedText.containsKey(storageKey(invocation.getArgument(0), invocation.getArgument(1)))));

        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRegistry(RuntimeConfig.ModelRegistryConfig.builder()
                        .repositoryUrl("https://github.com/alexk-dev/golemcore-models")
                        .branch("main")
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        service = new TestableModelRegistryService(runtimeConfigService, storagePort);
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

    private String storageKey(String directory, String path) {
        return directory + "/" + path;
    }

    private static final class TestableModelRegistryService extends ModelRegistryService {

        private final Map<String, String> remoteFiles = new ConcurrentHashMap<>();
        private final List<String> requestedUris = new ArrayList<>();
        private Instant now;
        private RuntimeException remoteFailure;

        private TestableModelRegistryService(RuntimeConfigService runtimeConfigService, StoragePort storagePort) {
            super(runtimeConfigService, storagePort);
        }

        private void setNow(Instant now) {
            this.now = now;
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
            return requestedUris;
        }

        @Override
        protected Instant now() {
            return now;
        }

        @Override
        protected String fetchRemoteText(URI uri) {
            requestedUris.add(uri.toString());
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
}

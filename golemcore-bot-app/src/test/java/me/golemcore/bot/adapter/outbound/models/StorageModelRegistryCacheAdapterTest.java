package me.golemcore.bot.adapter.outbound.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import me.golemcore.bot.port.outbound.ModelRegistryCachePort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StorageModelRegistryCacheAdapterTest {

    private StoragePort storagePort;
    private StorageModelRegistryCacheAdapter adapter;
    private Map<String, String> persistedText;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        persistedText = new ConcurrentHashMap<>();
        when(storagePort.getText(anyString(), anyString())).thenAnswer(invocation -> CompletableFuture.completedFuture(
                persistedText.get(storageKey(invocation.getArgument(0), invocation.getArgument(1)))));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            persistedText.put(storageKey(invocation.getArgument(0), invocation.getArgument(1)),
                    invocation.getArgument(2));
            return CompletableFuture.completedFuture(null);
        });
        when(storagePort.exists(anyString(), anyString())).thenAnswer(invocation -> CompletableFuture.completedFuture(
                persistedText.containsKey(storageKey(invocation.getArgument(0), invocation.getArgument(1)))));
        adapter = new StorageModelRegistryCacheAdapter(storagePort);
    }

    @Test
    void shouldRoundTripCachedEntry() {
        ModelRegistryCachePort.CachedRegistryEntry entry = new ModelRegistryCachePort.CachedRegistryEntry(
                Instant.parse("2026-03-23T12:00:00Z"),
                true,
                "{\"displayName\":\"GPT-5.1\"}");

        adapter.write("https://github.com/alexk-dev/golemcore-models", "main", "models/gpt-5.1.json", entry);
        ModelRegistryCachePort.CachedRegistryEntry loaded = adapter.read(
                "https://github.com/alexk-dev/golemcore-models",
                "main",
                "models/gpt-5.1.json");

        assertNotNull(loaded);
        assertEquals(entry.cachedAt(), loaded.cachedAt());
        assertEquals(entry.found(), loaded.found());
        assertEquals(entry.content(), loaded.content());
    }

    @Test
    void shouldReturnNullWhenEntryIsMissing() {
        assertNull(adapter.read("repo", "main", "models/missing.json"));
    }

    private String storageKey(String directory, String path) {
        return directory + "/" + path;
    }
}

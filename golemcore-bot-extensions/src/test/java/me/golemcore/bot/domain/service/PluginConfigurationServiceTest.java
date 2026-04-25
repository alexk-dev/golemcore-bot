package me.golemcore.bot.domain.service;

import me.golemcore.bot.adapter.outbound.config.StoragePluginConfigurationStoreAdapter;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginConfigurationServiceTest {

    private StoragePort storagePort;
    private PluginConfigurationService service;
    private Map<String, String> storedConfigs;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        storedConfigs = new ConcurrentHashMap<>();

        when(storagePort.exists(anyString(), anyString()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(
                        storedConfigs.containsKey(invocation.getArgument(1))));
        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(
                        storedConfigs.get(invocation.getArgument(1))));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    storedConfigs.put(invocation.getArgument(1), invocation.getArgument(2));
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    storedConfigs.remove(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });

        service = new PluginConfigurationService(new StoragePluginConfigurationStoreAdapter(storagePort));
    }

    @Test
    void shouldSaveAndReadPluginConfigUsingNormalizedPathWithDefensiveCopies() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("enabled", true);
        nested.put("bytes", new byte[] { 1, 2, 3 });
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("nested", nested);
        input.put("items", new ArrayList<>(List.of(Map.of("name", "browser"))));

        service.savePluginConfig("GolemCore/Browser", input);

        assertTrue(service.hasPluginConfig("golemcore/browser"));
        verify(storagePort).putTextAtomic(
                "preferences",
                "plugins/golemcore/browser.json",
                storedConfigs.get("plugins/golemcore/browser.json"),
                true);

        Map<String, Object> firstRead = service.getPluginConfig("golemcore/browser");

        Map<String, Object> firstNested = castMap(firstRead.get("nested"));
        List<Object> firstItems = castList(firstRead.get("items"));
        byte[] firstBytes = (byte[]) firstNested.get("bytes");
        firstNested.put("enabled", false);
        firstBytes[0] = 9;
        firstItems.add(Map.of("name", "mutated"));

        Map<String, Object> secondRead = service.getPluginConfig("golemcore/browser");
        Map<String, Object> secondNested = castMap(secondRead.get("nested"));
        List<Object> secondItems = castList(secondRead.get("items"));

        assertEquals(Boolean.TRUE, secondNested.get("enabled"));
        assertArrayEquals(new byte[] { 1, 2, 3 }, (byte[]) secondNested.get("bytes"));
        assertEquals(1, secondItems.size());
    }

    @Test
    void shouldDeletePluginConfigAndEvictCache() {
        service.savePluginConfig("golemcore/browser", Map.of("enabled", true));
        assertTrue(service.hasPluginConfig("golemcore/browser"));
        assertEquals(Boolean.TRUE, service.getPluginConfig("golemcore/browser").get("enabled"));

        service.deletePluginConfig("golemcore/browser");

        assertFalse(service.hasPluginConfig("golemcore/browser"));
        assertTrue(service.getPluginConfig("golemcore/browser").isEmpty());
        verify(storagePort).deleteObject("preferences", "plugins/golemcore/browser.json");
    }

    @Test
    void shouldLoadPersistedPluginConfigFromStorage() {
        storedConfigs.put("plugins/golemcore/browser.json", """
                {
                  "enabled": true,
                  "nested": {
                    "count": 2
                  }
                }
                """);

        Map<String, Object> config = service.getPluginConfig("golemcore/browser");

        assertEquals(Boolean.TRUE, config.get("enabled"));
        assertEquals(2, castMap(config.get("nested")).get("count"));
    }

    @Test
    void shouldWrapPluginConfigReadFailures() {
        when(storagePort.getText("preferences", "plugins/golemcore/browser.json"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("read failed")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.getPluginConfig("golemcore/browser"));

        assertEquals("Failed to read plugin config for golemcore/browser", exception.getMessage());
    }

    @Test
    void shouldWrapPluginConfigWriteFailures() {
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("write failed")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.savePluginConfig("golemcore/browser", Map.of("enabled", true)));

        assertEquals("Failed to write plugin config for golemcore/browser", exception.getMessage());
    }

    @Test
    void shouldRejectInvalidPluginId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.savePluginConfig("../browser", Map.of("enabled", true)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> castList(Object value) {
        return (List<Object>) value;
    }
}

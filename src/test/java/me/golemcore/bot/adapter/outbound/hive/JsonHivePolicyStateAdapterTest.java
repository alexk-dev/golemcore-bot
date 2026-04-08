package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JsonHivePolicyStateAdapterTest {

    @Mock
    private StoragePort storagePort;

    private JsonHivePolicyStateAdapter adapter;
    private ObjectMapper objectMapper;
    private AtomicReference<String> persistedJson;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        persistedJson = new AtomicReference<>();
        lenient().when(storagePort.getText("preferences", "hive-policy-state.json"))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(persistedJson.get()));
        lenient().when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    persistedJson.set(invocation.getArgument(2));
                    return CompletableFuture.completedFuture(null);
                });
        lenient().when(storagePort.deleteObject("preferences", "hive-policy-state.json"))
                .thenAnswer(invocation -> {
                    persistedJson.set(null);
                    return CompletableFuture.completedFuture(null);
                });
        adapter = new JsonHivePolicyStateAdapter(storagePort, objectMapper);
    }

    @Test
    void loadShouldReturnEmptyWhenNoPersistedStateExists() {
        assertTrue(adapter.load().isEmpty());
    }

    @Test
    void saveShouldPersistCopyAndLoadShouldReturnDefensiveCopy() {
        HivePolicyBindingState state = HivePolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .appliedVersion(3)
                .checksum("sha256:abcd")
                .syncStatus("OUT_OF_SYNC")
                .lastSyncRequestedAt(Instant.parse("2026-04-08T00:00:00Z"))
                .lastAppliedAt(Instant.parse("2026-04-08T00:05:00Z"))
                .lastErrorDigest("provider-missing")
                .lastErrorAt(Instant.parse("2026-04-08T00:06:00Z"))
                .build();

        adapter.save(state);
        state.setPolicyGroupId("mutated");
        Optional<HivePolicyBindingState> loaded = adapter.load();

        assertTrue(loaded.isPresent());
        assertEquals("pg-1", loaded.orElseThrow().getPolicyGroupId());
        assertEquals(4, loaded.orElseThrow().getTargetVersion());
        assertNotSame(state, loaded.orElseThrow());

        loaded.orElseThrow().setPolicyGroupId("changed-after-load");
        assertEquals("pg-1", adapter.load().orElseThrow().getPolicyGroupId());
    }

    @Test
    void saveShouldRejectNullState() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> adapter.save(null));

        assertEquals("Hive policy binding state is required", exception.getMessage());
    }

    @Test
    void saveShouldWrapPersistenceFailures() {
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk-full")));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> adapter.save(HivePolicyBindingState.builder().policyGroupId("pg-1").build()));

        assertEquals("Failed to persist Hive policy binding state", exception.getMessage());
    }

    @Test
    void clearShouldResetCachedStateEvenWhenDeleteFails() {
        adapter.save(HivePolicyBindingState.builder().policyGroupId("pg-1").build());
        when(storagePort.deleteObject("preferences", "hive-policy-state.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("permission-denied")));

        assertDoesNotThrow(() -> adapter.clear());
        assertTrue(adapter.load().isEmpty());
    }

    @Test
    void loadShouldIgnoreBlankPersistedJson() {
        persistedJson.set("   ");

        assertTrue(adapter.load().isEmpty());
    }

    @Test
    void loadShouldIgnoreInvalidPersistedJson() {
        persistedJson.set("{not-json");

        assertTrue(adapter.load().isEmpty());
    }

    @Test
    void loadShouldIgnoreStorageFailures() {
        when(storagePort.getText("preferences", "hive-policy-state.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("unavailable")));
        adapter = new JsonHivePolicyStateAdapter(storagePort, objectMapper);

        assertFalse(adapter.load().isPresent());
    }

    @Test
    void clearShouldDeletePersistedFile() {
        adapter.clear();

        verify(storagePort).deleteObject("preferences", "hive-policy-state.json");
    }
}

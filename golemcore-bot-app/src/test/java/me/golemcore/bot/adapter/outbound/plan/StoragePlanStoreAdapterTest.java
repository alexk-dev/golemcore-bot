package me.golemcore.bot.adapter.outbound.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoragePlanStoreAdapterTest {

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private StoragePlanStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        adapter = new StoragePlanStoreAdapter(storagePort, objectMapper);
    }

    @Test
    void shouldReturnEmptyPlansWhenStoredJsonIsBlank() {
        when(storagePort.getText("auto", "plans.json")).thenReturn(CompletableFuture.completedFuture("  "));

        List<Plan> plans = adapter.loadPlans();

        assertEquals(List.of(), plans);
    }

    @Test
    void shouldRoundTripPlansFromStorage() throws Exception {
        String json = objectMapper.writeValueAsString(List.of(Plan.builder().id("plan-1").title("Plan").build()));
        when(storagePort.getText("auto", "plans.json")).thenReturn(CompletableFuture.completedFuture(json));

        List<Plan> plans = adapter.loadPlans();

        assertEquals(1, plans.size());
        assertEquals("plan-1", plans.getFirst().getId());
    }

    @Test
    void shouldWrapLoadFailures() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IOException("broken plan file"));
        when(storagePort.getText("auto", "plans.json")).thenReturn(failed);

        IllegalStateException error = assertThrows(IllegalStateException.class, adapter::loadPlans);

        assertEquals("Failed to load plans", error.getMessage());
        assertInstanceOf(IOException.class, error.getCause());
    }

    @Test
    void shouldPersistPlansAsJson() {
        when(storagePort.putText("auto", "plans.json", "[{\"id\":\"plan-1\",\"title\":\"Plan\",\"description\":null,"
                + "\"markdown\":null,\"status\":\"COLLECTING\",\"steps\":[],\"modelTier\":null,"
                + "\"channelType\":null,\"chatId\":null,\"transportChatId\":null,\"createdAt\":null,"
                + "\"updatedAt\":null}]"))
                .thenReturn(CompletableFuture.completedFuture(null));

        adapter.savePlans(List.of(Plan.builder().id("plan-1").title("Plan").build()));

        verify(storagePort)
                .putText(
                        eq("auto"),
                        eq("plans.json"),
                        eq("[{\"id\":\"plan-1\",\"title\":\"Plan\",\"description\":null,"
                                + "\"markdown\":null,\"status\":\"COLLECTING\",\"steps\":[],\"modelTier\":null,"
                                + "\"channelType\":null,\"chatId\":null,\"transportChatId\":null,\"createdAt\":null,"
                                + "\"updatedAt\":null}]"));
    }

    @Test
    void shouldWrapSaveFailures() {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IOException("disk full"));
        when(storagePort.putText(eq("auto"), eq("plans.json"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(failed);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> adapter.savePlans(List.of(Plan.builder().id("plan-1").build())));

        assertEquals("Failed to persist plans", error.getMessage());
        assertInstanceOf(IOException.class, error.getCause());
    }
}

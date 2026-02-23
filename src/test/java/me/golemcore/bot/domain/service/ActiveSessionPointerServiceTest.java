package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActiveSessionPointerServiceTest {

    private StoragePort storagePort;
    private ActiveSessionPointerService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new ActiveSessionPointerService(storagePort, new ObjectMapper());
    }

    @Test
    void shouldPersistAndReadWebPointer() {
        String pointerKey = service.buildWebPointerKey("admin", "client-1");
        service.setActiveConversationKey(pointerKey, "session-1234");

        Optional<String> active = service.getActiveConversationKey(pointerKey);

        assertEquals(Optional.of("session-1234"), active);
        verify(storagePort).putTextAtomic(org.mockito.ArgumentMatchers.eq("preferences"),
                org.mockito.ArgumentMatchers.eq("session-pointers.json"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void shouldRollbackInMemoryStateWhenPersistFails() {
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk-full")));
        String pointerKey = service.buildWebPointerKey("admin", "client-1");

        assertThrows(IllegalStateException.class, () -> service.setActiveConversationKey(pointerKey, "session-1234"));
        assertEquals(Optional.empty(), service.getActiveConversationKey(pointerKey));
    }

    @Test
    void shouldClearPointer() {
        String pointerKey = service.buildTelegramPointerKey("100");
        service.setActiveConversationKey(pointerKey, "telegram-session");
        service.clearActiveConversationKey(pointerKey);

        assertEquals(Optional.empty(), service.getActiveConversationKey(pointerKey));
    }

    @Test
    void shouldNormalizeBlankPointerSegments() {
        assertEquals("web|_|_", service.buildWebPointerKey(" ", null));
        assertEquals("telegram|_", service.buildTelegramPointerKey("  "));
    }

    @Test
    void shouldIgnoreCorruptedStoredPointersAndContinueWorking() {
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture("{broken-json"));
        service = new ActiveSessionPointerService(storagePort, new ObjectMapper());

        assertEquals(Optional.empty(), service.getActiveConversationKey("web|admin|client-1"));
        service.setActiveConversationKey("web|admin|client-1", "session-100");

        assertEquals(Optional.of("session-100"), service.getActiveConversationKey("web|admin|client-1"));
    }

    @Test
    void shouldValidateArgumentsForSetPointer() {
        assertThrows(IllegalArgumentException.class, () -> service.setActiveConversationKey(" ", "session-1"));
        assertThrows(IllegalArgumentException.class, () -> service.setActiveConversationKey("web|admin|client-1", " "));
    }

    @Test
    void shouldReturnSnapshotOfPointers() {
        String webPointer = service.buildWebPointerKey("admin", "client-1");
        String tgPointer = service.buildTelegramPointerKey("100");
        service.setActiveConversationKey(webPointer, "session-1");
        service.setActiveConversationKey(tgPointer, "session-2");

        java.util.Map<String, String> snapshot = service.getPointersSnapshot();

        assertEquals("session-1", snapshot.get(webPointer));
        assertEquals("session-2", snapshot.get(tgPointer));
    }

    @Test
    void shouldRollbackClearWhenPersistFails() {
        String pointerKey = service.buildTelegramPointerKey("100");
        service.setActiveConversationKey(pointerKey, "telegram-session");
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk-full")));

        assertThrows(IllegalStateException.class, () -> service.clearActiveConversationKey(pointerKey));
        assertEquals(Optional.of("telegram-session"), service.getActiveConversationKey(pointerKey));
    }

    @Test
    void shouldNotPersistWhenClearingUnknownPointer() {
        service.clearActiveConversationKey("telegram|unknown");

        verify(storagePort, never()).putTextAtomic(org.mockito.ArgumentMatchers.eq("preferences"),
                org.mockito.ArgumentMatchers.eq("session-pointers.json"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(true));
        assertTrue(service.getActiveConversationKey("telegram|unknown").isEmpty());
    }
}

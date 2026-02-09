package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.Memory;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryServiceTest {

    private StoragePort storagePort;
    private BotProperties properties;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        properties = new BotProperties();
        properties.getMemory().setRecentDays(3);
        memoryService = new MemoryService(storagePort, properties);
    }

    // ===== getComponentType =====

    @Test
    void shouldReturnMemoryComponentType() {
        assertEquals("memory", memoryService.getComponentType());
    }

    // ===== readLongTerm =====

    @Test
    void shouldReadLongTermMemory() {
        when(storagePort.getText("memory", "MEMORY.md"))
                .thenReturn(CompletableFuture.completedFuture("Long term content"));

        String result = memoryService.readLongTerm();

        assertEquals("Long term content", result);
    }

    @Test
    void shouldReturnEmptyStringWhenNoLongTermMemory() {
        when(storagePort.getText("memory", "MEMORY.md"))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(new RuntimeException("Not found"))));

        String result = memoryService.readLongTerm();

        assertEquals("", result);
    }

    // ===== writeLongTerm =====

    @Test
    void shouldWriteLongTermMemory() {
        when(storagePort.putText("memory", "MEMORY.md", "New content"))
                .thenReturn(CompletableFuture.completedFuture(null));

        memoryService.writeLongTerm("New content");

        verify(storagePort).putText("memory", "MEMORY.md", "New content");
    }

    @Test
    void shouldHandleWriteFailureGracefully() {
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Write error")));

        assertDoesNotThrow(() -> memoryService.writeLongTerm("Content"));
    }

    // ===== readToday =====

    @Test
    void shouldReadTodayNotes() {
        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
        when(storagePort.getText("memory", todayKey))
                .thenReturn(CompletableFuture.completedFuture("Today's notes"));

        String result = memoryService.readToday();

        assertEquals("Today's notes", result);
    }

    @Test
    void shouldReturnEmptyStringWhenNoTodayNotes() {
        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
        when(storagePort.getText("memory", todayKey))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(new RuntimeException("Not found"))));

        String result = memoryService.readToday();

        assertEquals("", result);
    }

    // ===== appendToday =====

    @Test
    void shouldAppendToTodayNotes() {
        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
        when(storagePort.appendText("memory", todayKey, "New entry"))
                .thenReturn(CompletableFuture.completedFuture(null));

        memoryService.appendToday("New entry");

        verify(storagePort).appendText("memory", todayKey, "New entry");
    }

    @Test
    void shouldHandleAppendFailureGracefully() {
        when(storagePort.appendText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Append error")));

        assertDoesNotThrow(() -> memoryService.appendToday("Entry"));
    }

    // ===== getMemory =====

    @Test
    void shouldBuildMemoryWithAllParts() {
        when(storagePort.getText("memory", "MEMORY.md"))
                .thenReturn(CompletableFuture.completedFuture("Long term"));

        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
        when(storagePort.getText("memory", todayKey))
                .thenReturn(CompletableFuture.completedFuture("Today notes"));

        // Recent days: yesterday, 2 days ago, 3 days ago
        for (int i = 1; i <= 3; i++) {
            String dayKey = LocalDate.now().minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
            when(storagePort.getText("memory", dayKey))
                    .thenReturn(CompletableFuture.completedFuture("Notes for day -" + i));
        }

        Memory memory = memoryService.getMemory();

        assertEquals("Long term", memory.getLongTermContent());
        assertEquals("Today notes", memory.getTodayNotes());
        assertEquals(3, memory.getRecentDays().size());
    }

    @Test
    void shouldSkipBlankRecentDays() {
        when(storagePort.getText("memory", "MEMORY.md"))
                .thenReturn(CompletableFuture.completedFuture(""));

        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
        when(storagePort.getText("memory", todayKey))
                .thenReturn(CompletableFuture.completedFuture(""));

        for (int i = 1; i <= 3; i++) {
            String dayKey = LocalDate.now().minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
            when(storagePort.getText("memory", dayKey))
                    .thenReturn(CompletableFuture.completedFuture("   "));
        }

        Memory memory = memoryService.getMemory();

        assertEquals(0, memory.getRecentDays().size());
    }

    @Test
    void shouldHandleMissingRecentDaysGracefully() {
        when(storagePort.getText(anyString(), anyString()))
                .thenThrow(new RuntimeException("Not found"));

        Memory memory = memoryService.getMemory();

        assertNotNull(memory);
        assertEquals("", memory.getLongTermContent());
        assertEquals("", memory.getTodayNotes());
        assertEquals(0, memory.getRecentDays().size());
    }

    // ===== getMemoryContext =====

    @Test
    void shouldReturnFormattedContext() {
        when(storagePort.getText("memory", "MEMORY.md"))
                .thenReturn(CompletableFuture.completedFuture("Important fact"));

        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
        when(storagePort.getText("memory", todayKey))
                .thenReturn(CompletableFuture.completedFuture(""));

        for (int i = 1; i <= 3; i++) {
            String dayKey = LocalDate.now().minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
            when(storagePort.getText("memory", dayKey))
                    .thenThrow(new RuntimeException("Not found"));
        }

        String context = memoryService.getMemoryContext();

        assertTrue(context.contains("Long-term Memory"));
        assertTrue(context.contains("Important fact"));
    }
}

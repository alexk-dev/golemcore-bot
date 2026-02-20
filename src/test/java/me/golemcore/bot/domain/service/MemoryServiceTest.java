package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.Memory;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryServiceTest {

    private static final String MEMORY_DIR = "memory";
    private static final String MEMORY_FILE = "MEMORY.md";
    private static final String NOT_FOUND = "Not found";
    private static final String MD_EXTENSION = ".md";

    private StoragePort storagePort;
    private BotProperties properties;
    private RuntimeConfigService runtimeConfigService;
    private MemoryWriteService memoryWriteService;
    private MemoryRetrievalService memoryRetrievalService;
    private MemoryPromptPackService memoryPromptPackService;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        properties = new BotProperties();
        properties.getMemory().setDirectory(MEMORY_DIR);
        runtimeConfigService = mock(RuntimeConfigService.class);
        memoryWriteService = mock(MemoryWriteService.class);
        memoryRetrievalService = mock(MemoryRetrievalService.class);
        memoryPromptPackService = mock(MemoryPromptPackService.class);
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemoryRecentDays()).thenReturn(3);
        when(runtimeConfigService.isMemoryLegacyDailyNotesEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemorySoftPromptBudgetTokens()).thenReturn(1800);
        when(runtimeConfigService.getMemoryMaxPromptBudgetTokens()).thenReturn(3500);
        when(runtimeConfigService.getMemoryWorkingTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryEpisodicTopK()).thenReturn(8);
        when(runtimeConfigService.getMemorySemanticTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryProceduralTopK()).thenReturn(4);
        memoryService = new MemoryService(storagePort, properties, runtimeConfigService,
                memoryWriteService, memoryRetrievalService, memoryPromptPackService);
    }

    // ===== getComponentType =====

    @Test
    void shouldReturnMemoryComponentType() {
        assertEquals("memory", memoryService.getComponentType());
    }

    // ===== readLongTerm =====

    @Test
    void shouldReadLongTermMemory() {
        when(storagePort.getText(MEMORY_DIR, MEMORY_FILE))
                .thenReturn(CompletableFuture.completedFuture("Long term content"));

        String result = memoryService.readLongTerm();

        assertEquals("Long term content", result);
    }

    @Test
    void shouldReturnEmptyStringWhenNoLongTermMemory() {
        when(storagePort.getText(MEMORY_DIR, MEMORY_FILE))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(new RuntimeException(NOT_FOUND))));

        String result = memoryService.readLongTerm();

        assertEquals("", result);
    }

    // ===== writeLongTerm =====

    @Test
    void shouldWriteLongTermMemory() {
        when(storagePort.putText(MEMORY_DIR, MEMORY_FILE, "New content"))
                .thenReturn(CompletableFuture.completedFuture(null));

        memoryService.writeLongTerm("New content");

        verify(storagePort).putText(MEMORY_DIR, MEMORY_FILE, "New content");
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
        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + MD_EXTENSION;
        when(storagePort.getText(MEMORY_DIR, todayKey))
                .thenReturn(CompletableFuture.completedFuture("Today's notes"));

        String result = memoryService.readToday();

        assertEquals("Today's notes", result);
    }

    @Test
    void shouldReturnEmptyStringWhenNoTodayNotes() {
        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + MD_EXTENSION;
        when(storagePort.getText(MEMORY_DIR, todayKey))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(new RuntimeException(NOT_FOUND))));

        String result = memoryService.readToday();

        assertEquals("", result);
    }

    // ===== appendToday =====

    @Test
    void shouldAppendToTodayNotes() {
        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + MD_EXTENSION;
        when(storagePort.appendText(MEMORY_DIR, todayKey, "New entry"))
                .thenReturn(CompletableFuture.completedFuture(null));

        memoryService.appendToday("New entry");

        verify(storagePort).appendText(MEMORY_DIR, todayKey, "New entry");
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
        when(storagePort.getText(MEMORY_DIR, MEMORY_FILE))
                .thenReturn(CompletableFuture.completedFuture("Long term"));

        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + MD_EXTENSION;
        when(storagePort.getText(MEMORY_DIR, todayKey))
                .thenReturn(CompletableFuture.completedFuture("Today notes"));

        // Recent days: yesterday, 2 days ago, 3 days ago
        for (int i = 1; i <= 3; i++) {
            String dayKey = LocalDate.now().minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE) + MD_EXTENSION;
            when(storagePort.getText(MEMORY_DIR, dayKey))
                    .thenReturn(CompletableFuture.completedFuture("Notes for day -" + i));
        }

        Memory memory = memoryService.getMemory();

        assertEquals("Long term", memory.getLongTermContent());
        assertEquals("Today notes", memory.getTodayNotes());
        assertEquals(3, memory.getRecentDays().size());
    }

    @Test
    void shouldSkipBlankRecentDays() {
        when(storagePort.getText(MEMORY_DIR, MEMORY_FILE))
                .thenReturn(CompletableFuture.completedFuture(""));

        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + MD_EXTENSION;
        when(storagePort.getText(MEMORY_DIR, todayKey))
                .thenReturn(CompletableFuture.completedFuture(""));

        for (int i = 1; i <= 3; i++) {
            String dayKey = LocalDate.now().minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE) + MD_EXTENSION;
            when(storagePort.getText(MEMORY_DIR, dayKey))
                    .thenReturn(CompletableFuture.completedFuture("   "));
        }

        Memory memory = memoryService.getMemory();

        assertEquals(0, memory.getRecentDays().size());
    }

    @Test
    void shouldHandleMissingRecentDaysGracefully() {
        when(storagePort.getText(anyString(), anyString()))
                .thenThrow(new RuntimeException(NOT_FOUND));

        Memory memory = memoryService.getMemory();

        assertNotNull(memory);
        assertEquals("", memory.getLongTermContent());
        assertEquals("", memory.getTodayNotes());
        assertEquals(0, memory.getRecentDays().size());
    }

    // ===== getMemoryContext =====

    @Test
    void shouldReturnFormattedContext() {
        when(storagePort.getText(MEMORY_DIR, MEMORY_FILE))
                .thenReturn(CompletableFuture.completedFuture("Important fact"));

        String todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + MD_EXTENSION;
        when(storagePort.getText(MEMORY_DIR, todayKey))
                .thenReturn(CompletableFuture.completedFuture(""));

        for (int i = 1; i <= 3; i++) {
            String dayKey = LocalDate.now().minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE) + MD_EXTENSION;
            when(storagePort.getText(MEMORY_DIR, dayKey))
                    .thenThrow(new RuntimeException(NOT_FOUND));
        }

        String context = memoryService.getMemoryContext();

        assertTrue(context.contains("Long-term Memory"));
        assertTrue(context.contains("Important fact"));
    }

    @Test
    void shouldBuildMemoryPackWithStructuredContext() {
        MemoryItem item = MemoryItem.builder()
                .id("m1")
                .type(MemoryItem.Type.PROJECT_FACT)
                .content("Project uses Spring")
                .build();
        when(memoryRetrievalService.retrieve(any()))
                .thenReturn(List.of(MemoryScoredItem.builder().item(item).score(0.9).build()));
        when(memoryPromptPackService.build(any(), any()))
                .thenReturn(MemoryPack.builder()
                        .items(List.of(item))
                        .diagnostics(Map.of("selectedCount", 1))
                        .renderedContext("## Semantic Memory\n- [PROJECT_FACT] Project uses Spring")
                        .build());
        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        MemoryPack pack = memoryService.buildMemoryPack(MemoryQuery.builder().queryText("spring").build());

        assertTrue(pack.getRenderedContext().contains("Semantic Memory"));
        assertEquals(1, pack.getItems().size());
    }
}

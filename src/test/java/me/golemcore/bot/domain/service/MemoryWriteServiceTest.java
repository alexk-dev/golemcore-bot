package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryWriteServiceTest {

    private StoragePort storagePort;
    private RuntimeConfigService runtimeConfigService;
    private MemoryPromotionService memoryPromotionService;
    private MemoryWriteService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        memoryPromotionService = mock(MemoryPromotionService.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        BotProperties properties = new BotProperties();

        service = new MemoryWriteService(
                storagePort,
                properties,
                runtimeConfigService,
                memoryPromotionService,
                objectMapper);

        when(runtimeConfigService.isMemoryEnabled()).thenReturn(true);
        when(runtimeConfigService.isMemoryCodeAwareExtractionEnabled()).thenReturn(false);
        when(memoryPromotionService.isPromotionEnabled()).thenReturn(false);
        when(storagePort.appendText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(""));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void shouldExtractFileReferencesAndExtensionTagsFromTurnContent() throws Exception {
        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .timestamp(Instant.parse("2026-02-22T12:00:00Z"))
                .activeSkill("coding")
                .userText("Check src/main/App.java and dashboard/src/pages/settings/MemoryTab.tsx")
                .assistantText("Updated docs/ADR_MEMORY_V3.md and scripts/build.kts")
                .build();

        service.persistTurnMemory(event);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).appendText(anyString(), anyString(), payloadCaptor.capture());

        MemoryItem persisted = readFirstJsonlItem(payloadCaptor.getValue());
        assertNotNull(persisted);

        List<String> references = persisted.getReferences();
        assertTrue(references.contains("src/main/App.java"));
        assertTrue(references.contains("dashboard/src/pages/settings/MemoryTab.tsx"));
        assertTrue(references.contains("docs/ADR_MEMORY_V3.md"));
        assertTrue(references.contains("scripts/build.kts"));

        List<String> tags = persisted.getTags();
        assertTrue(tags.contains("coding"));
        assertTrue(tags.contains("java"));
        assertTrue(tags.contains("tsx"));
        assertTrue(tags.contains("md"));
        assertTrue(tags.contains("kts"));
    }

    @Test
    void shouldIgnoreUnsupportedFileExtensions() throws Exception {
        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .timestamp(Instant.parse("2026-02-22T12:00:00Z"))
                .userText("Ignore archive.tar.gz and script.exe, keep config.yaml and query.sql")
                .assistantText("Also skip image.png and notes.docx")
                .build();

        service.persistTurnMemory(event);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).appendText(anyString(), anyString(), payloadCaptor.capture());

        MemoryItem persisted = readFirstJsonlItem(payloadCaptor.getValue());
        List<String> references = persisted.getReferences();

        assertTrue(references.contains("config.yaml"));
        assertTrue(references.contains("query.sql"));
        assertFalse(references.contains("archive.tar.gz"));
        assertFalse(references.contains("script.exe"));
        assertFalse(references.contains("image.png"));
        assertFalse(references.contains("notes.docx"));
    }

    @Test
    void shouldExtractCaseInsensitiveExtensionsAndTrimTrailingDotPunctuation() throws Exception {
        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .timestamp(Instant.parse("2026-02-22T12:00:00Z"))
                .activeSkill("coding")
                .userText("Use src/main/App.JAVA. and docs/README.MD...")
                .assistantText("Patched api/UserService.KT), updated ui/Panel.TSX.")
                .build();

        MemoryItem persisted = persistAndRead(event);
        List<String> references = persisted.getReferences();
        List<String> tags = persisted.getTags();

        assertTrue(references.contains("src/main/App.JAVA"));
        assertTrue(references.contains("docs/README.MD"));
        assertTrue(references.contains("api/UserService.KT"));
        assertTrue(references.contains("ui/Panel.TSX"));
        assertTrue(tags.contains("java"));
        assertTrue(tags.contains("md"));
        assertTrue(tags.contains("kt"));
        assertTrue(tags.contains("tsx"));
    }

    @Test
    void shouldDeduplicateFileAndTestReferencesAcrossTurnContent() throws Exception {
        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .timestamp(Instant.parse("2026-02-22T12:00:00Z"))
                .userText("See src/main/App.java and SessionServiceTest#shouldHandle")
                .assistantText("Duplicate refs: src/main/App.java SessionServiceTest#shouldHandle")
                .build();

        MemoryItem persisted = persistAndRead(event);
        List<String> references = persisted.getReferences();

        assertEquals(2, references.size());
        assertTrue(references.contains("src/main/App.java"));
        assertTrue(references.contains("SessionServiceTest#shouldHandle"));
    }

    @Test
    void shouldHandleLargeInputWithoutPathologicalSlowdown() throws Exception {
        String longNoise = "a".repeat(250_000);
        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .timestamp(Instant.parse("2026-02-22T12:00:00Z"))
                .userText(longNoise + " src/main/App.java")
                .assistantText("done")
                .build();

        assertTimeout(Duration.ofSeconds(2), () -> {
            MemoryItem persisted = persistAndRead(event);
            assertTrue(persisted.getReferences().contains("src/main/App.java"));
        });
    }

    @Test
    void shouldSkipPersistWhenMemoryIsDisabled() {
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(false);

        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .timestamp(Instant.parse("2026-02-22T12:00:00Z"))
                .userText("src/main/App.java")
                .assistantText("ack")
                .build();

        service.persistTurnMemory(event);

        verify(storagePort, never()).appendText(anyString(), anyString(), anyString());
    }

    private MemoryItem persistAndRead(TurnMemoryEvent event) throws Exception {
        service.persistTurnMemory(event);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).appendText(anyString(), anyString(), payloadCaptor.capture());
        return readFirstJsonlItem(payloadCaptor.getValue());
    }

    private MemoryItem readFirstJsonlItem(String payload) throws Exception {
        String[] lines = payload.split("\\R");
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                return objectMapper.readValue(line, MemoryItem.class);
            }
        }
        return null;
    }
}

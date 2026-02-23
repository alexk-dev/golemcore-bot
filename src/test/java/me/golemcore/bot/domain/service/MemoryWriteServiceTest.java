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
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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

    @Test
    void shouldExtractDerivedItemsWhenCodeAwareExtractionEnabled() throws Exception {
        when(runtimeConfigService.isMemoryCodeAwareExtractionEnabled()).thenReturn(true);

        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .timestamp(Instant.parse("2026-02-22T12:00:00Z"))
                .activeSkill("coding")
                .userText("We must keep API compatibility. Build failed with NullPointerException.")
                .assistantText("Issue fixed and resolved with a patch solution.")
                .toolOutputs(List.of("stacktrace in SessionService.java"))
                .build();

        service.persistTurnMemory(event);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).appendText(anyString(), anyString(), payloadCaptor.capture());
        List<MemoryItem> items = readJsonlItems(payloadCaptor.getValue());

        assertEquals(4, items.size());
        Set<MemoryItem.Type> types = new HashSet<>();
        for (MemoryItem item : items) {
            types.add(item.getType());
        }
        assertTrue(types.contains(MemoryItem.Type.TASK_STATE));
        assertTrue(types.contains(MemoryItem.Type.CONSTRAINT));
        assertTrue(types.contains(MemoryItem.Type.FAILURE));
        assertTrue(types.contains(MemoryItem.Type.FIX));
    }

    @Test
    void shouldPromoteToSemanticAndProceduralWhenEnabled() {
        when(runtimeConfigService.isMemoryCodeAwareExtractionEnabled()).thenReturn(true);
        when(memoryPromotionService.isPromotionEnabled()).thenReturn(true);
        when(memoryPromotionService.shouldPromoteToSemantic(org.mockito.ArgumentMatchers
                .argThat(item -> item != null && item.getType() == MemoryItem.Type.CONSTRAINT))).thenReturn(true);
        when(memoryPromotionService.shouldPromoteToProcedural(org.mockito.ArgumentMatchers.argThat(item -> item != null
                && (item.getType() == MemoryItem.Type.FAILURE || item.getType() == MemoryItem.Type.FIX))))
                .thenReturn(true);

        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .timestamp(Instant.parse("2026-02-22T12:00:00Z"))
                .userText("We must keep compatibility; build failed with exception; issue fixed.")
                .assistantText("resolved and patched")
                .build();

        service.persistTurnMemory(event);

        verify(storagePort, atLeastOnce()).putTextAtomic(eq("memory"), eq("items/semantic.jsonl"), anyString(),
                eq(true));
        verify(storagePort, atLeastOnce()).putTextAtomic(eq("memory"), eq("items/procedural.jsonl"), anyString(),
                eq(true));
    }

    @Test
    void shouldSkipPersistWhenTurnContainsNoContent() {
        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .timestamp(Instant.parse("2026-02-22T12:00:00Z"))
                .userText(" ")
                .assistantText(" ")
                .build();

        service.persistTurnMemory(event);

        verify(storagePort, never()).appendText(anyString(), anyString(), anyString());
    }

    @Test
    void shouldApplyDecayAndTtlDuringSemanticUpsert() throws Exception {
        when(runtimeConfigService.isMemoryDecayEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemoryDecayDays()).thenReturn(7);

        Instant now = Instant.now();
        MemoryItem fresh = MemoryItem.builder()
                .id("fresh")
                .fingerprint("fresh-fp")
                .layer(MemoryItem.Layer.SEMANTIC)
                .type(MemoryItem.Type.PROJECT_FACT)
                .content("fresh")
                .createdAt(now.minus(1, ChronoUnit.DAYS))
                .updatedAt(now.minus(1, ChronoUnit.DAYS))
                .build();
        MemoryItem ttlExpired = MemoryItem.builder()
                .id("ttl-expired")
                .fingerprint("ttl-fp")
                .layer(MemoryItem.Layer.SEMANTIC)
                .type(MemoryItem.Type.PROJECT_FACT)
                .content("ttl")
                .ttlDays(1)
                .createdAt(now.minus(3, ChronoUnit.DAYS))
                .updatedAt(now.minus(3, ChronoUnit.DAYS))
                .build();
        MemoryItem decayExpired = MemoryItem.builder()
                .id("decay-expired")
                .fingerprint("decay-fp")
                .layer(MemoryItem.Layer.SEMANTIC)
                .type(MemoryItem.Type.PROJECT_FACT)
                .content("old")
                .createdAt(now.minus(20, ChronoUnit.DAYS))
                .updatedAt(now.minus(20, ChronoUnit.DAYS))
                .build();
        when(storagePort.getText("memory", "items/semantic.jsonl"))
                .thenReturn(CompletableFuture.completedFuture(toJsonl(List.of(fresh, ttlExpired, decayExpired))));

        MemoryItem incoming = MemoryItem.builder()
                .id("new-item")
                .fingerprint("new-fp")
                .type(MemoryItem.Type.PROJECT_FACT)
                .title("new")
                .content("new content")
                .build();

        service.upsertSemanticItem(incoming);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).putTextAtomic(eq("memory"), eq("items/semantic.jsonl"), payloadCaptor.capture(), eq(true));

        List<MemoryItem> items = readJsonlItems(payloadCaptor.getValue());
        Set<String> ids = new HashSet<>();
        for (MemoryItem item : items) {
            ids.add(item.getId());
        }

        assertTrue(ids.contains("fresh"));
        assertTrue(ids.contains("new-item"));
        assertFalse(ids.contains("ttl-expired"));
        assertFalse(ids.contains("decay-expired"));
    }

    @Test
    void shouldMergeExistingItemByFingerprintDuringUpsert() throws Exception {
        MemoryItem existing = MemoryItem.builder()
                .id("existing")
                .fingerprint("same-fp")
                .layer(MemoryItem.Layer.SEMANTIC)
                .type(MemoryItem.Type.PROJECT_FACT)
                .title("Old title")
                .content("short")
                .confidence(0.60)
                .salience(0.55)
                .tags(List.of("java"))
                .references(List.of("A.java"))
                .createdAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .updatedAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .build();
        when(storagePort.getText("memory", "items/semantic.jsonl"))
                .thenReturn(CompletableFuture.completedFuture(toJsonl(List.of(existing))));

        MemoryItem incoming = MemoryItem.builder()
                .id("new-id")
                .fingerprint("same-fp")
                .type(MemoryItem.Type.DECISION)
                .title("New title")
                .content("much longer replacement content")
                .confidence(0.82)
                .salience(0.88)
                .ttlDays(30)
                .tags(List.of("spring"))
                .references(List.of("B.java"))
                .build();

        service.upsertSemanticItem(incoming);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).putTextAtomic(eq("memory"), eq("items/semantic.jsonl"), payloadCaptor.capture(), eq(true));

        List<MemoryItem> items = readJsonlItems(payloadCaptor.getValue());
        assertEquals(1, items.size());
        MemoryItem merged = items.get(0);
        assertEquals("existing", merged.getId());
        assertEquals(MemoryItem.Type.DECISION, merged.getType());
        assertEquals("New title", merged.getTitle());
        assertEquals("much longer replacement content", merged.getContent());
        assertEquals(0.82, merged.getConfidence());
        assertEquals(0.88, merged.getSalience());
        assertEquals(Integer.valueOf(30), merged.getTtlDays());
        assertTrue(merged.getTags().contains("java"));
        assertTrue(merged.getTags().contains("spring"));
        assertTrue(merged.getReferences().contains("A.java"));
        assertTrue(merged.getReferences().contains("B.java"));
    }

    @Test
    void shouldSkipDirectUpsertWhenMemoryDisabled() {
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(false);

        service.upsertProceduralItem(MemoryItem.builder()
                .id("p1")
                .content("procedure")
                .build());

        verify(storagePort, never()).putTextAtomic(anyString(), anyString(), anyString(), anyBoolean());
    }

    private MemoryItem persistAndRead(TurnMemoryEvent event) throws Exception {
        service.persistTurnMemory(event);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).appendText(anyString(), anyString(), payloadCaptor.capture());
        return readFirstJsonlItem(payloadCaptor.getValue());
    }

    private List<MemoryItem> readJsonlItems(String payload) throws Exception {
        List<MemoryItem> items = new java.util.ArrayList<>();
        String[] lines = payload.split("\\R");
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                items.add(objectMapper.readValue(line, MemoryItem.class));
            }
        }
        return items;
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

    private String toJsonl(List<MemoryItem> items) throws Exception {
        StringBuilder payload = new StringBuilder();
        for (MemoryItem item : items) {
            payload.append(objectMapper.writeValueAsString(item)).append("\n");
        }
        return payload.toString();
    }
}

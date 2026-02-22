package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryRetrievalServiceTest {

    private StoragePort storagePort;
    private RuntimeConfigService runtimeConfigService;
    private MemoryRetrievalService service;
    private ObjectMapper objectMapper;
    private Map<String, String> storedJsonl;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        storedJsonl = new HashMap<>();
        BotProperties properties = new BotProperties();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new MemoryRetrievalService(storagePort, properties, runtimeConfigService, objectMapper);

        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(
                        storedJsonl.getOrDefault(invocation.getArgument(1), "")));
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemorySoftPromptBudgetTokens()).thenReturn(1800);
        when(runtimeConfigService.getMemoryMaxPromptBudgetTokens()).thenReturn(3500);
        when(runtimeConfigService.getMemoryWorkingTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryEpisodicTopK()).thenReturn(8);
        when(runtimeConfigService.getMemorySemanticTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryProceduralTopK()).thenReturn(4);
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(3);
        when(runtimeConfigService.isMemoryDecayEnabled()).thenReturn(false);
        when(runtimeConfigService.getMemoryDecayDays()).thenReturn(30);
    }

    @Test
    void shouldReturnEmptyWhenMemoryDisabled() {
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(false);

        List<MemoryScoredItem> result = service.retrieve(MemoryQuery.builder().queryText("incident").build());

        assertTrue(result.isEmpty());
        verify(storagePort, never()).getText(anyString(), anyString());
    }

    @Test
    void shouldUseConfiguredRetrievalLookbackWhenDecayDisabled() {
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(3);

        assertTrue(service.retrieve(MemoryQuery.builder().queryText("incident").build()).isEmpty());

        verify(storagePort, times(5)).getText(eq("memory"), anyString());
    }

    @Test
    void shouldClampRetrievalLookbackToInternalMax() {
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(999);

        assertTrue(service.retrieve(MemoryQuery.builder().queryText("incident").build()).isEmpty());

        verify(storagePort, times(92)).getText(eq("memory"), anyString());
    }

    @Test
    void shouldClampRetrievalLookbackToMinimumOneDay() {
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(0);

        assertTrue(service.retrieve(MemoryQuery.builder().queryText("incident").build()).isEmpty());

        verify(storagePort, times(3)).getText(eq("memory"), anyString());
    }

    @Test
    void shouldIgnoreInvalidJsonLinesAndStillReturnValidItems() throws Exception {
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(1);
        MemoryItem valid = item("s-valid", MemoryItem.Layer.SEMANTIC, MemoryItem.Type.PROJECT_FACT, "redis config",
                Instant.now(), Instant.now(), "fp-valid", null, List.of("redis"));
        String payload = "{not-json}\n" + objectMapper.writeValueAsString(valid) + "\n";
        storedJsonl.put("items/semantic.jsonl", payload);

        List<MemoryScoredItem> result = service.retrieve(MemoryQuery.builder()
                .queryText("redis")
                .workingTopK(0)
                .episodicTopK(0)
                .semanticTopK(5)
                .proceduralTopK(0)
                .build());

        assertEquals(1, result.size());
        assertEquals("s-valid", result.get(0).getItem().getId());
    }

    @Test
    void shouldFilterByTtlAndDecayWhenEnabled() throws Exception {
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(1);
        when(runtimeConfigService.isMemoryDecayEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemoryDecayDays()).thenReturn(30);

        Instant now = Instant.now();
        MemoryItem fresh = item("fresh", MemoryItem.Layer.SEMANTIC, MemoryItem.Type.PROJECT_FACT, "fresh fact",
                now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), "fresh-fp", null, List.of("project"));
        MemoryItem ttlExpired = item("ttl-expired", MemoryItem.Layer.SEMANTIC, MemoryItem.Type.PROJECT_FACT,
                "expired ttl", now.minus(10, ChronoUnit.DAYS), now.minus(10, ChronoUnit.DAYS), "ttl-fp", 3,
                List.of("project"));
        MemoryItem decayExpired = item("decay-expired", MemoryItem.Layer.SEMANTIC, MemoryItem.Type.PROJECT_FACT,
                "old by decay", now.minus(40, ChronoUnit.DAYS), now.minus(40, ChronoUnit.DAYS), "decay-fp", null,
                List.of("project"));
        MemoryItem noTimestamp = item("no-ts", MemoryItem.Layer.SEMANTIC, MemoryItem.Type.PROJECT_FACT, "untimed fact",
                null, null, "no-ts-fp", null, List.of("project"));

        putJsonl("items/semantic.jsonl", List.of(fresh, ttlExpired, decayExpired, noTimestamp));

        List<MemoryScoredItem> result = service.retrieve(MemoryQuery.builder()
                .queryText("fact")
                .workingTopK(0)
                .episodicTopK(0)
                .semanticTopK(10)
                .proceduralTopK(0)
                .build());

        Set<String> ids = new HashSet<>();
        for (MemoryScoredItem scored : result) {
            ids.add(scored.getItem().getId());
        }
        assertTrue(ids.contains("fresh"));
        assertTrue(ids.contains("no-ts"));
        assertTrue(!ids.contains("ttl-expired"));
        assertTrue(!ids.contains("decay-expired"));
    }

    @Test
    void shouldApplyTopKPerLayerAndDeduplicateByFingerprint() throws Exception {
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(1);

        Instant now = Instant.now();
        MemoryItem episodicBest = item("e-best", MemoryItem.Layer.EPISODIC, MemoryItem.Type.FAILURE,
                "redis failure", now.minus(1, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), "e-best-fp", null,
                List.of("ops"));
        MemoryItem episodicOther = item("e-other", MemoryItem.Layer.EPISODIC, MemoryItem.Type.TASK_STATE,
                "random note", now.minus(1, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), "e-other-fp", null,
                List.of("ops"));
        putJsonl(todayEpisodicPath(), List.of(episodicBest, episodicOther));

        MemoryItem semanticOne = item("s-1", MemoryItem.Layer.SEMANTIC, MemoryItem.Type.DECISION,
                "redis architecture decision", now, now, "dup-fp", null, List.of("backend"));
        MemoryItem semanticDup = item("s-2", MemoryItem.Layer.SEMANTIC, MemoryItem.Type.DECISION,
                "redis architecture decision", now, now, "dup-fp", null, List.of("backend"));
        putJsonl("items/semantic.jsonl", List.of(semanticOne, semanticDup));

        MemoryItem procedural = item("p-1", MemoryItem.Layer.PROCEDURAL, MemoryItem.Type.FIX,
                "fix redis failure by reloading cache", now, now, "p-1-fp", null, List.of("ops"));
        putJsonl("items/procedural.jsonl", List.of(procedural));

        List<MemoryScoredItem> result = service.retrieve(MemoryQuery.builder()
                .queryText("redis failure")
                .activeSkill("ops")
                .workingTopK(0)
                .episodicTopK(1)
                .semanticTopK(2)
                .proceduralTopK(1)
                .build());

        Set<String> ids = new HashSet<>();
        Set<String> fingerprints = new HashSet<>();
        int duplicateFingerprintCount = 0;
        for (MemoryScoredItem scored : result) {
            ids.add(scored.getItem().getId());
            String fingerprint = scored.getItem().getFingerprint();
            if (fingerprint != null && !fingerprints.add(fingerprint)) {
                duplicateFingerprintCount++;
            }
        }

        assertEquals(3, result.size());
        assertTrue(ids.contains("e-best"));
        assertTrue(ids.contains("p-1"));
        assertTrue(ids.contains("s-1") || ids.contains("s-2"));
        assertTrue(!ids.contains("e-other"));
        assertEquals(0, duplicateFingerprintCount);
    }

    @Test
    void shouldPreferSkillMatchedItemsWhenScoresOtherwiseEqual() throws Exception {
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(1);

        Instant now = Instant.now();
        MemoryItem skillMatched = item("p-skill", MemoryItem.Layer.PROCEDURAL, MemoryItem.Type.FIX,
                "refactor pipeline safely", now, now, "p-skill-fp", null, List.of("coding"));
        MemoryItem plain = item("p-plain", MemoryItem.Layer.PROCEDURAL, MemoryItem.Type.FIX,
                "refactor pipeline safely", now, now, "p-plain-fp", null, List.of("ops"));
        putJsonl("items/procedural.jsonl", List.of(skillMatched, plain));

        List<MemoryScoredItem> result = service.retrieve(MemoryQuery.builder()
                .queryText("refactor pipeline")
                .activeSkill("coding")
                .workingTopK(0)
                .episodicTopK(0)
                .semanticTopK(0)
                .proceduralTopK(2)
                .build());

        assertEquals(2, result.size());
        assertEquals("p-skill", result.get(0).getItem().getId());
    }

    private void putJsonl(String path, List<MemoryItem> items) throws Exception {
        StringBuilder payload = new StringBuilder();
        for (MemoryItem item : items) {
            payload.append(objectMapper.writeValueAsString(item)).append("\n");
        }
        storedJsonl.put(path, payload.toString());
    }

    private String todayEpisodicPath() {
        String date = LocalDate.now(ZoneId.systemDefault()).toString();
        return "items/episodic/" + date + ".jsonl";
    }

    private MemoryItem item(String id, MemoryItem.Layer layer, MemoryItem.Type type, String content,
            Instant createdAt, Instant updatedAt, String fingerprint, Integer ttlDays, List<String> tags) {
        return MemoryItem.builder()
                .id(id)
                .layer(layer)
                .type(type)
                .title(id)
                .content(content)
                .tags(tags)
                .confidence(0.80)
                .salience(0.80)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .fingerprint(fingerprint)
                .ttlDays(ttlDays)
                .build();
    }
}

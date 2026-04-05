package me.golemcore.bot.domain.selfevolving.tactic;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TacticRecordServiceTest {

    private StoragePort storagePort;
    private Map<String, String> persistedFiles;
    private TacticRecordService tacticRecordService;
    private TacticIndexRebuildService rebuildService;
    private ObjectProvider<TacticIndexRebuildService> rebuildServiceProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        storagePort = mock(StoragePort.class);
        persistedFiles = new ConcurrentHashMap<>();
        rebuildService = mock(TacticIndexRebuildService.class);
        rebuildServiceProvider = mock(ObjectProvider.class);
        when(rebuildServiceProvider.getIfAvailable()).thenReturn(rebuildService);

        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String directory = invocation.getArgument(0);
                    String path = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedFiles.put(directory + "/" + path, content);
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(
                        persistedFiles.get(invocation.getArgument(0) + "/" + invocation.getArgument(1))));
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String directory = invocation.getArgument(0);
                    String path = invocation.getArgument(1);
                    persistedFiles.remove(directory + "/" + path);
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.listObjects(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String directory = invocation.getArgument(0);
                    String prefix = invocation.getArgument(1);
                    List<String> keys = persistedFiles.keySet().stream()
                            .filter(key -> key.startsWith(directory + "/"))
                            .map(key -> key.substring(directory.length() + 1))
                            .filter(path -> path.startsWith(prefix))
                            .sorted()
                            .toList();
                    return CompletableFuture.completedFuture(keys);
                });

        tacticRecordService = new TacticRecordService(
                storagePort,
                Clock.fixed(Instant.parse("2026-04-01T21:00:00Z"), ZoneOffset.UTC),
                rebuildServiceProvider,
                null);
    }

    @Test
    void shouldPersistTacticsOutsideCuratedSkillsDirectory() {
        TacticRecord record = tacticRecordService.save(sampleTactic());

        assertEquals("stream-1", record.getArtifactStreamId());
        assertFalse(storagePort.listObjects("self-evolving", "tactics").join().isEmpty());
        assertTrue(persistedFiles.keySet().stream().anyMatch(path -> path.startsWith("self-evolving/tactics/")));
        verify(storagePort, never()).putText(eq("skills"), anyString(), anyString());
        verify(storagePort).putTextAtomic(eq("self-evolving"), eq("tactics/tactic-1.json"), anyString(), eq(true));
        verify(rebuildService, times(1)).onTacticChanged("tactic-1");
    }

    @Test
    void shouldLoadAndNormalizePersistedTacticsWhileIgnoringBrokenFiles() {
        persistedFiles.put("self-evolving/tactics/tactic-b.json", """
                {
                  "tacticId": " tactic-b ",
                  "artifactKey": "prompt:toolloop_core",
                  "artifactType": "prompt",
                  "contentRevisionId": "revision-b",
                  "successRate": 0.8,
                  "updatedAt": "2026-04-01T20:00:00Z"
                }
                """);
        persistedFiles.put("self-evolving/tactics/tactic-a.json", """
                {
                  "tacticId": "tactic-a",
                  "artifactStreamId": "stream-a",
                  "artifactKey": "skill:planner",
                  "artifactType": "skill",
                  "title": "Planner tactic",
                  "contentRevisionId": "revision-a",
                  "updatedAt": "2026-04-01T21:30:00Z"
                }
                """);
        persistedFiles.put("self-evolving/tactics/invalid.json", "{");
        persistedFiles.put("self-evolving/tactics/readme.txt", "ignore");

        List<TacticRecord> records = tacticRecordService.getAll();

        assertEquals(List.of("tactic-a", "tactic-b"), records.stream().map(TacticRecord::getTacticId).toList());
        TacticRecord normalized = records.get(1);
        assertEquals("prompt:toolloop_core", normalized.getAliases().getFirst());
        assertEquals("Prompt toolloop core", normalized.getTitle());
        assertEquals("candidate", normalized.getPromotionState());
        assertEquals("proposed", normalized.getRolloutStage());
        assertNull(records.getFirst().getSuccessRate());
        assertNull(records.getFirst().getBenchmarkWinRate());
        assertNull(records.getFirst().getGolemLocalUsageSuccess());
        assertEquals("pending", normalized.getEmbeddingStatus());
        assertEquals(List.of("prompt"), normalized.getTaskFamilies());
        assertEquals(List.of("prompt", "candidate"), normalized.getTags());
    }

    @Test
    void shouldReturnEmptyWhenLookupIdIsBlank() {
        Optional<TacticRecord> result = tacticRecordService.getById("   ");

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldRefreshCachedRecordWhenSavingUpdatedTactic() {
        persistedFiles.put("self-evolving/tactics/tactic-1.json", """
                {
                  "tacticId": "tactic-1",
                  "artifactStreamId": "stream-1",
                  "artifactKey": "skill:planner",
                  "artifactType": "skill",
                  "title": "Old planner",
                  "contentRevisionId": "revision-1",
                  "updatedAt": "2026-04-01T19:00:00Z"
                }
                """);

        List<TacticRecord> initial = tacticRecordService.getAll();
        TacticRecord saved = tacticRecordService.save(TacticRecord.builder()
                .tacticId("tactic-1")
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .title("New planner")
                .contentRevisionId("revision-2")
                .updatedAt(Instant.parse("2026-04-01T22:00:00Z"))
                .build());
        List<TacticRecord> records = tacticRecordService.getAll();

        assertEquals(1, initial.size());
        assertEquals("New planner", saved.getTitle());
        assertEquals(1, records.size());
        assertEquals("revision-2", records.getFirst().getContentRevisionId());
        assertEquals("New planner", records.getFirst().getTitle());
        assertNull(persistedFiles.get("skills/tactic-1.json"));
    }

    @Test
    void shouldDeactivatePersistedTacticAndTriggerRebuild() {
        tacticRecordService.save(sampleTactic());

        TacticRecord deactivated = tacticRecordService.deactivate("tactic-1");

        assertEquals("inactive", deactivated.getPromotionState());
        assertEquals("inactive", deactivated.getRolloutStage());
        assertEquals("inactive", tacticRecordService.getById("tactic-1").orElseThrow().getPromotionState());
        verify(rebuildService, times(2)).onTacticChanged("tactic-1");
    }

    @Test
    void shouldReactivatePersistedTacticAndTriggerRebuild() {
        tacticRecordService.save(sampleTactic());
        tacticRecordService.deactivate("tactic-1");

        TacticRecord reactivated = tacticRecordService.reactivate("tactic-1");

        assertEquals("active", reactivated.getPromotionState());
        assertEquals("active", reactivated.getRolloutStage());
        assertEquals("active", tacticRecordService.getById("tactic-1").orElseThrow().getPromotionState());
        verify(rebuildService, times(3)).onTacticChanged("tactic-1");
    }

    @Test
    void shouldDeletePersistedTacticAndTriggerRebuild() {
        tacticRecordService.save(sampleTactic());
        tacticRecordService.getAll();

        tacticRecordService.delete("tactic-1");

        assertTrue(tacticRecordService.getAll().isEmpty());
        assertTrue(tacticRecordService.getById("tactic-1").isEmpty());
        assertFalse(persistedFiles.containsKey("self-evolving/tactics/tactic-1.json"));
        verify(storagePort).deleteObject("self-evolving", "tactics/tactic-1.json");
        verify(rebuildService, times(2)).onTacticChanged("tactic-1");
    }

    @Test
    void shouldUpdateEmbeddingStatusWithoutTriggeringAnotherRebuild() {
        tacticRecordService.save(sampleTactic());
        clearInvocations(rebuildService);

        tacticRecordService.updateEmbeddingStatuses(Map.of("tactic-1", "indexed"));

        assertEquals("indexed", tacticRecordService.getById("tactic-1").orElseThrow().getEmbeddingStatus());
        assertTrue(
                persistedFiles.get("self-evolving/tactics/tactic-1.json").contains("\"embeddingStatus\":\"indexed\""));
        verify(rebuildService, never()).onTacticChanged("tactic-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldEnrichReturnedRecordsWhenQualityMetricsServiceIsAvailable() {
        ObjectProvider<TacticQualityMetricsService> qualityMetricsServiceProvider = mock(ObjectProvider.class);
        TacticQualityMetricsService qualityMetricsService = mock(TacticQualityMetricsService.class);
        when(qualityMetricsServiceProvider.getIfAvailable()).thenReturn(qualityMetricsService);
        when(qualityMetricsService.enrich(any(TacticRecord.class))).thenAnswer(invocation -> {
            TacticRecord input = invocation.getArgument(0);
            TacticRecord enriched = TacticRecord.builder()
                    .tacticId(input.getTacticId())
                    .artifactStreamId(input.getArtifactStreamId())
                    .originArtifactStreamId(input.getOriginArtifactStreamId())
                    .artifactKey(input.getArtifactKey())
                    .artifactType(input.getArtifactType())
                    .title(input.getTitle())
                    .aliases(input.getAliases())
                    .contentRevisionId(input.getContentRevisionId())
                    .intentSummary(input.getIntentSummary())
                    .behaviorSummary(input.getBehaviorSummary())
                    .promotionState(input.getPromotionState())
                    .rolloutStage(input.getRolloutStage())
                    .updatedAt(input.getUpdatedAt())
                    .successRate(0.75d)
                    .golemLocalUsageSuccess(0.75d)
                    .build();
            return enriched;
        });
        TacticRecordService enrichedService = new TacticRecordService(
                storagePort,
                Clock.fixed(Instant.parse("2026-04-01T21:00:00Z"), ZoneOffset.UTC),
                rebuildServiceProvider,
                qualityMetricsServiceProvider);
        enrichedService.save(sampleTactic());

        TacticRecord record = enrichedService.getById("tactic-1").orElseThrow();

        assertEquals(0.75d, record.getSuccessRate());
        assertEquals(0.75d, record.getGolemLocalUsageSuccess());
        verify(qualityMetricsService).enrich(any(TacticRecord.class));
    }

    private TacticRecord sampleTactic() {
        return TacticRecord.builder()
                .tacticId("tactic-1")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .title("Planner tactic")
                .contentRevisionId("revision-1")
                .intentSummary("Plan multi-step work")
                .behaviorSummary("Produces reusable planning steps")
                .promotionState("approved")
                .rolloutStage("approved")
                .build();
    }
}

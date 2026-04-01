package me.golemcore.bot.domain.service;

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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TacticRecordServiceTest {

    private StoragePort storagePort;
    private Map<String, String> persistedFiles;
    private TacticRecordService tacticRecordService;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        persistedFiles = new ConcurrentHashMap<>();

        when(storagePort.putText(anyString(), anyString(), anyString()))
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
                Clock.fixed(Instant.parse("2026-04-01T21:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldPersistTacticsOutsideCuratedSkillsDirectory() {
        TacticRecord record = tacticRecordService.save(sampleTactic());

        assertEquals("stream-1", record.getArtifactStreamId());
        assertFalse(storagePort.listObjects("self-evolving", "tactics").join().isEmpty());
        assertTrue(persistedFiles.keySet().stream().anyMatch(path -> path.startsWith("self-evolving/tactics/")));
        verify(storagePort, never()).putText(eq("skills"), anyString(), anyString());
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

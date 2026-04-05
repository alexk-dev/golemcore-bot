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

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvolutionCandidateServiceTacticRecordTest {

    private StoragePort storagePort;
    private Map<String, String> persistedFiles;
    private TacticRecordService tacticRecordService;
    private EvolutionCandidateService evolutionCandidateService;

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

        Clock clock = Clock.fixed(Instant.parse("2026-04-01T21:15:00Z"), ZoneOffset.UTC);
        tacticRecordService = new TacticRecordService(storagePort, clock, null);
        evolutionCandidateService = new EvolutionCandidateService(storagePort, tacticRecordService, clock);
    }

    @Test
    void shouldSkipTacticRecordForPlaceholderDiffAndStillCreateCandidate() {
        List<EvolutionCandidate> candidates = evolutionCandidateService.deriveCandidates(
                RunRecord.builder()
                        .id("run-derive")
                        .golemId("golem-1")
                        .artifactBundleId("bundle-1")
                        .build(),
                RunVerdict.builder()
                        .outcomeStatus("COMPLETED")
                        .processStatus("CLEAN")
                        .confidence(0.91)
                        .evidenceRefs(List.of(VerdictEvidenceRef.builder()
                                .traceId("trace-derive")
                                .spanId("skill-1")
                                .outputFragment("Planner tactic worked")
                                .build()))
                        .build());

        List<TacticRecord> tactics = tacticRecordService.getAll();

        assertEquals(1, candidates.size());
        assertEquals("selfevolving:derive:skill", candidates.getFirst().getProposedDiff());
        assertEquals(0, tactics.size());
        verify(storagePort, never()).putText(eq("skills"), anyString(), anyString());
    }

    @Test
    void shouldActivateCandidateUsingStructuredProposalSemantics() {
        EvolutionCandidate candidate = evolutionCandidateService.ensureArtifactIdentity(EvolutionCandidate.builder()
                .id("candidate-activate")
                .artifactType("skill")
                .status("proposed")
                .expectedImpact("Promote planner tactic immediately")
                .proposedDiff("selfevolving:derive:skill")
                .proposal(EvolutionProposal.builder()
                        .summary("Capture the successful planner tactic as reusable guidance")
                        .behaviorInstructions(
                                "Reuse the planner sequence when the task requires stepwise decomposition.")
                        .toolInstructions("Prefer planning before tool execution when the task spans multiple steps.")
                        .expectedOutcome("Increase success on multi-step tasks without extra retries.")
                        .approvalNotes("Promoted after reviewing the successful derive run.")
                        .build())
                .build());

        evolutionCandidateService.activateAsTactic(candidate);

        TacticRecord tactic = tacticRecordService.getById(candidate.getContentRevisionId()).orElseThrow();
        assertEquals(candidate.getContentRevisionId(), tactic.getTacticId());
        assertEquals("active", tactic.getPromotionState());
        assertEquals("active", tactic.getRolloutStage());
        assertEquals("Capture the successful planner tactic as reusable guidance", tactic.getIntentSummary());
        assertEquals("Reuse the planner sequence when the task requires stepwise decomposition.",
                tactic.getBehaviorSummary());
        assertEquals("Prefer planning before tool execution when the task spans multiple steps.",
                tactic.getToolSummary());
        assertEquals("Increase success on multi-step tasks without extra retries.", tactic.getOutcomeSummary());
        assertEquals("Promoted after reviewing the successful derive run.", tactic.getApprovalNotes());
        assertNull(tactic.getSuccessRate());
        assertNull(tactic.getBenchmarkWinRate());
        assertNull(tactic.getGolemLocalUsageSuccess());
    }
}

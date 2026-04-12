package me.golemcore.bot.domain.selfevolving.candidate;

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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.domain.selfevolving.artifact.EvolutionArtifactIdentityService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;

/**
 * Produces first-pass evolution candidates from judged run evidence and writes
 * immutable artifact revisions for every proposed change.
 */
@Service
@Slf4j
public class EvolutionCandidateService {

    private final TacticRecordService tacticRecordService;
    private final ArtifactBundleService artifactBundleService;
    private final EvolutionArtifactIdentityService evolutionArtifactIdentityService;
    private final EvolutionCandidateDerivationService evolutionCandidateDerivationService;
    private final EvolutionCandidateTacticMaterializer evolutionCandidateTacticMaterializer;

    public EvolutionCandidateService(
            TacticRecordService tacticRecordService,
            ArtifactBundleService artifactBundleService,
            EvolutionArtifactIdentityService evolutionArtifactIdentityService,
            EvolutionCandidateDerivationService evolutionCandidateDerivationService,
            EvolutionCandidateTacticMaterializer evolutionCandidateTacticMaterializer) {
        this.tacticRecordService = tacticRecordService;
        this.artifactBundleService = artifactBundleService;
        this.evolutionArtifactIdentityService = evolutionArtifactIdentityService;
        this.evolutionCandidateDerivationService = evolutionCandidateDerivationService;
        this.evolutionCandidateTacticMaterializer = evolutionCandidateTacticMaterializer;
    }

    public List<EvolutionCandidate> deriveCandidates(RunRecord runRecord, RunVerdict runVerdict) {
        return deriveCandidates(runRecord, runVerdict, null);
    }

    public List<EvolutionCandidate> deriveCandidates(
            RunRecord runRecord,
            RunVerdict runVerdict,
            EvolutionProposal proposal) {
        List<EvolutionCandidate> candidates = evolutionCandidateDerivationService.deriveCandidates(runRecord,
                runVerdict, proposal);
        if (candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream().map(evolutionArtifactIdentityService::ensureArtifactIdentity).toList();
    }

    public EvolutionCandidate ensureArtifactIdentity(EvolutionCandidate candidate) {
        return evolutionArtifactIdentityService.ensureArtifactIdentity(candidate);
    }

    public List<ArtifactRevisionRecord> getArtifactRevisionRecords() {
        return evolutionArtifactIdentityService.getArtifactRevisionRecords();
    }

    public TacticRecord activateAsTactic(EvolutionCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        evolutionArtifactIdentityService.ensureArtifactIdentity(candidate);
        EvolutionCandidate promotedCandidate = candidate.toBuilder()
                .status("active")
                .lifecycleState("active")
                .rolloutStage("active")
                .build();
        Optional<TacticRecord> tactic = syncTacticRecord(promotedCandidate);
        if (tactic.isPresent() && artifactBundleService != null) {
            try {
                artifactBundleService.promoteCandidateBundle(
                        UUID.randomUUID().toString(), promotedCandidate, "active");
            } catch (RuntimeException exception) { // NOSONAR - bundle failure should not break activation
                log.warn(
                        "[SelfEvolving] Failed to promote bundle for activated tactic {}: {}",
                        candidate.getContentRevisionId(),
                        exception.getMessage());
            }
        }
        return tactic.orElse(null);
    }

    public Optional<TacticRecord> syncTacticRecord(EvolutionCandidate candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        evolutionArtifactIdentityService.ensureArtifactIdentity(candidate);
        Optional<TacticRecord> tactic = evolutionCandidateTacticMaterializer.materialize(candidate);
        if (tactic.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(tacticRecordService.save(tactic.get()));
    }
}

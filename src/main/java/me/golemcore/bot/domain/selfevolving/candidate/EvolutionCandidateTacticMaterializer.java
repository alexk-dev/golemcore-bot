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

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Materializes tactic records from evolution candidates that carry usable
 * semantic content.
 */
@Service
public class EvolutionCandidateTacticMaterializer {

    private final Clock clock;

    public EvolutionCandidateTacticMaterializer(Clock clock) {
        this.clock = clock;
    }

    public Optional<TacticRecord> materialize(EvolutionCandidate candidate) {
        if (candidate == null || !shouldMaterializeTactic(candidate)) {
            return Optional.empty();
        }
        EvolutionProposal proposal = candidate.getProposal();
        return Optional.of(TacticRecord.builder()
                .tacticId(candidate.getContentRevisionId())
                .artifactStreamId(candidate.getArtifactStreamId())
                .originArtifactStreamId(candidate.getOriginArtifactStreamId())
                .artifactKey(candidate.getArtifactKey())
                .artifactType(candidate.getArtifactType())
                .title(resolveTacticTitle(candidate))
                .aliases(candidate.getArtifactAliases() != null ? new ArrayList<>(candidate.getArtifactAliases())
                        : new ArrayList<>())
                .contentRevisionId(candidate.getContentRevisionId())
                .intentSummary(firstNonBlank(
                        proposal != null ? proposal.getSummary() : null,
                        candidate.getExpectedImpact()))
                .behaviorSummary(firstNonBlank(
                        proposal != null ? proposal.getBehaviorInstructions() : null,
                        candidate.getProposedDiff()))
                .toolSummary(proposal != null ? proposal.getToolInstructions() : null)
                .outcomeSummary(firstNonBlank(
                        proposal != null ? proposal.getExpectedOutcome() : null,
                        candidate.getGoal()))
                .approvalNotes(proposal != null ? proposal.getApprovalNotes() : null)
                .evidenceSnippets(resolveEvidenceSnippets(candidate))
                .taskFamilies(resolveTaskFamilies(candidate))
                .tags(resolveTags(candidate))
                .promotionState(resolveTacticPromotionState(candidate))
                .rolloutStage(resolveTacticRolloutStage(candidate))
                .embeddingStatus("pending")
                .updatedAt(candidate.getCreatedAt() != null ? candidate.getCreatedAt() : Instant.now(clock))
                .build());
    }

    private boolean shouldMaterializeTactic(EvolutionCandidate candidate) {
        EvolutionProposal proposal = candidate.getProposal();
        if (proposal != null
                && (!StringValueSupport.isBlank(proposal.getSummary())
                        || !StringValueSupport.isBlank(proposal.getBehaviorInstructions())
                        || !StringValueSupport.isBlank(proposal.getToolInstructions())
                        || !StringValueSupport.isBlank(proposal.getExpectedOutcome()))) {
            return true;
        }
        return !isPlaceholderDiff(candidate.getProposedDiff());
    }

    private String resolveTacticPromotionState(EvolutionCandidate candidate) {
        if (candidate == null) {
            return "candidate";
        }
        if (!StringValueSupport.isBlank(candidate.getLifecycleState())) {
            return candidate.getLifecycleState();
        }
        return CandidateLifecycleResolver.resolveLifecycleState(candidate.getStatus());
    }

    private String resolveTacticRolloutStage(EvolutionCandidate candidate) {
        if (candidate == null) {
            return "proposed";
        }
        if (!StringValueSupport.isBlank(candidate.getRolloutStage())) {
            return candidate.getRolloutStage();
        }
        return CandidateLifecycleResolver.resolveRolloutStage(candidate.getStatus());
    }

    private String resolveTacticTitle(EvolutionCandidate candidate) {
        if (candidate == null || StringValueSupport.isBlank(candidate.getArtifactKey())) {
            return "Tactic";
        }
        String normalized = candidate.getArtifactKey().replace(':', ' ').replace('_', ' ').trim();
        if (normalized.isEmpty()) {
            return "Tactic";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private List<String> resolveEvidenceSnippets(EvolutionCandidate candidate) {
        List<String> snippets = new ArrayList<>();
        if (candidate == null || candidate.getEvidenceRefs() == null) {
            return snippets;
        }
        for (VerdictEvidenceRef evidenceRef : candidate.getEvidenceRefs()) {
            if (evidenceRef == null) {
                continue;
            }
            if (!StringValueSupport.isBlank(evidenceRef.getOutputFragment())) {
                snippets.add(evidenceRef.getOutputFragment().trim());
                continue;
            }
            if (!StringValueSupport.isBlank(evidenceRef.getSpanId())) {
                snippets.add("span:" + evidenceRef.getSpanId().trim());
                continue;
            }
            if (!StringValueSupport.isBlank(evidenceRef.getTraceId())) {
                snippets.add("trace:" + evidenceRef.getTraceId().trim());
            }
        }
        return snippets;
    }

    private List<String> resolveTaskFamilies(EvolutionCandidate candidate) {
        if (candidate == null || StringValueSupport.isBlank(candidate.getArtifactType())) {
            return List.of();
        }
        return List.of(candidate.getArtifactType());
    }

    private List<String> resolveTags(EvolutionCandidate candidate) {
        if (candidate == null) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        if (!StringValueSupport.isBlank(candidate.getArtifactType())) {
            tags.add(candidate.getArtifactType());
        }
        if (!StringValueSupport.isBlank(candidate.getLifecycleState())) {
            tags.add(candidate.getLifecycleState());
        }
        if (!StringValueSupport.isBlank(candidate.getGoal())) {
            tags.add(candidate.getGoal());
        }
        return tags;
    }

    private boolean isPlaceholderDiff(String proposedDiff) {
        if (StringValueSupport.isBlank(proposedDiff)) {
            return true;
        }
        return proposedDiff.matches("selfevolving:[a-z_]+:[a-z_]+");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!StringValueSupport.isBlank(value)) {
                return value;
            }
        }
        return null;
    }
}

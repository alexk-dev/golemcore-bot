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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

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
    private final Clock clock;

    public EvolutionCandidateService(
            TacticRecordService tacticRecordService,
            ArtifactBundleService artifactBundleService,
            EvolutionArtifactIdentityService evolutionArtifactIdentityService,
            Clock clock) {
        this.tacticRecordService = tacticRecordService;
        this.artifactBundleService = artifactBundleService;
        this.evolutionArtifactIdentityService = evolutionArtifactIdentityService;
        this.clock = clock;
    }

    public List<EvolutionCandidate> deriveCandidates(RunRecord runRecord, RunVerdict runVerdict) {
        return deriveCandidates(runRecord, runVerdict, null);
    }

    public List<EvolutionCandidate> deriveCandidates(
            RunRecord runRecord,
            RunVerdict runVerdict,
            EvolutionProposal proposal) {
        if (runRecord == null
                || runVerdict == null
                || runVerdict.getEvidenceRefs() == null
                || runVerdict.getEvidenceRefs().isEmpty()) {
            return List.of();
        }

        if ("FAILED".equals(runVerdict.getOutcomeStatus())) {
            String artifactType = resolveFixArtifactType(runVerdict);
            return List.of(buildCandidate(
                    runRecord,
                    runVerdict,
                    proposal,
                    "fix",
                    artifactType,
                    buildFixImpact(runVerdict, artifactType),
                    "high"));
        }

        if ("COMPLETED".equals(runVerdict.getOutcomeStatus())
                && "CLEAN".equals(runVerdict.getProcessStatus())
                && (runVerdict.getConfidence() == null || runVerdict.getConfidence() >= 0.8)) {
            String artifactType = resolveDeriveArtifactType(runVerdict, proposal);
            return List.of(buildCandidate(
                    runRecord,
                    runVerdict,
                    proposal,
                    "derive",
                    artifactType,
                    buildDeriveImpact(runVerdict),
                    "medium"));
        }

        return List.of();
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
        if (candidate == null || !shouldMaterializeTactic(candidate)) {
            return Optional.empty();
        }
        evolutionArtifactIdentityService.ensureArtifactIdentity(candidate);
        return Optional.of(tacticRecordService.save(buildTacticRecord(
                candidate,
                resolveTacticPromotionState(candidate),
                resolveTacticRolloutStage(candidate))));
    }

    private EvolutionCandidate buildCandidate(
            RunRecord runRecord,
            RunVerdict runVerdict,
            EvolutionProposal proposal,
            String goal,
            String artifactType,
            String expectedImpact,
            String riskLevel) {
        Instant createdAt = Instant.now(clock);
        String proposedDiff = proposal != null && !StringValueSupport.isBlank(proposal.getProposedPatch())
                ? proposal.getProposedPatch()
                : buildProposedDiff(goal, artifactType);
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id(UUID.randomUUID().toString())
                .golemId(runRecord.getGolemId())
                .goal(goal)
                .artifactType(artifactType)
                .baseVersion(runRecord.getArtifactBundleId())
                .proposedDiff(proposedDiff)
                .proposal(proposal)
                .expectedImpact(firstNonBlank(proposal != null ? proposal.getExpectedOutcome() : null, expectedImpact))
                .riskLevel(firstNonBlank(proposal != null ? proposal.getRiskLevel() : null, riskLevel))
                .status("proposed")
                .lifecycleState("candidate")
                .rolloutStage("proposed")
                .createdAt(createdAt)
                .sourceRunIds(List.of(runRecord.getId()))
                .evidenceRefs(runVerdict.getEvidenceRefs())
                .build();
        return evolutionArtifactIdentityService.ensureArtifactIdentity(candidate);
    }

    private TacticRecord buildTacticRecord(EvolutionCandidate candidate, String promotionState, String rolloutStage) {
        EvolutionProposal proposal = candidate.getProposal();
        return TacticRecord.builder()
                .tacticId(candidate.getContentRevisionId())
                .artifactStreamId(candidate.getArtifactStreamId())
                .originArtifactStreamId(candidate.getOriginArtifactStreamId())
                .artifactKey(candidate.getArtifactKey())
                .artifactType(candidate.getArtifactType())
                .title(resolveTacticTitle(candidate))
                .aliases(candidate.getArtifactAliases() != null ? new ArrayList<>(candidate.getArtifactAliases())
                        : new ArrayList<>())
                .contentRevisionId(candidate.getContentRevisionId())
                .intentSummary(
                        firstNonBlank(proposal != null ? proposal.getSummary() : null, candidate.getExpectedImpact()))
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
                .promotionState(promotionState)
                .rolloutStage(rolloutStage)
                .embeddingStatus("pending")
                .updatedAt(candidate.getCreatedAt() != null ? candidate.getCreatedAt() : Instant.now(clock))
                .build();
    }

    private boolean shouldMaterializeTactic(EvolutionCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        // Tighter criteria: only materialize when the proposal carries semantic
        // content a downstream consumer can actually act on. approvalNotes and
        // proposedPatch alone are metadata and do not justify a tactic row —
        // the patch content is already represented in candidate.proposedDiff.
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
        return resolveLifecycleState(candidate.getStatus());
    }

    private String resolveTacticRolloutStage(EvolutionCandidate candidate) {
        if (candidate == null) {
            return "proposed";
        }
        if (!StringValueSupport.isBlank(candidate.getRolloutStage())) {
            return candidate.getRolloutStage();
        }
        return resolveRolloutStage(candidate.getStatus());
    }

    private String resolveFixArtifactType(RunVerdict runVerdict) {
        List<String> findings = runVerdict.getProcessFindings();
        if (findings != null) {
            for (String finding : findings) {
                if (finding == null) {
                    continue;
                }
                if (finding.startsWith("tool_error") || finding.contains("tool_")) {
                    return "tool_policy";
                }
                if (finding.contains("skill")) {
                    return "skill";
                }
                if (finding.contains("tier")) {
                    return "routing_policy";
                }
            }
        }
        return "prompt";
    }

    private String buildFixImpact(RunVerdict runVerdict, String artifactType) {
        String evidenceSummary = extractFirstEvidenceFragment(runVerdict);
        String artifactLabel = resolveArtifactLabel(artifactType);
        if (evidenceSummary != null) {
            return "Failure observed: " + evidenceSummary + ". Proposed fix: adjust " + artifactLabel
                    + " to prevent recurrence.";
        }
        String outcomeSummary = runVerdict.getOutcomeSummary();
        if (!StringValueSupport.isBlank(outcomeSummary)) {
            return outcomeSummary + ". Proposed fix: adjust " + artifactLabel + " to prevent recurrence.";
        }
        return "Adjust " + artifactLabel + " to reduce the failure mode observed in this run.";
    }

    private String buildDeriveImpact(RunVerdict runVerdict) {
        String evidenceSummary = extractFirstEvidenceFragment(runVerdict);
        if (evidenceSummary != null) {
            return "Capture the successful pattern: " + evidenceSummary + ".";
        }
        String outcomeSummary = runVerdict.getOutcomeSummary();
        if (!StringValueSupport.isBlank(outcomeSummary)) {
            return "Capture a reusable pattern from: " + outcomeSummary + ".";
        }
        return "Capture a reusable high-signal pattern from a successful run.";
    }

    private String resolveDeriveArtifactType(RunVerdict runVerdict, EvolutionProposal proposal) {
        String hint = firstNonBlank(
                proposal != null ? proposal.getSummary() : null,
                proposal != null ? proposal.getBehaviorInstructions() : null);
        if (StringValueSupport.isBlank(hint)) {
            hint = firstNonBlank(extractFirstEvidenceFragment(runVerdict), runVerdict.getOutcomeSummary());
        }
        String normalizedHint = StringValueSupport.nullSafe(hint).trim().toLowerCase(Locale.ROOT);
        if (normalizedHint.contains("shell")
                || normalizedHint.contains("command")
                || normalizedHint.contains("tool")) {
            return "tool_policy";
        }
        if (normalizedHint.contains("route")
                || normalizedHint.contains("tier")
                || normalizedHint.contains("model")) {
            return "routing_policy";
        }
        if (normalizedHint.contains("memory")
                || normalizedHint.contains("retriev")) {
            return "memory_policy";
        }
        if (normalizedHint.contains("context")) {
            return "context_policy";
        }
        if (normalizedHint.contains("approval")
                || normalizedHint.contains("governance")) {
            return "governance_policy";
        }
        if (normalizedHint.contains("prompt")) {
            return "prompt";
        }
        return "skill";
    }

    private String extractFirstEvidenceFragment(RunVerdict runVerdict) {
        if (runVerdict.getEvidenceRefs() == null) {
            return null;
        }
        for (VerdictEvidenceRef evidenceRef : runVerdict.getEvidenceRefs()) {
            if (evidenceRef != null && !StringValueSupport.isBlank(evidenceRef.getOutputFragment())) {
                String fragment = evidenceRef.getOutputFragment().trim();
                if (fragment.length() > 200) {
                    fragment = fragment.substring(0, 200) + "...";
                }
                return fragment;
            }
        }
        return null;
    }

    private String resolveArtifactLabel(String artifactType) {
        return switch (artifactType) {
        case "tool_policy" -> "tool usage policy";
        case "routing_policy" -> "tier routing policy";
        case "memory_policy" -> "memory retrieval policy";
        case "context_policy" -> "context assembly policy";
        case "governance_policy" -> "approval policy";
        case "prompt" -> "prompt configuration";
        case "skill" -> "skill definition";
        default -> artifactType;
        };
    }

    private String buildProposedDiff(String goal, String artifactType) {
        return "selfevolving:" + goal + ":" + artifactType;
    }

    private String resolveLifecycleState(String status) {
        return CandidateLifecycleResolver.resolveLifecycleState(status);
    }

    private String resolveRolloutStage(String status) {
        return CandidateLifecycleResolver.resolveRolloutStage(status);
    }

    private String resolveLegacyStatus(String lifecycleState, String rolloutStage) {
        return CandidateLifecycleResolver.resolveLegacyStatus(lifecycleState, rolloutStage);
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

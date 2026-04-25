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
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Derives first-pass evolution candidates from judged run outcomes and
 * structured proposals.
 */
@Service
public class EvolutionCandidateDerivationService {

    private final Clock clock;

    public EvolutionCandidateDerivationService(Clock clock) {
        this.clock = clock;
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
        return EvolutionCandidate.builder()
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

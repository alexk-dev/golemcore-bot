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

import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import org.springframework.stereotype.Service;

import java.util.List;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Produces concrete structured change proposals from judged runs.
 */
@Service
public class LlmEvolutionService {

    public EvolutionProposal propose(RunRecord runRecord, RunVerdict runVerdict) {
        if (runRecord == null
                || runVerdict == null
                || runVerdict.getEvidenceRefs() == null
                || runVerdict.getEvidenceRefs().isEmpty()) {
            return null;
        }

        if ("FAILED".equals(runVerdict.getOutcomeStatus())) {
            String artifactType = resolveFixArtifactType(runVerdict);
            String artifactLabel = resolveArtifactLabel(artifactType);
            String evidenceSummary = extractFirstEvidenceFragment(runVerdict);
            String summary = "Harden " + artifactLabel + " after the observed failure";
            String rationale = buildFixRationale(runVerdict, evidenceSummary, artifactLabel);
            String behaviorInstructions = buildFixBehaviorInstructions(artifactType);
            String toolInstructions = buildFixToolInstructions(artifactType);
            String expectedOutcome = "Reduce repeated failures for this mode and recover with a safer fallback path.";
            String approvalNotes = buildApprovalNotes(runVerdict, "high");
            String proposedPatch = buildFixProposedPatch(artifactType);
            return EvolutionProposal.builder()
                    .summary(summary)
                    .rationale(rationale)
                    .behaviorInstructions(behaviorInstructions)
                    .toolInstructions(toolInstructions)
                    .expectedOutcome(expectedOutcome)
                    .approvalNotes(approvalNotes)
                    .proposedPatch(proposedPatch)
                    .riskLevel("high")
                    .build();
        }

        if ("COMPLETED".equals(runVerdict.getOutcomeStatus())
                && "CLEAN".equals(runVerdict.getProcessStatus())
                && (runVerdict.getConfidence() == null || runVerdict.getConfidence() >= 0.8)) {
            String successFocus = extractSuccessFocus(runVerdict);
            String summary = "Capture successful tactic from run: " + successFocus;
            String rationale = "The run completed cleanly"
                    + (StringValueSupport.isBlank(successFocus) ? "."
                            : " with reusable evidence: " + successFocus + ".");
            String behaviorInstructions = "Reuse the successful sequence demonstrated in this run when a similar "
                    + "task appears: " + successFocus;
            String toolInstructions = buildSuccessfulToolInstructions(successFocus);
            String expectedOutcome = "Reuse the successful pattern from " + successFocus
                    + " on similar tasks while keeping the run predictable.";
            String approvalNotes = buildApprovalNotes(runVerdict, "medium");
            String proposedPatch = "Document and reuse this successful sequence with clear entry conditions and "
                    + "checkpoints: " + successFocus + ".";
            return EvolutionProposal.builder()
                    .summary(summary)
                    .rationale(rationale)
                    .behaviorInstructions(behaviorInstructions)
                    .toolInstructions(toolInstructions)
                    .expectedOutcome(expectedOutcome)
                    .approvalNotes(approvalNotes)
                    .proposedPatch(proposedPatch)
                    .riskLevel("medium")
                    .build();
        }

        return null;
    }

    private String buildFixRationale(RunVerdict runVerdict, String evidenceSummary, String artifactLabel) {
        StringBuilder builder = new StringBuilder("The run failed");
        if (!StringValueSupport.isBlank(runVerdict.getOutcomeSummary())) {
            builder.append(": ").append(runVerdict.getOutcomeSummary().trim());
        }
        if (!StringValueSupport.isBlank(evidenceSummary)) {
            builder.append(". Evidence: ").append(evidenceSummary);
        }
        builder.append(". The proposal tightens ").append(artifactLabel)
                .append(" so the agent detects the failure mode earlier and replans instead of repeating it.");
        return builder.toString();
    }

    private String buildFixBehaviorInstructions(String artifactType) {
        return switch (artifactType) {
        case "tool_policy" -> "Before issuing a shell command, verify the executable exists. If a command exits with "
                + "exit code 127, inspect availability, pick an installed alternative, and replan instead of retrying "
                + "the same missing tool.";
        case "skill" -> "When the run fails due to skill misuse, choose a safer skill sequence and insert a recovery "
                + "checkpoint before reusing the same tactic.";
        case "routing_policy" -> "If the failure suggests poor tier routing, escalate earlier for brittle operations "
                + "and downgrade only after the task is stabilized.";
        default -> "Detect the failure mode earlier, branch into a recovery path, and avoid repeating the exact same "
                + "action without a remediation step.";
        };
    }

    private String buildFixToolInstructions(String artifactType) {
        return switch (artifactType) {
        case "tool_policy" -> "Prefer `command -v` before using shell tools. Avoid issuing the same missing command "
                + "twice without proving the binary is available.";
        case "skill" -> "Prefer explicit recovery skills or checklists before repeating the failing sequence.";
        case "routing_policy" -> "Prefer a higher-assurance model tier before executing brittle tool chains.";
        default -> "Prefer explicit validation steps before reissuing the action that failed.";
        };
    }

    private String buildFixProposedPatch(String artifactType) {
        if ("tool_policy".equals(artifactType)) {
            return "Verify tool availability before shell execution, and if the command exits with code 127 switch "
                    + "to an installed fallback instead of retrying the missing executable.";
        }
        return "Add an explicit recovery branch for the observed failure mode and avoid repeating the same action "
                + "without a remediation step.";
    }

    private String buildApprovalNotes(RunVerdict runVerdict, String riskLevel) {
        VerdictEvidenceRef evidenceRef = firstEvidenceRef(runVerdict);
        if (evidenceRef == null) {
            return "Risk: " + riskLevel + ".";
        }
        String traceId = StringValueSupport.nullSafe(evidenceRef.getTraceId()).trim();
        String spanId = StringValueSupport.nullSafe(evidenceRef.getSpanId()).trim();
        if (traceId.isEmpty() && spanId.isEmpty()) {
            return "Risk: " + riskLevel + ". Evidence anchor recorded for this reviewed run.";
        }
        if (traceId.isEmpty()) {
            return "Risk: " + riskLevel + ". Evidence anchored to span " + spanId + ".";
        }
        if (spanId.isEmpty()) {
            return "Risk: " + riskLevel + ". Evidence anchored to trace " + traceId + ".";
        }
        return "Risk: " + riskLevel + ". Evidence anchored to trace " + traceId + " span " + spanId + ".";
    }

    private VerdictEvidenceRef firstEvidenceRef(RunVerdict runVerdict) {
        List<VerdictEvidenceRef> evidenceRefs = runVerdict.getEvidenceRefs();
        if (evidenceRefs == null || evidenceRefs.isEmpty()) {
            return null;
        }
        return evidenceRefs.getFirst();
    }

    private String extractFirstEvidenceFragment(RunVerdict runVerdict) {
        List<VerdictEvidenceRef> evidenceRefs = runVerdict.getEvidenceRefs();
        if (evidenceRefs == null) {
            return null;
        }
        for (VerdictEvidenceRef evidenceRef : evidenceRefs) {
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

    private String extractSuccessFocus(RunVerdict runVerdict) {
        String focus = extractFirstEvidenceFragment(runVerdict);
        if (StringValueSupport.isBlank(focus)) {
            focus = runVerdict.getOutcomeSummary();
        }
        if (StringValueSupport.isBlank(focus)) {
            return "the reviewed run";
        }
        String trimmed = focus.trim();
        if (trimmed.length() > 120) {
            trimmed = trimmed.substring(0, 120).trim() + "...";
        }
        return trimmed;
    }

    private String buildSuccessfulToolInstructions(String successFocus) {
        String normalizedFocus = StringValueSupport.nullSafe(successFocus).toLowerCase();
        if (normalizedFocus.contains("shell")
                || normalizedFocus.contains("command")
                || normalizedFocus.contains("tool")) {
            return "Prefer the validated shell tool chain and checkpoints demonstrated by this successful run.";
        }
        if (normalizedFocus.contains("deploy")
                || normalizedFocus.contains("verification")
                || normalizedFocus.contains("release")) {
            return "Preserve the same verification checkpoints before marking similar release work as complete.";
        }
        return "Preserve the successful sequence and checkpoints shown by this run when the task shape matches.";
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
}

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
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.support.StringValueSupport;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Deterministic gate that decides whether an evolution proposal should proceed
 * to candidate derivation. Prevents low-signal runs from polluting the tactic
 * index with noise.
 */
@Service
@Slf4j
public class EvolutionGateService {

    private static final double MIN_CONFIDENCE_THRESHOLD = 0.5;

    /**
     * Returns {@code true} when the run, verdict, and proposal together carry
     * enough signal to justify creating evolution candidates.
     */
    public boolean shouldEvolve(RunRecord runRecord, RunVerdict verdict, EvolutionProposal proposal) {
        if (verdict == null) {
            log.debug("[EvolutionGate] Rejected: no verdict");
            return false;
        }
        if (proposal == null || isEmptyProposal(proposal)) {
            log.debug("[EvolutionGate] Rejected: empty proposal for run {}", runId(runRecord));
            return false;
        }
        if (!hasMinimumConfidence(verdict)) {
            log.debug("[EvolutionGate] Rejected: confidence {} below threshold {} for run {}",
                    verdict.getConfidence(), MIN_CONFIDENCE_THRESHOLD, runId(runRecord));
            return false;
        }
        if (!hasEvidence(verdict)) {
            log.debug("[EvolutionGate] Rejected: no evidence refs for run {}", runId(runRecord));
            return false;
        }
        return true;
    }

    private boolean hasMinimumConfidence(RunVerdict verdict) {
        return verdict.getConfidence() != null && verdict.getConfidence() >= MIN_CONFIDENCE_THRESHOLD;
    }

    private boolean hasEvidence(RunVerdict verdict) {
        List<VerdictEvidenceRef> refs = verdict.getEvidenceRefs();
        if (refs == null || refs.isEmpty()) {
            return false;
        }
        return refs.stream().anyMatch(ref -> ref != null
                && (!StringValueSupport.isBlank(ref.getOutputFragment())
                        || !StringValueSupport.isBlank(ref.getSpanId())
                        || !StringValueSupport.isBlank(ref.getMetricName())));
    }

    private boolean isEmptyProposal(EvolutionProposal proposal) {
        return StringValueSupport.isBlank(proposal.getSummary())
                && StringValueSupport.isBlank(proposal.getBehaviorInstructions())
                && StringValueSupport.isBlank(proposal.getToolInstructions())
                && StringValueSupport.isBlank(proposal.getExpectedOutcome());
    }

    private String runId(RunRecord runRecord) {
        return runRecord != null ? runRecord.getId() : "unknown";
    }
}

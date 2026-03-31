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

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCampaignDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCandidateDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Projects SelfEvolving core records into dashboard-friendly DTOs.
 */
@Service
@RequiredArgsConstructor
public class SelfEvolvingProjectionService {

    private final SelfEvolvingRunService selfEvolvingRunService;
    private final ArtifactBundleService artifactBundleService;
    private final DeterministicJudgeService deterministicJudgeService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final BenchmarkLabService benchmarkLabService;
    private final SessionPort sessionPort;

    public List<SelfEvolvingRunSummaryDto> listRuns() {
        return selfEvolvingRunService.getRuns().stream()
                .sorted(Comparator.comparing(
                        RunRecord::getStartedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toSummaryDto)
                .toList();
    }

    public Optional<SelfEvolvingRunDetailDto> getRun(String runId) {
        return selfEvolvingRunService.getRuns().stream()
                .filter(run -> run != null && runId.equals(run.getId()))
                .findFirst()
                .map(this::toDetailDto);
    }

    public List<SelfEvolvingCandidateDto> listCandidates() {
        return promotionWorkflowService.getCandidates().stream()
                .map(this::toCandidateDto)
                .toList();
    }

    public List<SelfEvolvingCampaignDto> listCampaigns() {
        return benchmarkLabService.getCampaigns().stream()
                .map(this::toCampaignDto)
                .toList();
    }

    private SelfEvolvingRunSummaryDto toSummaryDto(RunRecord runRecord) {
        RunVerdict verdict = resolveVerdict(runRecord);
        return SelfEvolvingRunSummaryDto.builder()
                .id(runRecord.getId())
                .golemId(runRecord.getGolemId())
                .sessionId(runRecord.getSessionId())
                .traceId(runRecord.getTraceId())
                .artifactBundleId(runRecord.getArtifactBundleId())
                .status(runRecord.getStatus())
                .outcomeStatus(verdict.getOutcomeStatus())
                .promotionRecommendation(verdict.getPromotionRecommendation())
                .startedAt(formatInstant(runRecord.getStartedAt()))
                .completedAt(formatInstant(runRecord.getCompletedAt()))
                .build();
    }

    private SelfEvolvingRunDetailDto toDetailDto(RunRecord runRecord) {
        ArtifactBundleRecord bundle = resolveArtifactBundle(runRecord.getArtifactBundleId()).orElse(null);
        RunVerdict verdict = resolveVerdict(runRecord);
        return SelfEvolvingRunDetailDto.builder()
                .id(runRecord.getId())
                .golemId(runRecord.getGolemId())
                .sessionId(runRecord.getSessionId())
                .traceId(runRecord.getTraceId())
                .artifactBundleId(runRecord.getArtifactBundleId())
                .status(runRecord.getStatus())
                .startedAt(formatInstant(runRecord.getStartedAt()))
                .completedAt(formatInstant(runRecord.getCompletedAt()))
                .artifactBundleStatus(bundle != null ? bundle.getStatus() : null)
                .verdict(toVerdictDto(verdict))
                .build();
    }

    private SelfEvolvingRunDetailDto.VerdictDto toVerdictDto(RunVerdict verdict) {
        return SelfEvolvingRunDetailDto.VerdictDto.builder()
                .outcomeStatus(verdict.getOutcomeStatus())
                .processStatus(verdict.getProcessStatus())
                .outcomeSummary(verdict.getOutcomeSummary())
                .processSummary(verdict.getProcessSummary())
                .promotionRecommendation(verdict.getPromotionRecommendation())
                .confidence(verdict.getConfidence())
                .processFindings(verdict.getProcessFindings())
                .build();
    }

    private SelfEvolvingCandidateDto toCandidateDto(EvolutionCandidate candidate) {
        return SelfEvolvingCandidateDto.builder()
                .id(candidate.getId())
                .goal(candidate.getGoal())
                .artifactType(candidate.getArtifactType())
                .status(candidate.getStatus())
                .riskLevel(candidate.getRiskLevel())
                .expectedImpact(candidate.getExpectedImpact())
                .sourceRunIds(candidate.getSourceRunIds())
                .build();
    }

    private SelfEvolvingCampaignDto toCampaignDto(BenchmarkCampaign campaign) {
        return SelfEvolvingCampaignDto.builder()
                .id(campaign.getId())
                .suiteId(campaign.getSuiteId())
                .baselineBundleId(campaign.getBaselineBundleId())
                .candidateBundleId(campaign.getCandidateBundleId())
                .status(campaign.getStatus())
                .startedAt(formatInstant(campaign.getStartedAt()))
                .completedAt(formatInstant(campaign.getCompletedAt()))
                .runIds(campaign.getRunIds())
                .build();
    }

    private RunVerdict resolveVerdict(RunRecord runRecord) {
        return deterministicJudgeService.evaluate(runRecord, resolveTrace(runRecord));
    }

    private Optional<ArtifactBundleRecord> resolveArtifactBundle(String artifactBundleId) {
        if (StringValueSupport.isBlank(artifactBundleId)) {
            return Optional.empty();
        }
        return artifactBundleService.getBundles().stream()
                .filter(bundle -> bundle != null && artifactBundleId.equals(bundle.getId()))
                .findFirst();
    }

    private TraceRecord resolveTrace(RunRecord runRecord) {
        if (runRecord == null || StringValueSupport.isBlank(runRecord.getSessionId())) {
            return null;
        }
        Optional<AgentSession> session = sessionPort.get(runRecord.getSessionId());
        if (session.isEmpty() || session.get().getTraces() == null) {
            return null;
        }
        if (StringValueSupport.isBlank(runRecord.getTraceId())) {
            return session.get().getTraces().stream()
                    .filter(trace -> trace != null)
                    .findFirst()
                    .orElse(null);
        }
        return session.get().getTraces().stream()
                .filter(trace -> trace != null && runRecord.getTraceId().equals(trace.getTraceId()))
                .findFirst()
                .orElse(null);
    }

    private String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}

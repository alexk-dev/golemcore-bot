package me.golemcore.bot.adapter.inbound.web.projection;

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

import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCampaignDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCandidateDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunSummaryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCatalogEntryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCompareOptionsDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactEvidenceDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactLineageDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactRevisionDiffDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactTransitionDiffDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactWorkspaceSummaryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchExplanationDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResponseDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResultDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchStatusDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkLabService;
import me.golemcore.bot.domain.selfevolving.benchmark.DeterministicJudgeService;
import me.golemcore.bot.domain.selfevolving.promotion.PromotionWorkflowService;
import me.golemcore.bot.domain.selfevolving.run.SelfEvolvingRunService;
import me.golemcore.bot.domain.selfevolving.tactic.LocalEmbeddingBootstrapService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticSearchMetricsService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticSearchService;
import me.golemcore.bot.domain.support.StringValueSupport;

/**
 * Projects SelfEvolving core records into dashboard-friendly DTOs.
 */
@Service
public class SelfEvolvingProjectionService {

    private final SelfEvolvingRunService selfEvolvingRunService;
    private final ArtifactBundleService artifactBundleService;
    private final DeterministicJudgeService deterministicJudgeService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final BenchmarkLabService benchmarkLabService;
    private final ArtifactProjectionService artifactProjectionService;
    private final SessionPort sessionPort;
    private final TacticRecordService tacticRecordService;
    private final TacticSearchService tacticSearchService;
    private final TacticSearchMetricsService tacticSearchMetricsService;
    private final LocalEmbeddingBootstrapService localEmbeddingBootstrapService;

    public SelfEvolvingProjectionService(
            SelfEvolvingRunService selfEvolvingRunService,
            ArtifactBundleService artifactBundleService,
            DeterministicJudgeService deterministicJudgeService,
            PromotionWorkflowService promotionWorkflowService,
            BenchmarkLabService benchmarkLabService,
            ArtifactProjectionService artifactProjectionService,
            SessionPort sessionPort,
            TacticRecordService tacticRecordService,
            TacticSearchService tacticSearchService,
            TacticSearchMetricsService tacticSearchMetricsService,
            LocalEmbeddingBootstrapService localEmbeddingBootstrapService) {
        this.selfEvolvingRunService = selfEvolvingRunService;
        this.artifactBundleService = artifactBundleService;
        this.deterministicJudgeService = deterministicJudgeService;
        this.promotionWorkflowService = promotionWorkflowService;
        this.benchmarkLabService = benchmarkLabService;
        this.artifactProjectionService = artifactProjectionService;
        this.sessionPort = sessionPort;
        this.tacticRecordService = tacticRecordService;
        this.tacticSearchService = tacticSearchService;
        this.tacticSearchMetricsService = tacticSearchMetricsService;
        this.localEmbeddingBootstrapService = localEmbeddingBootstrapService;
    }

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

    public List<SelfEvolvingArtifactCatalogEntryDto> listArtifacts(
            String artifactType,
            String artifactSubtype,
            String lifecycleState,
            String rolloutStage,
            Boolean hasPendingApproval,
            Boolean hasRegression,
            Boolean benchmarked,
            String query) {
        return artifactProjectionService.listArtifacts(
                artifactType,
                artifactSubtype,
                lifecycleState,
                rolloutStage,
                hasPendingApproval,
                hasRegression,
                benchmarked,
                query);
    }

    public List<SelfEvolvingTacticDto> listTactics() {
        if (tacticRecordService == null) {
            return List.of();
        }
        return tacticRecordService.getAll().stream()
                .map(this::toTacticDto)
                .toList();
    }

    public SelfEvolvingTacticSearchResponseDto searchTactics(String query) {
        List<SelfEvolvingTacticSearchResultDto> results;
        if (StringValueSupport.isBlank(query) || tacticSearchService == null) {
            results = listTactics().stream()
                    .map(this::toSearchResultDto)
                    .toList();
        } else {
            results = tacticSearchService.search(query).stream()
                    .map(this::toSearchResultDto)
                    .toList();
        }
        return SelfEvolvingTacticSearchResponseDto.builder()
                .query(query)
                .status(getTacticSearchStatus())
                .results(results)
                .build();
    }

    public SelfEvolvingTacticSearchStatusDto getTacticSearchStatus() {
        return buildTacticSearchStatusDto();
    }

    public Optional<SelfEvolvingTacticDto> getTactic(String tacticId) {
        if (tacticRecordService == null) {
            return Optional.empty();
        }
        return tacticRecordService.getById(tacticId).map(this::toTacticDto);
    }

    public Optional<SelfEvolvingTacticSearchExplanationDto> getTacticExplanation(String tacticId, String query) {
        if (tacticSearchService == null || tacticRecordService == null) {
            return Optional.empty();
        }
        return tacticRecordService.getById(tacticId)
                .flatMap(record -> tacticSearchService.search(
                        StringValueSupport.isBlank(query) ? record.getTitle() : query).stream()
                        .filter(result -> tacticId.equals(result.getTacticId()))
                        .findFirst()
                        .map(TacticSearchResult::getExplanation)
                        .map(this::toExplanationDto));
    }

    public Optional<SelfEvolvingArtifactLineageDto> getTacticLineage(String tacticId) {
        return tacticRecordService == null ? Optional.empty()
                : tacticRecordService.getById(tacticId)
                        .flatMap(record -> getArtifactLineage(record.getArtifactStreamId()));
    }

    public Optional<SelfEvolvingArtifactEvidenceDto> getTacticEvidence(String tacticId) {
        return tacticRecordService == null ? Optional.empty()
                : tacticRecordService.getById(tacticId)
                        .flatMap(record -> getArtifactRevisionEvidence(record.getArtifactStreamId(),
                                record.getContentRevisionId()));
    }

    public Optional<SelfEvolvingArtifactWorkspaceSummaryDto> getArtifactWorkspaceSummary(String artifactStreamId) {
        return artifactProjectionService.getArtifactWorkspaceSummary(artifactStreamId);
    }

    public Optional<SelfEvolvingArtifactLineageDto> getArtifactLineage(String artifactStreamId) {
        return artifactProjectionService.getArtifactLineage(artifactStreamId);
    }

    public Optional<SelfEvolvingArtifactRevisionDiffDto> getArtifactRevisionDiff(
            String artifactStreamId,
            String fromRevisionId,
            String toRevisionId) {
        return artifactProjectionService.getArtifactRevisionDiff(artifactStreamId, fromRevisionId, toRevisionId);
    }

    public Optional<SelfEvolvingArtifactTransitionDiffDto> getArtifactTransitionDiff(
            String artifactStreamId,
            String fromNodeId,
            String toNodeId) {
        return artifactProjectionService.getArtifactTransitionDiff(artifactStreamId, fromNodeId, toNodeId);
    }

    public Optional<SelfEvolvingArtifactEvidenceDto> getArtifactRevisionEvidence(
            String artifactStreamId,
            String revisionId) {
        return artifactProjectionService.getArtifactRevisionEvidence(artifactStreamId, revisionId);
    }

    public Optional<SelfEvolvingArtifactEvidenceDto> getArtifactCompareEvidence(
            String artifactStreamId,
            String fromRevisionId,
            String toRevisionId) {
        return artifactProjectionService.getArtifactCompareEvidence(artifactStreamId, fromRevisionId, toRevisionId);
    }

    public Optional<SelfEvolvingArtifactEvidenceDto> getArtifactTransitionEvidence(
            String artifactStreamId,
            String fromNodeId,
            String toNodeId) {
        return artifactProjectionService.getArtifactTransitionEvidence(artifactStreamId, fromNodeId, toNodeId);
    }

    public Optional<SelfEvolvingArtifactCompareOptionsDto> getArtifactCompareOptions(String artifactStreamId) {
        return artifactProjectionService.getArtifactCompareOptions(artifactStreamId);
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
        List<SelfEvolvingCandidateDto.EvidenceRefDto> evidenceRefDtos = candidate.getEvidenceRefs() == null
                ? List.of()
                : candidate.getEvidenceRefs().stream()
                        .map(ref -> SelfEvolvingCandidateDto.EvidenceRefDto.builder()
                                .traceId(ref.getTraceId())
                                .spanId(ref.getSpanId())
                                .outputFragment(ref.getOutputFragment())
                                .build())
                        .toList();
        SelfEvolvingCandidateDto.ProposalDto proposalDto = candidate.getProposal() == null ? null
                : SelfEvolvingCandidateDto.ProposalDto.builder()
                        .summary(candidate.getProposal().getSummary())
                        .rationale(candidate.getProposal().getRationale())
                        .behaviorInstructions(candidate.getProposal().getBehaviorInstructions())
                        .toolInstructions(candidate.getProposal().getToolInstructions())
                        .expectedOutcome(candidate.getProposal().getExpectedOutcome())
                        .approvalNotes(candidate.getProposal().getApprovalNotes())
                        .proposedPatch(candidate.getProposal().getProposedPatch())
                        .riskLevel(candidate.getProposal().getRiskLevel())
                        .build();
        return SelfEvolvingCandidateDto.builder()
                .id(candidate.getId())
                .goal(candidate.getGoal())
                .artifactType(candidate.getArtifactType())
                .artifactStreamId(candidate.getArtifactStreamId())
                .artifactKey(candidate.getArtifactKey())
                .status(candidate.getStatus())
                .riskLevel(candidate.getRiskLevel())
                .expectedImpact(candidate.getExpectedImpact())
                .proposedDiff(candidate.getProposedDiff())
                .proposal(proposalDto)
                .sourceRunIds(candidate.getSourceRunIds())
                .evidenceRefs(evidenceRefDtos)
                .build();
    }

    private SelfEvolvingTacticDto toTacticDto(TacticRecord record) {
        return SelfEvolvingTacticDto.builder()
                .tacticId(record.getTacticId())
                .artifactStreamId(record.getArtifactStreamId())
                .originArtifactStreamId(record.getOriginArtifactStreamId())
                .artifactKey(record.getArtifactKey())
                .artifactType(record.getArtifactType())
                .title(record.getTitle())
                .aliases(record.getAliases())
                .contentRevisionId(record.getContentRevisionId())
                .intentSummary(record.getIntentSummary())
                .behaviorSummary(record.getBehaviorSummary())
                .toolSummary(record.getToolSummary())
                .outcomeSummary(record.getOutcomeSummary())
                .benchmarkSummary(record.getBenchmarkSummary())
                .approvalNotes(record.getApprovalNotes())
                .evidenceSnippets(record.getEvidenceSnippets())
                .taskFamilies(record.getTaskFamilies())
                .tags(record.getTags())
                .promotionState(record.getPromotionState())
                .rolloutStage(record.getRolloutStage())
                .successRate(record.getSuccessRate())
                .benchmarkWinRate(record.getBenchmarkWinRate())
                .regressionFlags(record.getRegressionFlags())
                .recencyScore(record.getRecencyScore())
                .golemLocalUsageSuccess(record.getGolemLocalUsageSuccess())
                .embeddingStatus(record.getEmbeddingStatus())
                .updatedAt(formatInstant(record.getUpdatedAt()))
                .build();
    }

    private SelfEvolvingTacticSearchResultDto toSearchResultDto(SelfEvolvingTacticDto tactic) {
        return SelfEvolvingTacticSearchResultDto.builder()
                .tacticId(tactic.getTacticId())
                .artifactStreamId(tactic.getArtifactStreamId())
                .originArtifactStreamId(tactic.getOriginArtifactStreamId())
                .artifactKey(tactic.getArtifactKey())
                .artifactType(tactic.getArtifactType())
                .title(tactic.getTitle())
                .aliases(tactic.getAliases())
                .contentRevisionId(tactic.getContentRevisionId())
                .intentSummary(tactic.getIntentSummary())
                .behaviorSummary(tactic.getBehaviorSummary())
                .toolSummary(tactic.getToolSummary())
                .outcomeSummary(tactic.getOutcomeSummary())
                .benchmarkSummary(tactic.getBenchmarkSummary())
                .approvalNotes(tactic.getApprovalNotes())
                .evidenceSnippets(tactic.getEvidenceSnippets())
                .taskFamilies(tactic.getTaskFamilies())
                .tags(tactic.getTags())
                .promotionState(tactic.getPromotionState())
                .rolloutStage(tactic.getRolloutStage())
                .successRate(tactic.getSuccessRate())
                .benchmarkWinRate(tactic.getBenchmarkWinRate())
                .regressionFlags(tactic.getRegressionFlags())
                .recencyScore(tactic.getRecencyScore())
                .golemLocalUsageSuccess(tactic.getGolemLocalUsageSuccess())
                .embeddingStatus(tactic.getEmbeddingStatus())
                .updatedAt(tactic.getUpdatedAt())
                .build();
    }

    private SelfEvolvingTacticSearchResultDto toSearchResultDto(TacticSearchResult result) {
        return SelfEvolvingTacticSearchResultDto.builder()
                .tacticId(result.getTacticId())
                .artifactStreamId(result.getArtifactStreamId())
                .originArtifactStreamId(result.getOriginArtifactStreamId())
                .artifactKey(result.getArtifactKey())
                .artifactType(result.getArtifactType())
                .title(result.getTitle())
                .aliases(result.getAliases())
                .contentRevisionId(result.getContentRevisionId())
                .intentSummary(result.getIntentSummary())
                .behaviorSummary(result.getBehaviorSummary())
                .toolSummary(result.getToolSummary())
                .outcomeSummary(result.getOutcomeSummary())
                .benchmarkSummary(result.getBenchmarkSummary())
                .approvalNotes(result.getApprovalNotes())
                .evidenceSnippets(result.getEvidenceSnippets())
                .taskFamilies(result.getTaskFamilies())
                .tags(result.getTags())
                .promotionState(result.getPromotionState())
                .rolloutStage(result.getRolloutStage())
                .score(result.getScore())
                .successRate(result.getSuccessRate())
                .benchmarkWinRate(result.getBenchmarkWinRate())
                .regressionFlags(result.getRegressionFlags())
                .recencyScore(result.getRecencyScore())
                .golemLocalUsageSuccess(result.getGolemLocalUsageSuccess())
                .embeddingStatus(result.getEmbeddingStatus())
                .updatedAt(formatInstant(result.getUpdatedAt()))
                .explanation(toExplanationDto(result.getExplanation()))
                .build();
    }

    private SelfEvolvingTacticSearchExplanationDto toExplanationDto(TacticSearchExplanation explanation) {
        if (explanation == null) {
            return null;
        }
        return SelfEvolvingTacticSearchExplanationDto.builder()
                .searchMode(explanation.getSearchMode())
                .degradedReason(explanation.getDegradedReason())
                .bm25Score(explanation.getBm25Score())
                .vectorScore(explanation.getVectorScore())
                .rrfScore(explanation.getRrfScore())
                .qualityPrior(explanation.getQualityPrior())
                .mmrDiversityAdjustment(explanation.getMmrDiversityAdjustment())
                .negativeMemoryPenalty(explanation.getNegativeMemoryPenalty())
                .personalizationBoost(explanation.getPersonalizationBoost())
                .matchedQueryViews(explanation.getMatchedQueryViews())
                .matchedTerms(explanation.getMatchedTerms())
                .eligible(explanation.getEligible())
                .gatingReason(explanation.getGatingReason())
                .finalScore(explanation.getFinalScore())
                .build();
    }

    private SelfEvolvingTacticSearchStatusDto buildTacticSearchStatusDto() {
        if (localEmbeddingBootstrapService != null) {
            TacticSearchStatus status = localEmbeddingBootstrapService.probeStatus();
            SelfEvolvingTacticSearchStatusDto bootstrapStatus = SelfEvolvingTacticSearchStatusDto.builder()
                    .mode(status.getMode())
                    .reason(status.getReason())
                    .provider(status.getProvider())
                    .model(status.getModel())
                    .degraded(status.getDegraded())
                    .runtimeState(status.getRuntimeState())
                    .owned(status.getOwned())
                    .runtimeInstalled(status.getRuntimeInstalled())
                    .runtimeHealthy(status.getRuntimeHealthy())
                    .runtimeVersion(status.getRuntimeVersion())
                    .baseUrl(status.getBaseUrl())
                    .modelAvailable(status.getModelAvailable())
                    .restartAttempts(status.getRestartAttempts())
                    .nextRetryAt(formatInstant(status.getNextRetryAt()))
                    .nextRetryTime(status.getNextRetryTime())
                    .autoInstallConfigured(status.getAutoInstallConfigured())
                    .pullOnStartConfigured(status.getPullOnStartConfigured())
                    .pullAttempted(status.getPullAttempted())
                    .pullSucceeded(status.getPullSucceeded())
                    .updatedAt(formatInstant(status.getUpdatedAt()))
                    .build();
            return mergeSearchMetrics(bootstrapStatus);
        }
        if (tacticSearchMetricsService == null) {
            return SelfEvolvingTacticSearchStatusDto.builder()
                    .mode("bm25")
                    .degraded(false)
                    .build();
        }
        TacticSearchMetricsService.Snapshot snapshot = tacticSearchMetricsService.snapshot();
        return SelfEvolvingTacticSearchStatusDto.builder()
                .mode(snapshot.activeMode())
                .reason(snapshot.lastReason())
                .provider(snapshot.provider())
                .model(snapshot.model())
                .degraded(snapshot.degraded())
                .runtimeHealthy(snapshot.runtimeHealthy())
                .modelAvailable(snapshot.modelAvailable())
                .autoInstallConfigured(snapshot.autoInstallConfigured())
                .pullOnStartConfigured(snapshot.pullOnStartConfigured())
                .pullAttempted(snapshot.pullAttempted())
                .pullSucceeded(snapshot.pullSucceeded())
                .updatedAt(formatInstant(snapshot.updatedAt()))
                .build();
    }

    private SelfEvolvingTacticSearchStatusDto mergeSearchMetrics(SelfEvolvingTacticSearchStatusDto bootstrapStatus) {
        if (bootstrapStatus == null || tacticSearchMetricsService == null) {
            return bootstrapStatus;
        }
        TacticSearchMetricsService.Snapshot snapshot = tacticSearchMetricsService.snapshot();
        if (!hasMeaningfulSearchMetrics(snapshot)) {
            return bootstrapStatus;
        }
        return SelfEvolvingTacticSearchStatusDto.builder()
                .mode(firstNonBlank(snapshot.activeMode(), bootstrapStatus.getMode()))
                .reason(firstNonBlank(snapshot.lastReason(), bootstrapStatus.getReason()))
                .provider(firstNonBlank(snapshot.provider(), bootstrapStatus.getProvider()))
                .model(firstNonBlank(snapshot.model(), bootstrapStatus.getModel()))
                .degraded(Boolean.TRUE.equals(bootstrapStatus.getDegraded()) || snapshot.degraded())
                .runtimeState(bootstrapStatus.getRuntimeState())
                .owned(bootstrapStatus.getOwned())
                .runtimeInstalled(bootstrapStatus.getRuntimeInstalled())
                .runtimeHealthy(bootstrapStatus.getRuntimeHealthy())
                .runtimeVersion(bootstrapStatus.getRuntimeVersion())
                .baseUrl(bootstrapStatus.getBaseUrl())
                .modelAvailable(bootstrapStatus.getModelAvailable())
                .restartAttempts(bootstrapStatus.getRestartAttempts())
                .nextRetryAt(bootstrapStatus.getNextRetryAt())
                .nextRetryTime(bootstrapStatus.getNextRetryTime())
                .autoInstallConfigured(bootstrapStatus.getAutoInstallConfigured())
                .pullOnStartConfigured(bootstrapStatus.getPullOnStartConfigured())
                .pullAttempted(bootstrapStatus.getPullAttempted())
                .pullSucceeded(bootstrapStatus.getPullSucceeded())
                .updatedAt(firstNonBlank(formatInstant(snapshot.updatedAt()), bootstrapStatus.getUpdatedAt()))
                .build();
    }

    private boolean hasMeaningfulSearchMetrics(TacticSearchMetricsService.Snapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return snapshot.fallbackCount() > 0
                || snapshot.degradedQueryFailureCount() > 0
                || snapshot.degradedIndexFailureCount() > 0
                || snapshot.degraded()
                || !StringValueSupport.isBlank(snapshot.provider())
                || !StringValueSupport.isBlank(snapshot.model())
                || !"bm25".equalsIgnoreCase(snapshot.activeMode());
    }

    private String firstNonBlank(String primary, String fallback) {
        if (!StringValueSupport.isBlank(primary)) {
            return primary;
        }
        return fallback;
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
        Optional<RunVerdict> storedVerdict = selfEvolvingRunService.findVerdict(runRecord.getId());
        if (storedVerdict.isPresent()) {
            return storedVerdict.get();
        }
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

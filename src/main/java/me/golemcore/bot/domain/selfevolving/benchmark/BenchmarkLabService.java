package me.golemcore.bot.domain.selfevolving.benchmark;

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

import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaignVerdict;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCase;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkSuite;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.port.outbound.selfevolving.BenchmarkJournalPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import me.golemcore.bot.domain.selfevolving.promotion.PromotionWorkflowService;
import me.golemcore.bot.domain.selfevolving.run.SelfEvolvingRunService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticQualityMetricsService;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Harvests production runs into benchmark artifacts.
 */
@Service
@SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
public class BenchmarkLabService {

    private final BenchmarkJournalPort benchmarkJournal;
    private final SelfEvolvingRunService selfEvolvingRunService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final ObjectProvider<TacticQualityMetricsService> qualityMetricsServiceProvider;
    private final Clock clock;
    private final AtomicReference<List<BenchmarkCase>> caseCache = new AtomicReference<>();
    private final AtomicReference<List<BenchmarkSuite>> suiteCache = new AtomicReference<>();
    private final AtomicReference<List<BenchmarkCampaign>> campaignCache = new AtomicReference<>();
    private final AtomicReference<List<BenchmarkCampaignVerdict>> campaignVerdictCache = new AtomicReference<>();

    public BenchmarkLabService(BenchmarkJournalPort benchmarkJournal,
            SelfEvolvingRunService selfEvolvingRunService,
            PromotionWorkflowService promotionWorkflowService,
            ObjectProvider<TacticQualityMetricsService> qualityMetricsServiceProvider,
            Clock clock) {
        this.benchmarkJournal = benchmarkJournal;
        this.selfEvolvingRunService = selfEvolvingRunService;
        this.promotionWorkflowService = promotionWorkflowService;
        this.qualityMetricsServiceProvider = qualityMetricsServiceProvider;
        this.clock = clock;
    }

    public BenchmarkCampaign createRegressionCampaign(String runId) {
        RunRecord runRecord = selfEvolvingRunService.getRuns().stream()
                .filter(run -> run != null && runId.equals(run.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        String suiteId = UUID.randomUUID().toString();
        BenchmarkCase benchmarkCase = BenchmarkCase.builder()
                .id(UUID.randomUUID().toString())
                .suiteId(suiteId)
                .sourceRunId(runRecord.getId())
                .name("Harvested run " + runRecord.getId())
                .description("Regression case harvested from run " + runRecord.getId())
                .scoringContract("regression")
                .createdAt(Instant.now(clock))
                .inputPayload(buildInputPayload(runRecord))
                .expectedConstraints(buildExpectedConstraints(runRecord))
                .build();
        BenchmarkSuite benchmarkSuite = BenchmarkSuite.builder()
                .id(suiteId)
                .name("Regression suite " + runRecord.getId())
                .description("Harvested regression suite for run " + runRecord.getId())
                .taskFamily("harvested")
                .createdAt(Instant.now(clock))
                .caseIds(List.of(benchmarkCase.getId()))
                .build();
        BenchmarkCampaign benchmarkCampaign = BenchmarkCampaign.builder()
                .id(UUID.randomUUID().toString())
                .suiteId(suiteId)
                .baselineBundleId(resolveBaselineBundleId(runRecord))
                .candidateBundleId(runRecord.getArtifactBundleId())
                .status("created")
                .startedAt(Instant.now(clock))
                .runIds(List.of(runRecord.getId()))
                .build();

        saveCase(benchmarkCase);
        saveSuite(benchmarkSuite);
        saveCampaign(benchmarkCampaign);
        return benchmarkCampaign;
    }

    public List<BenchmarkSuite> getSuites() {
        List<BenchmarkSuite> cached = suiteCache.get();
        if (cached == null) {
            cached = benchmarkJournal.loadSuites();
            suiteCache.set(cached);
        }
        return cached;
    }

    /**
     * Records (or replaces) the verdict for a campaign, flips the campaign to
     * {@code "completed"}, and invalidates the tactic quality metrics cache so the
     * next enrichment pass incorporates the new benchmarkWinRate signal.
     */
    public BenchmarkCampaignVerdict recordCampaignVerdict(BenchmarkCampaignVerdict verdict) {
        if (verdict == null || StringValueSupport.isBlank(verdict.getCampaignId())) {
            throw new IllegalArgumentException("Verdict and campaignId must be non-null");
        }
        BenchmarkCampaignVerdict stored = verdict.getId() != null && verdict.getCreatedAt() != null
                ? verdict
                : BenchmarkCampaignVerdict.builder()
                        .id(verdict.getId() != null ? verdict.getId() : UUID.randomUUID().toString())
                        .campaignId(verdict.getCampaignId())
                        .recommendation(verdict.getRecommendation())
                        .summary(verdict.getSummary())
                        .confidence(verdict.getConfidence())
                        .qualityDelta(verdict.getQualityDelta())
                        .costDelta(verdict.getCostDelta())
                        .latencyDelta(verdict.getLatencyDelta())
                        .createdAt(verdict.getCreatedAt() != null ? verdict.getCreatedAt() : Instant.now(clock))
                        .build();
        List<BenchmarkCampaignVerdict> verdicts = new ArrayList<>(getCampaignVerdicts());
        verdicts.removeIf(v -> v != null && stored.getCampaignId().equals(v.getCampaignId()));
        verdicts.add(stored);
        saveCampaignVerdicts(verdicts);
        markCampaignCompleted(stored.getCampaignId());
        invalidateQualityMetricsCache();
        return stored;
    }

    public List<BenchmarkCampaignVerdict> getCampaignVerdicts() {
        List<BenchmarkCampaignVerdict> cached = campaignVerdictCache.get();
        if (cached == null) {
            cached = benchmarkJournal.loadCampaignVerdicts();
            campaignVerdictCache.set(cached);
        }
        return cached;
    }

    public Optional<BenchmarkCampaignVerdict> findVerdictByCampaignId(String campaignId) {
        if (StringValueSupport.isBlank(campaignId)) {
            return Optional.empty();
        }
        return getCampaignVerdicts().stream()
                .filter(v -> v != null && campaignId.equals(v.getCampaignId()))
                .findFirst();
    }

    public List<BenchmarkCampaign> getCampaigns() {
        List<BenchmarkCampaign> cached = campaignCache.get();
        if (cached == null) {
            cached = benchmarkJournal.loadCampaigns();
            campaignCache.set(cached);
        }
        return cached;
    }

    private void markCampaignCompleted(String campaignId) {
        List<BenchmarkCampaign> campaigns = new ArrayList<>(getCampaigns());
        boolean changed = false;
        for (int i = 0; i < campaigns.size(); i++) {
            BenchmarkCampaign campaign = campaigns.get(i);
            if (campaign == null || !campaignId.equals(campaign.getId())) {
                continue;
            }
            if (!"completed".equals(campaign.getStatus()) || campaign.getCompletedAt() == null) {
                campaigns.set(i, BenchmarkCampaign.builder()
                        .id(campaign.getId())
                        .suiteId(campaign.getSuiteId())
                        .baselineBundleId(campaign.getBaselineBundleId())
                        .candidateBundleId(campaign.getCandidateBundleId())
                        .status("completed")
                        .startedAt(campaign.getStartedAt())
                        .completedAt(Instant.now(clock))
                        .runIds(campaign.getRunIds() != null ? new ArrayList<>(campaign.getRunIds())
                                : new ArrayList<>())
                        .build());
                changed = true;
            }
            break;
        }
        if (changed) {
            saveCampaigns(campaigns);
        }
    }

    private void invalidateQualityMetricsCache() {
        if (qualityMetricsServiceProvider == null) {
            return;
        }
        TacticQualityMetricsService service = qualityMetricsServiceProvider.getIfAvailable();
        if (service != null) {
            service.invalidateCache();
        }
    }

    private List<BenchmarkCase> getCases() {
        List<BenchmarkCase> cached = caseCache.get();
        if (cached == null) {
            cached = benchmarkJournal.loadCases();
            caseCache.set(cached);
        }
        return cached;
    }

    private Map<String, Object> buildInputPayload(RunRecord runRecord) {
        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("runId", runRecord.getId());
        inputPayload.put("traceId", runRecord.getTraceId());
        inputPayload.put("golemId", runRecord.getGolemId());
        inputPayload.put("status", runRecord.getStatus());
        return inputPayload;
    }

    private Map<String, Object> buildExpectedConstraints(RunRecord runRecord) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("baselineBundleId", resolveBaselineBundleId(runRecord));
        constraints.put("candidateBundleId", runRecord.getArtifactBundleId());
        constraints.put("baselineStatus", runRecord.getStatus());
        return constraints;
    }

    private String resolveBaselineBundleId(RunRecord runRecord) {
        if (runRecord == null || StringValueSupport.isBlank(runRecord.getArtifactBundleId())
                || promotionWorkflowService == null) {
            return runRecord != null ? runRecord.getArtifactBundleId() : null;
        }
        return promotionWorkflowService.getPromotionDecisions().stream()
                .filter(decision -> decision != null)
                .filter(decision -> StringValueSupport.nullSafe(runRecord.getArtifactBundleId())
                        .equals(StringValueSupport.nullSafe(decision.getBundleId())))
                .map(PromotionDecision::getOriginBundleId)
                .filter(originBundleId -> !StringValueSupport.isBlank(originBundleId))
                .filter(originBundleId -> !originBundleId.equals(runRecord.getArtifactBundleId()))
                .findFirst()
                .orElse(runRecord.getArtifactBundleId());
    }

    private void saveCase(BenchmarkCase benchmarkCase) {
        List<BenchmarkCase> cases = new ArrayList<>(getCases());
        cases.add(benchmarkCase);
        saveCases(cases);
    }

    private void saveSuite(BenchmarkSuite benchmarkSuite) {
        List<BenchmarkSuite> suites = new ArrayList<>(getSuites());
        suites.add(benchmarkSuite);
        saveSuites(suites);
    }

    private void saveCampaign(BenchmarkCampaign benchmarkCampaign) {
        List<BenchmarkCampaign> campaigns = new ArrayList<>(getCampaigns());
        campaigns.add(benchmarkCampaign);
        saveCampaigns(campaigns);
    }

    private void saveCampaignVerdicts(List<BenchmarkCampaignVerdict> verdicts) {
        benchmarkJournal.saveCampaignVerdicts(verdicts);
        campaignVerdictCache.set(new ArrayList<>(verdicts));
    }

    private void saveCases(List<BenchmarkCase> cases) {
        benchmarkJournal.saveCases(cases);
        caseCache.set(new ArrayList<>(cases));
    }

    private void saveSuites(List<BenchmarkSuite> suites) {
        benchmarkJournal.saveSuites(suites);
        suiteCache.set(new ArrayList<>(suites));
    }

    private void saveCampaigns(List<BenchmarkCampaign> campaigns) {
        benchmarkJournal.saveCampaigns(campaigns);
        campaignCache.set(new ArrayList<>(campaigns));
    }
}

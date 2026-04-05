package me.golemcore.bot.domain.system;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchExplanationDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResponseDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResultDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchStatusDto;
import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.service.HiveSessionStateStore;
import me.golemcore.bot.domain.selfevolving.tactic.LocalEmbeddingBootstrapService;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.selfevolving.tactic.TacticSearchMetricsService;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class HiveRuntimeEventDispatchSystem implements AgentSystem {

    private final HiveEventBatchPublisher hiveEventBatchPublisher;
    private final TacticSearchMetricsService tacticSearchMetricsService;
    private final HiveSessionStateStore hiveSessionStateStore;
    private final LocalEmbeddingBootstrapService localEmbeddingBootstrapService;

    public HiveRuntimeEventDispatchSystem(
            HiveEventBatchPublisher hiveEventBatchPublisher,
            TacticSearchMetricsService tacticSearchMetricsService,
            HiveSessionStateStore hiveSessionStateStore,
            LocalEmbeddingBootstrapService localEmbeddingBootstrapService) {
        this.hiveEventBatchPublisher = hiveEventBatchPublisher;
        this.tacticSearchMetricsService = tacticSearchMetricsService;
        this.hiveSessionStateStore = hiveSessionStateStore;
        this.localEmbeddingBootstrapService = localEmbeddingBootstrapService;
    }

    @Override
    public String getName() {
        return "HiveRuntimeEventDispatchSystem";
    }

    @Override
    public int getOrder() {
        return 61;
    }

    @Override
    public AgentContext process(AgentContext context) {
        publishTacticSearch(context);
        if (context == null || context.getSession() == null || context.getSession().getChannelType() == null
                || !"hive".equalsIgnoreCase(context.getSession().getChannelType())) {
            return context;
        }
        List<RuntimeEvent> runtimeEvents = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        if (runtimeEvents == null || runtimeEvents.isEmpty()) {
            return context;
        }
        try {
            hiveEventBatchPublisher.publishRuntimeEvents(runtimeEvents, buildMetadata(context));
        } catch (RuntimeException exception) {
            log.warn("[Hive] Failed to dispatch runtime event batch: {}", exception.getMessage());
        }
        return context;
    }

    private void publishTacticSearch(AgentContext context) {
        if (context == null) {
            return;
        }
        if (!isHiveSessionAvailable()) {
            return;
        }
        Object queryValue = context.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY);
        if (!(queryValue instanceof TacticSearchQuery tacticSearchQuery)) {
            return;
        }
        String query = resolveQuery(tacticSearchQuery);
        if (query == null) {
            return;
        }
        List<TacticSearchResult> results = extractTacticResults(context);
        try {
            hiveEventBatchPublisher
                    .publishSelfEvolvingTacticSearchProjection(SelfEvolvingTacticSearchResponseDto.builder()
                            .query(query)
                            .status(buildTacticSearchStatusDto())
                            .results(results.stream().map(this::toSearchResultDto).toList())
                            .build());
        } catch (RuntimeException exception) {
            log.warn("[Hive] Failed to publish tactic search projection: {}", exception.getMessage());
        }
    }

    private boolean isHiveSessionAvailable() {
        if (hiveSessionStateStore == null) {
            return true;
        }
        return hiveSessionStateStore.load()
                .map(this::isCompleteHiveSession)
                .orElse(false);
    }

    private boolean isCompleteHiveSession(HiveSessionState sessionState) {
        return sessionState != null
                && !isBlank(sessionState.getServerUrl())
                && !isBlank(sessionState.getGolemId())
                && !isBlank(sessionState.getAccessToken());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> buildMetadata(AgentContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        copy(context, metadata, ContextAttributes.HIVE_CARD_ID);
        copy(context, metadata, ContextAttributes.HIVE_THREAD_ID);
        copy(context, metadata, ContextAttributes.HIVE_COMMAND_ID);
        copy(context, metadata, ContextAttributes.HIVE_RUN_ID);
        copy(context, metadata, ContextAttributes.HIVE_GOLEM_ID);
        return metadata;
    }

    private void copy(AgentContext context, Map<String, Object> metadata, String key) {
        Object value = context.getAttribute(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            metadata.put(key, stringValue);
        }
    }

    private List<TacticSearchResult> extractTacticResults(AgentContext context) {
        Object resultsValue = context.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_RESULTS);
        if (!(resultsValue instanceof List<?> rawResults) || rawResults.isEmpty()) {
            return List.of();
        }
        return rawResults.stream()
                .filter(TacticSearchResult.class::isInstance)
                .map(TacticSearchResult.class::cast)
                .toList();
    }

    private String resolveQuery(TacticSearchQuery tacticSearchQuery) {
        if (tacticSearchQuery.getRawQuery() != null && !tacticSearchQuery.getRawQuery().isBlank()) {
            return tacticSearchQuery.getRawQuery().trim();
        }
        if (tacticSearchQuery.getQueryViews() != null && !tacticSearchQuery.getQueryViews().isEmpty()) {
            return String.join(" ", tacticSearchQuery.getQueryViews()).trim();
        }
        return null;
    }

    private SelfEvolvingTacticSearchStatusDto buildTacticSearchStatusDto() {
        if (localEmbeddingBootstrapService != null) {
            TacticSearchStatus status = localEmbeddingBootstrapService.probeStatus();
            return SelfEvolvingTacticSearchStatusDto.builder()
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
                    .nextRetryAt(status.getNextRetryAt() != null ? status.getNextRetryAt().toString() : null)
                    .nextRetryTime(status.getNextRetryTime())
                    .autoInstallConfigured(status.getAutoInstallConfigured())
                    .pullOnStartConfigured(status.getPullOnStartConfigured())
                    .pullAttempted(status.getPullAttempted())
                    .pullSucceeded(status.getPullSucceeded())
                    .updatedAt(status.getUpdatedAt() != null ? status.getUpdatedAt().toString() : null)
                    .build();
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
                .updatedAt(snapshot.updatedAt() != null ? snapshot.updatedAt().toString() : null)
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
                .updatedAt(result.getUpdatedAt() != null ? result.getUpdatedAt().toString() : null)
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
                .rerankerVerdict(explanation.getRerankerVerdict())
                .matchedQueryViews(explanation.getMatchedQueryViews())
                .matchedTerms(explanation.getMatchedTerms())
                .eligible(explanation.getEligible())
                .gatingReason(explanation.getGatingReason())
                .finalScore(explanation.getFinalScore())
                .build();
    }
}

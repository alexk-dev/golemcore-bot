package me.golemcore.bot.domain.selfevolving.tactic;

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
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.port.outbound.selfevolving.TacticRecordStorePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Stores retrievable tactics separately from curated skills under
 * {@code self-evolving/tactics}.
 */
@Service
@Slf4j
public class TacticRecordService {

    private final TacticRecordStorePort tacticRecordStorePort;
    private final Clock clock;
    private final ObjectProvider<TacticIndexRebuildService> rebuildServiceProvider;
    private final ObjectProvider<TacticQualityMetricsService> qualityMetricsServiceProvider;
    private final AtomicReference<List<TacticRecord>> cache = new AtomicReference<>();

    public TacticRecordService(
            TacticRecordStorePort tacticRecordStorePort,
            Clock clock,
            ObjectProvider<TacticIndexRebuildService> rebuildServiceProvider,
            ObjectProvider<TacticQualityMetricsService> qualityMetricsServiceProvider) {
        this.tacticRecordStorePort = tacticRecordStorePort;
        this.clock = clock;
        this.rebuildServiceProvider = rebuildServiceProvider;
        this.qualityMetricsServiceProvider = qualityMetricsServiceProvider;
    }

    public TacticRecord save(TacticRecord record) {
        return saveInternal(record, true);
    }

    public void updateEmbeddingStatuses(Map<String, String> embeddingStatuses) {
        if (embeddingStatuses == null || embeddingStatuses.isEmpty()) {
            return;
        }
        for (TacticRecord record : getCachedRecords()) {
            if (record == null) {
                continue;
            }
            String updatedStatus = embeddingStatuses.get(record.getTacticId());
            if (StringValueSupport.isBlank(updatedStatus)) {
                continue;
            }
            String normalizedStatus = updatedStatus.trim();
            if (normalizedStatus.equals(record.getEmbeddingStatus())) {
                continue;
            }
            TacticRecord updated = copyRecord(record);
            updated.setEmbeddingStatus(normalizedStatus);
            saveInternal(updated, false);
        }
    }

    public List<TacticRecord> getAll() {
        List<TacticRecord> cached = getCachedRecords();
        TacticQualityMetricsService qualityMetricsService = resolveQualityMetricsService();
        if (qualityMetricsService == null) {
            List<TacticRecord> copies = new ArrayList<>(cached.size());
            for (TacticRecord record : cached) {
                copies.add(copyRecord(record));
            }
            return copies;
        }
        return new ArrayList<>(qualityMetricsService.enrichAll(cached));
    }

    private TacticQualityMetricsService resolveQualityMetricsService() {
        if (qualityMetricsServiceProvider == null) {
            return null;
        }
        return qualityMetricsServiceProvider.getIfAvailable();
    }

    private void invalidateQualityMetricsCache() {
        TacticQualityMetricsService qualityMetricsService = resolveQualityMetricsService();
        if (qualityMetricsService != null) {
            qualityMetricsService.invalidateCache();
        }
    }

    private List<TacticRecord> getCachedRecords() {
        List<TacticRecord> cached = cache.get();
        if (cached == null) {
            cached = loadAll();
            cache.set(cached);
        }
        return cached;
    }

    public java.util.Optional<TacticRecord> getById(String tacticId) {
        if (StringValueSupport.isBlank(tacticId)) {
            return java.util.Optional.empty();
        }
        return getRawById(tacticId).map(this::enrichMetrics);
    }

    private java.util.Optional<TacticRecord> getRawById(String tacticId) {
        return getCachedRecords().stream()
                .filter(record -> record != null && tacticId.trim().equals(record.getTacticId()))
                .findFirst();
    }

    public TacticRecord deactivate(String tacticId) {
        TacticRecord existing = getRawById(tacticId)
                .orElseThrow(() -> new IllegalArgumentException("Tactic not found: " + tacticId));
        TacticRecord updated = copyRecord(existing);
        updated.setPromotionState("inactive");
        updated.setRolloutStage("inactive");
        updated.setUpdatedAt(Instant.now(clock));
        return save(updated);
    }

    public TacticRecord reactivate(String tacticId) {
        TacticRecord existing = getRawById(tacticId)
                .orElseThrow(() -> new IllegalArgumentException("Tactic not found: " + tacticId));
        TacticRecord updated = copyRecord(existing);
        updated.setPromotionState("active");
        updated.setRolloutStage("active");
        updated.setUpdatedAt(Instant.now(clock));
        return save(updated);
    }

    public void delete(String tacticId) {
        TacticRecord existing = getRawById(tacticId)
                .orElseThrow(() -> new IllegalArgumentException("Tactic not found: " + tacticId));
        tacticRecordStorePort.delete(existing.getTacticId());
        removeFromCache(existing.getTacticId());
        invalidateQualityMetricsCache();
        triggerRebuild(existing.getTacticId());
    }

    private List<TacticRecord> loadAll() {
        List<TacticRecord> records = new ArrayList<>(tacticRecordStorePort.loadAll());
        records.sort(Comparator.comparing(TacticRecord::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<TacticRecord> normalizedRecords = new ArrayList<>();
        for (TacticRecord record : records) {
            normalizedRecords.add(normalize(record));
        }
        return normalizedRecords;
    }

    private void upsertCache(TacticRecord record) {
        List<TacticRecord> current = cache.get();
        if (current == null) {
            return;
        }
        List<TacticRecord> updated = new ArrayList<>(current);
        updated.removeIf(existing -> existing != null && record.getTacticId().equals(existing.getTacticId()));
        updated.add(record);
        updated.sort(Comparator.comparing(TacticRecord::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        cache.set(updated);
    }

    private void removeFromCache(String tacticId) {
        List<TacticRecord> current = cache.get();
        if (current == null) {
            return;
        }
        List<TacticRecord> updated = new ArrayList<>(current);
        updated.removeIf(existing -> existing != null && tacticId.equals(existing.getTacticId()));
        cache.set(updated);
    }

    private TacticRecord copyRecord(TacticRecord record) {
        return TacticRecord.builder()
                .tacticId(record.getTacticId())
                .artifactStreamId(record.getArtifactStreamId())
                .originArtifactStreamId(record.getOriginArtifactStreamId())
                .artifactKey(record.getArtifactKey())
                .artifactType(record.getArtifactType())
                .title(record.getTitle())
                .aliases(record.getAliases() != null ? new ArrayList<>(record.getAliases()) : new ArrayList<>())
                .contentRevisionId(record.getContentRevisionId())
                .intentSummary(record.getIntentSummary())
                .behaviorSummary(record.getBehaviorSummary())
                .toolSummary(record.getToolSummary())
                .outcomeSummary(record.getOutcomeSummary())
                .benchmarkSummary(record.getBenchmarkSummary())
                .approvalNotes(record.getApprovalNotes())
                .evidenceSnippets(record.getEvidenceSnippets() != null ? new ArrayList<>(record.getEvidenceSnippets())
                        : new ArrayList<>())
                .taskFamilies(record.getTaskFamilies() != null ? new ArrayList<>(record.getTaskFamilies())
                        : new ArrayList<>())
                .tags(record.getTags() != null ? new ArrayList<>(record.getTags()) : new ArrayList<>())
                .promotionState(record.getPromotionState())
                .rolloutStage(record.getRolloutStage())
                .successRate(record.getSuccessRate())
                .benchmarkWinRate(record.getBenchmarkWinRate())
                .regressionFlags(record.getRegressionFlags() != null ? new ArrayList<>(record.getRegressionFlags())
                        : new ArrayList<>())
                .recencyScore(record.getRecencyScore())
                .golemLocalUsageSuccess(record.getGolemLocalUsageSuccess())
                .embeddingStatus(record.getEmbeddingStatus())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private TacticRecord normalize(TacticRecord record) {
        TacticRecord normalized = record != null ? record : new TacticRecord();
        Instant updatedAt = normalized.getUpdatedAt() != null ? normalized.getUpdatedAt() : Instant.now(clock);

        String tacticId = firstNonBlank(normalized.getTacticId(), normalized.getContentRevisionId(),
                UUID.randomUUID().toString());
        String artifactStreamId = firstNonBlank(normalized.getArtifactStreamId(), tacticId);
        String artifactKey = firstNonBlank(normalized.getArtifactKey(), "tactic:" + tacticId);
        String artifactType = firstNonBlank(normalized.getArtifactType(), "skill");
        String title = firstNonBlank(normalized.getTitle(), humanizeArtifactKey(artifactKey));
        List<String> aliases = normalized.getAliases() != null && !normalized.getAliases().isEmpty()
                ? new ArrayList<>(normalized.getAliases())
                : new ArrayList<>(List.of(artifactKey));

        normalized.setTacticId(tacticId);
        normalized.setArtifactStreamId(artifactStreamId);
        normalized.setOriginArtifactStreamId(firstNonBlank(normalized.getOriginArtifactStreamId(), artifactStreamId));
        normalized.setArtifactKey(artifactKey);
        normalized.setArtifactType(artifactType);
        normalized.setTitle(title);
        normalized.setAliases(aliases);
        normalized.setContentRevisionId(firstNonBlank(normalized.getContentRevisionId(), tacticId));
        normalized.setPromotionState(firstNonBlank(normalized.getPromotionState(), "candidate"));
        normalized.setRolloutStage(firstNonBlank(normalized.getRolloutStage(), "proposed"));
        normalized.setRegressionFlags(
                normalized.getRegressionFlags() != null ? new ArrayList<>(normalized.getRegressionFlags())
                        : new ArrayList<>());
        normalized.setTaskFamilies(normalized.getTaskFamilies() != null && !normalized.getTaskFamilies().isEmpty()
                ? new ArrayList<>(normalized.getTaskFamilies())
                : new ArrayList<>(List.of(artifactType)));
        normalized.setTags(normalized.getTags() != null && !normalized.getTags().isEmpty()
                ? new ArrayList<>(normalized.getTags())
                : new ArrayList<>(List.of(artifactType, normalized.getPromotionState())));
        normalized.setEvidenceSnippets(normalized.getEvidenceSnippets() != null
                ? new ArrayList<>(normalized.getEvidenceSnippets())
                : new ArrayList<>());
        normalized.setEmbeddingStatus(firstNonBlank(normalized.getEmbeddingStatus(), "pending"));
        normalized.setUpdatedAt(updatedAt);
        return normalized;
    }

    private String humanizeArtifactKey(String artifactKey) {
        if (StringValueSupport.isBlank(artifactKey)) {
            return "Tactic";
        }
        String normalized = artifactKey.replace(':', ' ').replace('_', ' ').trim();
        if (normalized.isEmpty()) {
            return "Tactic";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!StringValueSupport.isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void triggerRebuild(String tacticId) {
        if (rebuildServiceProvider == null) {
            return;
        }
        TacticIndexRebuildService rebuildService = rebuildServiceProvider.getIfAvailable();
        if (rebuildService != null) {
            rebuildService.onTacticChanged(tacticId);
        }
    }

    private TacticRecord saveInternal(TacticRecord record, boolean triggerRebuild) {
        TacticRecord normalized = normalize(record);
        tacticRecordStorePort.save(normalized);
        upsertCache(normalized);
        invalidateQualityMetricsCache();
        if (triggerRebuild) {
            triggerRebuild(normalized.getTacticId());
        }
        return normalized;
    }

    private TacticRecord enrichMetrics(TacticRecord record) {
        if (record == null) {
            return null;
        }
        TacticQualityMetricsService qualityMetricsService = resolveQualityMetricsService();
        if (qualityMetricsService == null) {
            return copyRecord(record);
        }
        return qualityMetricsService.enrich(record);
    }
}

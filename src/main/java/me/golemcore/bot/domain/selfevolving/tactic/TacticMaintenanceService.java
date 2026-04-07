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
import me.golemcore.bot.domain.service.StringValueSupport;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Garbage-collects stale tactics and merges duplicates to keep the tactic index
 * clean and focused.
 */
@Service
@Slf4j
public class TacticMaintenanceService {

    private static final Duration DEFAULT_STALE_THRESHOLD = Duration.ofDays(30);
    private static final double DUPLICATE_TITLE_SIMILARITY_THRESHOLD = 0.85;

    private final TacticRecordService tacticRecordService;
    private final Clock clock;

    public TacticMaintenanceService(TacticRecordService tacticRecordService, Clock clock) {
        this.tacticRecordService = tacticRecordService;
        this.clock = clock;
    }

    /**
     * Finds tactic IDs eligible for garbage collection: inactive/candidate tactics
     * that have not been updated within the staleness threshold and have no strong
     * quality signal.
     */
    public List<String> findGcCandidates() {
        return findGcCandidates(DEFAULT_STALE_THRESHOLD);
    }

    public List<String> findGcCandidates(Duration stalenessThreshold) {
        Instant cutoff = Instant.now(clock).minus(stalenessThreshold);
        List<String> candidates = new ArrayList<>();
        for (TacticRecord record : tacticRecordService.getAll()) {
            if (record == null || StringValueSupport.isBlank(record.getTacticId())) {
                continue;
            }
            if (isProtectedState(record.getPromotionState())) {
                continue;
            }
            if (record.getUpdatedAt() != null && record.getUpdatedAt().isAfter(cutoff)) {
                continue;
            }
            if (hasStrongQualitySignal(record)) {
                continue;
            }
            candidates.add(record.getTacticId());
        }
        log.info("[TacticMaintenance] Found {} GC candidates with staleness threshold {}", candidates.size(),
                stalenessThreshold);
        return candidates;
    }

    /**
     * Deactivates all GC candidate tactics. Returns the IDs of tactics that were
     * deactivated.
     */
    public List<String> collectGarbage() {
        return collectGarbage(DEFAULT_STALE_THRESHOLD);
    }

    public List<String> collectGarbage(Duration stalenessThreshold) {
        List<String> candidates = findGcCandidates(stalenessThreshold);
        List<String> deactivated = new ArrayList<>();
        for (String tacticId : candidates) {
            try {
                tacticRecordService.deactivate(tacticId);
                deactivated.add(tacticId);
            } catch (RuntimeException exception) { // NOSONAR - GC must not break on single tactic
                log.debug("[TacticMaintenance] Failed to deactivate tactic {}: {}", tacticId, exception.getMessage());
            }
        }
        log.info("[TacticMaintenance] Deactivated {} stale tactics", deactivated.size());
        return deactivated;
    }

    /**
     * Finds groups of tactics that likely describe the same behavior based on title
     * similarity and shared artifact key.
     */
    public List<DuplicateGroup> findDuplicateGroups() {
        List<TacticRecord> records = tacticRecordService.getAll();
        Map<String, List<TacticRecord>> byArtifactKey = new LinkedHashMap<>();
        for (TacticRecord record : records) {
            if (record == null || StringValueSupport.isBlank(record.getArtifactKey())) {
                continue;
            }
            byArtifactKey.computeIfAbsent(record.getArtifactKey().trim().toLowerCase(), k -> new ArrayList<>())
                    .add(record);
        }
        List<DuplicateGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<TacticRecord>> entry : byArtifactKey.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            groups.add(selectPrimaryAndSecondaries(entry.getKey(), entry.getValue()));
        }
        findTitleSimilarityDuplicates(records, byArtifactKey, groups);
        log.info("[TacticMaintenance] Found {} duplicate groups", groups.size());
        return groups;
    }

    /**
     * Merges a secondary tactic into a primary one. Transfers evidence snippets and
     * aliases, then deactivates the secondary.
     */
    public void merge(String primaryId, String secondaryId) {
        TacticRecord primary = tacticRecordService.getById(primaryId)
                .orElseThrow(() -> new IllegalArgumentException("Primary tactic not found: " + primaryId));
        TacticRecord secondary = tacticRecordService.getById(secondaryId)
                .orElseThrow(() -> new IllegalArgumentException("Secondary tactic not found: " + secondaryId));
        List<String> mergedAliases = new ArrayList<>(
                primary.getAliases() != null ? primary.getAliases() : new ArrayList<>());
        if (secondary.getAliases() != null) {
            for (String alias : secondary.getAliases()) {
                if (alias != null && !mergedAliases.contains(alias)) {
                    mergedAliases.add(alias);
                }
            }
        }
        if (secondary.getTitle() != null && !mergedAliases.contains(secondary.getTitle())) {
            mergedAliases.add(secondary.getTitle());
        }
        primary.setAliases(mergedAliases);
        List<String> mergedEvidence = new ArrayList<>(
                primary.getEvidenceSnippets() != null ? primary.getEvidenceSnippets() : new ArrayList<>());
        if (secondary.getEvidenceSnippets() != null) {
            for (String snippet : secondary.getEvidenceSnippets()) {
                if (snippet != null && !mergedEvidence.contains(snippet)) {
                    mergedEvidence.add(snippet);
                }
            }
        }
        primary.setEvidenceSnippets(mergedEvidence);
        primary.setUpdatedAt(Instant.now(clock));
        tacticRecordService.save(primary);
        tacticRecordService.deactivate(secondaryId);
        log.info("[TacticMaintenance] Merged tactic {} into {}", secondaryId, primaryId);
    }

    private boolean isProtectedState(String promotionState) {
        return "approved".equalsIgnoreCase(promotionState)
                || "active".equalsIgnoreCase(promotionState);
    }

    private boolean hasStrongQualitySignal(TacticRecord record) {
        Double successRate = record.getSuccessRate();
        Double benchmarkWinRate = record.getBenchmarkWinRate();
        if (successRate != null && successRate >= 0.7) {
            return true;
        }
        return benchmarkWinRate != null && benchmarkWinRate >= 0.6;
    }

    private DuplicateGroup selectPrimaryAndSecondaries(String artifactKey, List<TacticRecord> records) {
        TacticRecord primary = records.getFirst();
        for (TacticRecord record : records) {
            if (isProtectedState(record.getPromotionState())) {
                primary = record;
                break;
            }
            if (hasStrongQualitySignal(record) && !hasStrongQualitySignal(primary)) {
                primary = record;
            }
        }
        List<String> secondaryIds = new ArrayList<>();
        for (TacticRecord record : records) {
            if (!record.getTacticId().equals(primary.getTacticId())) {
                secondaryIds.add(record.getTacticId());
            }
        }
        return new DuplicateGroup(primary.getTacticId(), secondaryIds, "artifact_key:" + artifactKey);
    }

    private void findTitleSimilarityDuplicates(List<TacticRecord> records,
            Map<String, List<TacticRecord>> alreadyGrouped, List<DuplicateGroup> groups) {
        for (int i = 0; i < records.size(); i++) {
            TacticRecord left = records.get(i);
            if (left == null || StringValueSupport.isBlank(left.getTitle())) {
                continue;
            }
            for (int j = i + 1; j < records.size(); j++) {
                TacticRecord right = records.get(j);
                if (right == null || StringValueSupport.isBlank(right.getTitle())) {
                    continue;
                }
                if (sameArtifactKey(left, right)) {
                    continue;
                }
                if (alreadyInGroup(groups, left.getTacticId(), right.getTacticId())) {
                    continue;
                }
                double similarity = titleSimilarity(left.getTitle(), right.getTitle());
                if (similarity >= DUPLICATE_TITLE_SIMILARITY_THRESHOLD) {
                    String primaryId = isProtectedState(left.getPromotionState()) ? left.getTacticId()
                            : right.getTacticId();
                    String secondaryId = primaryId.equals(left.getTacticId()) ? right.getTacticId()
                            : left.getTacticId();
                    groups.add(new DuplicateGroup(primaryId, List.of(secondaryId),
                            String.format("title_similarity:%.2f", similarity)));
                }
            }
        }
    }

    private boolean sameArtifactKey(TacticRecord left, TacticRecord right) {
        if (StringValueSupport.isBlank(left.getArtifactKey()) || StringValueSupport.isBlank(right.getArtifactKey())) {
            return false;
        }
        return left.getArtifactKey().trim().equalsIgnoreCase(right.getArtifactKey().trim());
    }

    private boolean alreadyInGroup(List<DuplicateGroup> groups, String idA, String idB) {
        for (DuplicateGroup group : groups) {
            boolean containsA = group.primaryId().equals(idA) || group.secondaryIds().contains(idA);
            boolean containsB = group.primaryId().equals(idB) || group.secondaryIds().contains(idB);
            if (containsA && containsB) {
                return true;
            }
        }
        return false;
    }

    /**
     * Jaccard similarity over lowercased whitespace-split tokens.
     */
    private double titleSimilarity(String titleA, String titleB) {
        List<String> tokensA = tokenize(titleA);
        List<String> tokensB = tokenize(titleB);
        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return 1.0;
        }
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return 0.0;
        }
        long intersection = tokensA.stream().filter(tokensB::contains).count();
        long union = tokensA.size() + tokensB.size() - intersection;
        return union > 0 ? (double) intersection / union : 0.0;
    }

    private List<String> tokenize(String value) {
        if (StringValueSupport.isBlank(value)) {
            return List.of();
        }
        return List.of(value.toLowerCase().split("[^a-z0-9]+")).stream()
                .filter(token -> !token.isBlank() && token.length() > 1)
                .toList();
    }

    public record DuplicateGroup(String primaryId, List<String> secondaryIds, String reason) {
    }
}

package me.golemcore.bot.domain.memory.retrieval;

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

import me.golemcore.bot.domain.memory.model.MemoryRetrievalPlan;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies a deterministic second-pass reranking over already scored candidates.
 * <p>
 * The first scoring pass optimizes for general prompt relevance. This reranker refines the order using more precise
 * signals such as exact title or content phrase matches, title token coverage, and active skill alignment before
 * layer-specific caps are applied.
 * </p>
 */
@Service
public class MemoryCandidateReranker {

    private static final RerankingProfile BALANCED_PROFILE = new RerankingProfile(0.18, 0.10, 0.08, 0.06);
    private static final RerankingProfile AGGRESSIVE_PROFILE = new RerankingProfile(0.26, 0.14, 0.10, 0.08);

    private final RuntimeConfigService runtimeConfigService;

    public MemoryCandidateReranker(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    /**
     * Reorders the scored candidates using deterministic retrieval-side signals.
     *
     * @param plan
     *            normalized retrieval plan
     * @param scored
     *            first-pass scored candidates
     *
     * @return candidates in refined relevance order
     */
    public List<MemoryScoredItem> rerank(MemoryRetrievalPlan plan, List<MemoryScoredItem> scored) {
        if (!runtimeConfigService.isMemoryRerankingEnabled() || scored.size() < 2) {
            return scored;
        }

        MemoryQuery query = plan != null ? plan.getQuery() : null;
        RerankingProfile profile = resolveProfile(runtimeConfigService.getMemoryRerankingProfile());
        List<RerankedCandidate> reranked = new ArrayList<>();

        for (int i = 0; i < scored.size(); i++) {
            MemoryScoredItem candidate = scored.get(i);
            MemoryItem item = candidate != null ? candidate.getItem() : null;
            double rerankScore = candidate != null ? candidate.getScore() : 0.0;
            rerankScore += titlePhraseBoost(query, item, profile);
            rerankScore += contentPhraseBoost(query, item, profile);
            rerankScore += titleTokenCoverageBoost(query, item, profile);
            rerankScore += skillTagBoost(query, item, profile);
            reranked.add(new RerankedCandidate(candidate, rerankScore, i, resolveTimestamp(item)));
        }

        reranked.sort(Comparator.comparingDouble(RerankedCandidate::rerankScore).reversed()
                .thenComparing(RerankedCandidate::baseScore, Comparator.reverseOrder())
                .thenComparing(RerankedCandidate::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(RerankedCandidate::originalIndex));

        return reranked.stream().map(RerankedCandidate::candidate).toList();
    }

    private double titlePhraseBoost(MemoryQuery query, MemoryItem item, RerankingProfile profile) {
        return containsNormalizedPhrase(query != null ? query.getQueryText() : null,
                item != null ? item.getTitle() : null) ? profile.titlePhraseBoost() : 0.0;
    }

    private double contentPhraseBoost(MemoryQuery query, MemoryItem item, RerankingProfile profile) {
        return containsNormalizedPhrase(query != null ? query.getQueryText() : null,
                item != null ? item.getContent() : null) ? profile.contentPhraseBoost() : 0.0;
    }

    private double titleTokenCoverageBoost(MemoryQuery query, MemoryItem item, RerankingProfile profile) {
        Set<String> queryTokens = tokenize(query != null ? query.getQueryText() : null);
        Set<String> titleTokens = tokenize(item != null ? item.getTitle() : null);
        if (queryTokens.isEmpty() || titleTokens.isEmpty() || !titleTokens.containsAll(queryTokens)) {
            return 0.0;
        }
        return profile.titleCoverageBoost();
    }

    private double skillTagBoost(MemoryQuery query, MemoryItem item, RerankingProfile profile) {
        if (query == null || item == null || query.getActiveSkill() == null || query.getActiveSkill().isBlank()
                || item.getTags() == null || item.getTags().isEmpty()) {
            return 0.0;
        }
        String activeSkill = query.getActiveSkill().trim().toLowerCase(Locale.ROOT);
        for (String tag : item.getTags()) {
            if (tag != null && activeSkill.equals(tag.trim().toLowerCase(Locale.ROOT))) {
                return profile.skillTagBoost();
            }
        }
        return 0.0;
    }

    private boolean containsNormalizedPhrase(String queryText, String candidateText) {
        String normalizedQuery = normalizePhrase(queryText);
        String normalizedCandidate = normalizePhrase(candidateText);
        if (normalizedQuery.isBlank() || normalizedCandidate.isBlank()) {
            return false;
        }
        return normalizedCandidate.contains(normalizedQuery);
    }

    private String normalizePhrase(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}0-9_./#-]+", " ").trim()
                .replaceAll("\\s+", " ");
        return normalized;
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String[] rawTokens = normalizePhrase(text).split(" ");
        for (String token : rawTokens) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private Instant resolveTimestamp(MemoryItem item) {
        if (item == null) {
            return null;
        }
        if (item.getUpdatedAt() != null) {
            return item.getUpdatedAt();
        }
        return item.getCreatedAt();
    }

    private RerankingProfile resolveProfile(String profile) {
        if ("aggressive".equalsIgnoreCase(profile)) {
            return AGGRESSIVE_PROFILE;
        }
        return BALANCED_PROFILE;
    }

    private record RerankedCandidate(MemoryScoredItem candidate, double rerankScore, int originalIndex,
            Instant timestamp) {
        private Double baseScore() {
            return candidate != null ? candidate.getScore() : 0.0;
        }
    }

    private record RerankingProfile(double titlePhraseBoost, double contentPhraseBoost, double titleCoverageBoost,
            double skillTagBoost) {
    }
}

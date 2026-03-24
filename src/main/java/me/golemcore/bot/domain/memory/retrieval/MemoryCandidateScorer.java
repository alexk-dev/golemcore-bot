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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scores collected candidates and sorts them by prompt relevance.
 */
@Service
public class MemoryCandidateScorer {

    /**
     * Score and order the collected candidates for the supplied plan.
     *
     * @param plan
     *            normalized retrieval plan
     * @param candidates
     *            collected memory items
     * @return sorted scored candidates
     */
    public List<MemoryScoredItem> score(MemoryRetrievalPlan plan, List<MemoryItem> candidates) {
        List<MemoryScoredItem> scored = new ArrayList<>();
        for (MemoryItem item : candidates) {
            double score = score(plan.getQuery(), item);
            scored.add(MemoryScoredItem.builder()
                    .item(item)
                    .score(score)
                    .build());
        }

        scored.sort(Comparator
                .comparingDouble(MemoryScoredItem::getScore)
                .reversed()
                .thenComparing((MemoryScoredItem scoredItem) -> resolveTimestamp(scoredItem.getItem()),
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return scored;
    }

    private double score(MemoryQuery query, MemoryItem item) {
        double relevance = lexicalRelevance(query.getQueryText(), item);
        double recency = recencyScore(item);
        double salience = clamp(defaultDouble(item.getSalience(), 0.50));
        double confidence = clamp(defaultDouble(item.getConfidence(), 0.55));

        double typeBoost = typeBoost(item.getType());
        double skillBoost = skillBoost(query.getActiveSkill(), item.getTags());

        return (relevance * 0.40)
                + (recency * 0.20)
                + (salience * 0.20)
                + (confidence * 0.20)
                + typeBoost
                + skillBoost;
    }

    private double lexicalRelevance(String queryText, MemoryItem item) {
        if (queryText == null || queryText.isBlank()) {
            return 0.20;
        }
        Set<String> queryTokens = tokenize(queryText);
        if (queryTokens.isEmpty()) {
            return 0.20;
        }

        String searchable = buildSearchableText(item);
        Set<String> contentTokens = tokenize(searchable);
        if (contentTokens.isEmpty()) {
            return 0.0;
        }

        int matches = 0;
        for (String token : queryTokens) {
            if (contentTokens.contains(token)) {
                matches++;
            }
        }
        return clamp((double) matches / (double) queryTokens.size());
    }

    private String buildSearchableText(MemoryItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.getTitle() != null) {
            sb.append(item.getTitle()).append(' ');
        }
        if (item.getContent() != null) {
            sb.append(item.getContent()).append(' ');
        }
        if (item.getTags() != null) {
            for (String tag : item.getTags()) {
                sb.append(tag).append(' ');
            }
        }
        return sb.toString();
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String[] raw = text.toLowerCase(Locale.ROOT).split("[^a-zа-я0-9_./#-]+");
        for (String token : raw) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private double recencyScore(MemoryItem item) {
        Instant timestamp = resolveTimestamp(item);
        if (timestamp == null) {
            return 0.30;
        }
        long days = ChronoUnit.DAYS.between(timestamp, Instant.now());
        if (days <= 0) {
            return 1.0;
        }
        if (days >= 30) {
            return 0.0;
        }
        return clamp(1.0 - (days / 30.0));
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

    private double typeBoost(MemoryItem.Type type) {
        if (type == null) {
            return 0.0;
        }
        return switch (type) {
        case FAILURE, FIX, CONSTRAINT -> 0.12;
        case DECISION, PROJECT_FACT, PREFERENCE -> 0.08;
        case TASK_STATE, COMMAND_RESULT -> 0.05;
        };
    }

    private double skillBoost(String activeSkill, List<String> tags) {
        if (activeSkill == null || activeSkill.isBlank() || tags == null || tags.isEmpty()) {
            return 0.0;
        }
        String normalized = activeSkill.toLowerCase(Locale.ROOT);
        for (String tag : tags) {
            if (normalized.equals(tag)) {
                return 0.10;
            }
        }
        return 0.0;
    }

    private double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private double defaultDouble(Double value, double fallback) {
        return value != null ? value : fallback;
    }
}

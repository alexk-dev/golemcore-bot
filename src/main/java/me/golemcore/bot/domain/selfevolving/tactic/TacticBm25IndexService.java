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

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticIndexDocument;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * In-memory lexical tactic index with BM25-style scoring.
 */
@Service
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public class TacticBm25IndexService {

    private static final double K1 = 1.2d;
    private static final double B = 0.75d;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public void replaceDocuments(List<TacticIndexDocument> documents) {
        List<TacticIndexDocument> safeDocuments = documents != null ? new ArrayList<>(documents) : new ArrayList<>();
        snapshot.set(buildSnapshot(safeDocuments));
    }

    public List<ScoredDocument> search(TacticSearchQuery query, int limit) {
        Snapshot current = snapshot();
        if (current.documents().isEmpty() || query == null) {
            return List.of();
        }
        List<String> queryTerms = expandQueryTerms(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        List<ScoredDocument> results = new ArrayList<>();
        for (TacticIndexDocument document : current.documents()) {
            Map<String, Integer> termFrequencies = current.termFrequencies().getOrDefault(document.getTacticId(),
                    Map.of());
            int documentLength = Math.max(current.documentLengths().getOrDefault(document.getTacticId(), 0), 1);
            double score = 0.0d;
            Set<String> matchedTerms = new LinkedHashSet<>();
            for (String queryTerm : queryTerms) {
                Integer frequency = termFrequencies.get(queryTerm);
                if (frequency == null || frequency <= 0) {
                    continue;
                }
                matchedTerms.add(queryTerm);
                double idf = current.inverseDocumentFrequency().getOrDefault(queryTerm, 0.0d);
                double numerator = frequency * (K1 + 1.0d);
                double denominator = frequency + K1 * (1.0d - B + B * documentLength / current.averageDocumentLength());
                score += idf * (numerator / denominator);
            }
            if (score > 0.0d) {
                results.add(new ScoredDocument(document, score, new ArrayList<>(matchedTerms)));
            }
        }

        return results.stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed()
                        .thenComparing(result -> result.document().getUpdatedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(limit, 1))
                .toList();
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    private Snapshot buildSnapshot(List<TacticIndexDocument> documents) {
        Map<String, Map<String, Integer>> termFrequencies = new HashMap<>();
        Map<String, Integer> documentLengths = new HashMap<>();
        Map<String, Integer> documentFrequencies = new HashMap<>();

        for (TacticIndexDocument document : documents) {
            List<String> tokens = tokenize(document.getLexicalText());
            Map<String, Integer> frequencies = new HashMap<>();
            for (String token : tokens) {
                frequencies.merge(token, 1, Integer::sum);
            }
            termFrequencies.put(document.getTacticId(), frequencies);
            documentLengths.put(document.getTacticId(), tokens.size());
            Set<String> uniqueTokens = new LinkedHashSet<>(tokens);
            for (String token : uniqueTokens) {
                documentFrequencies.merge(token, 1, Integer::sum);
            }
        }

        int documentCount = Math.max(documents.size(), 1);
        double averageLength = documents.isEmpty()
                ? 1.0d
                : documentLengths.values().stream().mapToInt(Integer::intValue).average().orElse(1.0d);
        Map<String, Double> inverseDocumentFrequency = documentFrequencies.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Math
                                .log(1.0d + (documentCount - entry.getValue() + 0.5d) / (entry.getValue() + 0.5d))));

        return new Snapshot(
                documents,
                termFrequencies,
                documentLengths,
                inverseDocumentFrequency,
                averageLength,
                Instant.now());
    }

    private List<String> expandQueryTerms(TacticSearchQuery query) {
        Set<String> terms = new LinkedHashSet<>();
        terms.addAll(tokenize(query.getRawQuery()));
        if (query.getQueryViews() != null) {
            query.getQueryViews().forEach(view -> terms.addAll(tokenize(view)));
        }
        if (query.getViewQueries() != null) {
            query.getViewQueries().values().forEach(view -> terms.addAll(tokenize(view)));
        }
        return new ArrayList<>(terms);
    }

    private List<String> tokenize(String value) {
        if (StringValueSupport.isBlank(value)) {
            return List.of();
        }
        return List.of(value.toLowerCase(Locale.ROOT).split("[^a-z0-9:_-]+")).stream()
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
    }

    public record ScoredDocument(TacticIndexDocument document, double score, List<String> matchedTerms) {
    }

    public record Snapshot(
            List<TacticIndexDocument> documents,
            Map<String, Map<String, Integer>> termFrequencies,
            Map<String, Integer> documentLengths,
            Map<String, Double> inverseDocumentFrequency,
            double averageDocumentLength,
            Instant updatedAt) {

        static Snapshot empty() {
            return new Snapshot(List.of(), Map.of(), Map.of(), Map.of(), 1.0d, Instant.EPOCH);
        }
    }
}

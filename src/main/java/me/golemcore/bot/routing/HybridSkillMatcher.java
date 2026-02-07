package me.golemcore.bot.routing;

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

import me.golemcore.bot.adapter.outbound.llm.LlmAdapterFactory;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import me.golemcore.bot.port.outbound.LlmPort;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Hybrid skill matcher that combines semantic search with LLM classification.
 *
 * <p>
 * Two-stage matching process:
 * <ol>
 * <li><b>Stage 1: Semantic Pre-filter</b> - Fast embedding-based similarity
 * search (~5ms) to identify top-K candidate skills using cosine similarity</li>
 * <li><b>Stage 2: LLM Classifier</b> - Accurate selection using fast LLM
 * (~200-400ms) to determine best skill from candidates and assign model
 * tier</li>
 * </ol>
 *
 * <p>
 * Optimizations:
 * <ul>
 * <li>Skip LLM stage if semantic score exceeds threshold (configurable, default
 * 0.95)</li>
 * <li>Result caching with TTL to avoid repeated classification for similar
 * queries</li>
 * <li>Graceful degradation to semantic-only if LLM unavailable</li>
 * <li>Automatic re-indexing if embedding store is empty</li>
 * </ul>
 *
 * <p>
 * The matcher maintains a cache with automatic eviction and periodic cleanup.
 * LLM classification uses a fast model (typically gpt-4o-mini) for efficiency.
 *
 * @since 1.0
 * @see SkillMatcher
 * @see SkillEmbeddingStore
 * @see LlmSkillClassifier
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HybridSkillMatcher implements SkillMatcher {

    private final BotProperties properties;
    private final EmbeddingPort embeddingPort;
    private final SkillEmbeddingStore embeddingStore;
    private final LlmSkillClassifier llmClassifier;
    private final LlmAdapterFactory llmAdapterFactory;

    /**
     * Simple cache for match results. Key: hash of (query embedding + skill set)
     */
    private final Map<String, CachedResult> resultCache = new ConcurrentHashMap<>();
    private ScheduledExecutorService cacheCleanupExecutor;

    private volatile boolean ready = false;

    @PostConstruct
    public void init() {
        if (!isEnabled()) {
            log.info("Skill matcher is disabled");
            return;
        }

        // Start periodic cache cleanup
        startCacheCleanup();
    }

    @Override
    public CompletableFuture<SkillMatchResult> match(
            String userMessage,
            List<Message> conversationHistory,
            List<Skill> availableSkills) {

        if (!isEnabled()) {
            return CompletableFuture.completedFuture(
                    SkillMatchResult.noMatch("Skill matcher disabled"));
        }

        if (availableSkills == null || availableSkills.isEmpty()) {
            return CompletableFuture.completedFuture(
                    SkillMatchResult.noMatch("No skills available"));
        }

        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            log.debug("[SkillMatcher] Starting match for query: '{}'", truncate(userMessage, 100));
            log.trace("[SkillMatcher] Available skills: {}", availableSkills.stream().map(s -> s.getName()).toList());

            try {
                // Re-index if store is empty
                if (embeddingStore.isEmpty()) {
                    log.debug("[SkillMatcher] Embedding store empty, indexing {} skills", availableSkills.size());
                    indexSkills(availableSkills);
                }

                // Stage 1: Semantic pre-filter
                log.debug("[SkillMatcher] Stage 1: Running semantic search...");
                List<SkillCandidate> candidates = semanticSearch(userMessage);
                log.debug("[SkillMatcher] Semantic search returned {} candidates", candidates.size());
                for (SkillCandidate c : candidates) {
                    log.trace("[SkillMatcher]   - {} (score: {})", c.getName(),
                            String.format("%.3f", c.getSemanticScore()));
                }

                if (candidates.isEmpty()) {
                    log.debug("[SkillMatcher] No semantic candidates, running LLM classifier for tier detection");
                    // Build synthetic candidates from all available skills for tier detection
                    BotProperties.SkillMatcherProperties config = properties.getRouter().getSkillMatcher();
                    if (config.getClassifier().isEnabled()) {
                        List<SkillCandidate> allSkillCandidates = availableSkills.stream()
                                .map(s -> SkillCandidate.builder()
                                        .name(s.getName())
                                        .description(s.getDescription())
                                        .semanticScore(0.0)
                                        .build())
                                .toList();
                        return runLlmClassifier(userMessage, conversationHistory, allSkillCandidates, null, startTime);
                    }
                    return SkillMatchResult.builder()
                            .selectedSkill(null)
                            .confidence(1.0)
                            .modelTier("fast")
                            .reason("No matching skills found")
                            .latencyMs(System.currentTimeMillis() - startTime)
                            .build();
                }

                // Check cache
                String cacheKey = computeCacheKey(userMessage, candidates);
                CachedResult cached = resultCache.get(cacheKey);
                if (cached != null && !cached.isExpired()) {
                    log.info("[SkillMatcher] Cache HIT, returning cached result");
                    SkillMatchResult result = cached.result;
                    return SkillMatchResult.builder()
                            .selectedSkill(result.getSelectedSkill())
                            .confidence(result.getConfidence())
                            .modelTier(result.getModelTier())
                            .reason(result.getReason())
                            .candidates(result.getCandidates())
                            .cached(true)
                            .latencyMs(System.currentTimeMillis() - startTime)
                            .llmClassifierUsed(result.isLlmClassifierUsed())
                            .build();
                }

                BotProperties.SkillMatcherProperties config = properties.getRouter().getSkillMatcher();

                // Check if top candidate has very high score - skip LLM
                SkillCandidate topCandidate = candidates.get(0);
                log.debug("[SkillMatcher] Top candidate: {} (score: {}), threshold: {}",
                        topCandidate.getName(), String.format("%.3f", topCandidate.getSemanticScore()),
                        config.getSkipClassifierThreshold());

                if (topCandidate.getSemanticScore() >= config.getSkipClassifierThreshold()) {
                    log.debug("[SkillMatcher] High semantic score, SKIPPING LLM classifier");

                    SkillMatchResult result = SkillMatchResult.fromSemantic(topCandidate, candidates);
                    result.setLatencyMs(System.currentTimeMillis() - startTime);

                    // Cache the result
                    cacheResult(cacheKey, result);

                    return result;
                }

                // Stage 2: LLM classifier (if enabled)
                if (config.getClassifier().isEnabled()) {
                    log.debug("[SkillMatcher] Stage 2: Running LLM classifier...");
                    return runLlmClassifier(userMessage, conversationHistory, candidates, cacheKey, startTime);
                }

                log.debug("[SkillMatcher] LLM classifier disabled, using semantic result");
                // LLM classifier disabled - use semantic result
                SkillMatchResult result = SkillMatchResult.fromSemantic(topCandidate, candidates);
                result.setLatencyMs(System.currentTimeMillis() - startTime);
                cacheResult(cacheKey, result);
                return result;

            } catch (Exception e) {
                log.error("[SkillMatcher] FAILED: {}", e.getMessage(), e);
                return SkillMatchResult.builder()
                        .selectedSkill(null)
                        .confidence(0)
                        .modelTier("fast")
                        .reason("Error: " + e.getMessage())
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }
        });
    }

    private SkillMatchResult runLlmClassifier(
            String userMessage,
            List<Message> conversationHistory,
            List<SkillCandidate> candidates,
            String cacheKey,
            long startTime) {

        try {
            // Get fast model for classification
            LlmPort classifierLlm = getClassifierLlm();
            log.debug("[SkillMatcher] Using LLM classifier with {} candidates", candidates.size());

            LlmSkillClassifier.ClassificationResult classification = llmClassifier
                    .classify(userMessage, conversationHistory, candidates, classifierLlm)
                    .get(properties.getRouter().getSkillMatcher().getClassifier().getTimeoutMs() + 500,
                            TimeUnit.MILLISECONDS);

            log.info("[SkillMatcher] LLM classifier result: skill={}, confidence={}, tier={}, reason={}",
                    classification.skill(), String.format("%.2f", classification.confidence()),
                    classification.modelTier(), classification.reason());

            SkillMatchResult result = SkillMatchResult.builder()
                    .selectedSkill(classification.skill())
                    .confidence(classification.confidence())
                    .modelTier(classification.modelTier())
                    .reason(classification.reason())
                    .candidates(candidates)
                    .llmClassifierUsed(true)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();

            // Cache the result
            cacheResult(cacheKey, result);

            return result;

        } catch (Exception e) {
            log.warn("[SkillMatcher] LLM classifier FAILED, using semantic fallback: {}", e.getMessage());

            // Fallback to semantic result
            SkillCandidate top = candidates.get(0);
            SkillMatchResult result = SkillMatchResult.builder()
                    .selectedSkill(top.getName())
                    .confidence(top.getSemanticScore())
                    .modelTier("balanced")
                    .reason("LLM classifier unavailable, semantic fallback")
                    .candidates(candidates)
                    .llmClassifierUsed(false)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();

            cacheResult(cacheKey, result);
            return result;
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    private LlmPort getClassifierLlm() {
        // For now, use the active adapter
        // In future, could route to specific fast model based on
        // properties.getRouter().getSkillMatcher().getClassifier().getModel()
        return llmAdapterFactory.getActiveAdapter();
    }

    private List<SkillCandidate> semanticSearch(String query) {
        try {
            // Generate query embedding
            float[] queryEmbedding = embeddingPort.embed(query).get(5, TimeUnit.SECONDS);

            BotProperties.SemanticSearchProperties config = properties.getRouter()
                    .getSkillMatcher()
                    .getSemanticSearch();

            // Find similar skills
            return embeddingStore.findSimilar(
                    queryEmbedding,
                    config.getTopK(),
                    config.getMinScore());

        } catch (Exception e) {
            log.error("Semantic search failed", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void indexSkills(List<Skill> skills) {
        if (!isEnabled()) {
            return;
        }

        if (!embeddingPort.isAvailable()) {
            log.warn("Embedding service unavailable, skills not indexed");
            return;
        }

        List<Skill> available = skills.stream()
                .filter(Skill::isAvailable)
                .toList();

        if (available.isEmpty()) {
            log.info("No available skills to index");
            return;
        }

        embeddingStore.indexSkills(available);
        ready = true;
    }

    @Override
    public void clearIndex() {
        embeddingStore.clear();
        resultCache.clear();
        ready = false;
    }

    @Override
    public boolean isReady() {
        return ready && !embeddingStore.isEmpty();
    }

    @Override
    public boolean isEnabled() {
        return properties.getRouter().getSkillMatcher().isEnabled();
    }

    private String computeCacheKey(String query, List<SkillCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append(query); // use actual query, not hashCode, to avoid collisions
        for (SkillCandidate c : candidates) {
            sb.append(":").append(c.getName());
        }
        return sb.toString();
    }

    private void cacheResult(String key, SkillMatchResult result) {
        if (key == null)
            return;

        BotProperties.CacheProperties cacheConfig = properties.getRouter()
                .getSkillMatcher()
                .getCache();

        if (!cacheConfig.isEnabled()) {
            return;
        }

        // Evict if cache is full
        if (resultCache.size() >= cacheConfig.getMaxSize()) {
            evictOldest();
        }

        resultCache.put(key, new CachedResult(
                result,
                Instant.now(),
                Duration.ofMinutes(cacheConfig.getTtlMinutes())));
    }

    private void evictOldest() {
        // Simple eviction: remove ~10% of oldest entries
        int toRemove = Math.max(1, resultCache.size() / 10);

        List<Map.Entry<String, CachedResult>> entries = new ArrayList<>(resultCache.entrySet());
        entries.sort(Comparator.comparing(e -> e.getValue().createdAt));

        for (int i = 0; i < toRemove && i < entries.size(); i++) {
            resultCache.remove(entries.get(i).getKey());
        }
    }

    private void startCacheCleanup() {
        cacheCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skill-matcher-cache-cleanup");
            t.setDaemon(true);
            return t;
        });
        cacheCleanupExecutor.scheduleAtFixedRate(
                () -> resultCache.entrySet().removeIf(e -> e.getValue().isExpired()),
                5, 5, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void destroy() {
        if (cacheCleanupExecutor != null) {
            cacheCleanupExecutor.shutdownNow();
        }
    }

    /**
     * Cached match result with TTL.
     */
    private record CachedResult(
            SkillMatchResult result,
            Instant createdAt,
            Duration ttl
    ) {
        boolean isExpired() {
            return Instant.now().isAfter(createdAt.plus(ttl));
        }
    }
}

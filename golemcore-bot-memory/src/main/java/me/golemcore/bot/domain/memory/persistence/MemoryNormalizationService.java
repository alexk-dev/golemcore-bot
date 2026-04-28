package me.golemcore.bot.domain.memory.persistence;

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

import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.memory.MemoryScopeSupport;
import me.golemcore.bot.domain.runtimeconfig.MemoryRuntimeConfigView;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Applies canonical normalization, identity matching, merge, and decay rules for structured memory records.
 */
@Service
public class MemoryNormalizationService {

    private final MemoryRuntimeConfigView runtimeConfigService;

    public MemoryNormalizationService(MemoryRuntimeConfigView runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    /**
     * Normalize extracted items before episodic persistence and promotion.
     *
     * @param items
     *            extracted items, possibly incomplete
     * @param scope
     *            target scope for persistence
     *
     * @return normalized copies ready for persistence
     */
    public List<MemoryItem> normalizeExtractedItems(List<MemoryItem> items, String scope) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<MemoryItem> normalizedItems = new ArrayList<>();
        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            MemoryItem.Layer layer = item.getLayer() != null ? item.getLayer() : MemoryItem.Layer.EPISODIC;
            normalizedItems.add(normalizeForLayer(item, layer, scope));
        }
        return normalizedItems;
    }

    /**
     * Normalize a single item for a specific target layer and scope.
     *
     * @param source
     *            item to normalize
     * @param layer
     *            target layer
     * @param scope
     *            target scope
     *
     * @return normalized copy
     */
    public MemoryItem normalizeForLayer(MemoryItem source, MemoryItem.Layer layer, String scope) {
        Instant now = Instant.now();
        MemoryItem normalized = new MemoryItem();
        if (source != null) {
            normalized.setId(source.getId());
            normalized.setType(source.getType());
            normalized.setTitle(source.getTitle());
            normalized.setContent(source.getContent());
            normalized.setTags(copy(source.getTags()));
            normalized.setSource(source.getSource());
            normalized.setConfidence(source.getConfidence());
            normalized.setSalience(source.getSalience());
            normalized.setTtlDays(source.getTtlDays());
            normalized.setCreatedAt(source.getCreatedAt());
            normalized.setUpdatedAt(source.getUpdatedAt());
            normalized.setLastAccessedAt(source.getLastAccessedAt());
            normalized.setReferences(copy(source.getReferences()));
            normalized.setFingerprint(source.getFingerprint());
        } else {
            normalized.setTags(new ArrayList<>());
            normalized.setReferences(new ArrayList<>());
        }

        if (normalized.getId() == null || normalized.getId().isBlank()) {
            normalized.setId(UUID.randomUUID().toString());
        }
        normalized.setLayer(layer);
        normalized.setScope(MemoryScopeSupport
                .normalizeScopeOrGlobal(scope != null ? scope : source != null ? source.getScope() : null));
        if (normalized.getCreatedAt() == null) {
            normalized.setCreatedAt(now);
        }
        normalized.setUpdatedAt(now);
        if (normalized.getConfidence() == null) {
            normalized.setConfidence(0.75);
        }
        if (normalized.getSalience() == null) {
            normalized.setSalience(0.70);
        }
        if (normalized.getFingerprint() == null || normalized.getFingerprint().isBlank()) {
            normalized.setFingerprint(
                    computeFingerprint(normalized.getType() + "|" + normalizeForFingerprint(normalized.getContent())));
        }
        if (normalized.getTags() == null) {
            normalized.setTags(new ArrayList<>());
        }
        if (normalized.getReferences() == null) {
            normalized.setReferences(new ArrayList<>());
        }
        return normalized;
    }

    /**
     * Check whether two items represent the same logical memory record.
     *
     * @param first
     *            first item
     * @param second
     *            second item
     *
     * @return {@code true} when the items share identity
     */
    public boolean sameIdentity(MemoryItem first, MemoryItem second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getFingerprint() != null && second.getFingerprint() != null
                && first.getFingerprint().equals(second.getFingerprint())) {
            return true;
        }
        return first.getId() != null && second.getId() != null && first.getId().equals(second.getId());
    }

    /**
     * Merge a candidate update into an existing stored item.
     *
     * @param existing
     *            currently stored item
     * @param candidate
     *            incoming replacement candidate
     */
    public void merge(MemoryItem existing, MemoryItem candidate) {
        if (existing == null || candidate == null) {
            return;
        }

        existing.setUpdatedAt(Instant.now());
        if (candidate.getContent() != null && !candidate.getContent().isBlank() && (existing.getContent() == null
                || candidate.getContent().length() > existing.getContent().length())) {
            existing.setContent(candidate.getContent());
        }
        if (candidate.getTitle() != null && !candidate.getTitle().isBlank()) {
            existing.setTitle(candidate.getTitle());
        }
        existing.setConfidence(
                Math.max(defaultDouble(existing.getConfidence(), 0.0), defaultDouble(candidate.getConfidence(), 0.0)));
        existing.setSalience(
                Math.max(defaultDouble(existing.getSalience(), 0.0), defaultDouble(candidate.getSalience(), 0.0)));
        existing.setType(candidate.getType() != null ? candidate.getType() : existing.getType());
        existing.setSource(candidate.getSource() != null ? candidate.getSource() : existing.getSource());
        existing.setScope(MemoryScopeSupport.normalizeScopeOrGlobal(candidate.getScope()));
        if (candidate.getTtlDays() != null) {
            existing.setTtlDays(candidate.getTtlDays());
        }
        if (candidate.getLastAccessedAt() != null) {
            existing.setLastAccessedAt(candidate.getLastAccessedAt());
        }

        Set<String> tags = new LinkedHashSet<>(copy(existing.getTags()));
        tags.addAll(copy(candidate.getTags()));
        existing.setTags(new ArrayList<>(tags));

        Set<String> references = new LinkedHashSet<>(copy(existing.getReferences()));
        references.addAll(copy(candidate.getReferences()));
        existing.setReferences(new ArrayList<>(references));
    }

    /**
     * Apply decay and TTL expiry rules to a mutable item list.
     *
     * @param items
     *            items to prune in place
     */
    public void applyDecay(List<MemoryItem> items) {
        if (items == null || !runtimeConfigService.isMemoryDecayEnabled()) {
            return;
        }

        Instant now = Instant.now();
        Instant threshold = now.minus(runtimeConfigService.getMemoryDecayDays(), ChronoUnit.DAYS);
        items.removeIf(item -> {
            if (item == null) {
                return true;
            }

            Integer ttlDays = item.getTtlDays();
            if (ttlDays != null && item.getCreatedAt() != null) {
                Instant ttlThreshold = item.getCreatedAt().plus(ttlDays, ChronoUnit.DAYS);
                if (now.isAfter(ttlThreshold)) {
                    return true;
                }
            }

            Instant updated = item.getUpdatedAt() != null ? item.getUpdatedAt() : item.getCreatedAt();
            return updated != null && updated.isBefore(threshold);
        });
    }

    /**
     * Compute a stable short fingerprint for a memory payload.
     *
     * @param content
     *            normalized source content
     *
     * @return short stable fingerprint
     */
    public String computeFingerprint(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12 && i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }
    }

    private List<String> copy(List<String> values) {
        return values != null ? new ArrayList<>(values) : new ArrayList<>();
    }

    private String normalizeForFingerprint(String text) {
        return normalizeText(text).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private double defaultDouble(Double value, double fallback) {
        return value != null ? value : fallback;
    }
}

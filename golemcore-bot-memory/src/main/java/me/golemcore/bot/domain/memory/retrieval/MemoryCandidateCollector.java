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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.memory.model.MemoryRetrievalPlan;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.memory.MemoryScopeSupport;
import me.golemcore.bot.domain.runtimeconfig.MemoryRuntimeConfigView;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.MemorySettingsPort;
import org.springframework.stereotype.Service;

/**
 * Loads raw memory candidates from the configured stores and applies storage-level filtering.
 */
@Service
@Slf4j
public class MemoryCandidateCollector {

    private static final String EPISODIC_PREFIX = "items/episodic/";
    private static final String SEMANTIC_FILE = "items/semantic.jsonl";
    private static final String PROCEDURAL_FILE = "items/procedural.jsonl";

    private final StoragePort storagePort;
    private final MemorySettingsPort settingsPort;
    private final MemoryRuntimeConfigView runtimeConfigService;
    private final ObjectMapper objectMapper;

    public MemoryCandidateCollector(StoragePort storagePort, MemorySettingsPort settingsPort,
            MemoryRuntimeConfigView runtimeConfigService, ObjectMapper objectMapper) {
        this.storagePort = storagePort;
        this.settingsPort = settingsPort;
        this.runtimeConfigService = runtimeConfigService;
        this.objectMapper = objectMapper;
    }

    /**
     * Collect raw candidates for the supplied retrieval plan.
     *
     * @param plan
     *            normalized retrieval plan
     *
     * @return filtered candidates from episodic, semantic, and procedural stores
     */
    public List<MemoryItem> collect(MemoryRetrievalPlan plan) {
        List<MemoryItem> candidates = new ArrayList<>();
        for (String scope : plan.getRequestedScopes()) {
            candidates.addAll(loadRecentEpisodic(scope, plan.getEpisodicLookbackDays()));
            candidates.addAll(loadJsonl(buildScopedPath(scope, SEMANTIC_FILE), scope));
            candidates.addAll(loadJsonl(buildScopedPath(scope, PROCEDURAL_FILE), scope));
        }
        return filterCandidates(candidates, plan.getRequestedScopes());
    }

    private List<MemoryItem> loadRecentEpisodic(String scope, int days) {
        List<MemoryItem> items = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            String date = LocalDate.now(ZoneId.systemDefault()).minusDays(i).toString();
            String path = buildScopedPath(scope, EPISODIC_PREFIX + date + ".jsonl");
            items.addAll(loadJsonl(path, scope));
        }
        return items;
    }

    private List<MemoryItem> loadJsonl(String path, String defaultScope) {
        List<MemoryItem> items = new ArrayList<>();
        try {
            String content = storagePort.getText(getMemoryDirectory(), path).join();
            if (content == null || content.isBlank()) {
                return items;
            }
            String[] lines = content.split("\\R");
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    MemoryItem item = objectMapper.readValue(line, MemoryItem.class);
                    String itemScope = MemoryScopeSupport.normalizeScopeOrGlobal(item.getScope());
                    if (MemoryScopeSupport.GLOBAL_SCOPE.equals(itemScope)
                            && MemoryScopeSupport.isSessionScope(defaultScope) && !path.startsWith("items/")) {
                        itemScope = MemoryScopeSupport.normalizeScopeOrGlobal(defaultScope);
                    }
                    item.setScope(itemScope);
                    items.add(item);
                } catch (IOException | RuntimeException e) {
                    log.trace("[MemoryRetrieval] Skipping invalid line in {}: {}", path, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.trace("[MemoryRetrieval] Failed to load {}: {}", path, e.getMessage());
        }
        return items;
    }

    private List<MemoryItem> filterCandidates(List<MemoryItem> items, List<String> requestedScopes) {
        List<MemoryItem> result = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return result;
        }
        Set<String> allowedScopes = requestedScopes.stream().map(MemoryScopeSupport::normalizeScopeOrGlobal)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Instant decayThreshold = Instant.now().minus(runtimeConfigService.getMemoryDecayDays(), ChronoUnit.DAYS);
        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            String itemScope = MemoryScopeSupport.normalizeScopeOrGlobal(item.getScope());
            if (!isScopeAllowed(allowedScopes, itemScope)) {
                continue;
            }
            item.setScope(itemScope);
            if (item.getContent() == null || item.getContent().isBlank()) {
                continue;
            }

            if (item.getTtlDays() != null && item.getCreatedAt() != null) {
                Instant ttlExpiry = item.getCreatedAt().plus(item.getTtlDays(), ChronoUnit.DAYS);
                if (Instant.now().isAfter(ttlExpiry)) {
                    continue;
                }
            }

            if (runtimeConfigService.isMemoryDecayEnabled()) {
                Instant timestamp = resolveTimestamp(item);
                if (timestamp != null && timestamp.isBefore(decayThreshold)) {
                    continue;
                }
            }

            result.add(item);
        }

        return result;
    }

    private boolean isScopeAllowed(Set<String> requestedScopes, String itemScope) {
        String normalizedItem = MemoryScopeSupport.normalizeScopeOrGlobal(itemScope);
        return requestedScopes.contains(normalizedItem);
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

    private String buildScopedPath(String scope, String relativePath) {
        String normalizedScope = MemoryScopeSupport.normalizeScopeOrGlobal(scope);
        return MemoryScopeSupport.toStoragePrefix(normalizedScope) + relativePath;
    }

    private String getMemoryDirectory() {
        String configured = settingsPort.memory().directory();
        if (configured == null || configured.isBlank()) {
            return "memory";
        }
        return configured;
    }
}

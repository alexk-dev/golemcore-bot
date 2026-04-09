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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.service.MemoryScopeSupport;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.BotSettingsPort;
import org.springframework.stereotype.Service;

/**
 * Persists memory items into the JSONL-backed stores used by Memory V2.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryPersistenceOrchestrator {

    private static final String EPISODIC_PREFIX = "items/episodic/";
    private static final String SEMANTIC_FILE = "items/semantic.jsonl";
    private static final String PROCEDURAL_FILE = "items/procedural.jsonl";

    private final StoragePort storagePort;
    private final BotSettingsPort settingsPort;
    private final RuntimeConfigService runtimeConfigService;
    private final MemoryNormalizationService memoryNormalizationService;
    private final ObjectMapper objectMapper;

    /**
     * Append episodic items to the dated JSONL store for the target scope.
     *
     * @param items
     *            episodic items to append
     * @param scope
     *            target scope
     */
    public void appendEpisodic(List<MemoryItem> items, String scope) {
        if (items == null || items.isEmpty()) {
            return;
        }

        List<MemoryItem> normalizedItems = new ArrayList<>();
        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            normalizedItems.add(memoryNormalizationService.normalizeForLayer(item, MemoryItem.Layer.EPISODIC, scope));
        }
        if (normalizedItems.isEmpty()) {
            return;
        }

        Instant timestamp = normalizedItems.get(0).getCreatedAt();
        String episodicPath = buildScopedPath(scope, EPISODIC_PREFIX + resolveDate(timestamp) + ".jsonl");
        StringBuilder payload = new StringBuilder();
        for (MemoryItem item : normalizedItems) {
            try {
                payload.append(objectMapper.writeValueAsString(item)).append("\n");
            } catch (JsonProcessingException e) {
                log.debug("[MemoryPersistence] Failed to serialize episodic item '{}': {}",
                        item.getTitle(), e.getMessage());
            }
        }
        if (payload.isEmpty()) {
            return;
        }

        try {
            storagePort.appendText(getMemoryDirectory(), episodicPath, payload.toString()).join();
            log.debug("[MemoryPersistence] Appended {} episodic item(s) to {}", normalizedItems.size(), episodicPath);
        } catch (RuntimeException e) {
            log.warn("[MemoryPersistence] Failed to append episodic items to {}: {}", episodicPath, e.getMessage());
        }
    }

    /**
     * Upsert a semantic memory item in its scoped store.
     *
     * @param item
     *            semantic item to persist
     */
    public void upsertSemantic(MemoryItem item) {
        if (item == null) {
            return;
        }
        String scope = MemoryScopeSupport.normalizeScopeOrGlobal(item.getScope());
        upsertItem(buildScopedPath(scope, SEMANTIC_FILE), item, MemoryItem.Layer.SEMANTIC, scope);
    }

    /**
     * Upsert a procedural memory item in its scoped store.
     *
     * @param item
     *            procedural item to persist
     */
    public void upsertProcedural(MemoryItem item) {
        if (item == null) {
            return;
        }
        String scope = MemoryScopeSupport.normalizeScopeOrGlobal(item.getScope());
        upsertItem(buildScopedPath(scope, PROCEDURAL_FILE), item, MemoryItem.Layer.PROCEDURAL, scope);
    }

    private void upsertItem(String filePath, MemoryItem sourceItem, MemoryItem.Layer targetLayer, String scope) {
        try {
            List<MemoryItem> items = readJsonl(filePath, scope);
            MemoryItem normalized = memoryNormalizationService.normalizeForLayer(sourceItem, targetLayer, scope);

            boolean updated = false;
            for (MemoryItem existing : items) {
                if (memoryNormalizationService.sameIdentity(existing, normalized)) {
                    memoryNormalizationService.merge(existing, normalized);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                items.add(normalized);
            }

            memoryNormalizationService.applyDecay(items);
            storagePort.putTextAtomic(getMemoryDirectory(), filePath, toJsonl(items), true).join();
            log.debug("[MemoryPersistence] Upserted {} item in {}", targetLayer, filePath);
        } catch (RuntimeException e) {
            log.warn("[MemoryPersistence] Failed upsert to {}: {}", filePath, e.getMessage());
        }
    }

    private List<MemoryItem> readJsonl(String filePath, String defaultScope) {
        List<MemoryItem> items = new ArrayList<>();
        try {
            String content = storagePort.getText(getMemoryDirectory(), filePath).join();
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
                    item.setScope(MemoryScopeSupport.normalizeScopeOrGlobal(
                            item.getScope() != null ? item.getScope() : defaultScope));
                    items.add(item);
                } catch (IOException | RuntimeException e) {
                    log.trace("[MemoryPersistence] Skipping invalid memory jsonl line: {}", e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.trace("[MemoryPersistence] Failed reading {}: {}", filePath, e.getMessage());
        }
        return items;
    }

    private String toJsonl(List<MemoryItem> items) {
        StringBuilder sb = new StringBuilder();
        for (MemoryItem item : items) {
            try {
                sb.append(objectMapper.writeValueAsString(item)).append("\n");
            } catch (JsonProcessingException e) {
                log.trace("[MemoryPersistence] Skipping non-serializable item '{}': {}",
                        item.getId(), e.getMessage());
            }
        }
        return sb.toString();
    }

    private String resolveDate(Instant timestamp) {
        Instant resolved = timestamp != null ? timestamp : Instant.now();
        return LocalDate.ofInstant(resolved, ZoneId.systemDefault()).toString();
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

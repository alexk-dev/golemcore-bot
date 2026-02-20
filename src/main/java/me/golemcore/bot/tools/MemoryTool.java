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

package me.golemcore.bot.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Tool for explicit Memory V2 operations from autonomous workflows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryTool implements ToolComponent {

    public static final String TOOL_NAME = "memory";

    private static final String OP_ADD = "memory_add";
    private static final String OP_SEARCH = "memory_search";
    private static final String OP_UPDATE = "memory_update";
    private static final String OP_PROMOTE = "memory_promote";
    private static final String OP_FORGET = "memory_forget";

    private static final String PARAM_OPERATION = "operation";
    private static final String PARAM_QUERY = "query";
    private static final String PARAM_ID = "id";
    private static final String PARAM_FINGERPRINT = "fingerprint";
    private static final String PARAM_LAYER = "layer";
    private static final String PARAM_TYPE = "type";
    private static final String PARAM_TITLE = "title";
    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_TAGS = "tags";
    private static final String PARAM_REFERENCES = "references";
    private static final String PARAM_CONFIDENCE = "confidence";
    private static final String PARAM_SALIENCE = "salience";
    private static final String PARAM_TTL_DAYS = "ttl_days";
    private static final String PARAM_LIMIT = "limit";

    private final MemoryComponent memoryComponent;
    private final RuntimeConfigService runtimeConfigService;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PARAM_OPERATION, Map.of(
                "type", "string",
                "enum", List.of(OP_ADD, OP_SEARCH, OP_UPDATE, OP_PROMOTE, OP_FORGET),
                "description", "Memory operation to perform"));
        properties.put(PARAM_QUERY, Map.of("type", "string", "description", "Search text for memory lookup"));
        properties.put(PARAM_ID, Map.of("type", "string", "description", "Memory item id"));
        properties.put(PARAM_FINGERPRINT, Map.of("type", "string", "description", "Memory item fingerprint"));
        properties.put(PARAM_LAYER, Map.of(
                "type", "string",
                "enum", List.of("semantic", "procedural"),
                "description", "Target memory layer for write operations"));
        properties.put(PARAM_TYPE, Map.of(
                "type", "string",
                "enum", List.of("decision", "constraint", "failure", "fix", "preference", "project_fact",
                        "task_state", "command_result"),
                "description", "Memory type for add/update/promote"));
        properties.put(PARAM_TITLE, Map.of("type", "string", "description", "Short memory title"));
        properties.put(PARAM_CONTENT, Map.of("type", "string", "description", "Memory content"));
        properties.put(PARAM_TAGS, Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional memory tags"));
        properties.put(PARAM_REFERENCES, Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional references (files/tests/urls)"));
        properties.put(PARAM_CONFIDENCE, Map.of(
                "type", "number",
                "minimum", 0,
                "maximum", 1,
                "description", "Confidence score [0..1]"));
        properties.put(PARAM_SALIENCE, Map.of(
                "type", "number",
                "minimum", 0,
                "maximum", 1,
                "description", "Salience score [0..1]"));
        properties.put(PARAM_TTL_DAYS, Map.of(
                "type", "integer",
                "minimum", 0,
                "description", "Retention ttl in days (0 = forget immediately)"));
        properties.put(PARAM_LIMIT, Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 50,
                "description", "Result limit for search/promote/forget"));

        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Structured memory operations: add, search, update, promote, forget.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of(PARAM_OPERATION)))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(ToolResult.failure("Memory is disabled"));
        }
        if (parameters == null) {
            return CompletableFuture.completedFuture(ToolResult.failure("Parameters are required"));
        }

        String operation = toStringValue(parameters.get(PARAM_OPERATION));
        if (operation == null || operation.isBlank()) {
            return CompletableFuture.completedFuture(ToolResult.failure("Missing required parameter: operation"));
        }

        try {
            ToolResult result = switch (operation) {
            case OP_ADD -> addMemory(parameters);
            case OP_SEARCH -> searchMemory(parameters);
            case OP_UPDATE -> updateMemory(parameters);
            case OP_PROMOTE -> promoteMemory(parameters);
            case OP_FORGET -> forgetMemory(parameters);
            default -> ToolResult.failure("Unknown operation: " + operation);
            };
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException e) {
            log.warn("[MemoryTool] operation={} failed: {}", operation, e.getMessage());
            return CompletableFuture.completedFuture(ToolResult.failure("Memory operation failed: " + e.getMessage()));
        }
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isMemoryEnabled();
    }

    private ToolResult addMemory(Map<String, Object> params) {
        String content = normalizeNonBlank(toStringValue(params.get(PARAM_CONTENT)));
        if (content == null) {
            return ToolResult.failure("Missing required parameter: content");
        }

        MemoryItem.Layer layer = resolveWritableLayer(params.get(PARAM_LAYER), MemoryItem.Layer.SEMANTIC);
        if (layer == null) {
            return ToolResult.failure("layer must be 'semantic' or 'procedural'");
        }

        String typeText = normalizeNonBlank(toStringValue(params.get(PARAM_TYPE)));
        MemoryItem.Type type = parseType(typeText);
        if (typeText != null && type == null) {
            return ToolResult.failure("Invalid memory type: " + typeText);
        }
        if (type == null) {
            type = MemoryItem.Type.PROJECT_FACT;
        }

        MemoryItem item = buildItem(params, layer, type, content);
        if (item.getTitle() == null || item.getTitle().isBlank()) {
            item.setTitle(truncate(content, 96));
        }
        if (item.getConfidence() == null) {
            item.setConfidence(0.85);
        }
        if (item.getSalience() == null) {
            item.setSalience(0.72);
        }

        upsertByLayer(layer, item);
        return ToolResult.success(
                "Memory added to " + layer + ": " + item.getTitle(),
                Map.of(
                        PARAM_ID, item.getId() != null ? item.getId() : "",
                        PARAM_FINGERPRINT, item.getFingerprint() != null ? item.getFingerprint() : "",
                        PARAM_LAYER, layer.name().toLowerCase(Locale.ROOT)));
    }

    private ToolResult updateMemory(Map<String, Object> params) {
        String id = normalizeNonBlank(toStringValue(params.get(PARAM_ID)));
        String fingerprint = normalizeNonBlank(toStringValue(params.get(PARAM_FINGERPRINT)));
        if (id == null && fingerprint == null) {
            return ToolResult.failure("memory_update requires id or fingerprint");
        }

        MemoryItem.Layer layer = resolveWritableLayer(params.get(PARAM_LAYER), MemoryItem.Layer.SEMANTIC);
        if (layer == null) {
            return ToolResult.failure("layer must be 'semantic' or 'procedural'");
        }

        String content = normalizeNonBlank(toStringValue(params.get(PARAM_CONTENT)));
        String typeText = normalizeNonBlank(toStringValue(params.get(PARAM_TYPE)));
        MemoryItem.Type type = parseType(typeText);
        if (typeText != null && type == null) {
            return ToolResult.failure("Invalid memory type: " + typeText);
        }
        MemoryItem item = buildItem(params, layer, type, content);
        item.setId(id);
        item.setFingerprint(fingerprint);

        boolean hasPayload = content != null
                || normalizeNonBlank(toStringValue(params.get(PARAM_TITLE))) != null
                || !item.getTags().isEmpty()
                || !item.getReferences().isEmpty()
                || item.getConfidence() != null
                || item.getSalience() != null
                || item.getTtlDays() != null
                || type != null;
        if (!hasPayload) {
            return ToolResult.failure("memory_update requires at least one field to update");
        }

        upsertByLayer(layer, item);
        return ToolResult.success(
                "Memory updated in " + layer,
                Map.of(
                        PARAM_ID, id != null ? id : "",
                        PARAM_FINGERPRINT, fingerprint != null ? fingerprint : "",
                        PARAM_LAYER, layer.name().toLowerCase(Locale.ROOT)));
    }

    private ToolResult searchMemory(Map<String, Object> params) {
        String queryText = normalizeNonBlank(toStringValue(params.get(PARAM_QUERY)));
        int limit = clampLimit(toInteger(params.get(PARAM_LIMIT)), 8);

        List<MemoryItem> items = searchCandidates(queryText, limit);
        if (items.isEmpty()) {
            return ToolResult.success("No memory items found.");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(items.size()).append(" memory item(s):\n");
        for (int i = 0; i < items.size(); i++) {
            MemoryItem item = items.get(i);
            String layer = item.getLayer() != null ? item.getLayer().name() : "EPISODIC";
            String type = item.getType() != null ? item.getType().name() : "ITEM";
            String title = normalizeNonBlank(item.getTitle());
            String content = normalizeNonBlank(item.getContent());
            sb.append(i + 1)
                    .append(". [")
                    .append(layer)
                    .append('/')
                    .append(type)
                    .append("] ");
            if (title != null) {
                sb.append(title).append(" - ");
            }
            sb.append(truncate(content, 140)).append('\n');

            Map<String, Object> row = new LinkedHashMap<>();
            row.put(PARAM_ID, item.getId());
            row.put(PARAM_FINGERPRINT, item.getFingerprint());
            row.put(PARAM_LAYER, layer.toLowerCase(Locale.ROOT));
            row.put(PARAM_TYPE, type.toLowerCase(Locale.ROOT));
            row.put(PARAM_TITLE, item.getTitle());
            row.put(PARAM_CONTENT, item.getContent());
            row.put(PARAM_TAGS, item.getTags() != null ? item.getTags() : List.of());
            rows.add(row);
        }

        return ToolResult.success(sb.toString().trim(), Map.of("items", rows));
    }

    private ToolResult promoteMemory(Map<String, Object> params) {
        MemoryItem.Layer targetLayer = resolveWritableLayer(params.get(PARAM_LAYER), MemoryItem.Layer.SEMANTIC);
        if (targetLayer == null) {
            return ToolResult.failure("layer must be 'semantic' or 'procedural'");
        }

        String explicitContent = normalizeNonBlank(toStringValue(params.get(PARAM_CONTENT)));
        String query = normalizeNonBlank(toStringValue(params.get(PARAM_QUERY)));
        String id = normalizeNonBlank(toStringValue(params.get(PARAM_ID)));
        String fingerprint = normalizeNonBlank(toStringValue(params.get(PARAM_FINGERPRINT)));

        MemoryItem source;
        if (explicitContent != null) {
            String typeText = normalizeNonBlank(toStringValue(params.get(PARAM_TYPE)));
            MemoryItem.Type explicitType = parseType(typeText);
            if (typeText != null && explicitType == null) {
                return ToolResult.failure("Invalid memory type: " + typeText);
            }
            if (explicitType == null) {
                explicitType = MemoryItem.Type.DECISION;
            }
            source = buildItem(params, targetLayer, explicitType, explicitContent);
            if (source.getTitle() == null || source.getTitle().isBlank()) {
                source.setTitle(truncate(explicitContent, 96));
            }
        } else {
            source = findItem(id, fingerprint, query, clampLimit(toInteger(params.get(PARAM_LIMIT)), 8)).orElse(null);
            if (source == null) {
                return ToolResult.failure("No source memory item found for promotion");
            }
        }

        MemoryItem promoted = cloneForLayer(source, targetLayer);
        promoted.setConfidence(Math.max(
                runtimeConfigService.getMemoryPromotionMinConfidence(),
                promoted.getConfidence() != null ? promoted.getConfidence() : 0.0));
        promoted.setSalience(promoted.getSalience() != null ? promoted.getSalience() : 0.75);

        upsertByLayer(targetLayer, promoted);
        return ToolResult.success(
                "Memory promoted to " + targetLayer,
                Map.of(
                        PARAM_ID, promoted.getId() != null ? promoted.getId() : "",
                        PARAM_FINGERPRINT, promoted.getFingerprint() != null ? promoted.getFingerprint() : "",
                        PARAM_LAYER, targetLayer.name().toLowerCase(Locale.ROOT)));
    }

    private ToolResult forgetMemory(Map<String, Object> params) {
        MemoryItem.Layer layer = resolveWritableLayer(params.get(PARAM_LAYER), MemoryItem.Layer.SEMANTIC);
        if (layer == null) {
            return ToolResult.failure("layer must be 'semantic' or 'procedural'");
        }

        String query = normalizeNonBlank(toStringValue(params.get(PARAM_QUERY)));
        String id = normalizeNonBlank(toStringValue(params.get(PARAM_ID)));
        String fingerprint = normalizeNonBlank(toStringValue(params.get(PARAM_FINGERPRINT)));
        int limit = clampLimit(toInteger(params.get(PARAM_LIMIT)), 5);

        List<MemoryItem> targets = new ArrayList<>();
        if (id != null || fingerprint != null || query != null) {
            Optional<MemoryItem> matched = findItem(id, fingerprint, query, limit);
            matched.ifPresent(targets::add);
            if (matched.isEmpty() && query != null) {
                targets.addAll(searchCandidates(query, limit));
            }
        }

        if (targets.isEmpty()) {
            if (id == null && fingerprint == null) {
                return ToolResult.failure("No memory items matched for forget");
            }
            MemoryItem tombstone = MemoryItem.builder()
                    .id(id)
                    .fingerprint(fingerprint)
                    .layer(layer)
                    .type(MemoryItem.Type.PROJECT_FACT)
                    .title("Forget marker")
                    .content("")
                    .ttlDays(0)
                    .source("tool:memory")
                    .updatedAt(Instant.now())
                    .createdAt(Instant.now())
                    .build();
            upsertByLayer(layer, tombstone);
            return ToolResult.success("Forget marker applied");
        }

        int forgotten = 0;
        for (MemoryItem target : targets) {
            if (target == null) {
                continue;
            }
            MemoryItem.Layer targetLayer = toWritableLayer(target.getLayer()).orElse(layer);
            MemoryItem tombstone = cloneForLayer(target, targetLayer);
            tombstone.setTtlDays(0);
            tombstone.setUpdatedAt(Instant.now());
            upsertByLayer(targetLayer, tombstone);
            forgotten++;
        }

        return ToolResult.success("Forgot " + forgotten + " memory item(s)");
    }

    private Optional<MemoryItem> findItem(String id, String fingerprint, String query, int limit) {
        int searchLimit = (id != null || fingerprint != null) ? 50 : limit;
        List<MemoryItem> candidates = searchCandidates(
                query != null ? query : idOrFingerprintQuery(id, fingerprint),
                searchLimit);
        for (MemoryItem candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (id != null && id.equals(candidate.getId())) {
                return Optional.of(candidate);
            }
            if (fingerprint != null && fingerprint.equals(candidate.getFingerprint())) {
                return Optional.of(candidate);
            }
        }
        if (id != null || fingerprint != null) {
            return Optional.empty();
        }
        if (!candidates.isEmpty()) {
            return Optional.of(candidates.get(0));
        }
        return Optional.empty();
    }

    private String idOrFingerprintQuery(String id, String fingerprint) {
        if (id != null && fingerprint != null) {
            return id + " " + fingerprint;
        }
        return id != null ? id : fingerprint;
    }

    private List<MemoryItem> searchCandidates(String queryText, int limit) {
        int topK = clampLimit(limit, 8);
        MemoryQuery query = MemoryQuery.builder()
                .queryText(queryText)
                .softPromptBudgetTokens(runtimeConfigService.getMemorySoftPromptBudgetTokens())
                .maxPromptBudgetTokens(runtimeConfigService.getMemoryMaxPromptBudgetTokens())
                .workingTopK(0)
                .episodicTopK(topK)
                .semanticTopK(topK)
                .proceduralTopK(topK)
                .build();
        return memoryComponent.queryItems(query);
    }

    private MemoryItem buildItem(Map<String, Object> params, MemoryItem.Layer layer, MemoryItem.Type type, String content) {
        Instant now = Instant.now();
        String title = normalizeNonBlank(toStringValue(params.get(PARAM_TITLE)));
        Integer ttlDays = toInteger(params.get(PARAM_TTL_DAYS));
        if (ttlDays != null && ttlDays < 0) {
            ttlDays = 0;
        }
        Double confidence = clampUnit(toDouble(params.get(PARAM_CONFIDENCE)));
        Double salience = clampUnit(toDouble(params.get(PARAM_SALIENCE)));

        return MemoryItem.builder()
                .id(normalizeNonBlank(toStringValue(params.get(PARAM_ID))))
                .layer(layer)
                .type(type)
                .title(title)
                .content(content)
                .tags(toStringList(params.get(PARAM_TAGS)))
                .references(toStringList(params.get(PARAM_REFERENCES)))
                .source("tool:memory")
                .confidence(confidence)
                .salience(salience)
                .ttlDays(ttlDays)
                .createdAt(now)
                .updatedAt(now)
                .fingerprint(normalizeNonBlank(toStringValue(params.get(PARAM_FINGERPRINT))))
                .build();
    }

    private MemoryItem cloneForLayer(MemoryItem source, MemoryItem.Layer layer) {
        return MemoryItem.builder()
                .id(source.getId())
                .layer(layer)
                .type(source.getType())
                .title(source.getTitle())
                .content(source.getContent())
                .tags(source.getTags() != null ? new ArrayList<>(source.getTags()) : List.of())
                .references(source.getReferences() != null ? new ArrayList<>(source.getReferences()) : List.of())
                .source("tool:memory")
                .confidence(source.getConfidence())
                .salience(source.getSalience())
                .ttlDays(source.getTtlDays())
                .createdAt(source.getCreatedAt())
                .updatedAt(Instant.now())
                .lastAccessedAt(source.getLastAccessedAt())
                .fingerprint(source.getFingerprint())
                .build();
    }

    private void upsertByLayer(MemoryItem.Layer layer, MemoryItem item) {
        if (layer == MemoryItem.Layer.PROCEDURAL) {
            memoryComponent.upsertProceduralItem(item);
            return;
        }
        memoryComponent.upsertSemanticItem(item);
    }

    private Optional<MemoryItem.Layer> toWritableLayer(MemoryItem.Layer layer) {
        if (layer == MemoryItem.Layer.PROCEDURAL) {
            return Optional.of(MemoryItem.Layer.PROCEDURAL);
        }
        if (layer == MemoryItem.Layer.SEMANTIC) {
            return Optional.of(MemoryItem.Layer.SEMANTIC);
        }
        return Optional.empty();
    }

    private MemoryItem.Layer resolveWritableLayer(Object value, MemoryItem.Layer fallback) {
        String text = normalizeNonBlank(toStringValue(value));
        if (text == null) {
            return fallback;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if ("semantic".equals(normalized)) {
            return MemoryItem.Layer.SEMANTIC;
        }
        if ("procedural".equals(normalized)) {
            return MemoryItem.Layer.PROCEDURAL;
        }
        return null;
    }

    private MemoryItem.Type parseType(String text) {
        if (text == null) {
            return null;
        }
        try {
            return MemoryItem.Type.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String toStringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l) {
            if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) {
                return null;
            }
            return l.intValue();
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (Object entry : raw) {
            if (entry instanceof String text) {
                String normalized = normalizeNonBlank(text);
                if (normalized != null) {
                    items.add(normalized);
                }
            }
        }
        return items;
    }

    private int clampLimit(Integer value, int fallback) {
        int resolved = value != null ? value : fallback;
        if (resolved < 1) {
            return 1;
        }
        return Math.min(resolved, 50);
    }

    private Double clampUnit(Double value) {
        if (value == null) {
            return null;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private String normalizeNonBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
}

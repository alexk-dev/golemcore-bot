package me.golemcore.bot.domain.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes structured memory records (JSONL) and handles promotion/upsert logic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryWriteService {

    private static final String EPISODIC_PREFIX = "items/episodic/";
    private static final String SEMANTIC_FILE = "items/semantic.jsonl";
    private static final String PROCEDURAL_FILE = "items/procedural.jsonl";

    private static final Pattern TEST_REFERENCE_PATTERN = Pattern.compile(
            "\\b([A-Za-z0-9_$.]+Test(?:#[A-Za-z0-9_]+)?)\\b");

    private final StoragePort storagePort;
    private final BotProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final MemoryPromotionService memoryPromotionService;
    private final ObjectMapper objectMapper;

    /**
     * Persist turn memory in structured form (episodic + optional promotions).
     */
    public void persistTurnMemory(TurnMemoryEvent event) {
        if (event == null || !runtimeConfigService.isMemoryEnabled()) {
            return;
        }

        String eventScope = MemoryScopeSupport.normalizeScopeOrGlobal(event.getScope());
        List<MemoryItem> extracted = extractItems(event, eventScope);
        if (extracted.isEmpty()) {
            return;
        }

        String episodicPath = buildScopedPath(eventScope,
                EPISODIC_PREFIX + resolveDate(event.getTimestamp()) + ".jsonl");
        appendItems(episodicPath, extracted);

        if (!memoryPromotionService.isPromotionEnabled()) {
            return;
        }

        for (MemoryItem item : extracted) {
            if (memoryPromotionService.shouldPromoteToSemantic(item)) {
                upsertItem(
                        buildScopedPath(MemoryScopeSupport.GLOBAL_SCOPE, SEMANTIC_FILE),
                        item,
                        MemoryItem.Layer.SEMANTIC,
                        MemoryScopeSupport.GLOBAL_SCOPE);
            }
            if (memoryPromotionService.shouldPromoteToProcedural(item)) {
                upsertItem(
                        buildScopedPath(MemoryScopeSupport.GLOBAL_SCOPE, PROCEDURAL_FILE),
                        item,
                        MemoryItem.Layer.PROCEDURAL,
                        MemoryScopeSupport.GLOBAL_SCOPE);
            }
        }
    }

    public void upsertSemanticItem(MemoryItem item) {
        if (item == null || !runtimeConfigService.isMemoryEnabled()) {
            return;
        }
        String scope = MemoryScopeSupport.normalizeScopeOrGlobal(item.getScope());
        upsertItem(buildScopedPath(scope, SEMANTIC_FILE), item, MemoryItem.Layer.SEMANTIC, scope);
    }

    public void upsertProceduralItem(MemoryItem item) {
        if (item == null || !runtimeConfigService.isMemoryEnabled()) {
            return;
        }
        String scope = MemoryScopeSupport.normalizeScopeOrGlobal(item.getScope());
        upsertItem(buildScopedPath(scope, PROCEDURAL_FILE), item, MemoryItem.Layer.PROCEDURAL, scope);
    }

    private void appendItems(String path, List<MemoryItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        StringBuilder payload = new StringBuilder();
        for (MemoryItem item : items) {
            if (item.getId() == null || item.getId().isBlank()) {
                item.setId(UUID.randomUUID().toString());
            }
            if (item.getCreatedAt() == null) {
                item.setCreatedAt(Instant.now());
            }
            if (item.getUpdatedAt() == null) {
                item.setUpdatedAt(item.getCreatedAt());
            }
            if (item.getFingerprint() == null || item.getFingerprint().isBlank()) {
                item.setFingerprint(
                        computeFingerprint(item.getType() + "|" + normalizeForFingerprint(item.getContent())));
            }
            item.setScope(MemoryScopeSupport.normalizeScopeOrGlobal(item.getScope()));
            try {
                payload.append(objectMapper.writeValueAsString(item)).append("\n");
            } catch (JsonProcessingException e) {
                log.debug("[MemoryWrite] Failed to serialize memory item '{}': {}", item.getTitle(), e.getMessage());
            }
        }

        if (payload.isEmpty()) {
            return;
        }

        try {
            storagePort.appendText(getMemoryDirectory(), path, payload.toString()).join();
            log.debug("[MemoryWrite] Appended {} item(s) to {}", items.size(), path);
        } catch (RuntimeException e) {
            log.warn("[MemoryWrite] Failed to append items to {}: {}", path, e.getMessage());
        }
    }

    private void upsertItem(String filePath, MemoryItem sourceItem, MemoryItem.Layer targetLayer, String scope) {
        try {
            List<MemoryItem> items = readJsonl(filePath, scope);
            MemoryItem normalized = normalizeItem(sourceItem, targetLayer, scope);

            boolean updated = false;
            for (MemoryItem existing : items) {
                if (sameIdentity(existing, normalized)) {
                    merge(existing, normalized);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                items.add(normalized);
            }

            applyDecay(items);
            String payload = toJsonl(items);
            storagePort.putTextAtomic(getMemoryDirectory(), filePath, payload, true).join();
            log.debug("[MemoryWrite] Upserted {} item in {}", targetLayer, filePath);
        } catch (RuntimeException e) {
            log.warn("[MemoryWrite] Failed upsert to {}: {}", filePath, e.getMessage());
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
                    log.trace("[MemoryWrite] Skipping invalid memory jsonl line: {}", e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.trace("[MemoryWrite] Failed reading {}: {}", filePath, e.getMessage());
        }
        return items;
    }

    private String toJsonl(List<MemoryItem> items) {
        StringBuilder sb = new StringBuilder();
        for (MemoryItem item : items) {
            try {
                sb.append(objectMapper.writeValueAsString(item)).append("\n");
            } catch (JsonProcessingException e) {
                log.trace("[MemoryWrite] Skipping non-serializable item '{}': {}", item.getId(), e.getMessage());
            }
        }
        return sb.toString();
    }

    private MemoryItem normalizeItem(MemoryItem source, MemoryItem.Layer layer, String scope) {
        MemoryItem normalized = source != null ? source : new MemoryItem();
        if (normalized.getId() == null || normalized.getId().isBlank()) {
            normalized.setId(UUID.randomUUID().toString());
        }
        normalized.setLayer(layer);
        normalized.setScope(MemoryScopeSupport.normalizeScopeOrGlobal(scope));
        if (normalized.getCreatedAt() == null) {
            normalized.setCreatedAt(Instant.now());
        }
        normalized.setUpdatedAt(Instant.now());
        if (normalized.getConfidence() == null) {
            normalized.setConfidence(0.75);
        }
        if (normalized.getSalience() == null) {
            normalized.setSalience(0.70);
        }
        if (normalized.getFingerprint() == null || normalized.getFingerprint().isBlank()) {
            normalized.setFingerprint(computeFingerprint(
                    normalized.getType() + "|" + normalizeForFingerprint(normalized.getContent())));
        }
        if (normalized.getTags() == null) {
            normalized.setTags(new ArrayList<>());
        }
        if (normalized.getReferences() == null) {
            normalized.setReferences(new ArrayList<>());
        }
        return normalized;
    }

    private boolean sameIdentity(MemoryItem first, MemoryItem second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getFingerprint() != null && second.getFingerprint() != null
                && first.getFingerprint().equals(second.getFingerprint())) {
            return true;
        }
        return first.getId() != null && second.getId() != null
                && first.getId().equals(second.getId());
    }

    private void merge(MemoryItem existing, MemoryItem candidate) {
        existing.setUpdatedAt(Instant.now());
        if (candidate.getContent() != null && !candidate.getContent().isBlank()
                && (existing.getContent() == null
                        || candidate.getContent().length() > existing.getContent().length())) {
            existing.setContent(candidate.getContent());
        }
        if (candidate.getTitle() != null && !candidate.getTitle().isBlank()) {
            existing.setTitle(candidate.getTitle());
        }
        existing.setConfidence(Math.max(defaultDouble(existing.getConfidence(), 0.0),
                defaultDouble(candidate.getConfidence(), 0.0)));
        existing.setSalience(Math.max(defaultDouble(existing.getSalience(), 0.0),
                defaultDouble(candidate.getSalience(), 0.0)));
        existing.setType(candidate.getType() != null ? candidate.getType() : existing.getType());
        existing.setSource(candidate.getSource() != null ? candidate.getSource() : existing.getSource());
        existing.setScope(MemoryScopeSupport.normalizeScopeOrGlobal(candidate.getScope()));
        if (candidate.getTtlDays() != null) {
            existing.setTtlDays(candidate.getTtlDays());
        }
        if (candidate.getLastAccessedAt() != null) {
            existing.setLastAccessedAt(candidate.getLastAccessedAt());
        }

        Set<String> tags = new LinkedHashSet<>();
        if (existing.getTags() != null) {
            tags.addAll(existing.getTags());
        }
        if (candidate.getTags() != null) {
            tags.addAll(candidate.getTags());
        }
        existing.setTags(new ArrayList<>(tags));

        Set<String> refs = new LinkedHashSet<>();
        if (existing.getReferences() != null) {
            refs.addAll(existing.getReferences());
        }
        if (candidate.getReferences() != null) {
            refs.addAll(candidate.getReferences());
        }
        existing.setReferences(new ArrayList<>(refs));
    }

    private void applyDecay(List<MemoryItem> items) {
        if (!runtimeConfigService.isMemoryDecayEnabled()) {
            return;
        }
        Instant threshold = Instant.now().minus(runtimeConfigService.getMemoryDecayDays(), ChronoUnit.DAYS);
        items.removeIf(item -> {
            if (item == null) {
                return true;
            }

            Integer ttlDays = item.getTtlDays();
            if (ttlDays != null && item.getCreatedAt() != null) {
                Instant ttlThreshold = item.getCreatedAt().plus(ttlDays, ChronoUnit.DAYS);
                if (Instant.now().isAfter(ttlThreshold)) {
                    return true;
                }
            }

            Instant updated = item.getUpdatedAt() != null ? item.getUpdatedAt() : item.getCreatedAt();
            return updated != null && updated.isBefore(threshold);
        });
    }

    private List<MemoryItem> extractItems(TurnMemoryEvent event, String scope) {
        List<MemoryItem> items = new ArrayList<>();
        Instant timestamp = event.getTimestamp() != null ? event.getTimestamp() : Instant.now();

        String user = normalizeText(event.getUserText());
        String assistant = normalizeText(event.getAssistantText());
        String joined = (user + "\n" + assistant).trim();

        if (!joined.isBlank()) {
            List<String> references = extractReferences(joined);
            MemoryItem turnItem = MemoryItem.builder()
                    .id(UUID.randomUUID().toString())
                    .layer(MemoryItem.Layer.EPISODIC)
                    .type(MemoryItem.Type.TASK_STATE)
                    .title(buildTurnTitle(user))
                    .content("User: " + truncate(user, 1200) + "\nAssistant: " + truncate(assistant, 1800))
                    .scope(scope)
                    .tags(extractTags(joined, event.getActiveSkill(), references))
                    .source("turn")
                    .confidence(0.65)
                    .salience(calculateSalience(joined))
                    .createdAt(timestamp)
                    .updatedAt(timestamp)
                    .references(references)
                    .fingerprint(computeFingerprint("TASK|" + normalizeForFingerprint(joined)))
                    .build();
            items.add(turnItem);
        }

        if (runtimeConfigService.isMemoryCodeAwareExtractionEnabled()) {
            String combined = (joined + "\n" + String.join("\n", event.getToolOutputs())).trim();
            if (containsConstraintSignal(combined)) {
                items.add(createDerivedItem(event, MemoryItem.Type.CONSTRAINT, "Constraint",
                        extractSentenceSnippet(combined, 360), 0.82, 0.78, scope));
            }
            if (containsFailureSignal(combined)) {
                items.add(createDerivedItem(event, MemoryItem.Type.FAILURE, "Failure context",
                        extractSentenceSnippet(combined, 420), 0.86, 0.80, scope));
            }
            if (containsFixSignal(combined)) {
                items.add(createDerivedItem(event, MemoryItem.Type.FIX, "Fix context",
                        extractSentenceSnippet(combined, 420), 0.83, 0.78, scope));
            }
        }

        return deduplicateByFingerprint(items);
    }

    private MemoryItem createDerivedItem(TurnMemoryEvent event, MemoryItem.Type type, String title, String content,
            double salience, double confidence, String scope) {
        Instant timestamp = event.getTimestamp() != null ? event.getTimestamp() : Instant.now();
        String normalized = normalizeText(content);
        List<String> references = extractReferences(normalized);
        return MemoryItem.builder()
                .id(UUID.randomUUID().toString())
                .layer(MemoryItem.Layer.EPISODIC)
                .type(type)
                .title(title)
                .content(normalized)
                .scope(scope)
                .tags(extractTags(normalized, event.getActiveSkill(), references))
                .source("derived")
                .confidence(confidence)
                .salience(salience)
                .createdAt(timestamp)
                .updatedAt(timestamp)
                .references(references)
                .fingerprint(computeFingerprint(type + "|" + normalizeForFingerprint(normalized)))
                .build();
    }

    private List<MemoryItem> deduplicateByFingerprint(List<MemoryItem> items) {
        Map<String, MemoryItem> dedup = new HashMap<>();
        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            String key = item.getFingerprint();
            if (key == null || key.isBlank()) {
                key = item.getId();
            }
            if (key == null) {
                continue;
            }
            dedup.putIfAbsent(key, item);
        }
        return new ArrayList<>(dedup.values());
    }

    private double calculateSalience(String content) {
        String lower = content != null ? content.toLowerCase(Locale.ROOT) : "";
        double salience = 0.55;
        if (containsFailureSignal(lower) || containsFixSignal(lower)) {
            salience += 0.20;
        }
        if (containsConstraintSignal(lower)) {
            salience += 0.15;
        }
        if (lower.contains("todo") || lower.contains("next") || lower.contains("blocker")) {
            salience += 0.10;
        }
        return Math.min(1.0, salience);
    }

    private List<String> extractTags(String content, String activeSkill, List<String> references) {
        Set<String> tags = new LinkedHashSet<>();
        String lower = content != null ? content.toLowerCase(Locale.ROOT) : "";

        if (activeSkill != null && !activeSkill.isBlank()) {
            tags.add(activeSkill.toLowerCase(Locale.ROOT));
        }
        if (lower.contains("java")) {
            tags.add("java");
        }
        if (lower.contains("maven") || lower.contains("./mvnw")) {
            tags.add("maven");
        }
        if (lower.contains("spring")) {
            tags.add("spring");
        }
        if (lower.contains("test")) {
            tags.add("tests");
        }
        if (lower.contains("docker")) {
            tags.add("docker");
        }
        if (lower.contains("sql") || lower.contains("postgres")) {
            tags.add("database");
        }

        List<String> fileReferences = references != null ? references : List.of();
        for (String file : fileReferences) {
            int dotIdx = file.lastIndexOf('.');
            if (dotIdx >= 0 && dotIdx + 1 < file.length()) {
                tags.add(file.substring(dotIdx + 1).toLowerCase(Locale.ROOT));
            }
        }

        return new ArrayList<>(tags);
    }

    private List<String> extractReferences(String content) {
        Set<String> refs = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return new ArrayList<>(refs);
        }

        refs.addAll(extractFileReferences(content));

        Matcher testMatcher = TEST_REFERENCE_PATTERN.matcher(content);
        while (testMatcher.find()) {
            refs.add(testMatcher.group(1));
        }

        return new ArrayList<>(refs);
    }

    /**
     * Fast O(n) file reference extraction without regex backtracking. Token format
     * mirrors legacy pattern: [A-Za-z0-9_./-]+ + '.' + known extension.
     */
    private List<String> extractFileReferences(String content) {
        List<String> refs = new ArrayList<>();
        int tokenStart = -1;
        int length = content.length();
        for (int i = 0; i <= length; i++) {
            char ch = i < length ? content.charAt(i) : ' ';
            if (i < length && isFileTokenChar(ch)) {
                if (tokenStart < 0) {
                    tokenStart = i;
                }
                continue;
            }
            if (tokenStart >= 0) {
                appendIfFileReference(content, tokenStart, i, refs);
                tokenStart = -1;
            }
        }
        return refs;
    }

    private void appendIfFileReference(String content, int start, int end, List<String> refs) {
        int effectiveEnd = trimTrailingDot(content, start, end);
        if (effectiveEnd <= start) {
            return;
        }

        int dotIndex = -1;
        for (int i = effectiveEnd - 1; i > start; i--) {
            if (content.charAt(i) == '.') {
                dotIndex = i;
                break;
            }
        }
        if (dotIndex <= start || dotIndex >= effectiveEnd - 1) {
            return;
        }
        if (!isKnownFileExtension(content, dotIndex + 1, effectiveEnd)) {
            return;
        }
        refs.add(content.substring(start, effectiveEnd));
    }

    private int trimTrailingDot(String content, int start, int end) {
        int trimmedEnd = end;
        while (trimmedEnd > start && content.charAt(trimmedEnd - 1) == '.') {
            trimmedEnd--;
        }
        return trimmedEnd;
    }

    private boolean isFileTokenChar(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '_'
                || ch == '.'
                || ch == '/'
                || ch == '-';
    }

    private boolean isKnownFileExtension(String content, int start, int end) {
        int length = end - start;
        return switch (length) {
        case 2 -> regionEquals(content, start, "kt")
                || regionEquals(content, start, "md")
                || regionEquals(content, start, "ts")
                || regionEquals(content, start, "js")
                || regionEquals(content, start, "py")
                || regionEquals(content, start, "go")
                || regionEquals(content, start, "rs");
        case 3 -> regionEquals(content, start, "kts")
                || regionEquals(content, start, "xml")
                || regionEquals(content, start, "yml")
                || regionEquals(content, start, "txt")
                || regionEquals(content, start, "tsx")
                || regionEquals(content, start, "jsx")
                || regionEquals(content, start, "sql");
        case 4 -> regionEquals(content, start, "java")
                || regionEquals(content, start, "yaml")
                || regionEquals(content, start, "json");
        case 6 -> regionEquals(content, start, "groovy");
        default -> false;
        };
    }

    private boolean regionEquals(String content, int start, String expected) {
        if (start < 0 || start + expected.length() > content.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if (Character.toLowerCase(content.charAt(start + i)) != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private String buildTurnTitle(String userText) {
        if (userText == null || userText.isBlank()) {
            return "Conversation update";
        }
        return "User asked: " + truncate(userText, 96);
    }

    private String extractSentenceSnippet(String content, int maxLen) {
        String normalized = normalizeText(content);
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        int sentenceEnd = normalized.lastIndexOf('.', maxLen);
        if (sentenceEnd > maxLen / 2) {
            return normalized.substring(0, sentenceEnd + 1).trim();
        }
        return truncate(normalized, maxLen);
    }

    private boolean containsConstraintSignal(String text) {
        String lower = text != null ? text.toLowerCase(Locale.ROOT) : "";
        return lower.contains("must ")
                || lower.contains("should ")
                || lower.contains("always ")
                || lower.contains("never ")
                || lower.contains("prefer ");
    }

    private boolean containsFailureSignal(String text) {
        String lower = text != null ? text.toLowerCase(Locale.ROOT) : "";
        return lower.contains("failed")
                || lower.contains("failure")
                || lower.contains("exception")
                || lower.contains("error")
                || lower.contains("stacktrace");
    }

    private boolean containsFixSignal(String text) {
        String lower = text != null ? text.toLowerCase(Locale.ROOT) : "";
        return lower.contains("fixed")
                || lower.contains("resolved")
                || lower.contains("patched")
                || lower.contains("workaround")
                || lower.contains("solution");
    }

    private String resolveDate(Instant timestamp) {
        Instant ts = timestamp != null ? timestamp : Instant.now();
        return LocalDate.ofInstant(ts, ZoneId.systemDefault()).toString();
    }

    private String buildScopedPath(String scope, String relativePath) {
        String normalizedScope = MemoryScopeSupport.normalizeScopeOrGlobal(scope);
        return MemoryScopeSupport.toStoragePrefix(normalizedScope) + relativePath;
    }

    private String computeFingerprint(String content) {
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

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String normalizeForFingerprint(String text) {
        return normalizeText(text).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
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

    private double defaultDouble(Double value, double fallback) {
        return value != null ? value : fallback;
    }

    private String getMemoryDirectory() {
        String configured = properties.getMemory().getDirectory();
        if (configured == null || configured.isBlank()) {
            return "memory";
        }
        return configured;
    }
}

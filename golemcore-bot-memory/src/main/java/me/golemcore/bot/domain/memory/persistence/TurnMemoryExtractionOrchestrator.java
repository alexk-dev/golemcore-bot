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

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts episodic and derived memory candidates from finalized turn events.
 */
@Service
@RequiredArgsConstructor
public class TurnMemoryExtractionOrchestrator {

    private static final Pattern TEST_REFERENCE_PATTERN = Pattern
            .compile("\\b([A-Za-z0-9_$.]+Test(?:#[A-Za-z0-9_]+)?)\\b");

    private final RuntimeConfigService runtimeConfigService;
    private final MemoryNormalizationService memoryNormalizationService;

    /**
     * Extract candidate memory items from a turn event.
     *
     * @param event
     *            finalized turn event
     * @param scope
     *            normalized target scope
     *
     * @return extracted memory candidates
     */
    public List<MemoryItem> extract(TurnMemoryEvent event, String scope) {
        if (event == null) {
            return List.of();
        }

        List<MemoryItem> items = new ArrayList<>();
        Instant timestamp = event.getTimestamp() != null ? event.getTimestamp() : Instant.now();

        String user = normalizeText(event.getUserText());
        String assistant = normalizeText(event.getAssistantText());
        String joined = (user + "\n" + assistant).trim();

        if (!joined.isBlank()) {
            List<String> references = extractReferences(joined);
            MemoryItem turnItem = MemoryItem.builder().id(UUID.randomUUID().toString()).layer(MemoryItem.Layer.EPISODIC)
                    .type(MemoryItem.Type.TASK_STATE).title(buildTurnTitle(user))
                    .content("User: " + truncate(user, 1200) + "\nAssistant: " + truncate(assistant, 1800)).scope(scope)
                    .tags(extractTags(joined, event.getActiveSkill(), references)).source("turn").confidence(0.65)
                    .salience(calculateSalience(joined)).createdAt(timestamp).updatedAt(timestamp)
                    .references(references)
                    .fingerprint(
                            memoryNormalizationService.computeFingerprint("TASK|" + normalizeForFingerprint(joined)))
                    .build();
            items.add(turnItem);
        }

        if (runtimeConfigService.isMemoryCodeAwareExtractionEnabled()) {
            List<String> toolOutputs = event.getToolOutputs() != null ? event.getToolOutputs() : List.of();
            String combined = (joined + "\n" + String.join("\n", toolOutputs)).trim();
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
        return MemoryItem.builder().id(UUID.randomUUID().toString()).layer(MemoryItem.Layer.EPISODIC).type(type)
                .title(title).content(normalized).scope(scope)
                .tags(extractTags(normalized, event.getActiveSkill(), references)).source("derived")
                .confidence(confidence).salience(salience).createdAt(timestamp).updatedAt(timestamp)
                .references(references)
                .fingerprint(
                        memoryNormalizationService.computeFingerprint(type + "|" + normalizeForFingerprint(normalized)))
                .build();
    }

    private List<MemoryItem> deduplicateByFingerprint(List<MemoryItem> items) {
        Map<String, MemoryItem> deduplicated = new LinkedHashMap<>();
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
            deduplicated.putIfAbsent(key, item);
        }
        return new ArrayList<>(deduplicated.values());
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
        Set<String> references = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return new ArrayList<>(references);
        }

        references.addAll(extractFileReferences(content));
        Matcher testMatcher = TEST_REFERENCE_PATTERN.matcher(content);
        while (testMatcher.find()) {
            references.add(testMatcher.group(1));
        }

        return new ArrayList<>(references);
    }

    private List<String> extractFileReferences(String content) {
        List<String> references = new ArrayList<>();
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
                appendIfFileReference(content, tokenStart, i, references);
                tokenStart = -1;
            }
        }
        return references;
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
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_'
                || ch == '.' || ch == '/' || ch == '-';
    }

    private boolean isKnownFileExtension(String content, int start, int end) {
        int length = end - start;
        return switch (length) {
            case 2 -> regionEquals(content, start, "kt") || regionEquals(content, start, "md")
                    || regionEquals(content, start, "ts") || regionEquals(content, start, "js")
                    || regionEquals(content, start, "py") || regionEquals(content, start, "go")
                    || regionEquals(content, start, "rs");
            case 3 -> regionEquals(content, start, "kts") || regionEquals(content, start, "xml")
                    || regionEquals(content, start, "yml") || regionEquals(content, start, "txt")
                    || regionEquals(content, start, "tsx") || regionEquals(content, start, "jsx")
                    || regionEquals(content, start, "sql");
            case 4 -> regionEquals(content, start, "java") || regionEquals(content, start, "yaml")
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
        return lower.contains("must ") || lower.contains("should ") || lower.contains("always ")
                || lower.contains("never ") || lower.contains("prefer ");
    }

    private boolean containsFailureSignal(String text) {
        String lower = text != null ? text.toLowerCase(Locale.ROOT) : "";
        return lower.contains("failed") || lower.contains("failure") || lower.contains("exception")
                || lower.contains("error") || lower.contains("stacktrace");
    }

    private boolean containsFixSignal(String text) {
        String lower = text != null ? text.toLowerCase(Locale.ROOT) : "";
        return lower.contains("fixed") || lower.contains("resolved") || lower.contains("patched")
                || lower.contains("workaround") || lower.contains("solution");
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
}

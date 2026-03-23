package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.SkillDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses and renders SKILL.md documents with YAML frontmatter. Metadata is kept
 * separate from the markdown body so callers can normalize inputs from tools,
 * API clients, and the Web UI.
 */
@Service
@Slf4j
public class SkillDocumentService {

    private static final int MAX_NESTING_DEPTH = 5;

    private static final List<String> FRONTMATTER_KEY_ORDER = List.of(
            "name",
            "description",
            "model_tier",
            "reflection_tier",
            "requires",
            "vars",
            "mcp",
            "next_skill",
            "conditional_next_skills");

    private final ObjectMapper yamlMapper = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build());

    public SkillDocument parseDocument(String content) {
        if (content == null) {
            return new SkillDocument(Map.of(), "", false);
        }

        FrontmatterSections sections = splitFrontmatter(content);
        if (sections == null) {
            return new SkillDocument(Map.of(), normalizeBody(content), false);
        }

        String frontmatter = sections.frontmatter();
        if (frontmatter.isBlank()) {
            return new SkillDocument(Map.of(), normalizeBody(sections.body()), true);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> yaml = yamlMapper.readValue(frontmatter, LinkedHashMap.class);
            Map<String, Object> metadata = yaml != null ? new LinkedHashMap<>(yaml) : new LinkedHashMap<>();
            return new SkillDocument(metadata, normalizeBody(sections.body()), true);
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to parse skill frontmatter YAML, treating as body-only: {}", ex.getMessage());
            return new SkillDocument(Map.of(), normalizeBody(sections.body()), true);
        }
    }

    public SkillDocument parseNormalizedDocument(String content) {
        SkillDocument current = parseDocument(content);
        if (!current.hasFrontmatter()) {
            return current;
        }

        Map<String, Object> metadata = new LinkedHashMap<>(current.metadata());
        String body = current.body();

        for (int depth = 0; depth < MAX_NESTING_DEPTH; depth++) {
            SkillDocument nested = parseDocument(body);
            if (!nested.hasFrontmatter()) {
                break;
            }
            mergeRepeatedFrontmatter(metadata, nested.metadata());
            body = nested.body();
        }

        return new SkillDocument(metadata, body, true);
    }

    public String normalizeAndRender(String content, Map<String, Object> overrides) {
        SkillDocument parsedDocument = parseNormalizedDocument(content);
        Map<String, Object> metadata = mergeMetadata(parsedDocument.metadata(), overrides);
        return renderDocument(metadata, parsedDocument.body());
    }

    public Map<String, Object> mergeMetadata(Map<String, Object> lowPriority, Map<String, Object> highPriority) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (lowPriority != null && !lowPriority.isEmpty()) {
            merged.putAll(lowPriority);
        }
        if (highPriority != null && !highPriority.isEmpty()) {
            merged.putAll(highPriority);
        }
        return merged;
    }

    public String renderDocument(Map<String, Object> metadata, String body) {
        String normalizedBody = body != null ? body : "";
        Map<String, Object> orderedMetadata = orderMetadata(metadata);
        if (orderedMetadata.isEmpty()) {
            return normalizedBody;
        }
        try {
            String yaml = yamlMapper.writeValueAsString(orderedMetadata).stripTrailing();
            return "---\n" + yaml + "\n---\n" + normalizedBody;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize skill metadata", ex);
        }
    }

    private void mergeRepeatedFrontmatter(Map<String, Object> metadata, Map<String, Object> nestedMetadata) {
        for (Map.Entry<String, Object> entry : nestedMetadata.entrySet()) {
            if (shouldAdoptNestedValue(metadata.get(entry.getKey()))) {
                metadata.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private boolean shouldAdoptNestedValue(Object currentValue) {
        if (currentValue == null) {
            return true;
        }
        if (currentValue instanceof String currentString) {
            return currentString.isBlank();
        }
        if (currentValue instanceof Map<?, ?> currentMap) {
            return currentMap.isEmpty();
        }
        if (currentValue instanceof List<?> currentList) {
            return currentList.isEmpty();
        }
        return false;
    }

    private Map<String, Object> orderMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String key : FRONTMATTER_KEY_ORDER) {
            if (metadata.containsKey(key)) {
                ordered.put(key, metadata.get(key));
            }
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (!ordered.containsKey(entry.getKey())) {
                ordered.put(entry.getKey(), entry.getValue());
            }
        }
        return ordered;
    }

    private String normalizeBody(String body) {
        return body == null ? "" : body.stripTrailing();
    }

    private FrontmatterSections splitFrontmatter(String content) {
        int openingLineEnd = findLineEnd(content, 0);
        if (!isFrontmatterDelimiterLine(content, 0, openingLineEnd)) {
            return null;
        }

        int currentIndex = advancePastLineEnding(content, openingLineEnd);
        if (currentIndex >= content.length()) {
            return null;
        }

        int frontmatterStart = currentIndex;
        while (currentIndex < content.length()) {
            int lineEnd = findLineEnd(content, currentIndex);
            if (isFrontmatterDelimiterLine(content, currentIndex, lineEnd)) {
                String frontmatter = content.substring(frontmatterStart, currentIndex);
                int bodyStart = skipBlankLines(content, advancePastLineEnding(content, lineEnd));
                return new FrontmatterSections(frontmatter, content.substring(bodyStart));
            }
            currentIndex = advancePastLineEnding(content, lineEnd);
        }
        return null;
    }

    private boolean isFrontmatterDelimiterLine(String content, int start, int end) {
        if (end - start < 3
                || content.charAt(start) != '-'
                || content.charAt(start + 1) != '-'
                || content.charAt(start + 2) != '-') {
            return false;
        }
        for (int index = start + 3; index < end; index++) {
            if (!Character.isWhitespace(content.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private int findLineEnd(String content, int start) {
        int index = start;
        while (index < content.length()) {
            char current = content.charAt(index);
            if (current == '\n' || current == '\r') {
                return index;
            }
            index++;
        }
        return index;
    }

    private int advancePastLineEnding(String content, int index) {
        int currentIndex = index;
        if (currentIndex < content.length() && content.charAt(currentIndex) == '\r') {
            currentIndex++;
        }
        if (currentIndex < content.length() && content.charAt(currentIndex) == '\n') {
            currentIndex++;
        }
        return currentIndex;
    }

    private int skipBlankLines(String content, int start) {
        int currentIndex = start;
        while (currentIndex < content.length()) {
            int lineEnd = findLineEnd(content, currentIndex);
            if (!isBlankLine(content, currentIndex, lineEnd)) {
                return currentIndex;
            }
            currentIndex = advancePastLineEnding(content, lineEnd);
        }
        return currentIndex;
    }

    private boolean isBlankLine(String content, int start, int end) {
        for (int index = start; index < end; index++) {
            if (!Character.isWhitespace(content.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private record FrontmatterSections(String frontmatter, String body) {
    }
}

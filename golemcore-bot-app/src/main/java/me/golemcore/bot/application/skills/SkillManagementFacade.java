package me.golemcore.bot.application.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillDocument;
import me.golemcore.bot.domain.model.SkillInstallRequest;
import me.golemcore.bot.domain.model.SkillInstallResult;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import me.golemcore.bot.domain.skills.SkillDocumentService;
import me.golemcore.bot.domain.skills.SkillService;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.StoragePort;

import java.util.concurrent.CompletableFuture;

public class SkillManagementFacade {

    private static final String SKILLS_DIR = "skills";

    private final SkillService skillService;
    private final SkillDocumentService skillDocumentService;
    private final SkillMarketplaceService skillMarketplaceService;
    private final McpPort mcpPort;
    private final StoragePort storagePort;

    public SkillManagementFacade(
            SkillService skillService,
            SkillDocumentService skillDocumentService,
            SkillMarketplaceService skillMarketplaceService,
            McpPort mcpPort,
            StoragePort storagePort) {
        this.skillService = skillService;
        this.skillDocumentService = skillDocumentService;
        this.skillMarketplaceService = skillMarketplaceService;
        this.mcpPort = mcpPort;
        this.storagePort = storagePort;
    }

    public List<Skill> listSkills() {
        return skillService.getAllSkills();
    }

    public SkillMarketplaceCatalog getMarketplace() {
        return skillMarketplaceService.getCatalog();
    }

    public SkillInstallResult installSkill(SkillInstallRequest request) {
        return skillMarketplaceService.install(request.getSkillId());
    }

    public Skill getSkill(String name) {
        return skillService.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Skill '" + name + "' not found"));
    }

    public CompletableFuture<Skill> createSkill(String name, String content) {
        if (name == null || name.isBlank() || !isValidMetadataName(name.trim()) || name.contains("/")) {
            throw new IllegalArgumentException("Skill name is required and must match [a-z0-9][a-z0-9-]*");
        }
        if (content == null) {
            throw new IllegalArgumentException("Skill content is required");
        }

        String normalizedName = name.trim();
        if (skillService.findByName(normalizedName).isPresent()) {
            throw new IllegalStateException("Skill '" + normalizedName + "' already exists");
        }

        SkillDocument parsedDocument = skillDocumentService.parseNormalizedDocument(content);
        Map<String, Object> metadata = skillDocumentService.mergeMetadata(
                parsedDocument.metadata(),
                Map.of("name", normalizedName));
        validateMetadata(metadata);

        String path = normalizedName + "/SKILL.md";
        String document = skillDocumentService.renderDocument(metadata, parsedDocument.body());
        return persistSkillAndReload(path, document, () -> skillService.findByName(normalizedName));
    }

    public CompletableFuture<Skill> updateSkill(String name, Map<String, Object> body) {
        String content = extractContent(body);
        if (content == null) {
            throw new IllegalArgumentException("Skill content is required");
        }

        Skill skill = skillService.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Skill '" + name + "' not found"));

        SkillDocument parsedDocument = skillDocumentService.parseNormalizedDocument(content);
        Map<String, Object> metadata = resolveUpdatedMetadata(skill, body, parsedDocument);
        validateMetadata(metadata);

        String path = skillMarketplaceService.resolveManagedSkillStoragePath(skill);
        String document = skillDocumentService.renderDocument(metadata, parsedDocument.body());
        return persistSkillAndReload(path, document, () -> skillService.findByLocation(path));
    }

    public void reloadSkill(String name) {
        skillService.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Skill '" + name + "' not found"));
        skillService.reload();
    }

    public void deleteSkill(String name) {
        Skill skill = skillService.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Skill '" + name + "' not found"));
        skillMarketplaceService.deleteManagedSkill(skill);
        skillService.reload();
    }

    public McpStatusView getMcpStatus(String name) {
        Optional<Skill> skill = skillService.findByName(name);
        if (skill.isEmpty() || !skill.get().hasMcp()) {
            return new McpStatusView(false, false, List.of());
        }
        List<String> tools = mcpPort.getToolNames(name);
        return new McpStatusView(true, !tools.isEmpty(), tools);
    }

    private CompletableFuture<Skill> persistSkillAndReload(
            String path,
            String document,
            java.util.function.Supplier<Optional<Skill>> skillLookup) {
        return storagePort.putText(SKILLS_DIR, path, document)
                .thenApply(ignored -> {
                    skillService.reload();
                    return skillLookup.get().orElseThrow(
                            () -> new IllegalStateException("Skill persisted but could not be reloaded: " + path));
                });
    }

    private Map<String, Object> resolveUpdatedMetadata(
            Skill skill,
            Map<String, Object> body,
            SkillDocument parsedDocument) {
        Optional<Map<String, Object>> explicitMetadata = extractMetadata(body);
        if (explicitMetadata.isPresent()) {
            return explicitMetadata.get();
        }
        return skillDocumentService.mergeMetadata(copyMetadata(skill.getMetadata()), parsedDocument.metadata());
    }

    private String extractContent(Map<String, Object> body) {
        Object content = body.get("content");
        if (content == null) {
            return null;
        }
        if (!(content instanceof String stringContent)) {
            throw new IllegalArgumentException("Skill content must be a string");
        }
        return stringContent;
    }

    private Optional<Map<String, Object>> extractMetadata(Map<String, Object> body) {
        if (!body.containsKey("metadata")) {
            return Optional.empty();
        }
        Object metadata = body.get("metadata");
        if (metadata == null) {
            return Optional.of(new LinkedHashMap<>());
        }
        if (!(metadata instanceof Map<?, ?> rawMetadata)) {
            throw new IllegalArgumentException("Skill metadata must be an object");
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMetadata.entrySet()) {
            copied.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Optional.of(copied);
    }

    private void validateMetadata(Map<String, Object> metadata) {
        Object name = metadata.get("name");
        if (name != null) {
            String normalizedName = name.toString().trim();
            if (!isValidMetadataName(normalizedName)) {
                throw new IllegalArgumentException(
                        "Skill metadata name must match [a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)*");
            }
            metadata.put("name", normalizedName);
        }

        normalizeTierMetadata(metadata, "model_tier");
        normalizeTierMetadata(metadata, "reflection_tier");
    }

    private void normalizeTierMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            return;
        }
        String normalizedTier = ModelTierCatalog.normalizeTierId(value.toString());
        if (!ModelTierCatalog.isExplicitSelectableTier(normalizedTier)) {
            throw new IllegalArgumentException("Skill metadata " + key + " must be a known tier id");
        }
        metadata.put(key, normalizedTier);
    }

    private boolean isValidMetadataName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        int segmentStart = 0;
        for (int index = 0; index <= value.length(); index++) {
            boolean atSeparator = index < value.length() && value.charAt(index) == '/';
            boolean atEnd = index == value.length();
            if (!atSeparator && !atEnd) {
                continue;
            }
            if (!isValidMetadataSegment(value, segmentStart, index)) {
                return false;
            }
            segmentStart = index + 1;
        }
        return true;
    }

    private boolean isValidMetadataSegment(String value, int startInclusive, int endExclusive) {
        if (startInclusive >= endExclusive) {
            return false;
        }
        char first = value.charAt(startInclusive);
        if (!isLowercaseLetterOrDigit(first)) {
            return false;
        }
        for (int index = startInclusive + 1; index < endExclusive; index++) {
            char current = value.charAt(index);
            if (!isLowercaseLetterOrDigit(current) && current != '-') {
                return false;
            }
        }
        return true;
    }

    private boolean isLowercaseLetterOrDigit(char value) {
        return (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9');
    }

    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(metadata);
    }

    public record McpStatusView(boolean hasMcp, boolean running, List<String> tools) {
    }
}

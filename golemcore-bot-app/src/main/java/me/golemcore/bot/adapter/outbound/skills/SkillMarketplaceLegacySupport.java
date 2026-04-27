package me.golemcore.bot.adapter.outbound.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillDocument;
import me.golemcore.bot.domain.model.SkillInstallResult;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import me.golemcore.bot.domain.model.SkillMarketplaceItem;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.skills.SkillDocumentService;
import me.golemcore.bot.domain.skills.SkillService;
import me.golemcore.bot.domain.service.WorkspacePathService;
import me.golemcore.bot.port.outbound.SkillMarketplaceArtifactPort;
import me.golemcore.bot.port.outbound.SkillMarketplaceCatalogPort;
import me.golemcore.bot.port.outbound.SkillMarketplaceInstallPort;
import me.golemcore.bot.port.outbound.SkillSettingsPort;
import me.golemcore.bot.port.outbound.StoragePort;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads skill marketplace metadata from a local registry directory or a remote
 * GitHub repository and installs skill artifacts into runtime storage.
 */
@Slf4j
public class SkillMarketplaceLegacySupport
        implements SkillMarketplaceCatalogPort, SkillMarketplaceArtifactPort, SkillMarketplaceInstallPort {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String SKILLS_DIR = "skills";
    private static final String MARKETPLACE_DIR = "marketplace";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String INSTALL_METADATA_FILE = ".marketplace-install.json";
    private static final String TYPE_SKILL = "skill";
    private final SkillSettingsPort settingsPort;
    private final StoragePort storagePort;
    private final SkillService skillService;
    private final SkillDocumentService skillDocumentService;
    private final SkillMarketplaceRegistryLoader registryLoader;

    public SkillMarketplaceLegacySupport(
            SkillSettingsPort settingsPort,
            StoragePort storagePort,
            SkillService skillService,
            RuntimeConfigService runtimeConfigService,
            WorkspacePathService workspacePathService,
            SkillDocumentService skillDocumentService) {
        this.settingsPort = settingsPort;
        this.storagePort = storagePort;
        this.skillService = skillService;
        this.skillDocumentService = skillDocumentService;
        this.registryLoader = new SkillMarketplaceRegistryLoader(
                settingsPort,
                runtimeConfigService,
                workspacePathService,
                skillDocumentService);
    }

    @Override
    public SkillMarketplaceCatalogData loadCatalog(
            SkillSettingsPort.MarketplaceSettings marketplaceSettings,
            Map<String, Object> runtimeSkillSettings,
            String repositoryDirectory,
            String sandboxPath,
            String repositoryUrl,
            String branch) {
        if (!marketplaceSettings().enabled()) {
            return new SkillMarketplaceCatalogData(
                    unavailable("Skill marketplace is disabled by backend configuration."),
                    Map.of(),
                    Map.of(),
                    new MarketplaceSourceRef("repository", null, null, null));
        }

        try {
            MarketplaceSource source = registryLoader.resolveMarketplaceSource(
                    marketplaceSettings,
                    runtimeSkillSettings,
                    repositoryDirectory,
                    sandboxPath,
                    repositoryUrl,
                    branch);
            Map<String, RegistryArtifact> artifacts = registryLoader.loadArtifacts(source);
            Map<String, InstalledArtifactMetadata> installed = loadInstalledArtifacts();

            List<SkillMarketplaceItem> items = artifacts.values().stream()
                    .map(artifact -> toMarketplaceItem(artifact, installed.get(artifact.artifactRef())))
                    .sorted(Comparator
                            .comparing(SkillMarketplaceItem::isUpdateAvailable).reversed()
                            .thenComparing(SkillMarketplaceItem::isInstalled).reversed()
                            .thenComparing(SkillMarketplaceItem::getMaintainer, Comparator.nullsLast(String::compareTo))
                            .thenComparing(SkillMarketplaceItem::getArtifactId))
                    .toList();

            Map<String, MarketplaceArtifactData> artifactData = new LinkedHashMap<>();
            for (RegistryArtifact artifact : artifacts.values()) {
                artifactData.put(artifact.artifactRef(), new MarketplaceArtifactData(
                        artifact.artifactRef(),
                        artifact.version(),
                        artifact.contentHash(),
                        source.type(),
                        source.displayValue()));
            }

            Map<String, InstalledMarketplaceArtifact> installedArtifacts = new LinkedHashMap<>();
            for (InstalledArtifactMetadata metadata : installed.values()) {
                installedArtifacts.put(metadata.artifactRef(), new InstalledMarketplaceArtifact(
                        metadata.artifactRef(),
                        metadata.version(),
                        metadata.artifactHash(),
                        metadata.installedContentHash(),
                        metadata.currentContentHash()));
            }

            SkillMarketplaceCatalog catalog = SkillMarketplaceCatalog.builder()
                    .available(true)
                    .sourceType(source.type())
                    .sourceDirectory(source.displayValue())
                    .items(items)
                    .build();
            return new SkillMarketplaceCatalogData(
                    catalog,
                    Map.copyOf(installedArtifacts),
                    Map.copyOf(artifactData),
                    new MarketplaceSourceRef(source.type(), source.displayValue(), source.repositoryUrl(),
                            source.branch()));
        } catch (RuntimeException ex) {
            log.warn("[Skills] Failed to load marketplace catalog: {}", ex.getMessage());
            return new SkillMarketplaceCatalogData(
                    unavailable(ex.getMessage()),
                    Map.of(),
                    Map.of(),
                    new MarketplaceSourceRef("repository", null, null, null));
        }
    }

    @Override
    public InstalledArtifactContent loadArtifactContent(
            MarketplaceSourceRef sourceRef,
            MarketplaceArtifactData artifactData,
            String requestedArtifactRef) {
        String artifactRef = normalizeArtifactRef(requestedArtifactRef);
        MarketplaceSource source = registryLoader.resolveMarketplaceSource(sourceRef, artifactData);
        Map<String, RegistryArtifact> artifacts = registryLoader.loadArtifacts(source);
        RegistryArtifact artifact = artifacts.get(artifactRef);
        if (artifact == null) {
            throw new IllegalArgumentException("Unknown skill artifact: " + artifactRef);
        }
        if (artifactData != null
                && artifactData.contentHash() != null
                && !Objects.equals(artifactData.contentHash(), artifact.contentHash())) {
            throw new IllegalStateException("Skill marketplace artifact changed since catalog was loaded: "
                    + artifactRef);
        }

        List<InstalledSkillPayload> payloads = buildInstalledSkillPayloads(source, artifact);
        List<InstalledSkillDocument> skillDocuments = new ArrayList<>();
        for (int index = 0; index < artifact.skills().size(); index++) {
            RegistrySkill skill = artifact.skills().get(index);
            InstalledSkillPayload payload = payloads.get(index);
            skillDocuments.add(new InstalledSkillDocument(
                    skill.skillId(),
                    skill.runtimeName(),
                    payload.storagePath(),
                    payload.content(),
                    skill.description(),
                    skill.modelTier()));
        }

        return new InstalledArtifactContent(
                artifact.artifactRef(),
                artifact.type(),
                artifact.version(),
                artifact.contentHash(),
                List.copyOf(skillDocuments),
                source.type(),
                source.displayValue());
    }

    @Override
    public void install(InstalledArtifactInstallRequest request) {
        String installBasePath = buildInstallBasePath(request.artifactRef());
        List<InstalledSkillPayload> payloads = request.skillDocuments().stream()
                .map(document -> new InstalledSkillPayload(document.storagePath(), document.content()))
                .toList();
        String installedContentHash = buildInstalledContentHash(payloads);

        try {
            deleteInstalledArtifact(installBasePath);
            for (InstalledSkillDocument document : request.skillDocuments()) {
                storagePort.putText(SKILLS_DIR, document.storagePath(), document.content()).join();
            }
            InstalledArtifactMetadata metadata = new InstalledArtifactMetadata(
                    request.artifactRef(),
                    request.version(),
                    request.sourceType(),
                    request.sourceDisplayValue(),
                    request.artifactHash(),
                    installedContentHash,
                    installedContentHash,
                    request.skillDocuments().stream().map(InstalledSkillDocument::runtimeName).toList(),
                    request.installedAt());
            writeInstalledMetadata(installBasePath, metadata);
            skillService.reload();
        } catch (RuntimeException exception) {
            deleteInstalledArtifact(installBasePath);
            throw new IllegalStateException("Failed to install skill artifact: " + request.artifactRef(),
                    exception);
        }
    }

    private String buildInstallBasePath(String artifactRef) {
        String normalizedArtifactRef = normalizeArtifactRef(artifactRef);
        String[] parts = normalizedArtifactRef.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Artifact ref must match <maintainer>/<artifact>");
        }
        return MARKETPLACE_DIR + "/" + parts[0] + "/" + parts[1];
    }

    private String normalizeArtifactRef(String artifactRef) {
        return registryLoader.normalizeArtifactRef(artifactRef);
    }

    public SkillMarketplaceCatalog getCatalog() {
        if (!marketplaceSettings().enabled()) {
            return unavailable("Skill marketplace is disabled by backend configuration.");
        }

        try {
            MarketplaceSource source = registryLoader.resolveMarketplaceSource();
            Map<String, RegistryArtifact> artifacts = registryLoader.loadArtifacts(source);
            Map<String, InstalledArtifactMetadata> installed = loadInstalledArtifacts();

            List<SkillMarketplaceItem> items = artifacts.values().stream()
                    .map(artifact -> toMarketplaceItem(artifact, installed.get(artifact.artifactRef())))
                    .sorted(Comparator
                            .comparing(SkillMarketplaceItem::isUpdateAvailable).reversed()
                            .thenComparing(SkillMarketplaceItem::isInstalled).reversed()
                            .thenComparing(SkillMarketplaceItem::getMaintainer, Comparator.nullsLast(String::compareTo))
                            .thenComparing(SkillMarketplaceItem::getArtifactId))
                    .toList();

            return SkillMarketplaceCatalog.builder()
                    .available(true)
                    .sourceType(source.type())
                    .sourceDirectory(source.displayValue())
                    .items(items)
                    .build();
        } catch (RuntimeException ex) {
            log.warn("[Skills] Failed to load marketplace catalog: {}", ex.getMessage());
            return unavailable(ex.getMessage());
        }
    }

    public SkillInstallResult install(String skillId) {
        String artifactRef = normalizeArtifactRef(skillId);
        if (artifactRef == null) {
            throw new IllegalArgumentException("skillId is required");
        }
        if (!marketplaceSettings().enabled()) {
            throw new IllegalStateException("Skill marketplace is disabled");
        }

        MarketplaceSource source = registryLoader.resolveMarketplaceSource();
        Map<String, RegistryArtifact> artifacts = registryLoader.loadArtifacts(source);
        RegistryArtifact artifact = artifacts.get(artifactRef);
        if (artifact == null) {
            throw new IllegalArgumentException("Unknown skill artifact: " + artifactRef);
        }

        Map<String, InstalledArtifactMetadata> installed = loadInstalledArtifacts();
        InstalledArtifactMetadata existing = installed.get(artifactRef);

        List<InstalledSkillPayload> payloads = buildInstalledSkillPayloads(source, artifact);
        String installBasePath = installBasePath(artifact);
        String artifactHash = artifact.contentHash();
        String installedContentHash = buildInstalledContentHash(payloads);

        try {
            deleteInstalledArtifact(installBasePath);
            for (InstalledSkillPayload payload : payloads) {
                storagePort.putText(SKILLS_DIR, payload.storagePath(), payload.content()).join();
            }

            InstalledArtifactMetadata metadata = new InstalledArtifactMetadata(
                    artifact.artifactRef(),
                    artifact.version(),
                    source.type(),
                    source.displayValue(),
                    artifactHash,
                    installedContentHash,
                    installedContentHash,
                    artifact.skills().stream().map(RegistrySkill::runtimeName).toList(),
                    Instant.now());
            writeInstalledMetadata(installBasePath, metadata);
        } catch (RuntimeException ex) {
            deleteInstalledArtifact(installBasePath);
            throw new IllegalStateException("Failed to install skill artifact: " + artifactRef, ex);
        }

        skillService.reload();

        InstalledArtifactMetadata metadata = new InstalledArtifactMetadata(
                artifact.artifactRef(),
                artifact.version(),
                source.type(),
                source.displayValue(),
                artifactHash,
                installedContentHash,
                installedContentHash,
                artifact.skills().stream().map(RegistrySkill::runtimeName).toList(),
                Instant.now());
        SkillMarketplaceItem installedItem = toMarketplaceItem(artifact, metadata);
        String status = resolveInstallStatus(existing, artifact);
        String message = switch (status) {
        case "updated" -> "Skill artifact '" + artifactRef + "' updated from marketplace.";
        case "already-installed" -> "Skill artifact '" + artifactRef + "' is already up to date.";
        default -> "Skill artifact '" + artifactRef + "' installed from marketplace.";
        };

        return new SkillInstallResult(status, message, installedItem);
    }

    private String resolveInstallStatus(InstalledArtifactMetadata existing, RegistryArtifact artifact) {
        if (existing == null) {
            return "installed";
        }
        if (Objects.equals(trimToNull(existing.version()), trimToNull(artifact.version()))
                && Objects.equals(trimToNull(existing.artifactHash()), trimToNull(artifact.contentHash()))
                && !hasLocalContentDrift(existing)) {
            return "already-installed";
        }
        return "updated";
    }

    private SkillMarketplaceItem toMarketplaceItem(RegistryArtifact artifact, InstalledArtifactMetadata installed) {
        String modelTier = artifact.skills().size() == 1 ? artifact.skills().getFirst().modelTier() : null;
        boolean isInstalled = installed != null;
        boolean updateAvailable = isInstalled
                && (!Objects.equals(trimToNull(installed.version()), trimToNull(artifact.version()))
                        || !Objects.equals(trimToNull(installed.artifactHash()), trimToNull(artifact.contentHash()))
                        || hasLocalContentDrift(installed));

        return SkillMarketplaceItem.builder()
                .id(artifact.artifactRef())
                .name(firstNonBlank(artifact.title(), artifact.artifactId()))
                .description(firstNonBlank(artifact.description(),
                        artifact.skills().size() == 1 ? artifact.skills().getFirst().description() : null))
                .maintainer(artifact.maintainerId())
                .maintainerDisplayName(artifact.maintainerDisplayName())
                .artifactId(artifact.artifactId())
                .artifactType(artifact.type())
                .version(artifact.version())
                .modelTier(modelTier)
                .sourcePath(artifact.manifestPath())
                .skillRefs(artifact.skills().stream().map(RegistrySkill::runtimeName).toList())
                .skillCount(artifact.skills().size())
                .installed(isInstalled)
                .updateAvailable(updateAvailable)
                .build();
    }

    private List<InstalledSkillPayload> buildInstalledSkillPayloads(MarketplaceSource source,
            RegistryArtifact artifact) {
        Map<String, String> aliasMap = new LinkedHashMap<>();
        for (RegistrySkill skill : artifact.skills()) {
            aliasMap.put(skill.skillId(), skill.runtimeName());
            if (skill.originalName() != null) {
                aliasMap.put(skill.originalName(), skill.runtimeName());
            }
            aliasMap.put(skill.runtimeName(), skill.runtimeName());
        }

        List<InstalledSkillPayload> payloads = new ArrayList<>();
        for (RegistrySkill skill : artifact.skills()) {
            String sourceContent = registryLoader.readSkillContent(source, skill.sourcePath());
            String transformed = rewriteSkillContent(sourceContent, skill.runtimeName(), aliasMap);
            payloads.add(new InstalledSkillPayload(installSkillStoragePath(artifact, skill), transformed));
        }
        return payloads;
    }

    private String rewriteSkillContent(String content, String runtimeName, Map<String, String> aliasMap) {
        SkillDocument document = skillDocumentService.parseDocument(content);
        Map<String, Object> metadata = new LinkedHashMap<>(document.metadata());
        metadata.put("name", runtimeName);

        rewriteStringField(metadata, "next_skill", aliasMap);

        Object conditional = metadata.get("conditional_next_skills");
        if (conditional instanceof Map<?, ?> conditionalMap) {
            Map<String, Object> rewritten = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : conditionalMap.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String stringValue && aliasMap.containsKey(stringValue)) {
                    rewritten.put(String.valueOf(entry.getKey()), aliasMap.get(stringValue));
                } else {
                    rewritten.put(String.valueOf(entry.getKey()), value);
                }
            }
            metadata.put("conditional_next_skills", rewritten);
        }

        Object requirements = metadata.get("requirements");
        if (requirements instanceof Map<?, ?> requirementsMap) {
            Map<String, Object> rewrittenRequirements = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : requirementsMap.entrySet()) {
                if ("skills".equals(String.valueOf(entry.getKey())) && entry.getValue() instanceof List<?> skills) {
                    List<Object> rewrittenSkills = new ArrayList<>();
                    for (Object skill : skills) {
                        if (skill instanceof String stringSkill && aliasMap.containsKey(stringSkill)) {
                            rewrittenSkills.add(aliasMap.get(stringSkill));
                        } else {
                            rewrittenSkills.add(skill);
                        }
                    }
                    rewrittenRequirements.put("skills", rewrittenSkills);
                } else {
                    rewrittenRequirements.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            metadata.put("requirements", rewrittenRequirements);
        }

        return skillDocumentService.renderDocument(metadata, document.body()) + "\n";
    }

    private void rewriteStringField(Map<String, Object> metadata, String key, Map<String, String> aliasMap) {
        Object value = metadata.get(key);
        if (value instanceof String stringValue && aliasMap.containsKey(stringValue)) {
            metadata.put(key, aliasMap.get(stringValue));
        }
    }

    private Map<String, InstalledArtifactMetadata> loadInstalledArtifacts() {
        List<String> keys = storagePort.listObjects(SKILLS_DIR, MARKETPLACE_DIR).join();
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, InstalledArtifactMetadata> installed = new LinkedHashMap<>();
        for (String key : keys) {
            if (!key.endsWith("/" + INSTALL_METADATA_FILE)) {
                continue;
            }
            try {
                String json = storagePort.getText(SKILLS_DIR, key).join();
                if (json == null || json.isBlank()) {
                    continue;
                }
                InstalledArtifactMetadata metadata = JSON_MAPPER.readValue(json, InstalledArtifactMetadata.class);
                if (metadata.artifactRef() != null) {
                    String installBasePath = key.substring(0, key.length() - ("/" + INSTALL_METADATA_FILE).length());
                    String currentContentHash = calculateInstalledArtifactHash(installBasePath);
                    installed.put(metadata.artifactRef(), new InstalledArtifactMetadata(
                            metadata.artifactRef(),
                            metadata.version(),
                            metadata.sourceType(),
                            metadata.sourceLocation(),
                            metadata.artifactHash(),
                            resolveStoredInstalledContentHash(metadata, currentContentHash),
                            currentContentHash,
                            metadata.installedSkillNames(),
                            metadata.installedAt()));
                }
            } catch (IOException | RuntimeException ex) {
                log.warn("[Skills] Failed to read installed marketplace metadata {}: {}", key, ex.getMessage());
            }
        }
        return Map.copyOf(installed);
    }

    private void writeInstalledMetadata(String installBasePath, InstalledArtifactMetadata metadata) {
        try {
            String json = JSON_MAPPER.writeValueAsString(metadata);
            storagePort.putText(SKILLS_DIR, installBasePath + "/" + INSTALL_METADATA_FILE, json).join();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write install metadata for " + metadata.artifactRef(), ex);
        }
    }

    private String resolveStoredInstalledContentHash(
            InstalledArtifactMetadata metadata,
            String currentContentHash) {
        String storedInstalledContentHash = firstNonBlank(
                trimToNull(metadata.installedContentHash()),
                trimToNull(metadata.currentContentHash()));
        if (storedInstalledContentHash == null) {
            return currentContentHash;
        }

        // Older installs stored the source artifact hash in currentContentHash.
        if (Objects.equals(trimToNull(storedInstalledContentHash), trimToNull(metadata.artifactHash()))
                && currentContentHash != null) {
            return currentContentHash;
        }
        return storedInstalledContentHash;
    }

    private void deleteInstalledArtifact(String installBasePath) {
        List<String> keys = storagePort.listObjects(SKILLS_DIR, installBasePath).join();
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            storagePort.deleteObject(SKILLS_DIR, key).join();
        }
    }

    public void deleteManagedSkill(Skill skill) {
        Skill managedSkill = Objects.requireNonNull(skill, "skill");
        Path location = managedSkill.getLocation();
        String storagePath = resolveManagedSkillStoragePath(managedSkill);
        String deleteScope = resolveDeleteScope(location, storagePath);

        List<String> keys = storagePort.listObjects(SKILLS_DIR, deleteScope).join();
        if (keys == null || keys.isEmpty()) {
            storagePort.deleteObject(SKILLS_DIR, storagePath).join();
            return;
        }
        for (String key : keys) {
            storagePort.deleteObject(SKILLS_DIR, key).join();
        }
    }

    public String resolveManagedSkillStoragePath(Skill skill) {
        Skill managedSkill = Objects.requireNonNull(skill, "skill must not be null");
        Path location = managedSkill.getLocation();
        if (location != null) {
            return location.toString().replace('\\', '/');
        }
        String skillName = managedSkill.getName();
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skill must define name or location");
        }
        return skillName + "/" + SKILL_FILE;
    }

    public Optional<String> resolveMarketplaceInstallBase(Path location) {
        if (location == null) {
            return Optional.empty();
        }
        String normalized = location.toString().replace('\\', '/');
        String prefix = MARKETPLACE_DIR + "/";
        if (!normalized.startsWith(prefix)) {
            return Optional.empty();
        }
        String[] parts = normalized.split("/");
        if (parts.length < 4) {
            return Optional.empty();
        }
        return Optional.of(parts[0] + "/" + parts[1] + "/" + parts[2]);
    }

    private String installBasePath(RegistryArtifact artifact) {
        return MARKETPLACE_DIR + "/" + artifact.maintainerId() + "/" + artifact.artifactId();
    }

    private String installSkillStoragePath(RegistryArtifact artifact, RegistrySkill skill) {
        String base = installBasePath(artifact);
        if (TYPE_SKILL.equals(artifact.type())) {
            return base + "/" + SKILL_FILE;
        }
        return base + "/skills/" + skill.skillId() + "/" + SKILL_FILE;
    }

    private SkillSettingsPort.MarketplaceSettings marketplaceSettings() {
        return settingsPort.skills().marketplace();
    }

    private SkillMarketplaceCatalog unavailable(String message) {
        return SkillMarketplaceCatalog.builder()
                .available(false)
                .message(message)
                .items(List.of())
                .build();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String resolveDeleteScope(Path location, String storagePath) {
        Optional<String> marketplaceBase = resolveMarketplaceInstallBase(location);
        if (marketplaceBase.isPresent()) {
            return marketplaceBase.get();
        }
        int lastSlash = storagePath.lastIndexOf('/');
        if (lastSlash > 0) {
            return storagePath.substring(0, lastSlash);
        }
        return storagePath;
    }

    private String calculateInstalledArtifactHash(String installBasePath) {
        List<String> keys = storagePort.listObjects(SKILLS_DIR, installBasePath).join();
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        List<String> contentEntries = keys.stream()
                .filter(key -> !key.endsWith("/" + INSTALL_METADATA_FILE))
                .sorted()
                .map(key -> key + "\n" + Optional.ofNullable(storagePort.getText(SKILLS_DIR, key).join()).orElse(""))
                .toList();
        if (contentEntries.isEmpty()) {
            return null;
        }
        return SkillMarketplaceHashing.sha256Hex(String.join("\n---\n", contentEntries));
    }

    private String buildInstalledContentHash(List<InstalledSkillPayload> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return null;
        }
        List<String> contentEntries = payloads.stream()
                .sorted(Comparator.comparing(InstalledSkillPayload::storagePath))
                .map(payload -> payload.storagePath() + "\n" + Optional.ofNullable(payload.content()).orElse(""))
                .toList();
        return SkillMarketplaceHashing.sha256Hex(String.join("\n---\n", contentEntries));
    }

    private boolean hasLocalContentDrift(InstalledArtifactMetadata installed) {
        if (installed == null) {
            return false;
        }
        String expectedContentHash = trimToNull(installed.installedContentHash());
        String currentContentHash = trimToNull(installed.currentContentHash());
        if (expectedContentHash == null || currentContentHash == null) {
            return false;
        }
        return !Objects.equals(expectedContentHash, currentContentHash);
    }

    private record InstalledSkillPayload(String storagePath, String content) {
    }

    private record InstalledArtifactMetadata(
            String artifactRef,
            String version,
            String sourceType,
            String sourceLocation,
            String artifactHash,
            String installedContentHash,
            String currentContentHash,
            List<String> installedSkillNames,
            Instant installedAt) {
    }
}

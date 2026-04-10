package me.golemcore.bot.application.skills;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillInstallResult;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import me.golemcore.bot.domain.model.SkillMarketplaceItem;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.SkillMarketplaceArtifactPort;
import me.golemcore.bot.port.outbound.SkillMarketplaceCatalogPort;
import me.golemcore.bot.port.outbound.SkillMarketplaceInstallPort;
import me.golemcore.bot.port.outbound.SkillSettingsPort;

public class SkillMarketplaceService {

    private final SkillSettingsPort skillSettingsPort;
    private final RuntimeConfigService runtimeConfigService;
    private final SkillMarketplaceCatalogPort skillMarketplaceCatalogPort;
    private final SkillMarketplaceArtifactPort skillMarketplaceArtifactPort;
    private final SkillMarketplaceInstallPort skillMarketplaceInstallPort;

    public SkillMarketplaceService(
            SkillSettingsPort skillSettingsPort,
            RuntimeConfigService runtimeConfigService,
            SkillMarketplaceCatalogPort skillMarketplaceCatalogPort,
            SkillMarketplaceArtifactPort skillMarketplaceArtifactPort,
            SkillMarketplaceInstallPort skillMarketplaceInstallPort) {
        this.skillSettingsPort = skillSettingsPort;
        this.runtimeConfigService = runtimeConfigService;
        this.skillMarketplaceCatalogPort = skillMarketplaceCatalogPort;
        this.skillMarketplaceArtifactPort = skillMarketplaceArtifactPort;
        this.skillMarketplaceInstallPort = skillMarketplaceInstallPort;
    }

    public SkillMarketplaceCatalog getCatalog() {
        SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData catalogData = loadCatalogData();
        SkillMarketplaceCatalog catalog = catalogData.catalog();
        return catalog != null ? catalog
                : SkillMarketplaceCatalog.builder()
                        .available(false)
                        .message("Skill marketplace is unavailable")
                        .build();
    }

    public SkillInstallResult install(String skillId) {
        String artifactRef = normalizeArtifactRef(skillId);
        if (artifactRef == null) {
            throw new IllegalArgumentException("skillId is required");
        }
        if (!skillSettingsPort.skills().marketplace().enabled()) {
            throw new IllegalStateException("Skill marketplace is disabled");
        }

        SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData catalogData = loadCatalogData();
        SkillMarketplaceCatalogPort.MarketplaceArtifactData artifact = catalogData.artifacts().get(artifactRef);
        if (artifact == null) {
            throw new IllegalArgumentException("Unknown skill artifact: " + artifactRef);
        }

        SkillMarketplaceArtifactPort.InstalledArtifactContent artifactContent = skillMarketplaceArtifactPort
                .loadArtifactContent(catalogData.source(), artifact, artifactRef);
        skillMarketplaceInstallPort.install(new SkillMarketplaceInstallPort.InstalledArtifactInstallRequest(
                artifactContent.artifactRef(),
                artifactContent.artifactType(),
                artifactContent.sourceType(),
                artifactContent.sourceDisplayValue(),
                artifactContent.version(),
                artifactContent.artifactHash(),
                artifactContent.skillDocuments(),
                Instant.now()));

        SkillMarketplaceCatalog refreshedCatalog = getCatalog();
        SkillMarketplaceItem installedItem = findCatalogItem(refreshedCatalog, artifactRef)
                .orElseGet(() -> buildFallbackCatalogItem(artifactContent));
        String status = resolveInstallStatus(catalogData.installedArtifacts().get(artifactRef), artifactContent);
        String message = switch (status) {
        case "updated" -> "Skill artifact '" + artifactRef + "' updated from marketplace.";
        case "already-installed" -> "Skill artifact '" + artifactRef + "' is already up to date.";
        default -> "Skill artifact '" + artifactRef + "' installed from marketplace.";
        };
        return new SkillInstallResult(status, message, installedItem);
    }

    public void deleteManagedSkill(Skill skill) {
        skillMarketplaceInstallPort.deleteManagedSkill(skill);
    }

    public String resolveManagedSkillStoragePath(Skill skill) {
        return skillMarketplaceInstallPort.resolveManagedSkillStoragePath(skill);
    }

    public Optional<String> resolveMarketplaceInstallBase(java.nio.file.Path location) {
        return skillMarketplaceInstallPort.resolveMarketplaceInstallBase(location);
    }

    private SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData loadCatalogData() {
        RuntimeConfig runtimeConfig = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.SkillsConfig skillsConfig = runtimeConfig != null && runtimeConfig.getSkills() != null
                ? runtimeConfig.getSkills()
                : new RuntimeConfig.SkillsConfig();
        return skillMarketplaceCatalogPort.loadCatalog(
                skillSettingsPort.skills().marketplace(),
                extractRuntimeSkillSettings(skillsConfig),
                skillsConfig.getMarketplaceRepositoryDirectory(),
                skillsConfig.getMarketplaceSandboxPath(),
                skillsConfig.getMarketplaceRepositoryUrl(),
                skillsConfig.getMarketplaceBranch());
    }

    private Map<String, Object> extractRuntimeSkillSettings(RuntimeConfig.SkillsConfig skillsConfig) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (skillsConfig == null) {
            return settings;
        }
        putIfNotBlank(settings, "marketplaceSourceType", skillsConfig.getMarketplaceSourceType());
        putIfNotBlank(settings, "marketplaceRepositoryDirectory", skillsConfig.getMarketplaceRepositoryDirectory());
        putIfNotBlank(settings, "marketplaceSandboxPath", skillsConfig.getMarketplaceSandboxPath());
        putIfNotBlank(settings, "marketplaceRepositoryUrl", skillsConfig.getMarketplaceRepositoryUrl());
        putIfNotBlank(settings, "marketplaceBranch", skillsConfig.getMarketplaceBranch());
        return settings;
    }

    private void putIfNotBlank(Map<String, Object> settings, String key, String value) {
        if (value != null && !value.isBlank()) {
            settings.put(key, value);
        }
    }

    private Optional<SkillMarketplaceItem> findCatalogItem(SkillMarketplaceCatalog catalog, String artifactRef) {
        if (catalog == null || catalog.getItems() == null) {
            return Optional.empty();
        }
        return catalog.getItems().stream()
                .filter(item -> artifactRef.equals(item.getId()))
                .findFirst();
    }

    private SkillMarketplaceItem buildFallbackCatalogItem(
            SkillMarketplaceArtifactPort.InstalledArtifactContent artifactContent) {
        String modelTier = artifactContent.skillDocuments().size() == 1
                ? artifactContent.skillDocuments().getFirst().modelTier()
                : null;
        return SkillMarketplaceItem.builder()
                .id(artifactContent.artifactRef())
                .name(artifactContent.artifactRef())
                .artifactId(extractArtifactId(artifactContent.artifactRef()))
                .artifactType(artifactContent.artifactType())
                .version(artifactContent.version())
                .modelTier(modelTier)
                .skillRefs(artifactContent.skillDocuments().stream()
                        .map(SkillMarketplaceArtifactPort.InstalledSkillDocument::runtimeName)
                        .toList())
                .skillCount(artifactContent.skillDocuments().size())
                .installed(true)
                .updateAvailable(false)
                .build();
    }

    private String extractArtifactId(String artifactRef) {
        String[] parts = artifactRef.split("/");
        return parts.length == 2 ? parts[1] : artifactRef;
    }

    private String resolveInstallStatus(
            SkillMarketplaceCatalogPort.InstalledMarketplaceArtifact installed,
            SkillMarketplaceArtifactPort.InstalledArtifactContent artifactContent) {
        if (installed == null) {
            return "installed";
        }
        boolean sameVersion = equalsNullable(trimToNull(installed.version()), trimToNull(artifactContent.version()));
        boolean sameArtifactHash = equalsNullable(trimToNull(installed.artifactHash()),
                trimToNull(artifactContent.artifactHash()));
        boolean localDrift = hasLocalContentDrift(installed);
        if (sameVersion && sameArtifactHash && !localDrift) {
            return "already-installed";
        }
        return "updated";
    }

    private boolean hasLocalContentDrift(SkillMarketplaceCatalogPort.InstalledMarketplaceArtifact installed) {
        String expectedContentHash = trimToNull(installed.installedContentHash());
        String currentContentHash = trimToNull(installed.currentContentHash());
        if (expectedContentHash == null || currentContentHash == null) {
            return false;
        }
        return !expectedContentHash.equals(currentContentHash);
    }

    private boolean equalsNullable(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private String normalizeArtifactRef(String artifactRef) {
        if (artifactRef == null || artifactRef.isBlank()) {
            return null;
        }
        String normalized = artifactRef.trim().toLowerCase(java.util.Locale.ROOT);
        String[] parts = normalized.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Artifact ref must match <maintainer>/<artifact>");
        }
        return normalizeSlug(parts[0]) + "/" + normalizeSlug(parts[1]);
    }

    private String normalizeSlug(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Slug is required");
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            boolean alphanumeric = (current >= 'a' && current <= 'z') || (current >= '0' && current <= '9');
            if (index == 0 && !alphanumeric) {
                throw new IllegalArgumentException("Slug must start with [a-z0-9]");
            }
            if (!alphanumeric && current != '-') {
                throw new IllegalArgumentException("Slug must contain only [a-z0-9-]");
            }
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

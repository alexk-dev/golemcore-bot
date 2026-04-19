package me.golemcore.bot.port.outbound;

import java.util.Map;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import me.golemcore.bot.port.outbound.SkillSettingsPort.MarketplaceSettings;

public interface SkillMarketplaceCatalogPort {

    SkillMarketplaceCatalogData loadCatalog(
            MarketplaceSettings marketplaceSettings,
            Map<String, Object> runtimeSkillSettings,
            String repositoryDirectory,
            String sandboxPath,
            String repositoryUrl,
            String branch);

    record SkillMarketplaceCatalogData(
            SkillMarketplaceCatalog catalog,
            Map<String, InstalledMarketplaceArtifact> installedArtifacts,
            Map<String, MarketplaceArtifactData> artifacts,
            MarketplaceSourceRef source) {
    }

    record MarketplaceArtifactData(
            String artifactRef,
            String version,
            String contentHash,
            String sourceType,
            String sourceDisplayValue) {
    }

    record InstalledMarketplaceArtifact(
            String artifactRef,
            String version,
            String artifactHash,
            String installedContentHash,
            String currentContentHash) {
    }

    record MarketplaceSourceRef(
            String type,
            String displayValue,
            String repositoryUrl,
            String branch) {
    }
}

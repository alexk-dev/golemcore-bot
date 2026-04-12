package me.golemcore.bot.port.outbound;

import java.util.List;
import me.golemcore.bot.port.outbound.SkillMarketplaceCatalogPort.MarketplaceArtifactData;
import me.golemcore.bot.port.outbound.SkillMarketplaceCatalogPort.MarketplaceSourceRef;

public interface SkillMarketplaceArtifactPort {

    InstalledArtifactContent loadArtifactContent(
            MarketplaceSourceRef source,
            MarketplaceArtifactData artifact,
            String requestedArtifactRef);

    record InstalledArtifactContent(
            String artifactRef,
            String artifactType,
            String version,
            String artifactHash,
            List<InstalledSkillDocument> skillDocuments,
            String sourceType,
            String sourceDisplayValue) {
    }

    record InstalledSkillDocument(
            String skillId,
            String runtimeName,
            String storagePath,
            String content,
            String description,
            String modelTier) {
    }
}

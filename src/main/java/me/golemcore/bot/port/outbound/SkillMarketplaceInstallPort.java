package me.golemcore.bot.port.outbound;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.model.Skill;

public interface SkillMarketplaceInstallPort {

    void install(InstalledArtifactInstallRequest request);

    void deleteManagedSkill(Skill skill);

    String resolveManagedSkillStoragePath(Skill skill);

    Optional<String> resolveMarketplaceInstallBase(Path location);

    record InstalledArtifactInstallRequest(
            String artifactRef,
            String artifactType,
            String sourceType,
            String sourceDisplayValue,
            String version,
            String artifactHash,
            List<SkillMarketplaceArtifactPort.InstalledSkillDocument> skillDocuments,
            Instant installedAt) {
    }
}

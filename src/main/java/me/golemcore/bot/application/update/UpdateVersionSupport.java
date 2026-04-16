package me.golemcore.bot.application.update;

import me.golemcore.bot.domain.service.RuntimeVersionSupport;

final class UpdateVersionSupport extends RuntimeVersionSupport {

    @Override
    public String extractVersionFromAssetName(String assetName) {
        return super.extractVersionFromAssetName(assetName);
    }

    @Override
    public String normalizeVersion(String version) {
        return super.normalizeVersion(version);
    }

    @Override
    public boolean isRemoteVersionNewer(String candidateVersion, String currentVersion) {
        return super.isRemoteVersionNewer(candidateVersion, currentVersion);
    }

    @Override
    public int compareVersions(String left, String right) {
        return super.compareVersions(left, right);
    }
}

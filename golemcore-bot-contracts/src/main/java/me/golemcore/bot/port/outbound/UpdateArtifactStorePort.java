package me.golemcore.bot.port.outbound;

import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

public interface UpdateArtifactStorePort {

    Optional<StoredArtifact> findCurrentArtifact();

    Optional<StoredArtifact> findStagedArtifact();

    PreparedArtifact stageReleaseAsset(StageArtifactRequest request);

    void activateStagedArtifact(String assetName);

    void cleanupTempArtifact(String assetName);

    record StoredArtifact(String assetName, Instant modifiedAt) {
    }

    record PreparedArtifact(String assetName, Instant preparedAt) {
    }

    record StageArtifactRequest(String assetName, InputStream assetStream, ReleaseSourcePort.ChecksumInfo checksumInfo) {
    }
}

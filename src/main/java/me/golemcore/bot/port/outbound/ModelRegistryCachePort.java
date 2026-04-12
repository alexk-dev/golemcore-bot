package me.golemcore.bot.port.outbound;

import java.time.Instant;

public interface ModelRegistryCachePort {

    CachedRegistryEntry read(String repositoryUrl, String branch, String relativePath);

    void write(String repositoryUrl, String branch, String relativePath, CachedRegistryEntry entry);

    record CachedRegistryEntry(Instant cachedAt, boolean found, String content) {
    }
}

package me.golemcore.bot.adapter.outbound.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.golemcore.bot.port.outbound.ModelRegistryCachePort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

@Component
public class StorageModelRegistryCacheAdapter implements ModelRegistryCachePort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final String CACHE_DIR = "cache";
    private static final String CACHE_PREFIX = "model-registry";

    private final StoragePort storagePort;

    public StorageModelRegistryCacheAdapter(StoragePort storagePort) {
        this.storagePort = storagePort;
    }

    @Override
    public CachedRegistryEntry read(String repositoryUrl, String branch, String relativePath) {
        String cachePath = cachePath(repositoryUrl, branch, relativePath);
        try {
            Boolean exists = storagePort.exists(CACHE_DIR, cachePath).join();
            if (!Boolean.TRUE.equals(exists)) {
                return null;
            }
            String json = storagePort.getText(CACHE_DIR, cachePath).join();
            if (json == null || json.isBlank()) {
                return null;
            }
            CacheEntry payload = OBJECT_MAPPER.readValue(json, CacheEntry.class);
            return new CachedRegistryEntry(payload.getCachedAt(), payload.isFound(), payload.getContent());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse model registry cache entry " + relativePath, exception);
        }
    }

    @Override
    public void write(String repositoryUrl, String branch, String relativePath, CachedRegistryEntry entry) {
        String cachePath = cachePath(repositoryUrl, branch, relativePath);
        try {
            CacheEntry payload = new CacheEntry(entry.cachedAt(), entry.found(), entry.content());
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            storagePort.putTextAtomic(CACHE_DIR, cachePath, json, false).join();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize model registry cache entry " + relativePath,
                    exception);
        }
    }

    private String cachePath(String repositoryUrl, String branch, String relativePath) {
        return CACHE_PREFIX + "/" + sha256Hex(repositoryUrl + "\n" + branch) + "/" + relativePath + ".cache.json";
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                builder.append(String.format("%02x", current & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CacheEntry {
        private java.time.Instant cachedAt;
        private boolean found;
        private String content;
    }
}

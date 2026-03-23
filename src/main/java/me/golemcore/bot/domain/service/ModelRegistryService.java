package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModelRegistryService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Duration CACHE_TTL = Duration.ofDays(1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String CACHE_DIR = "cache";
    private static final String CACHE_PREFIX = "model-registry";
    private static final String DEFAULT_BRANCH = "main";
    private static final String DEFAULT_REPOSITORY_URL = "https://github.com/alexk-dev/golemcore-models";
    private static final String DEFAULT_RAW_BASE_URL = "https://raw.githubusercontent.com";
    private static final String GITHUB_USER_AGENT = "golemcore-bot-model-registry";

    private final RuntimeConfigService runtimeConfigService;
    private final StoragePort storagePort;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ResolveResult resolveDefaults(String provider, String modelId) {
        String normalizedProvider = requireValue(provider, "provider");
        String normalizedModelId = normalizeModelId(modelId);
        RegistrySource source = resolveSource();
        List<RegistryCandidate> candidates = List.of(
                new RegistryCandidate("provider",
                        "providers/" + normalizedProvider + "/" + normalizedModelId + ".json"),
                new RegistryCandidate("shared", "models/" + normalizedModelId + ".json"));

        ResolveResult freshCachedResult = findFreshCachedResult(source, candidates, normalizedProvider);
        if (freshCachedResult != null) {
            return freshCachedResult;
        }

        for (RegistryCandidate candidate : candidates) {
            ResolveResult result = resolveCandidate(source, candidate, normalizedProvider);
            if (result != null) {
                return result;
            }
        }
        return new ResolveResult(null, null, "miss");
    }

    protected Instant now() {
        return Instant.now();
    }

    protected String fetchRemoteText(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", GITHUB_USER_AGENT)
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            throw new IllegalStateException(
                    "Model registry request failed with status " + response.statusCode() + " for " + uri);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching model registry config: " + uri, ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to fetch model registry config: " + uri, ex);
        }
    }

    private ResolveResult findFreshCachedResult(RegistrySource source, List<RegistryCandidate> candidates,
            String provider) {
        for (RegistryCandidate candidate : candidates) {
            CacheEntry cacheEntry = readCacheEntry(source, candidate);
            if (cacheEntry == null || !cacheEntry.isFound() || !isFresh(cacheEntry)) {
                continue;
            }
            ModelConfigService.ModelSettings settings = tryParseSettings(cacheEntry.getContent(), provider,
                    candidate.relativePath());
            if (settings != null) {
                return new ResolveResult(settings, candidate.configSource(), "fresh-hit");
            }
        }
        return null;
    }

    private ResolveResult resolveCandidate(RegistrySource source, RegistryCandidate candidate, String provider) {
        CacheEntry cacheEntry = readCacheEntry(source, candidate);
        ModelConfigService.ModelSettings cachedSettings = null;

        if (cacheEntry != null && cacheEntry.isFound()) {
            cachedSettings = tryParseSettings(cacheEntry.getContent(), provider, candidate.relativePath());
            if (cachedSettings != null && isFresh(cacheEntry)) {
                return new ResolveResult(cachedSettings, candidate.configSource(), "fresh-hit");
            }
        }

        if (source == null) {
            if (cachedSettings != null) {
                return new ResolveResult(cachedSettings, candidate.configSource(), "stale-hit");
            }
            return null;
        }

        try {
            URI remoteUri = remoteFileUri(source, candidate.relativePath());
            String remoteText = fetchRemoteText(remoteUri);
            if (remoteText == null) {
                writeCacheEntry(source, candidate, new CacheEntry(now(), false, null));
                if (cachedSettings != null) {
                    return new ResolveResult(cachedSettings, candidate.configSource(), "stale-hit");
                }
                return null;
            }

            ModelConfigService.ModelSettings settings = tryParseSettings(remoteText, provider,
                    candidate.relativePath());
            if (settings == null) {
                if (cachedSettings != null) {
                    return new ResolveResult(cachedSettings, candidate.configSource(), "stale-hit");
                }
                return null;
            }

            writeCacheEntry(source, candidate, new CacheEntry(now(), true, remoteText));
            return new ResolveResult(settings, candidate.configSource(), "remote-hit");
        } catch (RuntimeException ex) {
            log.warn("[ModelRegistry] Failed to refresh {}: {}", candidate.relativePath(), ex.getMessage());
            if (cachedSettings != null) {
                return new ResolveResult(cachedSettings, candidate.configSource(), "stale-hit");
            }
            return null;
        }
    }

    private CacheEntry readCacheEntry(RegistrySource source, RegistryCandidate candidate) {
        if (source == null) {
            return null;
        }
        String cachePath = cachePath(source, candidate.relativePath());
        try {
            Boolean exists = storagePort.exists(CACHE_DIR, cachePath).join();
            if (!Boolean.TRUE.equals(exists)) {
                return null;
            }
            String json = storagePort.getText(CACHE_DIR, cachePath).join();
            if (json == null || json.isBlank()) {
                return null;
            }
            return OBJECT_MAPPER.readValue(json, CacheEntry.class);
        } catch (IOException | RuntimeException ex) { // NOSONAR
            log.warn("[ModelRegistry] Failed to read cache entry {}: {}", candidate.relativePath(), ex.getMessage());
            return null;
        }
    }

    private void writeCacheEntry(RegistrySource source, RegistryCandidate candidate, CacheEntry entry) {
        String cachePath = cachePath(source, candidate.relativePath());
        try {
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entry);
            storagePort.putTextAtomic(CACHE_DIR, cachePath, json, false).join();
        } catch (IOException | RuntimeException ex) { // NOSONAR
            log.warn("[ModelRegistry] Failed to write cache entry {}: {}", candidate.relativePath(), ex.getMessage());
        }
    }

    private ModelConfigService.ModelSettings tryParseSettings(String json, String provider, String sourcePath) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            ModelConfigService.ModelSettings settings = OBJECT_MAPPER.readValue(
                    json, ModelConfigService.ModelSettings.class);
            settings.setProvider(provider);
            return settings;
        } catch (IOException ex) {
            log.warn("[ModelRegistry] Invalid config for {}: {}", sourcePath, ex.getMessage());
            return null;
        }
    }

    private boolean isFresh(CacheEntry entry) {
        return entry.getCachedAt() != null && !entry.getCachedAt().plus(CACHE_TTL).isBefore(now());
    }

    private RegistrySource resolveSource() {
        RuntimeConfig runtimeConfig = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.ModelRegistryConfig config = runtimeConfig.getModelRegistry();
        String repositoryUrl = trimToNull(config != null ? config.getRepositoryUrl() : null);
        if (repositoryUrl == null) {
            return new RegistrySource(DEFAULT_REPOSITORY_URL, DEFAULT_BRANCH);
        }
        String branch = trimToNull(config != null ? config.getBranch() : null);
        return new RegistrySource(repositoryUrl, branch != null ? branch : DEFAULT_BRANCH);
    }

    private URI remoteFileUri(RegistrySource source, String filePath) {
        GitHubRepository repository = parseRemoteRepository(source.repositoryUrl());
        String relativePath = repository.owner() + "/" + repository.name() + "/" + encodePathSegment(source.branch())
                + "/" + encodeRelativePath(filePath);
        return repositoryRawBaseUrl().resolve(relativePath);
    }

    private URI repositoryRawBaseUrl() {
        return URI.create(DEFAULT_RAW_BASE_URL + "/");
    }

    private GitHubRepository parseRemoteRepository(String repositoryUrl) {
        URI url = URI.create(repositoryUrl.endsWith("/") ? repositoryUrl : repositoryUrl + "/");
        String path = url.getPath();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Model registry repository URL is invalid: " + url);
        }
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        String[] segments = normalized.split("/");
        if (segments.length < 3) {
            throw new IllegalStateException("Model registry repository URL must match <host>/<owner>/<repo>");
        }
        return new GitHubRepository(segments[segments.length - 2], segments[segments.length - 1]);
    }

    private String cachePath(RegistrySource source, String relativePath) {
        return CACHE_PREFIX + "/" + sha256Hex(source.repositoryUrl() + "\n" + source.branch()) + "/"
                + relativePath + ".cache.json";
    }

    private String encodeRelativePath(String value) {
        String[] segments = value.split("/");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(encodePathSegment(segments[i]));
        }
        return builder.toString();
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String normalizeModelId(String modelId) {
        String normalized = requireValue(modelId, "modelId");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        normalized = stripDatedSuffix(normalized);
        return requireValue(normalized, "modelId");
    }

    private String stripDatedSuffix(String modelId) {
        String monthYearStripped = stripMonthYearSuffix(modelId);
        if (!monthYearStripped.equals(modelId)) {
            return monthYearStripped;
        }
        String isoDateStripped = stripIsoDateSuffix(modelId);
        if (!isoDateStripped.equals(modelId)) {
            return isoDateStripped;
        }
        return stripCompactDateSuffix(modelId);
    }

    private String stripMonthYearSuffix(String modelId) {
        int yearDash = modelId.lastIndexOf('-');
        if (yearDash <= 0 || modelId.length() - yearDash - 1 != 4) {
            return modelId;
        }
        String yearPart = modelId.substring(yearDash + 1);
        if (!isDigits(yearPart)) {
            return modelId;
        }

        int monthDash = modelId.lastIndexOf('-', yearDash - 1);
        if (monthDash <= 0 || yearDash - monthDash - 1 != 2) {
            return modelId;
        }
        String monthPart = modelId.substring(monthDash + 1, yearDash);
        if (!isDigits(monthPart)) {
            return modelId;
        }

        int month = Integer.parseInt(monthPart);
        if (month < 1 || month > 12) {
            return modelId;
        }
        return modelId.substring(0, monthDash);
    }

    private String stripIsoDateSuffix(String modelId) {
        int dayDash = modelId.lastIndexOf('-');
        if (dayDash <= 0 || modelId.length() - dayDash - 1 != 2) {
            return modelId;
        }
        String dayPart = modelId.substring(dayDash + 1);
        if (!isDigits(dayPart)) {
            return modelId;
        }

        int monthDash = modelId.lastIndexOf('-', dayDash - 1);
        if (monthDash <= 0 || dayDash - monthDash - 1 != 2) {
            return modelId;
        }
        String monthPart = modelId.substring(monthDash + 1, dayDash);
        if (!isDigits(monthPart)) {
            return modelId;
        }

        int yearDash = modelId.lastIndexOf('-', monthDash - 1);
        if (yearDash <= 0 || monthDash - yearDash - 1 != 4) {
            return modelId;
        }
        String yearPart = modelId.substring(yearDash + 1, monthDash);
        if (!isDigits(yearPart)) {
            return modelId;
        }

        int month = Integer.parseInt(monthPart);
        int day = Integer.parseInt(dayPart);
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return modelId;
        }
        return modelId.substring(0, yearDash);
    }

    private String stripCompactDateSuffix(String modelId) {
        int dash = modelId.lastIndexOf('-');
        if (dash <= 0 || modelId.length() - dash - 1 != 8) {
            return modelId;
        }
        String compactDate = modelId.substring(dash + 1);
        if (!isDigits(compactDate)) {
            return modelId;
        }

        int month = Integer.parseInt(compactDate.substring(4, 6));
        int day = Integer.parseInt(compactDate.substring(6, 8));
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return modelId;
        }
        return modelId.substring(0, dash);
    }

    private boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String requireValue(String value, String fieldName) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
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

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                builder.append(String.format("%02x", current & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record ResolveResult(ModelConfigService.ModelSettings defaultSettings, String configSource,
            String cacheStatus) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CacheEntry {
        private Instant cachedAt;
        private boolean found;
        private String content;
    }

    private record RegistrySource(String repositoryUrl, String branch) {
    }

    private record RegistryCandidate(String configSource, String relativePath) {
    }

    private record GitHubRepository(String owner, String name) {
    }
}

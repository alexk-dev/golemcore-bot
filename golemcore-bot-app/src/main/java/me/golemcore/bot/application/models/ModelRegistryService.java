package me.golemcore.bot.application.models;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.port.outbound.ModelRegistryCachePort;
import me.golemcore.bot.port.outbound.ModelRegistryDocumentPort;
import me.golemcore.bot.port.outbound.ModelRegistryRemotePort;

@Slf4j
public class ModelRegistryService {

    private static final Duration CACHE_TTL = Duration.ofDays(1);
    private static final String DEFAULT_BRANCH = "main";
    private static final String DEFAULT_REPOSITORY_URL = "https://github.com/alexk-dev/golemcore-models";
    private static final String DEFAULT_RAW_BASE_URL = "https://raw.githubusercontent.com";

    private final RuntimeConfigService runtimeConfigService;
    private final ModelRegistryRemotePort modelRegistryRemotePort;
    private final ModelRegistryCachePort modelRegistryCachePort;
    private final ModelRegistryDocumentPort modelRegistryDocumentPort;

    public ModelRegistryService(
            RuntimeConfigService runtimeConfigService,
            ModelRegistryRemotePort modelRegistryRemotePort,
            ModelRegistryCachePort modelRegistryCachePort,
            ModelRegistryDocumentPort modelRegistryDocumentPort) {
        this.runtimeConfigService = runtimeConfigService;
        this.modelRegistryRemotePort = modelRegistryRemotePort;
        this.modelRegistryCachePort = modelRegistryCachePort;
        this.modelRegistryDocumentPort = modelRegistryDocumentPort;
    }

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
        return modelRegistryRemotePort.fetchText(uri);
    }

    private ResolveResult findFreshCachedResult(RegistrySource source, List<RegistryCandidate> candidates,
            String provider) {
        for (RegistryCandidate candidate : candidates) {
            ModelRegistryCachePort.CachedRegistryEntry cacheEntry = readCacheEntry(source, candidate);
            if (cacheEntry == null || !cacheEntry.found() || !isFresh(cacheEntry)) {
                continue;
            }
            ModelCatalogEntry settings = tryParseSettings(cacheEntry.content(), provider,
                    candidate.relativePath());
            if (settings != null) {
                return new ResolveResult(settings, candidate.configSource(), "fresh-hit");
            }
        }
        return null;
    }

    private ResolveResult resolveCandidate(RegistrySource source, RegistryCandidate candidate, String provider) {
        ModelRegistryCachePort.CachedRegistryEntry cacheEntry = readCacheEntry(source, candidate);
        ModelCatalogEntry cachedSettings = null;

        if (cacheEntry != null && cacheEntry.found()) {
            cachedSettings = tryParseSettings(cacheEntry.content(), provider, candidate.relativePath());
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
                writeCacheEntry(source, candidate, new ModelRegistryCachePort.CachedRegistryEntry(now(), false, null));
                if (cachedSettings != null) {
                    return new ResolveResult(cachedSettings, candidate.configSource(), "stale-hit");
                }
                return null;
            }

            ModelCatalogEntry settings = tryParseSettings(remoteText, provider,
                    candidate.relativePath());
            if (settings == null) {
                if (cachedSettings != null) {
                    return new ResolveResult(cachedSettings, candidate.configSource(), "stale-hit");
                }
                return null;
            }

            writeCacheEntry(source, candidate, new ModelRegistryCachePort.CachedRegistryEntry(now(), true, remoteText));
            return new ResolveResult(settings, candidate.configSource(), "remote-hit");
        } catch (RuntimeException ex) {
            log.warn("[ModelRegistry] Failed to refresh {}: {}", candidate.relativePath(), ex.getMessage());
            if (cachedSettings != null) {
                return new ResolveResult(cachedSettings, candidate.configSource(), "stale-hit");
            }
            return null;
        }
    }

    private ModelRegistryCachePort.CachedRegistryEntry readCacheEntry(RegistrySource source,
            RegistryCandidate candidate) {
        if (source == null) {
            return null;
        }
        try {
            return modelRegistryCachePort.read(source.repositoryUrl(), source.branch(), candidate.relativePath());
        } catch (RuntimeException ex) { // NOSONAR
            log.warn("[ModelRegistry] Failed to read cache entry {}: {}", candidate.relativePath(), ex.getMessage());
            return null;
        }
    }

    private void writeCacheEntry(
            RegistrySource source,
            RegistryCandidate candidate,
            ModelRegistryCachePort.CachedRegistryEntry entry) {
        try {
            modelRegistryCachePort.write(source.repositoryUrl(), source.branch(), candidate.relativePath(), entry);
        } catch (RuntimeException ex) { // NOSONAR
            log.warn("[ModelRegistry] Failed to write cache entry {}: {}", candidate.relativePath(), ex.getMessage());
        }
    }

    private ModelCatalogEntry tryParseSettings(String json, String provider, String sourcePath) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            ModelCatalogEntry settings = modelRegistryDocumentPort.parseCatalogEntry(json);
            return settings.withProvider(provider);
        } catch (RuntimeException ex) {
            log.warn("[ModelRegistry] Invalid config for {}: {}", sourcePath, ex.getMessage());
            return null;
        }
    }

    private boolean isFresh(ModelRegistryCachePort.CachedRegistryEntry entry) {
        return entry.cachedAt() != null && !entry.cachedAt().plus(CACHE_TTL).isBefore(now());
    }

    private RegistrySource resolveSource() {
        RuntimeConfig runtimeConfig = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.ModelRegistryConfig config = runtimeConfig.getModelRegistry();
        if (config == null) {
            return new RegistrySource(DEFAULT_REPOSITORY_URL, DEFAULT_BRANCH);
        }
        String repositoryUrl = trimToNull(config.getRepositoryUrl());
        if (repositoryUrl == null) {
            return new RegistrySource(DEFAULT_REPOSITORY_URL, DEFAULT_BRANCH);
        }
        String branch = trimToNull(config.getBranch());
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

    public record ResolveResult(ModelCatalogEntry defaultCatalogEntry, String configSource,
            String cacheStatus) {

        public ModelCatalogEntry defaultSettings() {
            return defaultCatalogEntry;
        }
    }

    private record RegistrySource(String repositoryUrl, String branch) {
    }

    private record RegistryCandidate(String configSource, String relativePath) {
    }

    private record GitHubRepository(String owner, String name) {
    }
}

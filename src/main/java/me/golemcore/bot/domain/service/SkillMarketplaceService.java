package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillInstallResult;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import me.golemcore.bot.domain.model.SkillMarketplaceItem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads skill marketplace metadata and installs skill files into runtime
 * storage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillMarketplaceService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String GITHUB_USER_AGENT = "golemcore-bot-skill-marketplace";
    private static final String SKILLS_DIR = "skills";
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String DEFAULT_REPOSITORY_URL = "https://github.com/alexk-dev/golemcore-skills";
    private static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    private static final String DEFAULT_RAW_BASE_URL = "https://raw.githubusercontent.com";
    private static final String DEFAULT_BRANCH = "main";
    private static final String DESCRIPTION_KEY = "description";
    private static final String MODEL_TIER_KEY = "model_tier";
    private static final String SUPPRESS_UNCHECKED = "unchecked";
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$",
            Pattern.DOTALL);

    private final BotProperties botProperties;
    private final StoragePort storagePort;
    private final SkillService skillService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Object remoteCatalogLock = new Object();
    private final AtomicReference<RemoteCatalogCache> remoteCatalogCache = new AtomicReference<>();

    public SkillMarketplaceCatalog getCatalog() {
        if (!botProperties.getSkills().isMarketplaceEnabled()) {
            return unavailable("Skill marketplace is disabled by backend configuration.");
        }

        Map<String, RemoteSkillEntry> remoteEntries;
        try {
            remoteEntries = loadRemoteSkillEntries();
        } catch (RuntimeException ex) {
            log.warn("[Skills] Failed to load marketplace catalog: {}", ex.getMessage());
            return unavailable("Skill marketplace metadata could not be loaded from " + repositoryUrl());
        }

        Map<String, Skill> installedByName = new LinkedHashMap<>();
        for (Skill skill : skillService.getAllSkills()) {
            installedByName.put(skill.getName(), skill);
        }

        List<SkillMarketplaceItem> items = remoteEntries.values().stream()
                .map(entry -> toMarketplaceItem(entry, installedByName.get(entry.id())))
                .sorted(Comparator
                        .comparing(SkillMarketplaceItem::isUpdateAvailable).reversed()
                        .thenComparing(SkillMarketplaceItem::isInstalled).reversed()
                        .thenComparing(SkillMarketplaceItem::getName))
                .toList();

        return SkillMarketplaceCatalog.builder()
                .available(true)
                .sourceDirectory(repositoryUrl().toString())
                .items(items)
                .build();
    }

    public SkillInstallResult install(String skillId) {
        String normalizedSkillId = normalizeSkillId(skillId);
        if (normalizedSkillId == null) {
            throw new IllegalArgumentException("skillId is required");
        }
        if (!botProperties.getSkills().isMarketplaceEnabled()) {
            throw new IllegalStateException("Skill marketplace is disabled");
        }

        Map<String, RemoteSkillEntry> entries = loadRemoteSkillEntries();
        RemoteSkillEntry entry = entries.get(normalizedSkillId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown skill: " + normalizedSkillId);
        }

        Optional<Skill> existingSkill = skillService.findByName(normalizedSkillId);

        String content = fetchRemoteSkillContent(entry.path());
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Skill content is empty for " + normalizedSkillId);
        }

        String targetPath = normalizedSkillId + "/" + SKILL_FILE_NAME;
        storagePort.putText(SKILLS_DIR, targetPath, content).join();
        skillService.reload();

        Skill installedSkill = skillService.findByName(normalizedSkillId).orElse(null);
        SkillMarketplaceItem installedItem = toMarketplaceItem(entry, installedSkill);

        String status = resolveInstallStatus(existingSkill.orElse(null), entry);
        String message = switch (status) {
        case "updated" -> "Skill '" + normalizedSkillId + "' updated from marketplace.";
        case "already-installed" -> "Skill '" + normalizedSkillId + "' is already up to date.";
        default -> "Skill '" + normalizedSkillId + "' installed from marketplace.";
        };

        return new SkillInstallResult(status, message, installedItem);
    }

    private String resolveInstallStatus(Skill existingSkill, RemoteSkillEntry entry) {
        if (existingSkill == null) {
            return "installed";
        }

        String existingDescription = trimToNull(existingSkill.getDescription());
        String existingModelTier = trimToNull(existingSkill.getModelTier());
        String remoteDescription = trimToNull(entry.description());
        String remoteModelTier = trimToNull(entry.modelTier());

        boolean descriptionChanged = existingDescription == null
                ? remoteDescription != null
                : !existingDescription.equals(remoteDescription);
        boolean modelTierChanged = existingModelTier == null
                ? remoteModelTier != null
                : !existingModelTier.equals(remoteModelTier);

        return descriptionChanged || modelTierChanged ? "updated" : "already-installed";
    }

    private SkillMarketplaceItem toMarketplaceItem(RemoteSkillEntry entry, Skill installedSkill) {
        String remoteDescription = trimToNull(entry.description());
        String installedDescription = installedSkill != null ? trimToNull(installedSkill.getDescription()) : null;

        String remoteModelTier = trimToNull(entry.modelTier());
        String installedModelTier = installedSkill != null ? trimToNull(installedSkill.getModelTier()) : null;

        String description = remoteDescription != null ? remoteDescription : installedDescription;
        String modelTier = remoteModelTier != null ? remoteModelTier : installedModelTier;

        boolean installed = installedSkill != null;
        boolean descriptionChanged = installedDescription == null
                ? remoteDescription != null
                : !installedDescription.equals(remoteDescription);
        boolean modelTierChanged = installedModelTier == null
                ? remoteModelTier != null
                : !installedModelTier.equals(remoteModelTier);
        boolean updateAvailable = installed && (descriptionChanged || modelTierChanged);

        return SkillMarketplaceItem.builder()
                .id(entry.id())
                .name(entry.id())
                .description(description)
                .modelTier(modelTier)
                .sourcePath(entry.path())
                .installed(installed)
                .updateAvailable(updateAvailable)
                .build();
    }

    private Map<String, RemoteSkillEntry> loadRemoteSkillEntries() {
        Duration cacheTtl = botProperties.getSkills().getMarketplaceRemoteCacheTtl();
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            return fetchRemoteSkillEntries();
        }

        RemoteCatalogCache currentCache = remoteCatalogCache.get();
        Instant now = Instant.now();
        if (currentCache != null && now.isBefore(currentCache.loadedAt().plus(cacheTtl))) {
            return currentCache.entries();
        }

        synchronized (remoteCatalogLock) {
            RemoteCatalogCache lockedCache = remoteCatalogCache.get();
            Instant refreshedNow = Instant.now();
            if (lockedCache != null && refreshedNow.isBefore(lockedCache.loadedAt().plus(cacheTtl))) {
                return lockedCache.entries();
            }
            Map<String, RemoteSkillEntry> loaded = fetchRemoteSkillEntries();
            remoteCatalogCache.set(new RemoteCatalogCache(refreshedNow, loaded));
            return loaded;
        }
    }

    private Map<String, RemoteSkillEntry> fetchRemoteSkillEntries() {
        RemoteRepositoryTree tree = fetchRemoteRepositoryTree();
        Map<String, RemoteSkillEntry> result = new LinkedHashMap<>();

        for (String path : tree.skillPaths()) {
            String skillId = extractSkillId(path);
            if (skillId == null) {
                continue;
            }
            try {
                String content = fetchRemoteSkillContent(path);
                Map<String, String> frontmatter = parseFrontmatter(content);
                RemoteSkillEntry entry = new RemoteSkillEntry(
                        skillId,
                        path,
                        trimToNull(frontmatter.get(DESCRIPTION_KEY)),
                        trimToNull(frontmatter.get(MODEL_TIER_KEY)));
                result.put(skillId, entry);
            } catch (RuntimeException ex) {
                log.warn("[Skills] Failed to read remote skill {}: {}", path, ex.getMessage());
            }
        }

        return Map.copyOf(result);
    }

    private RemoteRepositoryTree fetchRemoteRepositoryTree() {
        URI treeUri = remoteTreeApiUri();
        String responseBody = fetchText(treeUri);
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON_MAPPER.readTree(responseBody);
            List<String> skillPaths = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode treeNode = root.path("tree");
            if (!treeNode.isArray()) {
                return new RemoteRepositoryTree(List.of());
            }

            for (com.fasterxml.jackson.databind.JsonNode entry : treeNode) {
                String type = entry.path("type").asText("");
                String path = entry.path("path").asText("");
                if (!"blob".equals(type) || path.isBlank()) {
                    continue;
                }
                if (path.endsWith("/" + SKILL_FILE_NAME) || path.equals(SKILL_FILE_NAME)) {
                    skillPaths.add(path);
                }
            }

            skillPaths.sort(String::compareTo);
            return new RemoteRepositoryTree(List.copyOf(skillPaths));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse skill marketplace tree metadata", ex);
        }
    }

    private String fetchRemoteSkillContent(String path) {
        URI uri = remoteRawFileUri(path);
        return fetchText(uri);
    }

    private String fetchText(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", GITHUB_USER_AGENT)
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading skill marketplace metadata from " + uri, ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load skill marketplace metadata from " + uri, ex);
        }

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("Skill marketplace request failed with HTTP " + statusCode + " for " + uri);
        }
        return response.body();
    }

    private Map<String, String> parseFrontmatter(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }

        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.matches()) {
            return Map.of();
        }

        String frontmatter = matcher.group(1);
        try {
            @SuppressWarnings(SUPPRESS_UNCHECKED)
            Map<String, Object> yaml = YAML_MAPPER.readValue(frontmatter, Map.class);
            if (yaml == null || yaml.isEmpty()) {
                return Map.of();
            }

            Map<String, String> result = new LinkedHashMap<>();
            Object description = yaml.get(DESCRIPTION_KEY);
            if (description != null) {
                result.put(DESCRIPTION_KEY, description.toString());
            }
            Object modelTier = yaml.get(MODEL_TIER_KEY);
            if (modelTier != null) {
                result.put(MODEL_TIER_KEY, modelTier.toString());
            }
            return result;
        } catch (IOException | RuntimeException ex) {
            log.debug("[Skills] Failed to parse remote skill frontmatter", ex);
            return Map.of();
        }
    }

    private String extractSkillId(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalizedPath = path.trim();
        if (!normalizedPath.endsWith("/" + SKILL_FILE_NAME) && !normalizedPath.equals(SKILL_FILE_NAME)) {
            return null;
        }

        String[] parts = normalizedPath.split("/");
        if (parts.length != 2) {
            return null;
        }

        String candidate = parts[0];
        return normalizeSkillId(candidate);
    }

    private String normalizeSkillId(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        String normalized = skillId.trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            boolean alphanumeric = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
            boolean hyphen = ch == '-';
            if (index == 0 && !alphanumeric) {
                throw new IllegalArgumentException("Skill id must start with [a-z0-9]");
            }
            if (!alphanumeric && !hyphen) {
                throw new IllegalArgumentException("Skill id must contain only [a-z0-9-]");
            }
        }
        return normalized;
    }

    private URI repositoryUrl() {
        String configured = botProperties.getSkills().getMarketplaceRepositoryUrl();
        String value = configured != null && !configured.isBlank() ? configured : DEFAULT_REPOSITORY_URL;
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private String repositoryBranch() {
        String configured = botProperties.getSkills().getMarketplaceBranch();
        return configured != null && !configured.isBlank() ? configured : DEFAULT_BRANCH;
    }

    private URI repositoryApiBaseUrl() {
        String configured = botProperties.getSkills().getMarketplaceApiBaseUrl();
        String value = configured != null && !configured.isBlank() ? configured : DEFAULT_API_BASE_URL;
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private URI repositoryRawBaseUrl() {
        String configured = botProperties.getSkills().getMarketplaceRawBaseUrl();
        String value = configured != null && !configured.isBlank() ? configured : DEFAULT_RAW_BASE_URL;
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private URI remoteTreeApiUri() {
        GitHubRepository repository = parseRemoteRepository();
        String encodedBranch = encodePathSegment(repositoryBranch());
        String relativePath = "repos/" + repository.owner() + "/" + repository.name()
                + "/git/trees/" + encodedBranch + "?recursive=1";
        return repositoryApiBaseUrl().resolve(relativePath);
    }

    private URI remoteRawFileUri(String filePath) {
        GitHubRepository repository = parseRemoteRepository();
        String relativePath = repository.owner() + "/" + repository.name() + "/"
                + repositoryBranch() + "/" + filePath;
        return repositoryRawBaseUrl().resolve(relativePath);
    }

    private GitHubRepository parseRemoteRepository() {
        URI url = repositoryUrl();
        String path = url.getPath();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Skill marketplace repository URL is invalid: " + url);
        }
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        String[] segments = normalized.split("/");
        if (segments.length < 3) {
            throw new IllegalStateException("Skill marketplace repository URL must match <host>/<owner>/<repo>");
        }
        String owner = segments[segments.length - 2];
        String name = segments[segments.length - 1];
        return new GitHubRepository(owner, name);
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private SkillMarketplaceCatalog unavailable(String message) {
        return SkillMarketplaceCatalog.builder()
                .available(false)
                .message(message)
                .items(List.of())
                .build();
    }

    private record RemoteCatalogCache(Instant loadedAt, Map<String, RemoteSkillEntry> entries) {
    }

    private record RemoteRepositoryTree(List<String> skillPaths) {
    }

    private record RemoteSkillEntry(String id, String path, String description, String modelTier) {
    }

    private record GitHubRepository(String owner, String name) {
    }
}

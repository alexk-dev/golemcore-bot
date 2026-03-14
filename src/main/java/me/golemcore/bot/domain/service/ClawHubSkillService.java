package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.ClawHubInstallResult;
import me.golemcore.bot.domain.model.ClawHubSkillCatalog;
import me.golemcore.bot.domain.model.ClawHubSkillItem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads public skills from ClawHub and installs them into the local skills
 * workspace.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClawHubSkillService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$",
            Pattern.DOTALL);
    private static final Pattern VALID_SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final String SKILLS_DIR = "skills";
    private static final String CLAWHUB_DIR = "clawhub";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String INSTALL_METADATA_FILE = ".clawhub-install.json";
    private static final String DEFAULT_BASE_URL = "https://clawhub.ai";
    private static final String USER_AGENT = "golemcore-bot-clawhub";

    private final BotProperties botProperties;
    private final StoragePort storagePort;
    private final SkillService skillService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ClawHubSkillCatalog getCatalog(String query, Integer limit) {
        if (!botProperties.getSkills().isClawHubEnabled()) {
            return unavailable("ClawHub integration is disabled by backend configuration.");
        }

        try {
            String trimmedQuery = trimToNull(query);
            int effectiveLimit = normalizeLimit(limit);
            Map<String, InstalledSkillMetadata> installed = loadInstalledSkills();
            List<ClawHubSkillItem> items = trimmedQuery != null
                    ? searchSkills(trimmedQuery, effectiveLimit, installed)
                    : listLatestSkills(effectiveLimit, installed);
            return ClawHubSkillCatalog.builder()
                    .available(true)
                    .siteUrl(baseUrl().toString())
                    .items(items)
                    .build();
        } catch (RuntimeException ex) {
            log.warn("[ClawHub] Failed to load catalog: {}", ex.getMessage());
            return unavailable(ex.getMessage());
        }
    }

    public ClawHubInstallResult install(String slug, String version) {
        if (!botProperties.getSkills().isClawHubEnabled()) {
            throw new IllegalStateException("ClawHub integration is disabled");
        }

        String normalizedSlug = normalizeSlug(slug);
        SkillVersionMetadata versionMetadata = resolveVersion(normalizedSlug, version);
        String installPath = installBasePath(normalizedSlug);
        Map<String, InstalledSkillMetadata> installed = loadInstalledSkills();
        InstalledSkillMetadata existing = installed.get(normalizedSlug);

        deleteInstalledSkill(normalizedSlug);

        byte[] zipBytes = fetchBytes(downloadUri(normalizedSlug, versionMetadata.version()));
        List<ZipEntryPayload> payloads = prepareZipPayloads(normalizedSlug, zipBytes);
        for (ZipEntryPayload payload : payloads) {
            storagePort.putObject(SKILLS_DIR, installPath + "/" + payload.path(), payload.content()).join();
        }

        InstalledSkillMetadata metadata = new InstalledSkillMetadata(
                normalizedSlug,
                versionMetadata.version(),
                baseUrl().toString(),
                runtimeName(normalizedSlug),
                sha256Hex(zipBytes),
                Instant.now());
        writeInstalledMetadata(normalizedSlug, metadata);

        skillService.reload();

        String status = resolveStatus(existing, metadata);
        String message = switch (status) {
        case "updated" -> "ClawHub skill '" + normalizedSlug + "' updated.";
        case "already-installed" -> "ClawHub skill '" + normalizedSlug + "' is already up to date.";
        default -> "ClawHub skill '" + normalizedSlug + "' installed.";
        };

        return new ClawHubInstallResult(status, message, ClawHubSkillItem.builder()
                .slug(normalizedSlug)
                .displayName(versionMetadata.displayName())
                .summary(versionMetadata.summary())
                .version(versionMetadata.version())
                .installed(true)
                .installedVersion(versionMetadata.version())
                .runtimeName(runtimeName(normalizedSlug))
                .build());
    }

    private List<ClawHubSkillItem> searchSkills(
            String query,
            int limit,
            Map<String, InstalledSkillMetadata> installed) {
        URI uri = baseUrl().resolve("/api/v1/search?q=" + encode(query));
        JsonNode root = fetchJson(uri);
        List<ClawHubSkillItem> items = new ArrayList<>();
        for (JsonNode result : root.path("results")) {
            if (items.size() >= limit) {
                break;
            }
            String slug = trimToNull(result.path("slug").asText(null));
            if (slug == null || !VALID_SLUG.matcher(slug).matches()) {
                continue;
            }
            InstalledSkillMetadata installedMetadata = installed.get(slug);
            items.add(ClawHubSkillItem.builder()
                    .slug(slug)
                    .displayName(firstNonBlank(
                            trimToNull(result.path("displayName").asText(null)),
                            slug))
                    .summary(trimToNull(result.path("summary").asText(null)))
                    .version(trimToNull(result.path("version").asText(null)))
                    .updatedAt(result.path("updatedAt").isNumber() ? result.path("updatedAt").asLong() : null)
                    .installed(installedMetadata != null)
                    .installedVersion(installedMetadata != null ? installedMetadata.version() : null)
                    .runtimeName(runtimeName(slug))
                    .build());
        }
        return List.copyOf(items);
    }

    private List<ClawHubSkillItem> listLatestSkills(
            int limit,
            Map<String, InstalledSkillMetadata> installed) {
        URI uri = baseUrl().resolve("/api/v1/skills?limit=" + limit + "&sort=updated");
        JsonNode root = fetchJson(uri);
        List<ClawHubSkillItem> items = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            String slug = trimToNull(item.path("slug").asText(null));
            if (slug == null || !VALID_SLUG.matcher(slug).matches()) {
                continue;
            }
            JsonNode latestVersion = item.path("latestVersion");
            InstalledSkillMetadata installedMetadata = installed.get(slug);
            items.add(ClawHubSkillItem.builder()
                    .slug(slug)
                    .displayName(firstNonBlank(
                            trimToNull(item.path("displayName").asText(null)),
                            slug))
                    .summary(trimToNull(item.path("summary").asText(null)))
                    .version(trimToNull(latestVersion.path("version").asText(null)))
                    .updatedAt(item.path("updatedAt").isNumber() ? item.path("updatedAt").asLong() : null)
                    .installed(installedMetadata != null)
                    .installedVersion(installedMetadata != null ? installedMetadata.version() : null)
                    .runtimeName(runtimeName(slug))
                    .build());
        }
        items.sort(Comparator.comparing(ClawHubSkillItem::getUpdatedAt, Comparator.nullsLast(Long::compareTo))
                .reversed());
        return List.copyOf(items);
    }

    private SkillVersionMetadata resolveVersion(String slug, String requestedVersion) {
        JsonNode root = fetchJson(skillUri(slug));
        if (root.path("skill").isNull() || root.path("skill").isMissingNode()) {
            throw new IllegalArgumentException("Unknown ClawHub skill: " + slug);
        }
        String resolvedVersion = trimToNull(requestedVersion);
        if (resolvedVersion == null) {
            resolvedVersion = trimToNull(root.path("latestVersion").path("version").asText(null));
        }
        if (resolvedVersion == null) {
            throw new IllegalStateException("ClawHub skill '" + slug + "' has no resolvable version");
        }
        return new SkillVersionMetadata(
                resolvedVersion,
                trimToNull(root.path("skill").path("displayName").asText(null)),
                trimToNull(root.path("skill").path("summary").asText(null)));
    }

    private List<ZipEntryPayload> prepareZipPayloads(String slug, byte[] zipBytes) {
        List<ZipEntryPayload> rawEntries = readZipEntries(zipBytes);
        if (rawEntries.isEmpty()) {
            throw new IllegalStateException("Downloaded ClawHub archive is empty");
        }

        String commonPrefix = resolveCommonTopLevelPrefix(rawEntries);
        List<ZipEntryPayload> normalizedEntries = rawEntries.stream()
                .map(entry -> new ZipEntryPayload(stripPrefix(entry.path(), commonPrefix), entry.content()))
                .toList();

        long skillFileCount = normalizedEntries.stream()
                .filter(entry -> SKILL_FILE.equals(entry.path()))
                .count();
        if (skillFileCount != 1) {
            throw new IllegalStateException("ClawHub archive must contain exactly one root SKILL.md");
        }

        List<ZipEntryPayload> payloads = new ArrayList<>();
        for (ZipEntryPayload entry : normalizedEntries) {
            if (entry.path().isBlank()) {
                continue;
            }
            if (SKILL_FILE.equals(entry.path())) {
                String rewritten = rewriteSkillName(new String(entry.content(), StandardCharsets.UTF_8),
                        runtimeName(slug));
                payloads.add(new ZipEntryPayload(entry.path(), rewritten.getBytes(StandardCharsets.UTF_8)));
            } else {
                payloads.add(entry);
            }
        }
        return List.copyOf(payloads);
    }

    private List<ZipEntryPayload> readZipEntries(byte[] zipBytes) {
        try (ZipInputStream inputStream = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            List<ZipEntryPayload> entries = new ArrayList<>();
            ZipEntry entry;
            while ((entry = inputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = sanitizeZipPath(entry.getName());
                if (path == null) {
                    continue;
                }
                entries.add(new ZipEntryPayload(path, inputStream.readAllBytes()));
            }
            return List.copyOf(entries);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to extract downloaded ClawHub archive", ex);
        }
    }

    private String rewriteSkillName(String content, String runtimeName) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content == null ? "" : content);
        Map<String, Object> metadata;
        String body;
        if (matcher.matches()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> yaml = YAML_MAPPER.readValue(matcher.group(1), LinkedHashMap.class);
                metadata = yaml != null ? yaml : new LinkedHashMap<>();
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to parse ClawHub SKILL.md frontmatter", ex);
            }
            body = matcher.group(2);
        } else {
            metadata = new LinkedHashMap<>();
            body = content == null ? "" : content;
        }
        metadata.put("name", runtimeName);
        try {
            String yaml = YAML_MAPPER.writeValueAsString(metadata).stripTrailing();
            String normalizedBody = body == null ? "" : body.stripTrailing();
            if (normalizedBody.isEmpty()) {
                return "---\n" + yaml + "\n---\n";
            }
            return "---\n" + yaml + "\n---\n\n" + normalizedBody + "\n";
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to rewrite ClawHub SKILL.md", ex);
        }
    }

    private Map<String, InstalledSkillMetadata> loadInstalledSkills() {
        List<String> keys = storagePort.listObjects(SKILLS_DIR, CLAWHUB_DIR).join();
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, InstalledSkillMetadata> installed = new LinkedHashMap<>();
        for (String key : keys) {
            if (!key.endsWith("/" + INSTALL_METADATA_FILE)) {
                continue;
            }
            try {
                String json = storagePort.getText(SKILLS_DIR, key).join();
                if (json == null || json.isBlank()) {
                    continue;
                }
                InstalledSkillMetadata metadata = JSON_MAPPER.readValue(json, InstalledSkillMetadata.class);
                if (metadata.slug() != null) {
                    installed.put(metadata.slug(), metadata);
                }
            } catch (IOException | RuntimeException ex) {
                log.warn("[ClawHub] Failed to read installed skill metadata {}: {}", key, ex.getMessage());
            }
        }
        return Map.copyOf(installed);
    }

    private void writeInstalledMetadata(String slug, InstalledSkillMetadata metadata) {
        try {
            String json = JSON_MAPPER.writeValueAsString(metadata);
            storagePort.putText(SKILLS_DIR, installBasePath(slug) + "/" + INSTALL_METADATA_FILE, json).join();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write ClawHub install metadata for " + slug, ex);
        }
    }

    private void deleteInstalledSkill(String slug) {
        String prefix = installBasePath(slug);
        List<String> keys = storagePort.listObjects(SKILLS_DIR, prefix).join();
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            storagePort.deleteObject(SKILLS_DIR, key).join();
        }
    }

    private String resolveStatus(InstalledSkillMetadata existing, InstalledSkillMetadata metadata) {
        if (existing == null) {
            return "installed";
        }
        if (Objects.equals(trimToNull(existing.version()), trimToNull(metadata.version()))
                && Objects.equals(trimToNull(existing.contentHash()), trimToNull(metadata.contentHash()))) {
            return "already-installed";
        }
        return "updated";
    }

    private ClawHubSkillCatalog unavailable(String message) {
        return ClawHubSkillCatalog.builder()
                .available(false)
                .message(message)
                .siteUrl(baseUrl().toString())
                .items(List.of())
                .build();
    }

    private URI baseUrl() {
        String configured = trimToNull(botProperties.getSkills().getClawHubBaseUrl());
        String value = configured != null ? configured : DEFAULT_BASE_URL;
        return URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
    }

    private URI skillUri(String slug) {
        return baseUrl().resolve("/api/v1/skills/" + encode(slug));
    }

    private URI downloadUri(String slug, String version) {
        String path = "/api/v1/download?slug=" + encode(slug) + "&version=" + encode(version);
        return baseUrl().resolve(path);
    }

    private JsonNode fetchJson(URI uri) {
        try {
            return JSON_MAPPER.readTree(fetchBytes(uri));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse ClawHub response from " + uri, ex);
        }
    }

    private byte[] fetchBytes(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .header("User-Agent", USER_AGENT)
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading ClawHub resource " + uri, ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load ClawHub resource " + uri, ex);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = new String(response.body(), StandardCharsets.UTF_8);
            throw new IllegalStateException("ClawHub request failed with HTTP " + response.statusCode()
                    + " for " + uri + (body.isBlank() ? "" : ": " + body));
        }
        return response.body();
    }

    private int normalizeLimit(Integer value) {
        if (value == null) {
            return 24;
        }
        return Math.max(1, Math.min(50, value));
    }

    private String installBasePath(String slug) {
        return CLAWHUB_DIR + "/" + slug;
    }

    private String runtimeName(String slug) {
        return CLAWHUB_DIR + "/" + slug;
    }

    private String normalizeSlug(String slug) {
        String normalized = trimToNull(slug);
        if (normalized == null || !VALID_SLUG.matcher(normalized).matches()) {
            throw new IllegalArgumentException("ClawHub slug must match [a-z0-9][a-z0-9-]*");
        }
        return normalized;
    }

    private String sanitizeZipPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String normalized = rawPath.replace('\\', '/').replaceAll("^/+", "").replaceAll("^\\./+", "");
        if (normalized.isBlank() || normalized.endsWith("/")) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                return null;
            }
            segments.add(segment);
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    private String resolveCommonTopLevelPrefix(List<ZipEntryPayload> entries) {
        String candidate = null;
        for (ZipEntryPayload entry : entries) {
            int slashIndex = entry.path().indexOf('/');
            if (slashIndex <= 0) {
                return null;
            }
            String segment = entry.path().substring(0, slashIndex);
            if (candidate == null) {
                candidate = segment;
                continue;
            }
            if (!candidate.equals(segment)) {
                return null;
            }
        }
        return candidate;
    }

    private String stripPrefix(String path, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return path;
        }
        String expectedPrefix = prefix + "/";
        return path.startsWith(expectedPrefix) ? path.substring(expectedPrefix.length()) : path;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value);
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                builder.append(String.format("%02x", current & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record ZipEntryPayload(String path, byte[] content) {
    }

    private record SkillVersionMetadata(String version, String displayName, String summary) {
    }

    private record InstalledSkillMetadata(
            String slug,
            String version,
            String registryUrl,
            String runtimeName,
            String contentHash,
            Instant installedAt) {
    }
}

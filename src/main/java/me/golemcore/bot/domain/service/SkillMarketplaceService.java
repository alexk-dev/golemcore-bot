package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads skill marketplace metadata from a local registry directory or a remote
 * GitHub repository and installs skill artifacts into runtime storage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillMarketplaceService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String GITHUB_USER_AGENT = "golemcore-bot-skill-marketplace";
    private static final String SKILLS_DIR = "skills";
    private static final String REGISTRY_DIR = "registry";
    private static final String MARKETPLACE_DIR = "marketplace";
    private static final String MANIFEST_FILE = "artifact.yaml";
    private static final String MAINTAINER_FILE = "maintainer.yaml";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String INSTALL_METADATA_FILE = ".marketplace-install.json";
    private static final String SOURCE_REPOSITORY = "repository";
    private static final String SOURCE_DIRECTORY = "directory";
    private static final String SOURCE_SANDBOX = "sandbox";
    private static final String TYPE_SKILL = "skill";
    private static final String TYPE_PACK = "pack";
    private static final String DEFAULT_REPOSITORY_URL = "https://github.com/alexk-dev/golemcore-skills";
    private static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    private static final String DEFAULT_RAW_BASE_URL = "https://raw.githubusercontent.com";
    private static final String DEFAULT_BRANCH = "main";
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$",
            Pattern.DOTALL);

    private final BotProperties botProperties;
    private final StoragePort storagePort;
    private final SkillService skillService;
    private final RuntimeConfigService runtimeConfigService;
    private final WorkspacePathService workspacePathService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Object remoteCatalogLock = new Object();
    private final AtomicReference<RemoteCatalogCache> remoteCatalogCache = new AtomicReference<>();

    public SkillMarketplaceCatalog getCatalog() {
        if (!botProperties.getSkills().isMarketplaceEnabled()) {
            return unavailable("Skill marketplace is disabled by backend configuration.");
        }

        try {
            MarketplaceSource source = resolveMarketplaceSource();
            Map<String, RegistryArtifact> artifacts = loadArtifacts(source);
            Map<String, InstalledArtifactMetadata> installed = loadInstalledArtifacts();

            List<SkillMarketplaceItem> items = artifacts.values().stream()
                    .map(artifact -> toMarketplaceItem(artifact, installed.get(artifact.artifactRef())))
                    .sorted(Comparator
                            .comparing(SkillMarketplaceItem::isUpdateAvailable).reversed()
                            .thenComparing(SkillMarketplaceItem::isInstalled).reversed()
                            .thenComparing(SkillMarketplaceItem::getMaintainer, Comparator.nullsLast(String::compareTo))
                            .thenComparing(SkillMarketplaceItem::getArtifactId))
                    .toList();

            return SkillMarketplaceCatalog.builder()
                    .available(true)
                    .sourceType(source.type())
                    .sourceDirectory(source.displayValue())
                    .items(items)
                    .build();
        } catch (RuntimeException ex) {
            log.warn("[Skills] Failed to load marketplace catalog: {}", ex.getMessage());
            return unavailable(ex.getMessage());
        }
    }

    public SkillInstallResult install(String skillId) {
        String artifactRef = normalizeArtifactRef(skillId);
        if (artifactRef == null) {
            throw new IllegalArgumentException("skillId is required");
        }
        if (!botProperties.getSkills().isMarketplaceEnabled()) {
            throw new IllegalStateException("Skill marketplace is disabled");
        }

        MarketplaceSource source = resolveMarketplaceSource();
        Map<String, RegistryArtifact> artifacts = loadArtifacts(source);
        RegistryArtifact artifact = artifacts.get(artifactRef);
        if (artifact == null) {
            throw new IllegalArgumentException("Unknown skill artifact: " + artifactRef);
        }

        Map<String, InstalledArtifactMetadata> installed = loadInstalledArtifacts();
        InstalledArtifactMetadata existing = installed.get(artifactRef);

        List<InstalledSkillPayload> payloads = buildInstalledSkillPayloads(source, artifact);
        String installBasePath = installBasePath(artifact);
        String artifactHash = artifact.contentHash();
        String installedContentHash = buildInstalledContentHash(payloads);

        deleteInstalledArtifact(installBasePath);
        for (InstalledSkillPayload payload : payloads) {
            storagePort.putText(SKILLS_DIR, payload.storagePath(), payload.content()).join();
        }

        InstalledArtifactMetadata metadata = new InstalledArtifactMetadata(
                artifact.artifactRef(),
                artifact.version(),
                source.type(),
                source.displayValue(),
                artifactHash,
                installedContentHash,
                installedContentHash,
                artifact.skills().stream().map(RegistrySkill::runtimeName).toList(),
                Instant.now());
        writeInstalledMetadata(installBasePath, metadata);

        skillService.reload();

        SkillMarketplaceItem installedItem = toMarketplaceItem(artifact, metadata);
        String status = resolveInstallStatus(existing, artifact);
        String message = switch (status) {
        case "updated" -> "Skill artifact '" + artifactRef + "' updated from marketplace.";
        case "already-installed" -> "Skill artifact '" + artifactRef + "' is already up to date.";
        default -> "Skill artifact '" + artifactRef + "' installed from marketplace.";
        };

        return new SkillInstallResult(status, message, installedItem);
    }

    private String resolveInstallStatus(InstalledArtifactMetadata existing, RegistryArtifact artifact) {
        if (existing == null) {
            return "installed";
        }
        if (Objects.equals(trimToNull(existing.version()), trimToNull(artifact.version()))
                && Objects.equals(trimToNull(existing.artifactHash()), trimToNull(artifact.contentHash()))
                && !hasLocalContentDrift(existing)) {
            return "already-installed";
        }
        return "updated";
    }

    private SkillMarketplaceItem toMarketplaceItem(RegistryArtifact artifact, InstalledArtifactMetadata installed) {
        String modelTier = artifact.skills().size() == 1 ? artifact.skills().getFirst().modelTier() : null;
        boolean isInstalled = installed != null;
        boolean updateAvailable = isInstalled
                && (!Objects.equals(trimToNull(installed.version()), trimToNull(artifact.version()))
                        || !Objects.equals(trimToNull(installed.artifactHash()), trimToNull(artifact.contentHash()))
                        || hasLocalContentDrift(installed));

        return SkillMarketplaceItem.builder()
                .id(artifact.artifactRef())
                .name(firstNonBlank(artifact.title(), artifact.artifactId()))
                .description(firstNonBlank(artifact.description(),
                        artifact.skills().size() == 1 ? artifact.skills().getFirst().description() : null))
                .maintainer(artifact.maintainerId())
                .maintainerDisplayName(artifact.maintainerDisplayName())
                .artifactId(artifact.artifactId())
                .artifactType(artifact.type())
                .version(artifact.version())
                .modelTier(modelTier)
                .sourcePath(artifact.manifestPath())
                .skillRefs(artifact.skills().stream().map(RegistrySkill::runtimeName).toList())
                .skillCount(artifact.skills().size())
                .installed(isInstalled)
                .updateAvailable(updateAvailable)
                .build();
    }

    private List<InstalledSkillPayload> buildInstalledSkillPayloads(MarketplaceSource source,
            RegistryArtifact artifact) {
        Map<String, String> aliasMap = new LinkedHashMap<>();
        for (RegistrySkill skill : artifact.skills()) {
            aliasMap.put(skill.skillId(), skill.runtimeName());
            if (skill.originalName() != null) {
                aliasMap.put(skill.originalName(), skill.runtimeName());
            }
            aliasMap.put(skill.runtimeName(), skill.runtimeName());
        }

        List<InstalledSkillPayload> payloads = new ArrayList<>();
        for (RegistrySkill skill : artifact.skills()) {
            String sourceContent = readSkillContent(source, skill.sourcePath());
            String transformed = rewriteSkillContent(sourceContent, skill.runtimeName(), aliasMap);
            payloads.add(new InstalledSkillPayload(installSkillStoragePath(artifact, skill), transformed));
        }
        return payloads;
    }

    private String rewriteSkillContent(String content, String runtimeName, Map<String, String> aliasMap) {
        FrontmatterDocument document = parseFrontmatterDocument(content);
        Map<String, Object> metadata = new LinkedHashMap<>(document.metadata());
        metadata.put("name", runtimeName);

        rewriteStringField(metadata, "next_skill", aliasMap);

        Object conditional = metadata.get("conditional_next_skills");
        if (conditional instanceof Map<?, ?> conditionalMap) {
            Map<String, Object> rewritten = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : conditionalMap.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String stringValue && aliasMap.containsKey(stringValue)) {
                    rewritten.put(String.valueOf(entry.getKey()), aliasMap.get(stringValue));
                } else {
                    rewritten.put(String.valueOf(entry.getKey()), value);
                }
            }
            metadata.put("conditional_next_skills", rewritten);
        }

        Object requirements = metadata.get("requirements");
        if (requirements instanceof Map<?, ?> requirementsMap) {
            Map<String, Object> rewrittenRequirements = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : requirementsMap.entrySet()) {
                if ("skills".equals(String.valueOf(entry.getKey())) && entry.getValue() instanceof List<?> skills) {
                    List<Object> rewrittenSkills = new ArrayList<>();
                    for (Object skill : skills) {
                        if (skill instanceof String stringSkill && aliasMap.containsKey(stringSkill)) {
                            rewrittenSkills.add(aliasMap.get(stringSkill));
                        } else {
                            rewrittenSkills.add(skill);
                        }
                    }
                    rewrittenRequirements.put("skills", rewrittenSkills);
                } else {
                    rewrittenRequirements.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            metadata.put("requirements", rewrittenRequirements);
        }

        String body = document.body();
        String normalizedBody = body == null ? "" : body.stripTrailing();
        try {
            String yaml = YAML_MAPPER.writeValueAsString(metadata).stripTrailing();
            if (normalizedBody.isEmpty()) {
                return "---\n" + yaml + "\n---\n";
            }
            return "---\n" + yaml + "\n---\n\n" + normalizedBody + "\n";
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to rewrite skill frontmatter for " + runtimeName, ex);
        }
    }

    private void rewriteStringField(Map<String, Object> metadata, String key, Map<String, String> aliasMap) {
        Object value = metadata.get(key);
        if (value instanceof String stringValue && aliasMap.containsKey(stringValue)) {
            metadata.put(key, aliasMap.get(stringValue));
        }
    }

    private Map<String, RegistryArtifact> loadArtifacts(MarketplaceSource source) {
        return (SOURCE_DIRECTORY.equals(source.type()) || SOURCE_SANDBOX.equals(source.type()))
                ? loadLocalArtifacts(source)
                : loadRemoteArtifacts(source);
    }

    private Map<String, RegistryArtifact> loadLocalArtifacts(MarketplaceSource source) {
        Path repositoryRoot = source.localRepositoryRoot();
        Path registryRoot = locateRegistryRoot(repositoryRoot)
                .orElseThrow(() -> new IllegalStateException("Marketplace registry directory is missing under "
                        + repositoryRoot));

        Map<String, MaintainerInfo> maintainers = loadLocalMaintainers(registryRoot);
        List<Path> manifestPaths;
        try (Stream<Path> stream = Files.walk(registryRoot, 4)) {
            manifestPaths = stream.filter(Files::isRegularFile)
                    .filter(path -> MANIFEST_FILE.equals(fileNameOrEmpty(path)))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan marketplace registry " + registryRoot, ex);
        }

        Map<String, RegistryArtifact> artifacts = new LinkedHashMap<>();
        for (Path manifestPath : manifestPaths) {
            try {
                RegistryArtifact artifact = loadLocalArtifact(registryRoot, manifestPath, maintainers);
                artifacts.putIfAbsent(artifact.artifactRef(), artifact);
            } catch (RuntimeException ex) {
                log.warn("[Skills] Failed to read local artifact {}: {}", manifestPath, ex.getMessage());
            }
        }
        return Map.copyOf(artifacts);
    }

    private Map<String, MaintainerInfo> loadLocalMaintainers(Path registryRoot) {
        Map<String, MaintainerInfo> maintainers = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(registryRoot, 2)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> MAINTAINER_FILE.equals(fileNameOrEmpty(path)))
                    .sorted()
                    .forEach(path -> {
                        try {
                            MaintainerManifest manifest = parseMaintainerManifest(Files.readString(path));
                            String maintainerId = normalizeSlug(firstNonBlank(
                                    trimToNull(manifest.getId()),
                                    trimToNull(path.getParent() != null ? path.getParent().getFileName().toString()
                                            : null)));
                            maintainers.putIfAbsent(maintainerId, new MaintainerInfo(
                                    maintainerId,
                                    firstNonBlank(trimToNull(manifest.getDisplayName()), maintainerId)));
                        } catch (IOException | RuntimeException ex) {
                            log.warn("[Skills] Failed to read maintainer metadata {}: {}", path, ex.getMessage());
                        }
                    });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan maintainer metadata under " + registryRoot, ex);
        }
        return Map.copyOf(maintainers);
    }

    private RegistryArtifact loadLocalArtifact(
            Path registryRoot,
            Path manifestPath,
            Map<String, MaintainerInfo> maintainers) {
        String content;
        try {
            content = Files.readString(manifestPath);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read artifact manifest " + manifestPath, ex);
        }
        ArtifactManifest manifest = parseArtifactManifest(content);

        Path artifactDir = manifestPath.getParent();
        if (artifactDir == null || artifactDir.getParent() == null) {
            throw new IllegalStateException("Artifact manifest must live under registry/<maintainer>/<artifact>");
        }

        String maintainerId = normalizeMaintainerId(manifest, artifactDir.getParent().getFileName().toString());
        String artifactId = normalizeArtifactId(manifest, artifactDir.getFileName().toString());
        validateManifestOwnership(manifest, maintainerId, artifactId);

        MaintainerInfo maintainer = maintainers.getOrDefault(maintainerId,
                new MaintainerInfo(maintainerId, maintainerId));
        String type = normalizeArtifactType(manifest.getType());
        List<ArtifactSkillEntry> manifestSkills = resolveManifestSkillsLocal(type, artifactDir, manifest);

        List<RegistrySkill> skills = new ArrayList<>();
        List<String> skillContentEntries = new ArrayList<>();
        for (ArtifactSkillEntry manifestSkill : manifestSkills) {
            Path skillPath = resolveLocalSkillPath(artifactDir, manifestSkill.path());
            String skillContent;
            try {
                skillContent = Files.readString(skillPath);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read skill file " + skillPath, ex);
            }
            SkillMetadata skillMetadata = parseSkillMetadata(skillContent);
            String runtimeName = buildRuntimeSkillName(maintainerId, artifactId, type, manifestSkill.id());
            skillContentEntries.add(runtimeName + "\n" + skillContent);
            skills.add(new RegistrySkill(
                    manifestSkill.id(),
                    registryRoot.getParent().relativize(skillPath).toString().replace('\\', '/'),
                    runtimeName,
                    skillMetadata.name(),
                    skillMetadata.description(),
                    skillMetadata.modelTier()));
        }

        return new RegistryArtifact(
                maintainerId + "/" + artifactId,
                maintainerId,
                maintainer.displayName(),
                artifactId,
                type,
                trimToNull(manifest.getVersion()),
                trimToNull(manifest.getTitle()),
                trimToNull(manifest.getDescription()),
                registryRoot.getParent().relativize(manifestPath).toString().replace('\\', '/'),
                buildArtifactContentHash(content, skillContentEntries),
                List.copyOf(skills));
    }

    private Map<String, RegistryArtifact> loadRemoteArtifacts(MarketplaceSource source) {
        Duration cacheTtl = botProperties.getSkills().getMarketplaceRemoteCacheTtl();
        String sourceKey = source.repositoryUrl() + "#" + source.branch();
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            return fetchRemoteArtifacts(source, sourceKey);
        }

        RemoteCatalogCache currentCache = remoteCatalogCache.get();
        Instant now = Instant.now();
        if (currentCache != null
                && currentCache.sourceKey().equals(sourceKey)
                && now.isBefore(currentCache.loadedAt().plus(cacheTtl))) {
            return currentCache.artifacts();
        }

        synchronized (remoteCatalogLock) {
            RemoteCatalogCache locked = remoteCatalogCache.get();
            Instant refreshedNow = Instant.now();
            if (locked != null
                    && locked.sourceKey().equals(sourceKey)
                    && refreshedNow.isBefore(locked.loadedAt().plus(cacheTtl))) {
                return locked.artifacts();
            }
            Map<String, RegistryArtifact> fetched = fetchRemoteArtifacts(source, sourceKey);
            remoteCatalogCache.set(new RemoteCatalogCache(sourceKey, refreshedNow, fetched));
            return fetched;
        }
    }

    private Map<String, RegistryArtifact> fetchRemoteArtifacts(MarketplaceSource source, String sourceKey) {
        RemoteRepositoryTree tree = fetchRemoteRepositoryTree(source);
        Map<String, MaintainerInfo> maintainers = loadRemoteMaintainers(source, tree);
        Map<String, RegistryArtifact> artifacts = new LinkedHashMap<>();

        for (String manifestPath : tree.filePaths()) {
            if (!isRemoteArtifactManifestPath(manifestPath)) {
                continue;
            }
            try {
                String manifestContent = fetchRemoteText(source, manifestPath);
                ArtifactManifest manifest = parseArtifactManifest(manifestContent);
                String[] segments = manifestPath.split("/");
                String maintainerSegment = segments[1];
                String artifactSegment = segments[2];
                String maintainerId = normalizeMaintainerId(manifest, maintainerSegment);
                String artifactId = normalizeArtifactId(manifest, artifactSegment);
                validateManifestOwnership(manifest, maintainerId, artifactId);
                MaintainerInfo maintainer = maintainers.getOrDefault(maintainerId,
                        new MaintainerInfo(maintainerId, maintainerId));
                String type = normalizeArtifactType(manifest.getType());
                String artifactBasePath = REGISTRY_DIR + "/" + maintainerSegment + "/" + artifactSegment;
                List<ArtifactSkillEntry> manifestSkills = resolveManifestSkillsRemote(type, artifactBasePath, manifest,
                        tree);

                List<RegistrySkill> skills = new ArrayList<>();
                List<String> skillContentEntries = new ArrayList<>();
                for (ArtifactSkillEntry manifestSkill : manifestSkills) {
                    String skillSourcePath = artifactBasePath + "/"
                            + normalizeRelativeArtifactPath(manifestSkill.path());
                    String skillContent = fetchRemoteText(source, skillSourcePath);
                    SkillMetadata skillMetadata = parseSkillMetadata(skillContent);
                    String runtimeName = buildRuntimeSkillName(maintainerId, artifactId, type, manifestSkill.id());
                    skillContentEntries.add(runtimeName + "\n" + skillContent);
                    skills.add(new RegistrySkill(
                            manifestSkill.id(),
                            skillSourcePath,
                            runtimeName,
                            skillMetadata.name(),
                            skillMetadata.description(),
                            skillMetadata.modelTier()));
                }

                RegistryArtifact artifact = new RegistryArtifact(
                        maintainerId + "/" + artifactId,
                        maintainerId,
                        maintainer.displayName(),
                        artifactId,
                        type,
                        trimToNull(manifest.getVersion()),
                        trimToNull(manifest.getTitle()),
                        trimToNull(manifest.getDescription()),
                        manifestPath,
                        buildArtifactContentHash(manifestContent, skillContentEntries),
                        List.copyOf(skills));
                artifacts.putIfAbsent(artifact.artifactRef(), artifact);
            } catch (RuntimeException ex) {
                log.warn("[Skills] Failed to read remote artifact {}: {}", manifestPath, ex.getMessage());
            }
        }

        return Map.copyOf(artifacts);
    }

    private Map<String, MaintainerInfo> loadRemoteMaintainers(MarketplaceSource source, RemoteRepositoryTree tree) {
        Map<String, MaintainerInfo> maintainers = new LinkedHashMap<>();
        for (String path : tree.filePaths()) {
            if (!isRemoteMaintainerManifestPath(path)) {
                continue;
            }
            try {
                MaintainerManifest manifest = parseMaintainerManifest(fetchRemoteText(source, path));
                String[] segments = path.split("/");
                String maintainerId = normalizeSlug(firstNonBlank(trimToNull(manifest.getId()), segments[1]));
                maintainers.putIfAbsent(maintainerId, new MaintainerInfo(
                        maintainerId,
                        firstNonBlank(trimToNull(manifest.getDisplayName()), maintainerId)));
            } catch (RuntimeException ex) {
                log.warn("[Skills] Failed to read remote maintainer {}: {}", path, ex.getMessage());
            }
        }
        return Map.copyOf(maintainers);
    }

    private RemoteRepositoryTree fetchRemoteRepositoryTree(MarketplaceSource source) {
        URI treeUri = remoteTreeApiUri(source);
        String responseBody = fetchText(treeUri);
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON_MAPPER.readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode treeNode = root.path("tree");
            if (!treeNode.isArray()) {
                return new RemoteRepositoryTree(List.of());
            }

            List<String> paths = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode entry : treeNode) {
                String type = entry.path("type").asText("");
                String path = entry.path("path").asText("");
                if ("blob".equals(type) && !path.isBlank()) {
                    paths.add(path);
                }
            }
            paths.sort(String::compareTo);
            return new RemoteRepositoryTree(List.copyOf(paths));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse skill marketplace tree metadata", ex);
        }
    }

    private List<ArtifactSkillEntry> resolveManifestSkillsLocal(String type, Path artifactDir,
            ArtifactManifest manifest) {
        List<ArtifactSkillEntry> declared = normalizeManifestSkills(manifest.getSkills());
        if (!declared.isEmpty()) {
            return declared;
        }
        if (TYPE_SKILL.equals(type)) {
            return List.of(new ArtifactSkillEntry(normalizeSlug(artifactDir.getFileName().toString()), SKILL_FILE));
        }

        try (Stream<Path> stream = Files.walk(artifactDir.resolve("skills"))) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> SKILL_FILE.equals(fileNameOrEmpty(path)))
                    .sorted()
                    .map(path -> {
                        String skillId = normalizeSlug(path.getParent().getFileName().toString());
                        String relativePath = artifactDir.relativize(path).toString().replace('\\', '/');
                        return new ArtifactSkillEntry(skillId, relativePath);
                    })
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan pack skills under " + artifactDir, ex);
        }
    }

    private List<ArtifactSkillEntry> resolveManifestSkillsRemote(
            String type,
            String artifactBasePath,
            ArtifactManifest manifest,
            RemoteRepositoryTree tree) {
        List<ArtifactSkillEntry> declared = normalizeManifestSkills(manifest.getSkills());
        if (!declared.isEmpty()) {
            return declared;
        }
        if (TYPE_SKILL.equals(type)) {
            String artifactId = normalizeSlug(artifactBasePath.substring(artifactBasePath.lastIndexOf('/') + 1));
            return List.of(new ArtifactSkillEntry(artifactId, SKILL_FILE));
        }

        List<ArtifactSkillEntry> result = new ArrayList<>();
        String prefix = artifactBasePath + "/skills/";
        for (String path : tree.filePaths()) {
            if (!path.startsWith(prefix) || !path.endsWith("/" + SKILL_FILE)) {
                continue;
            }
            String relativePath = path.substring(artifactBasePath.length() + 1);
            String[] segments = relativePath.split("/");
            if (segments.length < 3) {
                continue;
            }
            String skillId = normalizeSlug(segments[1]);
            result.add(new ArtifactSkillEntry(skillId, relativePath));
        }
        result.sort(Comparator.comparing(ArtifactSkillEntry::id));
        return List.copyOf(result);
    }

    private List<ArtifactSkillEntry> normalizeManifestSkills(List<ArtifactSkillManifest> declaredSkills) {
        if (declaredSkills == null || declaredSkills.isEmpty()) {
            return List.of();
        }
        List<ArtifactSkillEntry> skills = new ArrayList<>();
        for (ArtifactSkillManifest skill : declaredSkills) {
            if (skill == null) {
                continue;
            }
            String id = normalizeSlug(skill.getId());
            String path = normalizeRelativeArtifactPath(skill.getPath());
            skills.add(new ArtifactSkillEntry(id, path));
        }
        return List.copyOf(skills);
    }

    private Map<String, InstalledArtifactMetadata> loadInstalledArtifacts() {
        List<String> keys = storagePort.listObjects(SKILLS_DIR, MARKETPLACE_DIR).join();
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, InstalledArtifactMetadata> installed = new LinkedHashMap<>();
        for (String key : keys) {
            if (!key.endsWith("/" + INSTALL_METADATA_FILE)) {
                continue;
            }
            try {
                String json = storagePort.getText(SKILLS_DIR, key).join();
                if (json == null || json.isBlank()) {
                    continue;
                }
                InstalledArtifactMetadata metadata = JSON_MAPPER.readValue(json, InstalledArtifactMetadata.class);
                if (metadata.artifactRef() != null) {
                    String installBasePath = key.substring(0, key.length() - ("/" + INSTALL_METADATA_FILE).length());
                    String currentContentHash = calculateInstalledArtifactHash(installBasePath);
                    installed.put(metadata.artifactRef(), new InstalledArtifactMetadata(
                            metadata.artifactRef(),
                            metadata.version(),
                            metadata.sourceType(),
                            metadata.sourceLocation(),
                            metadata.artifactHash(),
                            resolveStoredInstalledContentHash(metadata, currentContentHash),
                            currentContentHash,
                            metadata.installedSkillNames(),
                            metadata.installedAt()));
                }
            } catch (IOException | RuntimeException ex) {
                log.warn("[Skills] Failed to read installed marketplace metadata {}: {}", key, ex.getMessage());
            }
        }
        return Map.copyOf(installed);
    }

    private void writeInstalledMetadata(String installBasePath, InstalledArtifactMetadata metadata) {
        try {
            String json = JSON_MAPPER.writeValueAsString(metadata);
            storagePort.putText(SKILLS_DIR, installBasePath + "/" + INSTALL_METADATA_FILE, json).join();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write install metadata for " + metadata.artifactRef(), ex);
        }
    }

    private String resolveStoredInstalledContentHash(
            InstalledArtifactMetadata metadata,
            String currentContentHash) {
        String storedInstalledContentHash = firstNonBlank(
                trimToNull(metadata.installedContentHash()),
                trimToNull(metadata.currentContentHash()));
        if (storedInstalledContentHash == null) {
            return currentContentHash;
        }

        // Older installs stored the source artifact hash in currentContentHash.
        if (Objects.equals(trimToNull(storedInstalledContentHash), trimToNull(metadata.artifactHash()))
                && currentContentHash != null) {
            return currentContentHash;
        }
        return storedInstalledContentHash;
    }

    private void deleteInstalledArtifact(String installBasePath) {
        List<String> keys = storagePort.listObjects(SKILLS_DIR, installBasePath).join();
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            storagePort.deleteObject(SKILLS_DIR, key).join();
        }
    }

    public void deleteManagedSkill(Skill skill) {
        Path location = skill != null ? skill.getLocation() : null;
        String storagePath = resolveManagedSkillStoragePath(skill);
        String deleteScope = resolveDeleteScope(location, storagePath);

        List<String> keys = storagePort.listObjects(SKILLS_DIR, deleteScope).join();
        if (keys == null || keys.isEmpty()) {
            storagePort.deleteObject(SKILLS_DIR, storagePath).join();
            return;
        }
        for (String key : keys) {
            storagePort.deleteObject(SKILLS_DIR, key).join();
        }
    }

    public String resolveManagedSkillStoragePath(Skill skill) {
        Path location = skill != null ? skill.getLocation() : null;
        if (location != null) {
            return location.toString().replace('\\', '/');
        }
        return skill.getName() + "/" + SKILL_FILE;
    }

    public Optional<String> resolveMarketplaceInstallBase(Path location) {
        if (location == null) {
            return Optional.empty();
        }
        String normalized = location.toString().replace('\\', '/');
        String prefix = MARKETPLACE_DIR + "/";
        if (!normalized.startsWith(prefix)) {
            return Optional.empty();
        }
        String[] parts = normalized.split("/");
        if (parts.length < 4) {
            return Optional.empty();
        }
        return Optional.of(parts[0] + "/" + parts[1] + "/" + parts[2]);
    }

    private String installBasePath(RegistryArtifact artifact) {
        return MARKETPLACE_DIR + "/" + artifact.maintainerId() + "/" + artifact.artifactId();
    }

    private String installSkillStoragePath(RegistryArtifact artifact, RegistrySkill skill) {
        String base = installBasePath(artifact);
        if (TYPE_SKILL.equals(artifact.type())) {
            return base + "/" + SKILL_FILE;
        }
        return base + "/skills/" + skill.skillId() + "/" + SKILL_FILE;
    }

    private String readSkillContent(MarketplaceSource source, String sourcePath) {
        if (SOURCE_DIRECTORY.equals(source.type()) || SOURCE_SANDBOX.equals(source.type())) {
            Path root = source.localRepositoryRoot();
            if (root == null) {
                throw new IllegalStateException("Local marketplace repository is not configured");
            }
            Path filePath = root.resolve(normalizeRelativeArtifactPath(sourcePath)).normalize();
            try {
                return Files.readString(filePath);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read skill file " + filePath, ex);
            }
        }
        return fetchRemoteText(source, sourcePath);
    }

    private String fetchRemoteText(MarketplaceSource source, String relativePath) {
        URI uri = remoteRawFileUri(source, relativePath);
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

    private MarketplaceSource resolveMarketplaceSource() {
        RuntimeConfig.SkillsConfig skillsConfig = Optional
                .ofNullable(runtimeConfigService.getRuntimeConfig().getSkills())
                .orElseGet(RuntimeConfig.SkillsConfig::new);

        String configuredSourceType = trimToNull(skillsConfig.getMarketplaceSourceType());
        String configuredDirectory = firstNonBlank(
                trimToNull(skillsConfig.getMarketplaceRepositoryDirectory()),
                trimToNull(botProperties.getSkills().getMarketplaceRepositoryDirectory()));
        String configuredSandboxPath = firstNonBlank(
                trimToNull(skillsConfig.getMarketplaceSandboxPath()),
                trimToNull(botProperties.getSkills().getMarketplaceSandboxPath()));
        String configuredRepositoryUrl = firstNonBlank(
                trimToNull(skillsConfig.getMarketplaceRepositoryUrl()),
                trimToNull(botProperties.getSkills().getMarketplaceRepositoryUrl()),
                DEFAULT_REPOSITORY_URL);
        String configuredBranch = firstNonBlank(
                trimToNull(skillsConfig.getMarketplaceBranch()),
                trimToNull(botProperties.getSkills().getMarketplaceBranch()),
                DEFAULT_BRANCH);

        String sourceType = normalizeSourceType(configuredSourceType, configuredDirectory, configuredSandboxPath);
        if (SOURCE_DIRECTORY.equals(sourceType)) {
            if (configuredDirectory == null) {
                throw new IllegalStateException(
                        "Local marketplace source is selected but no local path is configured.");
            }
            Path requestedPath = resolveConfiguredPath(configuredDirectory);
            Path repositoryRoot = resolveLocalRepositoryRoot(requestedPath)
                    .orElseThrow(() -> new IllegalStateException(
                            "Marketplace repository was not found at " + requestedPath));
            return new MarketplaceSource(sourceType, repositoryRoot.toString(), configuredRepositoryUrl,
                    configuredBranch, repositoryRoot);
        }
        if (SOURCE_SANDBOX.equals(sourceType)) {
            if (configuredSandboxPath == null) {
                throw new IllegalStateException(
                        "Sandbox marketplace source is selected but no sandbox path is configured.");
            }
            Path requestedPath = resolveSandboxConfiguredPath(configuredSandboxPath);
            Path repositoryRoot = resolveLocalRepositoryRoot(requestedPath)
                    .orElseThrow(() -> new IllegalStateException(
                            "Marketplace repository was not found at sandbox path " + configuredSandboxPath));
            return new MarketplaceSource(sourceType, sandboxDisplayPath(repositoryRoot), configuredRepositoryUrl,
                    configuredBranch, repositoryRoot);
        }

        String repositoryUrl = configuredRepositoryUrl != null ? configuredRepositoryUrl : DEFAULT_REPOSITORY_URL;
        return new MarketplaceSource(sourceType, remoteRepositoryUrl(repositoryUrl).toString(), repositoryUrl,
                configuredBranch, null);
    }

    private String normalizeSourceType(
            String configuredSourceType,
            String configuredDirectory,
            String configuredSandboxPath) {
        if (configuredSourceType == null) {
            if (configuredDirectory != null) {
                return SOURCE_DIRECTORY;
            }
            if (configuredSandboxPath != null) {
                return SOURCE_SANDBOX;
            }
            return SOURCE_REPOSITORY;
        }
        String normalized = configuredSourceType.trim().toLowerCase(Locale.ROOT);
        if (SOURCE_DIRECTORY.equals(normalized)) {
            return SOURCE_DIRECTORY;
        }
        if (SOURCE_SANDBOX.equals(normalized)) {
            return SOURCE_SANDBOX;
        }
        return SOURCE_REPOSITORY;
    }

    private Path resolveConfiguredPath(String configuredPath) {
        String expanded = configuredPath.replace("${user.home}", System.getProperty("user.home"));
        if (expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        if ("~".equals(expanded)) {
            expanded = System.getProperty("user.home");
        }
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    private Path resolveSandboxConfiguredPath(String configuredPath) {
        try {
            return workspacePathService.resolveSafePath(configuredPath);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid sandbox path: " + ex.getMessage(), ex);
        }
    }

    private Optional<Path> resolveLocalRepositoryRoot(Path configuredPath) {
        if (!Files.isDirectory(configuredPath)) {
            return Optional.empty();
        }
        if (REGISTRY_DIR.equals(fileNameOrEmpty(configuredPath))) {
            return Optional.ofNullable(configuredPath.getParent());
        }
        if (Files.isDirectory(configuredPath.resolve(REGISTRY_DIR))) {
            return Optional.of(configuredPath);
        }
        return Optional.empty();
    }

    private String sandboxDisplayPath(Path repositoryRoot) {
        String relativePath = workspacePathService.toRelativePath(repositoryRoot);
        return relativePath.isBlank() ? "." : relativePath;
    }

    private Optional<Path> locateRegistryRoot(Path repositoryRoot) {
        if (repositoryRoot == null) {
            return Optional.empty();
        }
        if (REGISTRY_DIR.equals(fileNameOrEmpty(repositoryRoot)) && Files.isDirectory(repositoryRoot)) {
            return Optional.of(repositoryRoot);
        }
        Path registryRoot = repositoryRoot.resolve(REGISTRY_DIR);
        return Files.isDirectory(registryRoot) ? Optional.of(registryRoot) : Optional.empty();
    }

    private Path resolveLocalSkillPath(Path artifactDir, String relativePath) {
        Path resolved = artifactDir.resolve(normalizeRelativeArtifactPath(relativePath)).normalize();
        if (!resolved.startsWith(artifactDir)) {
            throw new IllegalStateException("Skill path escapes artifact directory: " + relativePath);
        }
        return resolved;
    }

    private String normalizeRelativeArtifactPath(String path) {
        String normalized = trimToNull(path);
        if (normalized == null) {
            throw new IllegalArgumentException("Artifact skill path is required");
        }
        Path candidate = Path.of(normalized.replace('\\', '/')).normalize();
        if (candidate.isAbsolute() || candidate.startsWith("..")) {
            throw new IllegalArgumentException("Artifact skill path must stay within artifact directory");
        }
        return candidate.toString().replace('\\', '/');
    }

    private String buildRuntimeSkillName(String maintainerId, String artifactId, String type, String skillId) {
        if (TYPE_SKILL.equals(type)) {
            return maintainerId + "/" + artifactId;
        }
        return maintainerId + "/" + artifactId + "/" + skillId;
    }

    private void validateManifestOwnership(ArtifactManifest manifest, String maintainerId, String artifactId) {
        String declaredMaintainer = trimToNull(manifest.getMaintainer());
        if (declaredMaintainer != null && !normalizeSlug(declaredMaintainer).equals(maintainerId)) {
            throw new IllegalStateException("Artifact maintainer does not match its registry path");
        }
        String declaredId = trimToNull(manifest.getId());
        if (declaredId != null && !normalizeSlug(declaredId).equals(artifactId)) {
            throw new IllegalStateException("Artifact id does not match its registry path");
        }
    }

    private String normalizeMaintainerId(ArtifactManifest manifest, String fallback) {
        return normalizeSlug(firstNonBlank(trimToNull(manifest.getMaintainer()), trimToNull(fallback)));
    }

    private String normalizeArtifactId(ArtifactManifest manifest, String fallback) {
        return normalizeSlug(firstNonBlank(trimToNull(manifest.getId()), trimToNull(fallback)));
    }

    private String normalizeArtifactRef(String artifactRef) {
        if (artifactRef == null || artifactRef.isBlank()) {
            return null;
        }
        String normalized = artifactRef.trim().toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Artifact ref must match <maintainer>/<artifact>");
        }
        return normalizeSlug(parts[0]) + "/" + normalizeSlug(parts[1]);
    }

    private String normalizeSlug(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Slug is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            boolean alphanumeric = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
            boolean hyphen = ch == '-';
            if (index == 0 && !alphanumeric) {
                throw new IllegalArgumentException("Slug must start with [a-z0-9]");
            }
            if (!alphanumeric && !hyphen) {
                throw new IllegalArgumentException("Slug must contain only [a-z0-9-]");
            }
        }
        return normalized;
    }

    private String normalizeArtifactType(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException("Artifact type is required");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (TYPE_SKILL.equals(lower) || TYPE_PACK.equals(lower)) {
            return lower;
        }
        throw new IllegalArgumentException("Unsupported artifact type: " + value);
    }

    private ArtifactManifest parseArtifactManifest(String content) {
        try {
            ArtifactManifest manifest = YAML_MAPPER.readValue(content, ArtifactManifest.class);
            if (manifest == null) {
                throw new IllegalStateException("Artifact manifest is empty");
            }
            return manifest;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse artifact manifest", ex);
        }
    }

    private MaintainerManifest parseMaintainerManifest(String content) {
        try {
            MaintainerManifest manifest = YAML_MAPPER.readValue(content, MaintainerManifest.class);
            return manifest != null ? manifest : new MaintainerManifest();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse maintainer manifest", ex);
        }
    }

    private SkillMetadata parseSkillMetadata(String content) {
        FrontmatterDocument document = parseFrontmatterDocument(content);
        Map<String, Object> metadata = document.metadata();
        return new SkillMetadata(
                trimToNull(asString(metadata.get("name"))),
                trimToNull(asString(metadata.get("description"))),
                trimToNull(asString(metadata.get("model_tier"))));
    }

    private FrontmatterDocument parseFrontmatterDocument(String content) {
        if (content == null) {
            return new FrontmatterDocument(Map.of(), "");
        }
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.matches()) {
            return new FrontmatterDocument(Map.of(), content);
        }

        String frontmatter = matcher.group(1);
        String body = matcher.group(2);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> yaml = YAML_MAPPER.readValue(frontmatter, LinkedHashMap.class);
            return new FrontmatterDocument(yaml != null ? yaml : Map.of(), body);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse skill frontmatter", ex);
        }
    }

    private boolean isRemoteArtifactManifestPath(String path) {
        String[] segments = path.split("/");
        return segments.length == 4
                && REGISTRY_DIR.equals(segments[0])
                && MANIFEST_FILE.equals(segments[3]);
    }

    private boolean isRemoteMaintainerManifestPath(String path) {
        String[] segments = path.split("/");
        return segments.length == 3
                && REGISTRY_DIR.equals(segments[0])
                && MAINTAINER_FILE.equals(segments[2]);
    }

    private URI remoteRepositoryUrl(String repositoryUrl) {
        String value = trimToNull(repositoryUrl);
        if (value == null) {
            value = DEFAULT_REPOSITORY_URL;
        }
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private URI remoteTreeApiUri(MarketplaceSource source) {
        GitHubRepository repository = parseRemoteRepository(source.repositoryUrl());
        String encodedBranch = encodePathSegment(source.branch());
        String relativePath = "repos/" + repository.owner() + "/" + repository.name()
                + "/git/trees/" + encodedBranch + "?recursive=1";
        return repositoryApiBaseUrl().resolve(relativePath);
    }

    private URI remoteRawFileUri(MarketplaceSource source, String filePath) {
        GitHubRepository repository = parseRemoteRepository(source.repositoryUrl());
        String relativePath = repository.owner() + "/" + repository.name() + "/"
                + source.branch() + "/" + normalizeRelativeArtifactPath(filePath);
        return repositoryRawBaseUrl().resolve(relativePath);
    }

    private URI repositoryApiBaseUrl() {
        String configured = trimToNull(botProperties.getSkills().getMarketplaceApiBaseUrl());
        String value = configured != null ? configured : DEFAULT_API_BASE_URL;
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private URI repositoryRawBaseUrl() {
        String configured = trimToNull(botProperties.getSkills().getMarketplaceRawBaseUrl());
        String value = configured != null ? configured : DEFAULT_RAW_BASE_URL;
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private GitHubRepository parseRemoteRepository(String repositoryUrl) {
        URI url = remoteRepositoryUrl(repositoryUrl);
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
        return new GitHubRepository(segments[segments.length - 2], segments[segments.length - 1]);
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private SkillMarketplaceCatalog unavailable(String message) {
        return SkillMarketplaceCatalog.builder()
                .available(false)
                .message(message)
                .items(List.of())
                .build();
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

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private String fileNameOrEmpty(Path path) {
        return path != null && path.getFileName() != null ? path.getFileName().toString() : "";
    }

    private String resolveDeleteScope(Path location, String storagePath) {
        Optional<String> marketplaceBase = resolveMarketplaceInstallBase(location);
        if (marketplaceBase.isPresent()) {
            return marketplaceBase.get();
        }
        int lastSlash = storagePath.lastIndexOf('/');
        if (lastSlash > 0) {
            return storagePath.substring(0, lastSlash);
        }
        return storagePath;
    }

    private String calculateInstalledArtifactHash(String installBasePath) {
        List<String> keys = storagePort.listObjects(SKILLS_DIR, installBasePath).join();
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        List<String> contentEntries = keys.stream()
                .filter(key -> !key.endsWith("/" + INSTALL_METADATA_FILE))
                .sorted()
                .map(key -> key + "\n" + Optional.ofNullable(storagePort.getText(SKILLS_DIR, key).join()).orElse(""))
                .toList();
        if (contentEntries.isEmpty()) {
            return null;
        }
        return sha256Hex(String.join("\n---\n", contentEntries));
    }

    private String buildInstalledContentHash(List<InstalledSkillPayload> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return null;
        }
        List<String> contentEntries = payloads.stream()
                .sorted(Comparator.comparing(InstalledSkillPayload::storagePath))
                .map(payload -> payload.storagePath() + "\n" + Optional.ofNullable(payload.content()).orElse(""))
                .toList();
        return sha256Hex(String.join("\n---\n", contentEntries));
    }

    private String buildArtifactContentHash(String manifestContent, List<String> skillContentEntries) {
        List<String> parts = new ArrayList<>();
        parts.add("manifest\n" + manifestContent);
        parts.addAll(skillContentEntries.stream().sorted().toList());
        return sha256Hex(String.join("\n===\n", parts));
    }

    private boolean hasLocalContentDrift(InstalledArtifactMetadata installed) {
        if (installed == null) {
            return false;
        }
        String expectedContentHash = trimToNull(installed.installedContentHash());
        String currentContentHash = trimToNull(installed.currentContentHash());
        if (expectedContentHash == null || currentContentHash == null) {
            return false;
        }
        return !Objects.equals(expectedContentHash, currentContentHash);
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

    private record MarketplaceSource(
            String type,
            String displayValue,
            String repositoryUrl,
            String branch,
            Path localRepositoryRoot) {
    }

    private record RegistryArtifact(
            String artifactRef,
            String maintainerId,
            String maintainerDisplayName,
            String artifactId,
            String type,
            String version,
            String title,
            String description,
            String manifestPath,
            String contentHash,
            List<RegistrySkill> skills) {
    }

    private record RegistrySkill(
            String skillId,
            String sourcePath,
            String runtimeName,
            String originalName,
            String description,
            String modelTier) {
    }

    private record ArtifactSkillEntry(String id, String path) {
    }

    private record InstalledSkillPayload(String storagePath, String content) {
    }

    private record InstalledArtifactMetadata(
            String artifactRef,
            String version,
            String sourceType,
            String sourceLocation,
            String artifactHash,
            String installedContentHash,
            String currentContentHash,
            List<String> installedSkillNames,
            Instant installedAt) {
    }

    private record FrontmatterDocument(Map<String, Object> metadata, String body) {
    }

    private record SkillMetadata(String name, String description, String modelTier) {
    }

    private record MaintainerInfo(String id, String displayName) {
    }

    private record RemoteRepositoryTree(List<String> filePaths) {
    }

    private record RemoteCatalogCache(String sourceKey, Instant loadedAt, Map<String, RegistryArtifact> artifacts) {
    }

    private record GitHubRepository(String owner, String name) {
    }

    @Data
    private static class ArtifactManifest {
        private String schema;
        private String type;
        private String maintainer;
        private String id;
        private String version;
        private String title;
        private String description;
        private List<ArtifactSkillManifest> skills = List.of();
    }

    @Data
    private static class ArtifactSkillManifest {
        private String id;
        private String path;
    }

    @Data
    private static class MaintainerManifest {
        private String schema;
        private String id;
        @JsonProperty("display_name")
        private String displayName;
        private String github;
        private String contact;
    }
}

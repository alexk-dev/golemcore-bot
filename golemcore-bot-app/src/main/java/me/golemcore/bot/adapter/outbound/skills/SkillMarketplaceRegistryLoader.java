package me.golemcore.bot.adapter.outbound.skills;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SkillDocumentService;
import me.golemcore.bot.domain.service.WorkspacePathService;
import me.golemcore.bot.port.outbound.SkillMarketplaceCatalogPort.MarketplaceArtifactData;
import me.golemcore.bot.port.outbound.SkillMarketplaceCatalogPort.MarketplaceSourceRef;
import me.golemcore.bot.port.outbound.SkillSettingsPort;

@Slf4j
final class SkillMarketplaceRegistryLoader {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String GITHUB_USER_AGENT = "golemcore-bot-skill-marketplace";
    private static final String REGISTRY_DIR = "registry";
    private static final String MANIFEST_FILE = "artifact.yaml";
    private static final String MAINTAINER_FILE = "maintainer.yaml";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String SOURCE_REPOSITORY = "repository";
    private static final String SOURCE_DIRECTORY = "directory";
    private static final String SOURCE_SANDBOX = "sandbox";
    private static final String TYPE_SKILL = "skill";
    private static final String TYPE_PACK = "pack";
    private static final String DEFAULT_REPOSITORY_URL = "https://github.com/alexk-dev/golemcore-skills";
    private static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    private static final String DEFAULT_RAW_BASE_URL = "https://raw.githubusercontent.com";
    private static final String DEFAULT_BRANCH = "main";

    private final SkillSettingsPort settingsPort;
    private final RuntimeConfigService runtimeConfigService;
    private final WorkspacePathService workspacePathService;
    private final SkillDocumentService skillDocumentService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Object remoteCatalogLock = new Object();
    private final AtomicReference<RemoteCatalogCache> remoteCatalogCache = new AtomicReference<>();

    SkillMarketplaceRegistryLoader(
            SkillSettingsPort settingsPort,
            RuntimeConfigService runtimeConfigService,
            WorkspacePathService workspacePathService,
            SkillDocumentService skillDocumentService) {
        this.settingsPort = settingsPort;
        this.runtimeConfigService = runtimeConfigService;
        this.workspacePathService = workspacePathService;
        this.skillDocumentService = skillDocumentService;
    }

    Map<String, RegistryArtifact> loadArtifacts(MarketplaceSource source) {
        return (SOURCE_DIRECTORY.equals(source.type()) || SOURCE_SANDBOX.equals(source.type()))
                ? loadLocalArtifacts(source)
                : loadRemoteArtifacts(source);
    }

    String readSkillContent(MarketplaceSource source, String sourcePath) {
        if (SOURCE_DIRECTORY.equals(source.type()) || SOURCE_SANDBOX.equals(source.type())) {
            Path root = source.localRepositoryRoot();
            if (root == null) {
                throw new IllegalStateException("Local marketplace repository is not configured");
            }
            Path filePath = root.resolve(normalizeRelativeArtifactPath(sourcePath)).normalize();
            try {
                return Files.readString(filePath);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read skill file " + filePath, exception);
            }
        }
        return fetchRemoteText(source, sourcePath);
    }

    MarketplaceSource resolveMarketplaceSource() {
        RuntimeConfig.SkillsConfig skillsConfig = Optional
                .ofNullable(runtimeConfigService.getRuntimeConfig().getSkills())
                .orElseGet(RuntimeConfig.SkillsConfig::new);

        return resolveMarketplaceSource(
                marketplaceSettings(),
                Map.of(
                        "marketplaceSourceType", Optional.ofNullable(skillsConfig.getMarketplaceSourceType())
                                .orElse(""),
                        "marketplaceRepositoryDirectory",
                        Optional.ofNullable(skillsConfig.getMarketplaceRepositoryDirectory()).orElse(""),
                        "marketplaceSandboxPath", Optional.ofNullable(skillsConfig.getMarketplaceSandboxPath())
                                .orElse(""),
                        "marketplaceRepositoryUrl", Optional.ofNullable(skillsConfig.getMarketplaceRepositoryUrl())
                                .orElse(""),
                        "marketplaceBranch", Optional.ofNullable(skillsConfig.getMarketplaceBranch()).orElse("")),
                skillsConfig.getMarketplaceRepositoryDirectory(),
                skillsConfig.getMarketplaceSandboxPath(),
                skillsConfig.getMarketplaceRepositoryUrl(),
                skillsConfig.getMarketplaceBranch());
    }

    MarketplaceSource resolveMarketplaceSource(
            SkillSettingsPort.MarketplaceSettings marketplaceSettings,
            Map<String, Object> runtimeSkillSettings,
            String repositoryDirectory,
            String sandboxPath,
            String repositoryUrl,
            String branch) {
        String configuredSourceType = trimToNull(runtimeString(runtimeSkillSettings, "marketplaceSourceType"));
        String configuredDirectory = firstNonBlank(
                runtimeString(runtimeSkillSettings, "marketplaceRepositoryDirectory"),
                repositoryDirectory,
                marketplaceSettings.repositoryDirectory());
        String configuredSandboxPath = firstNonBlank(
                runtimeString(runtimeSkillSettings, "marketplaceSandboxPath"),
                sandboxPath,
                marketplaceSettings.sandboxPath());
        String configuredRepositoryUrl = firstNonBlank(
                runtimeString(runtimeSkillSettings, "marketplaceRepositoryUrl"),
                repositoryUrl,
                marketplaceSettings.repositoryUrl(),
                DEFAULT_REPOSITORY_URL);
        String configuredBranch = firstNonBlank(
                runtimeString(runtimeSkillSettings, "marketplaceBranch"),
                branch,
                marketplaceSettings.branch(),
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

        String resolvedRepositoryUrl = configuredRepositoryUrl != null ? configuredRepositoryUrl
                : DEFAULT_REPOSITORY_URL;
        return new MarketplaceSource(sourceType, remoteRepositoryUrl(resolvedRepositoryUrl).toString(),
                resolvedRepositoryUrl,
                configuredBranch, null);
    }

    MarketplaceSource resolveMarketplaceSource(MarketplaceSourceRef sourceRef, MarketplaceArtifactData artifactData) {
        String sourceType = firstNonBlank(
                sourceRef != null ? sourceRef.type() : null,
                artifactData != null ? artifactData.sourceType() : null,
                SOURCE_REPOSITORY);
        String displayValue = firstNonBlank(
                sourceRef != null ? sourceRef.displayValue() : null,
                artifactData != null ? artifactData.sourceDisplayValue() : null);
        String repositoryUrl = firstNonBlank(sourceRef != null ? sourceRef.repositoryUrl() : null, displayValue,
                DEFAULT_REPOSITORY_URL);
        String branch = firstNonBlank(sourceRef != null ? sourceRef.branch() : null, DEFAULT_BRANCH);
        String normalizedSourceType = normalizeSourceType(sourceType, null, null);
        if (SOURCE_DIRECTORY.equals(normalizedSourceType)) {
            if (displayValue == null) {
                throw new IllegalStateException("Catalog source is missing local repository path.");
            }
            Path repositoryRoot = resolveLocalRepositoryRoot(resolveConfiguredPath(displayValue))
                    .orElseThrow(() -> new IllegalStateException(
                            "Marketplace repository was not found at " + displayValue));
            return new MarketplaceSource(normalizedSourceType, repositoryRoot.toString(), repositoryUrl, branch,
                    repositoryRoot);
        }
        if (SOURCE_SANDBOX.equals(normalizedSourceType)) {
            if (displayValue == null) {
                throw new IllegalStateException("Catalog source is missing sandbox repository path.");
            }
            Path repositoryRoot = resolveLocalRepositoryRoot(resolveSandboxConfiguredPath(displayValue))
                    .orElseThrow(() -> new IllegalStateException(
                            "Marketplace repository was not found at sandbox path " + displayValue));
            return new MarketplaceSource(normalizedSourceType, displayValue, repositoryUrl, branch, repositoryRoot);
        }
        String normalizedRepositoryUrl = firstNonBlank(sourceRef != null ? sourceRef.repositoryUrl() : null,
                displayValue,
                DEFAULT_REPOSITORY_URL);
        return new MarketplaceSource(normalizedSourceType, remoteRepositoryUrl(normalizedRepositoryUrl).toString(),
                normalizedRepositoryUrl, branch, null);
    }

    String normalizeArtifactRef(String artifactRef) {
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
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan marketplace registry " + registryRoot, exception);
        }

        Map<String, RegistryArtifact> artifacts = new LinkedHashMap<>();
        for (Path manifestPath : manifestPaths) {
            try {
                RegistryArtifact artifact = loadLocalArtifact(registryRoot, manifestPath, maintainers);
                artifacts.putIfAbsent(artifact.artifactRef(), artifact);
            } catch (RuntimeException exception) {
                log.warn("[Skills] Failed to read local artifact {}: {}", manifestPath, exception.getMessage());
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
                                    trimToNull(parentDirectoryNameOrNull(path))));
                            maintainers.putIfAbsent(maintainerId, new MaintainerInfo(
                                    maintainerId,
                                    firstNonBlank(trimToNull(manifest.getDisplayName()), maintainerId)));
                        } catch (IOException | RuntimeException exception) {
                            log.warn("[Skills] Failed to read maintainer metadata {}: {}", path,
                                    exception.getMessage());
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan maintainer metadata under " + registryRoot, exception);
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
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read artifact manifest " + manifestPath, exception);
        }
        ArtifactManifest manifest = parseArtifactManifest(content);

        Path artifactDir = Objects.requireNonNull(manifestPath.getParent(),
                "Artifact manifest must live under registry/<maintainer>/<artifact>");
        Path maintainerDir = Objects.requireNonNull(artifactDir.getParent(),
                "Artifact manifest must live under registry/<maintainer>/<artifact>");
        String maintainerFileName = fileNameOrEmpty(maintainerDir);
        String artifactFileName = fileNameOrEmpty(artifactDir);
        Path registryParent = registryRoot.getParent();
        if (maintainerFileName.isBlank() || artifactFileName.isBlank() || registryParent == null) {
            throw new IllegalStateException("Artifact manifest path is invalid: " + manifestPath);
        }

        String maintainerId = normalizeMaintainerId(manifest, maintainerFileName);
        String artifactId = normalizeArtifactId(manifest, artifactFileName);
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
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read skill file " + skillPath, exception);
            }
            SkillMetadata skillMetadata = parseSkillMetadata(skillContent);
            String runtimeName = buildRuntimeSkillName(maintainerId, artifactId, type, manifestSkill.id());
            skillContentEntries.add(runtimeName + "\n" + skillContent);
            skills.add(new RegistrySkill(
                    manifestSkill.id(),
                    registryParent.relativize(skillPath).toString().replace('\\', '/'),
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
                registryParent.relativize(manifestPath).toString().replace('\\', '/'),
                buildArtifactContentHash(content, skillContentEntries),
                List.copyOf(skills));
    }

    private Map<String, RegistryArtifact> loadRemoteArtifacts(MarketplaceSource source) {
        Duration cacheTtl = marketplaceSettings().remoteCacheTtl();
        String sourceKey = source.repositoryUrl() + "#" + source.branch();
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            return fetchRemoteArtifacts(source);
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
            Map<String, RegistryArtifact> fetched = fetchRemoteArtifacts(source);
            remoteCatalogCache.set(new RemoteCatalogCache(sourceKey, refreshedNow, fetched));
            return fetched;
        }
    }

    private Map<String, RegistryArtifact> fetchRemoteArtifacts(MarketplaceSource source) {
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
            } catch (RuntimeException exception) {
                log.warn("[Skills] Failed to read remote artifact {}: {}", manifestPath, exception.getMessage());
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
            } catch (RuntimeException exception) {
                log.warn("[Skills] Failed to read remote maintainer {}: {}", path, exception.getMessage());
            }
        }
        return Map.copyOf(maintainers);
    }

    private RemoteRepositoryTree fetchRemoteRepositoryTree(MarketplaceSource source) {
        URI treeUri = remoteTreeApiUri(source);
        String responseBody = fetchText(treeUri);
        try {
            JsonNode root = JSON_MAPPER.readTree(responseBody);
            JsonNode treeNode = root.path("tree");
            if (!treeNode.isArray()) {
                return new RemoteRepositoryTree(List.of());
            }

            List<String> paths = new ArrayList<>();
            for (JsonNode entry : treeNode) {
                String type = entry.path("type").asText("");
                String path = entry.path("path").asText("");
                if ("blob".equals(type) && !path.isBlank()) {
                    paths.add(path);
                }
            }
            paths.sort(String::compareTo);
            return new RemoteRepositoryTree(List.copyOf(paths));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse skill marketplace tree metadata", exception);
        }
    }

    private List<ArtifactSkillEntry> resolveManifestSkillsLocal(String type, Path artifactDir,
            ArtifactManifest manifest) {
        List<ArtifactSkillEntry> declared = normalizeManifestSkills(manifest.getSkills());
        if (!declared.isEmpty()) {
            return declared;
        }
        if (TYPE_SKILL.equals(type)) {
            Path artifactFileName = artifactDir.getFileName();
            if (artifactFileName == null) {
                throw new IllegalStateException("Artifact directory is invalid: " + artifactDir);
            }
            return List.of(new ArtifactSkillEntry(normalizeSlug(artifactFileName.toString()), SKILL_FILE));
        }

        try (Stream<Path> stream = Files.walk(artifactDir.resolve("skills"))) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> SKILL_FILE.equals(fileNameOrEmpty(path)))
                    .sorted()
                    .map(path -> {
                        String skillId = normalizeSlug(requireParentDirectoryName(path));
                        String relativePath = artifactDir.relativize(path).toString().replace('\\', '/');
                        return new ArtifactSkillEntry(skillId, relativePath);
                    })
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan pack skills under " + artifactDir, exception);
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
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading skill marketplace metadata from " + uri,
                    exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load skill marketplace metadata from " + uri, exception);
        }

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("Skill marketplace request failed with HTTP " + statusCode + " for " + uri);
        }
        return response.body();
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
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid sandbox path: " + exception.getMessage(), exception);
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

    private String normalizeSlug(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Slug is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            boolean alphanumeric = (current >= 'a' && current <= 'z') || (current >= '0' && current <= '9');
            boolean hyphen = current == '-';
            if (index == 0 && !alphanumeric) {
                throw new IllegalArgumentException("Slug must start with [a-z0-9]");
            }
            if (!alphanumeric && !hyphen) {
                throw new IllegalArgumentException("Slug must contain only [a-z0-9-]");
            }
        }
        return normalized;
    }

    private String parentDirectoryNameOrNull(Path path) {
        if (path == null) {
            return null;
        }
        Path parent = path.getParent();
        if (parent == null) {
            return null;
        }
        Path fileName = parent.getFileName();
        if (fileName == null) {
            return null;
        }
        return fileName.toString();
    }

    private String requireParentDirectoryName(Path path) {
        String value = parentDirectoryNameOrNull(path);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Path has no parent directory name: " + path);
        }
        return value;
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
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse artifact manifest", exception);
        }
    }

    private MaintainerManifest parseMaintainerManifest(String content) {
        try {
            MaintainerManifest manifest = YAML_MAPPER.readValue(content, MaintainerManifest.class);
            return manifest != null ? manifest : new MaintainerManifest();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse maintainer manifest", exception);
        }
    }

    private SkillMetadata parseSkillMetadata(String content) {
        Map<String, Object> metadata = skillDocumentService.parseDocument(content).metadata();
        return new SkillMetadata(
                trimToNull(asString(metadata.get("name"))),
                trimToNull(asString(metadata.get("description"))),
                trimToNull(asString(metadata.get("model_tier"))));
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
        String configured = trimToNull(marketplaceSettings().apiBaseUrl());
        String value = configured != null ? configured : DEFAULT_API_BASE_URL;
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private URI repositoryRawBaseUrl() {
        String configured = trimToNull(marketplaceSettings().rawBaseUrl());
        String value = configured != null ? configured : DEFAULT_RAW_BASE_URL;
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private SkillSettingsPort.MarketplaceSettings marketplaceSettings() {
        return settingsPort.skills().marketplace();
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

    private String runtimeString(Map<String, Object> settings, String key) {
        if (settings == null) {
            return null;
        }
        Object value = settings.get(key);
        return value != null ? value.toString() : null;
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private String fileNameOrEmpty(Path path) {
        if (path == null) {
            return "";
        }
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : "";
    }

    private String buildArtifactContentHash(String manifestContent, List<String> skillContentEntries) {
        List<String> parts = new ArrayList<>();
        parts.add("manifest\n" + manifestContent);
        parts.addAll(skillContentEntries.stream().sorted().toList());
        return SkillMarketplaceHashing.sha256Hex(String.join("\n===\n", parts));
    }

}

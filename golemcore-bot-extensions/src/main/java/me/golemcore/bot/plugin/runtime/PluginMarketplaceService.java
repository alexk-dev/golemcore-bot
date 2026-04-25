package me.golemcore.bot.plugin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.plugin.runtime.config.PluginRuntimeProperties;
import me.golemcore.plugin.api.extension.spi.PluginDescriptor;
import me.golemcore.plugin.api.extension.spi.PluginSettingsCatalogItem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads marketplace metadata from the plugins repository and installs plugin
 * artifacts into the runtime plugin directory.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PluginMarketplaceService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String PLUGIN_MANIFEST_PATH = "META-INF/golemcore/plugin.yaml";
    private static final String GITHUB_USER_AGENT = "golemcore-bot-marketplace";
    private static final Duration METADATA_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ARTIFACT_REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final Map<String, String> DISPLAY_NAME_OVERRIDES = Map.of(
            "lightrag", "LightRAG",
            "elevenlabs", "ElevenLabs");
    private static final Pattern GITHUB_PACKAGE_ENTRY_PATTERN = Pattern.compile(
            "title=\"([^\"]+)\" href=\"(/[^\"]+/packages/\\d+)\"");
    private static final Pattern GITHUB_PACKAGE_DOWNLOAD_PATTERN = Pattern.compile(
            "href=\"(https?://[^\"]+)\"");

    private final PluginRuntimeProperties pluginRuntimeProperties;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final PluginManager pluginManager;
    private final PluginSettingsRegistry pluginSettingsRegistry;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Object remoteCatalogLock = new Object();

    private final AtomicReference<RemoteCatalogCache> remoteCatalogCache = new AtomicReference<>();

    public PluginMarketplaceCatalog getCatalog() {
        if (!pluginRuntimeProperties.getMarketplace().isEnabled()) {
            return unavailable("Plugin marketplace is disabled by backend configuration.");
        }

        Optional<Path> repositoryRoot = resolveLocalRepositoryRoot();
        if (isLocalRepositoryConfigured() && repositoryRoot.isEmpty()) {
            return unavailable(
                    "Marketplace repository was not found. Configure bot.plugins.marketplace.repository-directory.");
        }

        String engineVersion = resolveEngineVersion();
        Map<String, TrustedPluginRecord> trustedPlugins;
        String sourceDirectory;
        try {
            if (repositoryRoot.isPresent()) {
                Path repoRoot = repositoryRoot.get();
                Path registryRoot = repoRoot.resolve("registry");
                if (!Files.isDirectory(registryRoot)) {
                    return unavailable("Marketplace registry directory is missing under " + repoRoot);
                }
                trustedPlugins = loadTrustedPlugins(repoRoot, engineVersion);
                sourceDirectory = repoRoot.toString();
            } else {
                trustedPlugins = loadRemoteCatalogPlugins(engineVersion);
                sourceDirectory = remoteRepositoryUrl().toString();
            }
        } catch (RuntimeException ex) {
            log.warn("[Plugins] Failed to load marketplace catalog: {}", ex.getMessage());
            return unavailable(buildMarketplaceLoadFailureMessage(repositoryRoot.isPresent()));
        }

        Map<String, PluginRuntimeInfo> loadedById = pluginManager.listPlugins().stream()
                .collect(java.util.stream.Collectors.toMap(
                        PluginRuntimeInfo::getId,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, String> settingsRouteByPlugin = pluginSettingsRegistry.listCatalogItems().stream()
                .collect(java.util.stream.Collectors.toMap(
                        PluginSettingsCatalogItem::getPluginId,
                        PluginSettingsCatalogItem::getRouteKey,
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<PluginMarketplaceItem> items = loadMarketplaceItems(trustedPlugins, loadedById, settingsRouteByPlugin);
        return PluginMarketplaceCatalog.builder()
                .available(true)
                .sourceDirectory(sourceDirectory)
                .items(items)
                .build();
    }

    public PluginInstallResult install(String pluginId, String version) {
        PluginCoordinates requestedPlugin = normalizePluginCoordinates(pluginId);
        if (requestedPlugin == null) {
            throw new IllegalArgumentException("pluginId is required");
        }
        String normalizedPluginId = requestedPlugin.id();
        String normalizedVersion = normalizeVersion(version);
        if (!pluginRuntimeProperties.getMarketplace().isEnabled()) {
            throw new IllegalStateException("Plugin marketplace is disabled");
        }

        String engineVersion = resolveEngineVersion();
        TrustedPluginRecord trustedPlugin = loadTrustedPluginForInstall(requestedPlugin, engineVersion);
        if (trustedPlugin == null) {
            throw new IllegalArgumentException("Unknown plugin: " + normalizedPluginId);
        }
        TrustedVersionRecord selectedVersion = selectVersion(trustedPlugin, normalizedVersion);
        String trustedPluginId = trustedPlugin.coordinates().id();
        String trustedVersion = selectedVersion.versionMetadata().getVersion();
        if (!selectedVersion.compatible()) {
            throw new IllegalStateException("Plugin " + normalizedPluginId + " is not compatible with engine "
                    + engineVersion);
        }
        if (!selectedVersion.artifactReference().isAvailable()) {
            throw new IllegalStateException(
                    "Artifact not found for " + normalizedPluginId + " at " + selectedVersion.artifactReference());
        }

        String artifactFileName = selectedVersion.artifactReference().fileName();
        Path pluginsRoot = pluginDirectoryRoot();
        Path destination = installDestination(pluginsRoot, trustedPlugin.coordinates(), trustedVersion,
                artifactFileName);
        String previouslyInstalledVersion = findInstalledVersion(trustedPlugin.coordinates());
        try {
            Path destinationParent = requireDestinationParent(pluginsRoot, destination);
            Files.createDirectories(destinationParent);
            Path tempFile = ensureContainedInRoot(pluginsRoot,
                    Files.createTempFile(destinationParent, "plugin-", ".tmp"),
                    "plugin temp artifact");
            try {
                copyArtifactTo(selectedVersion.artifactReference(), tempFile);
                moveAtomically(tempFile, destination);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to install plugin artifact for " + normalizedPluginId, ex);
        }

        boolean loaded = pluginManager.reloadPlugin(trustedPluginId);
        PluginMarketplaceItem plugin = getCatalog().getItems().stream()
                .filter(item -> trustedPluginId.equals(item.getId()))
                .findFirst()
                .orElseGet(() -> toMarketplaceItem(
                        trustedPlugin.indexMetadata(),
                        selectedVersion.versionMetadata(),
                        selectedVersion.descriptor(),
                        previouslyInstalledVersion,
                        null,
                        null,
                        selectedVersion.artifactReference(),
                        selectedVersion.compatible()));

        String status = resolveInstallStatus(previouslyInstalledVersion, trustedVersion);
        String message = buildInstallMessage(plugin, status, loaded);
        return new PluginInstallResult(status, message, plugin);
    }

    public PluginUninstallResult uninstall(String pluginId) {
        PluginCoordinates requestedPlugin = normalizePluginCoordinates(pluginId);
        if (requestedPlugin == null) {
            throw new IllegalArgumentException("pluginId is required");
        }

        String normalizedPluginId = requestedPlugin.id();
        String installedVersion = findInstalledVersion(requestedPlugin);
        String pluginName = humanizePluginName(requestedPlugin.name());
        if (installedVersion == null) {
            return new PluginUninstallResult("not-installed", pluginName + " is not installed.");
        }

        Path pluginsRoot = pluginDirectoryRoot();
        Path pluginDir = installedPluginDirectory(pluginsRoot, requestedPlugin);
        boolean unloaded = pluginManager.unloadPlugin(normalizedPluginId);
        deleteRecursively(pluginsRoot, pluginDir, "installed plugin directory");
        boolean reloaded = pluginManager.reloadPlugin(normalizedPluginId);
        String message = buildUninstallMessage(pluginName, unloaded, reloaded);
        return new PluginUninstallResult("uninstalled", message);
    }

    private List<PluginMarketplaceItem> loadMarketplaceItems(
            Map<String, TrustedPluginRecord> trustedPlugins,
            Map<String, PluginRuntimeInfo> loadedById,
            Map<String, String> settingsRouteByPlugin) {
        return trustedPlugins.values().stream()
                .map(plugin -> {
                    TrustedVersionRecord latestVersion = latestVersion(plugin);
                    String pluginId = plugin.coordinates().id();
                    return toMarketplaceItem(
                            plugin.indexMetadata(),
                            latestVersion.versionMetadata(),
                            latestVersion.descriptor(),
                            findInstalledVersion(plugin.coordinates()),
                            loadedById.get(pluginId),
                            settingsRouteByPlugin.get(pluginId),
                            latestVersion.artifactReference(),
                            latestVersion.compatible());
                })
                .sorted(Comparator
                        .comparing(PluginMarketplaceItem::isUpdateAvailable).reversed()
                        .thenComparing(PluginMarketplaceItem::isInstalled).reversed()
                        .thenComparing(PluginMarketplaceItem::getName))
                .toList();
    }

    private Map<String, TrustedPluginRecord> loadTrustedPlugins(Path repoRoot, String engineVersion) {
        Path registryRoot = requireDirectory(repoRoot.resolve("registry"), "marketplace registry");
        List<Path> indexPaths;
        try (Stream<Path> stream = Files.walk(registryRoot, 3)) {
            indexPaths = stream.filter(path -> "index.yaml".equals(fileNameOrEmpty(path)))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan marketplace registry " + registryRoot, ex);
        }

        Map<String, TrustedPluginRecord> trustedPlugins = new LinkedHashMap<>();
        for (Path indexPath : indexPaths) {
            try {
                TrustedPluginRecord plugin = loadTrustedPlugin(repoRoot, indexPath, engineVersion);
                if (plugin != null) {
                    trustedPlugins.putIfAbsent(plugin.coordinates().id(), plugin);
                }
            } catch (RuntimeException | IOException ex) {
                log.warn("[Plugins] Failed to read marketplace entry {}: {}", indexPath, ex.getMessage());
            }
        }
        return trustedPlugins;
    }

    private TrustedPluginRecord loadTrustedPlugin(Path repoRoot, Path indexPath, String engineVersion)
            throws IOException {
        RegistryIndexMetadata index = YAML_MAPPER.readValue(indexPath.toFile(), RegistryIndexMetadata.class);
        if (index == null || index.getId() == null || index.getLatest() == null) {
            return null;
        }
        String normalizedIndexPluginId = normalizePluginId(index.getId());
        if (normalizedIndexPluginId == null) {
            return null;
        }
        PluginCoordinates coordinates = parsePluginId(normalizedIndexPluginId);
        index.setId(coordinates.id());
        index.setVersions(normalizePublishedVersions(index.getVersions()));
        Path pluginRegistryDir = requireDirectory(
                indexPath.toAbsolutePath().normalize().resolve("..").normalize(),
                "plugin registry directory");
        Map<String, TrustedVersionRecord> versions = loadTrustedVersions(
                repoRoot,
                pluginRegistryDir,
                coordinates,
                index,
                engineVersion);
        String latestVersion = resolvePublishedVersion(index.getLatest(), index.getVersions(), versions.keySet());
        index.setLatest(latestVersion);
        return new TrustedPluginRecord(coordinates, index, versions, latestVersion);
    }

    private Map<String, TrustedVersionRecord> loadTrustedVersions(
            Path repoRoot,
            Path pluginRegistryDir,
            PluginCoordinates coordinates,
            RegistryIndexMetadata index,
            String engineVersion) throws IOException {
        Path versionsDir = requireDirectory(pluginRegistryDir.resolve("versions"), "plugin versions directory");
        List<Path> versionPaths;
        try (Stream<Path> stream = Files.list(versionsDir)) {
            versionPaths = stream.filter(Files::isRegularFile)
                    .filter(path -> fileNameOrEmpty(path).endsWith(".yaml"))
                    .sorted()
                    .toList();
        }

        Map<String, TrustedVersionRecord> versions = new LinkedHashMap<>();
        for (Path versionPath : versionPaths) {
            RegistryVersionMetadata versionMetadata = readVersionMetadata(versionPath);
            if (versionMetadata == null || versionMetadata.getVersion() == null) {
                continue;
            }
            String normalizedVersion = normalizeVersion(versionMetadata.getVersion());
            String metadataPluginId = versionMetadata.getId() != null
                    ? normalizePluginId(versionMetadata.getId())
                    : coordinates.id();
            if (!coordinates.id().equals(metadataPluginId)) {
                throw new IllegalArgumentException("Version metadata id must match plugin id");
            }
            versionMetadata.setId(metadataPluginId);
            versionMetadata.setVersion(normalizedVersion);
            ArtifactReference artifactReference = resolveLocalArtifactReference(repoRoot, coordinates, versionMetadata);
            PluginDescriptor descriptor = readDescriptor(artifactReference.localPath(), versionMetadata, index);
            boolean compatible = PluginVersionSupport.matchesVersionConstraint(
                    engineVersion,
                    versionMetadata.getEngineVersion());
            versions.put(normalizedVersion,
                    new TrustedVersionRecord(versionMetadata, descriptor, artifactReference, compatible));
        }
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("No marketplace versions found for " + coordinates.id());
        }
        return versions;
    }

    private Map<String, TrustedPluginRecord> loadRemoteCatalogPlugins(String engineVersion) {
        Duration cacheTtl = pluginRuntimeProperties.getMarketplace().getRemoteCacheTtl();
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            return fetchRemoteCatalogPlugins(engineVersion);
        }

        RemoteCatalogCache currentCache = remoteCatalogCache.get();
        Instant now = Instant.now();
        if (currentCache != null && now.isBefore(currentCache.loadedAt().plus(cacheTtl))) {
            return currentCache.trustedPlugins();
        }

        synchronized (remoteCatalogLock) {
            RemoteCatalogCache lockedCache = remoteCatalogCache.get();
            Instant refreshedNow = Instant.now();
            if (lockedCache != null && refreshedNow.isBefore(lockedCache.loadedAt().plus(cacheTtl))) {
                return lockedCache.trustedPlugins();
            }
            Map<String, TrustedPluginRecord> loaded = fetchRemoteCatalogPlugins(engineVersion);
            remoteCatalogCache.set(new RemoteCatalogCache(refreshedNow, loaded));
            return loaded;
        }
    }

    private Map<String, TrustedPluginRecord> fetchRemoteCatalogPlugins(String engineVersion) {
        RemoteRepositoryTree repositoryTree = fetchRemoteRepositoryTree();
        Map<String, URI> packagePageUrisByArtifactId = fetchRemotePackagePageUris();
        List<String> indexPaths = repositoryTree.indexPaths();
        Map<String, TrustedPluginRecord> trustedPlugins = new LinkedHashMap<>();
        for (String indexPath : indexPaths) {
            try {
                TrustedPluginRecord plugin = loadRemoteCatalogPlugin(indexPath, engineVersion,
                        packagePageUrisByArtifactId);
                if (plugin != null) {
                    trustedPlugins.putIfAbsent(plugin.coordinates().id(), plugin);
                }
            } catch (RuntimeException ex) {
                log.warn("[Plugins] Failed to read remote marketplace entry {}: {}", indexPath, ex.getMessage());
            }
        }
        return Map.copyOf(trustedPlugins);
    }

    private TrustedPluginRecord loadRemoteCatalogPlugin(
            String indexPath,
            String engineVersion,
            Map<String, URI> packagePageUrisByArtifactId) {
        RegistryIndexMetadata index = fetchRemoteYaml(indexPath, RegistryIndexMetadata.class);
        if (index == null || index.getId() == null || index.getLatest() == null) {
            return null;
        }
        String normalizedIndexPluginId = normalizePluginId(index.getId());
        if (normalizedIndexPluginId == null) {
            return null;
        }
        PluginCoordinates coordinates = parsePluginId(normalizedIndexPluginId);
        index.setId(coordinates.id());
        index.setVersions(normalizePublishedVersions(index.getVersions()));

        TrustedVersionRecord latestVersion = loadRemoteLatestVersionRecord(
                coordinates,
                index,
                engineVersion,
                packagePageUrisByArtifactId);
        return new TrustedPluginRecord(
                coordinates,
                index,
                Map.of(latestVersion.versionMetadata().getVersion(), latestVersion),
                latestVersion.versionMetadata().getVersion());
    }

    private TrustedPluginRecord loadRemotePlugin(PluginCoordinates coordinates, String engineVersion) {
        Map<String, URI> packagePageUrisByArtifactId = fetchRemotePackagePageUris();
        RegistryIndexMetadata index = fetchRemoteYaml(remoteRegistryIndexPath(coordinates),
                RegistryIndexMetadata.class);
        if (index == null || index.getId() == null) {
            throw new IllegalArgumentException("Unknown plugin: " + coordinates.id());
        }
        String normalizedIndexPluginId = normalizePluginId(index.getId());
        if (normalizedIndexPluginId == null || !coordinates.id().equals(normalizedIndexPluginId)) {
            throw new IllegalArgumentException("Unknown plugin: " + coordinates.id());
        }
        index.setId(coordinates.id());
        index.setVersions(normalizePublishedVersions(index.getVersions()));

        Map<String, TrustedVersionRecord> versions = loadRemoteVersions(
                coordinates,
                index,
                engineVersion,
                packagePageUrisByArtifactId);
        String latestVersion = resolvePublishedVersion(index.getLatest(), index.getVersions(), versions.keySet());
        return new TrustedPluginRecord(coordinates, index, versions, latestVersion);
    }

    private Map<String, TrustedVersionRecord> loadRemoteVersions(
            PluginCoordinates coordinates,
            RegistryIndexMetadata index,
            String engineVersion,
            Map<String, URI> packagePageUrisByArtifactId) {
        List<String> versionsToLoad = new ArrayList<>();
        if (index.getVersions() != null && !index.getVersions().isEmpty()) {
            versionsToLoad.addAll(index.getVersions());
        } else {
            String latestVersion = normalizeVersion(index.getLatest());
            if (latestVersion != null) {
                versionsToLoad.add(latestVersion);
            }
        }

        Map<String, TrustedVersionRecord> versions = new LinkedHashMap<>();
        for (String version : versionsToLoad) {
            try {
                TrustedVersionRecord versionRecord = loadRemoteVersionRecord(coordinates, index, version,
                        engineVersion, packagePageUrisByArtifactId);
                versions.put(versionRecord.versionMetadata().getVersion(), versionRecord);
            } catch (RuntimeException ex) {
                log.warn("[Plugins] Failed to read remote marketplace version {} {}: {}",
                        coordinates.id(), version, ex.getMessage());
            }
        }
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("No marketplace versions found for " + coordinates.id());
        }
        return versions;
    }

    private TrustedVersionRecord loadRemoteLatestVersionRecord(
            PluginCoordinates coordinates,
            RegistryIndexMetadata index,
            String engineVersion,
            Map<String, URI> packagePageUrisByArtifactId) {
        RuntimeException lastError = null;
        for (String candidate : buildRemoteVersionCandidates(index)) {
            try {
                return loadRemoteVersionRecord(coordinates, index, candidate, engineVersion,
                        packagePageUrisByArtifactId);
            } catch (RuntimeException ex) {
                lastError = ex;
            }
        }
        if (lastError != null) {
            throw new IllegalStateException(
                    "Failed to load latest marketplace version for " + coordinates.id(),
                    lastError);
        }
        throw new IllegalArgumentException("No marketplace versions found for " + coordinates.id());
    }

    private List<String> buildRemoteVersionCandidates(RegistryIndexMetadata index) {
        List<String> candidates = new ArrayList<>();
        String latestVersion = normalizeVersion(index.getLatest());
        if (latestVersion != null) {
            candidates.add(latestVersion);
        }
        if (index.getVersions() != null && !index.getVersions().isEmpty()) {
            List<String> published = new ArrayList<>(index.getVersions());
            published.sort(PluginVersionSupport::compareVersions);
            for (int indexPosition = published.size() - 1; indexPosition >= 0; indexPosition--) {
                String publishedVersion = published.get(indexPosition);
                if (!candidates.contains(publishedVersion)) {
                    candidates.add(publishedVersion);
                }
            }
        }
        return candidates;
    }

    private TrustedVersionRecord loadRemoteVersionRecord(
            PluginCoordinates coordinates,
            RegistryIndexMetadata index,
            String version,
            String engineVersion,
            Map<String, URI> packagePageUrisByArtifactId) {
        RegistryVersionMetadata versionMetadata = fetchRemoteYaml(
                remoteRegistryVersionPath(coordinates, version),
                RegistryVersionMetadata.class);
        normalizeVersionMetadata(versionMetadata, coordinates);
        ArtifactReference artifactReference = resolveRemoteArtifactReference(coordinates, versionMetadata,
                packagePageUrisByArtifactId);
        PluginDescriptor descriptor = buildDescriptorFromMetadata(versionMetadata, index);
        boolean compatible = PluginVersionSupport.matchesVersionConstraint(
                engineVersion,
                versionMetadata.getEngineVersion());
        return new TrustedVersionRecord(versionMetadata, descriptor, artifactReference, compatible);
    }

    private void normalizeVersionMetadata(RegistryVersionMetadata versionMetadata, PluginCoordinates coordinates) {
        if (versionMetadata == null || versionMetadata.getVersion() == null) {
            throw new IllegalArgumentException("Marketplace version metadata is invalid for " + coordinates.id());
        }
        String normalizedVersion = normalizeVersion(versionMetadata.getVersion());
        String metadataPluginId = versionMetadata.getId() != null
                ? normalizePluginId(versionMetadata.getId())
                : coordinates.id();
        if (!coordinates.id().equals(metadataPluginId)) {
            throw new IllegalArgumentException("Version metadata id must match plugin id");
        }
        versionMetadata.setId(metadataPluginId);
        versionMetadata.setVersion(normalizedVersion);
    }

    private RemoteRepositoryTree fetchRemoteRepositoryTree() {
        URI treeUri = remoteTreeApiUri();
        String responseBody = fetchText(treeUri, METADATA_REQUEST_TIMEOUT);
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON_MAPPER.readTree(responseBody);
            List<String> indexPaths = new ArrayList<>();
            Set<String> blobPaths = new HashSet<>();
            com.fasterxml.jackson.databind.JsonNode treeNode = root.path("tree");
            if (!treeNode.isArray()) {
                return new RemoteRepositoryTree(List.of(), Set.of());
            }
            for (com.fasterxml.jackson.databind.JsonNode entry : treeNode) {
                String type = entry.path("type").asText("");
                String path = entry.path("path").asText("");
                if ("blob".equals(type) && !path.isBlank()) {
                    blobPaths.add(path);
                    if (isRegistryIndexPath(path)) {
                        indexPaths.add(path);
                    }
                }
            }
            indexPaths.sort(String::compareTo);
            return new RemoteRepositoryTree(List.copyOf(indexPaths), Set.copyOf(blobPaths));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse marketplace tree metadata", ex);
        }
    }

    private boolean isRegistryIndexPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        Path candidate = Path.of(path);
        return candidate.getNameCount() == 4
                && "registry".equals(candidate.getName(0).toString())
                && "index.yaml".equals(candidate.getName(3).toString());
    }

    private TrustedVersionRecord latestVersion(TrustedPluginRecord plugin) {
        TrustedVersionRecord latestVersion = plugin.versions().get(plugin.latestVersion());
        if (latestVersion == null) {
            throw new IllegalStateException(
                    "Latest marketplace version was not found for " + plugin.coordinates().id());
        }
        return latestVersion;
    }

    private TrustedVersionRecord selectVersion(TrustedPluginRecord plugin, String requestedVersion) {
        String versionCandidate = requestedVersion != null ? requestedVersion : plugin.latestVersion();
        String selectedVersion = resolvePublishedVersion(
                versionCandidate,
                plugin.indexMetadata().getVersions(),
                plugin.versions().keySet());
        TrustedVersionRecord version = plugin.versions().get(selectedVersion);
        if (version == null) {
            throw new IllegalArgumentException("Unknown plugin version: " + selectedVersion);
        }
        return version;
    }

    private String resolvePublishedVersion(
            String candidateVersion,
            List<String> publishedVersions,
            Set<String> availableVersions) {
        String normalizedVersion = normalizeVersion(candidateVersion);
        if (normalizedVersion == null) {
            throw new IllegalArgumentException("Plugin version is required");
        }
        if (publishedVersions != null && !publishedVersions.isEmpty()) {
            boolean published = false;
            for (String publishedVersion : publishedVersions) {
                if (normalizedVersion.equals(publishedVersion)) {
                    published = true;
                    break;
                }
            }
            if (!published) {
                throw new IllegalArgumentException("Unknown plugin version: " + normalizedVersion);
            }
        }
        if (!availableVersions.contains(normalizedVersion)) {
            throw new IllegalArgumentException("Unknown plugin version: " + normalizedVersion);
        }
        return normalizedVersion;
    }

    private List<String> normalizePublishedVersions(List<String> versions) {
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }
        List<String> normalizedVersions = new ArrayList<>();
        for (String version : versions) {
            String normalizedVersion = normalizeVersion(version);
            if (normalizedVersion != null) {
                normalizedVersions.add(normalizedVersion);
            }
        }
        return List.copyOf(normalizedVersions);
    }

    private PluginMarketplaceItem toMarketplaceItem(
            RegistryIndexMetadata index,
            RegistryVersionMetadata version,
            PluginDescriptor descriptor,
            String installedVersion,
            PluginRuntimeInfo loaded,
            String settingsRouteKey,
            ArtifactReference artifactReference,
            boolean compatible) {
        boolean installed = installedVersion != null && !installedVersion.isBlank();
        boolean loadedFlag = loaded != null && loaded.isLoaded();
        String loadedVersion = loaded != null ? loaded.getVersion() : null;
        String name = humanizePluginName(descriptor.getName() != null ? descriptor.getName() : index.getName());
        return PluginMarketplaceItem.builder()
                .id(index.getId())
                .provider(descriptor.getProvider())
                .name(name)
                .description(descriptor.getDescription())
                .version(version.getVersion())
                .pluginApiVersion(version.getPluginApiVersion())
                .engineVersion(version.getEngineVersion())
                .sourceUrl(descriptor.getSourceUrl())
                .license(descriptor.getLicense())
                .maintainers(descriptor.getMaintainers() != null ? descriptor.getMaintainers() : List.of())
                .official("golemcore".equals(descriptor.getProvider()))
                .compatible(compatible)
                .artifactAvailable(artifactReference.isAvailable())
                .installed(installed)
                .loaded(loadedFlag)
                .updateAvailable(
                        installed && PluginVersionSupport.compareVersions(version.getVersion(), installedVersion) > 0)
                .installedVersion(installedVersion)
                .loadedVersion(loadedVersion)
                .settingsRouteKey(settingsRouteKey)
                .build();
    }

    private RegistryVersionMetadata readVersionMetadata(Path versionPath) throws IOException {
        if (!Files.isRegularFile(versionPath)) {
            throw new IllegalArgumentException("Unknown plugin version metadata: " + versionPath.getFileName());
        }
        return YAML_MAPPER.readValue(versionPath.toFile(), RegistryVersionMetadata.class);
    }

    private PluginDescriptor readDescriptor(
            Path artifactPath,
            RegistryVersionMetadata versionMetadata,
            RegistryIndexMetadata indexMetadata) throws IOException {
        PluginDescriptor descriptor = Files.isRegularFile(artifactPath)
                ? readDescriptorFromArtifact(artifactPath)
                : new PluginDescriptor();
        descriptor.setId(coalesce(descriptor.getId(), versionMetadata.getId(), indexMetadata.getId()));
        descriptor.setProvider(coalesce(descriptor.getProvider(), ownerFromPluginId(indexMetadata.getId())));
        descriptor.setName(coalesce(descriptor.getName(), indexMetadata.getName()));
        descriptor.setVersion(coalesce(descriptor.getVersion(), versionMetadata.getVersion()));
        descriptor.setPluginApiVersion(descriptor.getPluginApiVersion() != null
                ? descriptor.getPluginApiVersion()
                : versionMetadata.getPluginApiVersion());
        descriptor.setEngineVersion(coalesce(descriptor.getEngineVersion(), versionMetadata.getEngineVersion()));
        descriptor.setEntrypoint(coalesce(descriptor.getEntrypoint(), versionMetadata.getEntrypoint()));
        descriptor.setSourceUrl(
                coalesce(descriptor.getSourceUrl(), versionMetadata.getSourceUrl(), indexMetadata.getSource()));
        descriptor.setLicense(coalesce(descriptor.getLicense(), versionMetadata.getLicense()));
        descriptor.setMaintainers(descriptor.getMaintainers() != null && !descriptor.getMaintainers().isEmpty()
                ? descriptor.getMaintainers()
                : versionMetadata.getMaintainers());
        return descriptor;
    }

    private PluginDescriptor buildDescriptorFromMetadata(
            RegistryVersionMetadata versionMetadata,
            RegistryIndexMetadata indexMetadata) {
        PluginDescriptor descriptor = new PluginDescriptor();
        descriptor.setId(coalesce(versionMetadata.getId(), indexMetadata.getId()));
        descriptor.setProvider(ownerFromPluginId(indexMetadata.getId()));
        descriptor.setName(indexMetadata.getName());
        descriptor.setVersion(versionMetadata.getVersion());
        descriptor.setPluginApiVersion(versionMetadata.getPluginApiVersion());
        descriptor.setEngineVersion(versionMetadata.getEngineVersion());
        descriptor.setEntrypoint(versionMetadata.getEntrypoint());
        descriptor.setSourceUrl(coalesce(versionMetadata.getSourceUrl(), indexMetadata.getSource()));
        descriptor.setLicense(versionMetadata.getLicense());
        descriptor.setMaintainers(versionMetadata.getMaintainers());
        return descriptor;
    }

    private PluginDescriptor readDescriptorFromArtifact(Path artifactPath) throws IOException {
        try (JarFile jarFile = new JarFile(artifactPath.toFile())) {
            java.util.jar.JarEntry entry = jarFile.getJarEntry(PLUGIN_MANIFEST_PATH);
            if (entry == null) {
                return new PluginDescriptor();
            }
            try (InputStream stream = jarFile.getInputStream(entry)) {
                return YAML_MAPPER.readValue(stream, PluginDescriptor.class);
            }
        }
    }

    private Optional<Path> resolveLocalRepositoryRoot() {
        String configured = pluginRuntimeProperties.getMarketplace().getRepositoryDirectory();
        if (configured != null && !configured.isBlank()) {
            Path configuredPath = Path.of(configured).toAbsolutePath().normalize();
            return Files.isDirectory(configuredPath) ? Optional.of(configuredPath) : Optional.empty();
        }
        return Optional.empty();
    }

    private boolean isLocalRepositoryConfigured() {
        String configured = pluginRuntimeProperties.getMarketplace().getRepositoryDirectory();
        return configured != null && !configured.isBlank();
    }

    private ArtifactReference resolveLocalArtifactReference(
            Path repositoryRoot,
            PluginCoordinates coordinates,
            RegistryVersionMetadata versionMetadata) {
        Path artifactDirectory = artifactDirectory(repositoryRoot, coordinates, versionMetadata.getVersion());
        if (!Files.isDirectory(artifactDirectory)) {
            return ArtifactReference.local(missingArtifactPath(repositoryRoot));
        }
        String expectedFileName = parseArtifactFileName(versionMetadata.getArtifactUrl(), coordinates,
                versionMetadata.getVersion());
        try (Stream<Path> stream = Files.list(artifactDirectory)) {
            List<Path> jarFiles = stream.filter(Files::isRegularFile)
                    .filter(path -> fileNameOrEmpty(path).endsWith(".jar"))
                    .sorted()
                    .toList();
            if (expectedFileName != null) {
                Path resolvedPath = jarFiles.stream()
                        .filter(path -> expectedFileName.equals(fileNameOrEmpty(path)))
                        .findFirst()
                        .orElseGet(() -> missingArtifactPath(repositoryRoot));
                return ArtifactReference.local(resolvedPath);
            }
            Path resolvedPath = jarFiles.isEmpty() ? missingArtifactPath(repositoryRoot) : jarFiles.getFirst();
            return ArtifactReference.local(resolvedPath);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect plugin artifacts under " + artifactDirectory, ex);
        }
    }

    private Path installDestination(
            Path pluginsRoot,
            PluginCoordinates coordinates,
            String version,
            String artifactFileName) {
        String safeVersion = normalizeVersion(version);
        String safeArtifactFileName = validateFileName(artifactFileName, ".jar", "artifact file name");
        Path destination = pluginsRoot
                .resolve(coordinates.owner())
                .resolve(coordinates.name())
                .resolve(safeVersion)
                .resolve(safeArtifactFileName);
        return ensureContainedInRoot(pluginsRoot, destination, "plugin install destination");
    }

    private TrustedPluginRecord loadTrustedPluginForInstall(PluginCoordinates coordinates, String engineVersion) {
        if (isLocalRepositoryConfigured()) {
            Path repositoryRoot = resolveLocalRepositoryRoot()
                    .orElseThrow(() -> new IllegalStateException("Marketplace repository was not found"));
            Map<String, TrustedPluginRecord> trustedPlugins = loadTrustedPlugins(repositoryRoot, engineVersion);
            return trustedPlugins.get(coordinates.id());
        }
        return loadRemotePlugin(coordinates, engineVersion);
    }

    private String buildMarketplaceLoadFailureMessage(boolean localRepository) {
        if (localRepository) {
            return "Marketplace repository could not be loaded from configured directory.";
        }
        return "Marketplace repository could not be loaded from " + remoteRepositoryUrl();
    }

    private void copyArtifactTo(ArtifactReference artifactReference, Path destination) throws IOException {
        if (artifactReference.localPath() != null) {
            Files.copy(artifactReference.localPath(), destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        URI remoteUri = artifactReference.remoteUri();
        if (remoteUri == null) {
            throw new IllegalStateException("Artifact source is not available");
        }

        HttpRequest request = HttpRequest.newBuilder(remoteUri)
                .GET()
                .timeout(ARTIFACT_REQUEST_TIMEOUT)
                .header("User-Agent", GITHUB_USER_AGENT)
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading marketplace artifact", ex);
        }
        ensureSuccessfulResponse(response, remoteUri);
        try (InputStream inputStream = response.body()) {
            Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private <T> T fetchRemoteYaml(String path, Class<T> type) {
        URI uri = remoteRawFileUri(path);
        String payload = fetchText(uri, METADATA_REQUEST_TIMEOUT);
        try {
            return YAML_MAPPER.readValue(payload, type);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse marketplace metadata from " + uri, ex);
        }
    }

    private String fetchText(URI uri, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .header("User-Agent", GITHUB_USER_AGENT)
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading marketplace metadata from " + uri, ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load marketplace metadata from " + uri, ex);
        }
        ensureSuccessfulResponse(response, uri);
        return response.body();
    }

    private void ensureSuccessfulResponse(HttpResponse<?> response, URI uri) {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        throw new IllegalStateException("Marketplace request failed with HTTP " + statusCode + " for " + uri);
    }

    private ArtifactReference resolveRemoteArtifactReference(
            PluginCoordinates coordinates,
            RegistryVersionMetadata versionMetadata,
            Map<String, URI> packagePageUrisByArtifactId) {
        String artifactUrl = versionMetadata.getArtifactUrl();
        if (artifactUrl == null || artifactUrl.isBlank()) {
            return ArtifactReference.missing();
        }
        if (looksLikeAbsoluteHttpUrl(artifactUrl)) {
            URI artifactUri = URI.create(artifactUrl);
            String artifactFileName = resolveRemoteArtifactFileName(artifactUri);
            return artifactFileName != null
                    ? ArtifactReference.remote(artifactUri, artifactFileName)
                    : ArtifactReference.missing();
        }
        String artifactFileName = parseArtifactFileName(artifactUrl, coordinates, versionMetadata.getVersion());
        if (artifactFileName == null) {
            return ArtifactReference.missing();
        }
        URI packagePageUri = findRemotePackagePageUri(packagePageUrisByArtifactId, artifactFileName,
                versionMetadata.getVersion());
        if (packagePageUri == null) {
            return ArtifactReference.missing();
        }
        URI downloadUri = resolveRemotePackageDownloadUri(packagePageUri, versionMetadata.getVersion(),
                artifactFileName);
        if (downloadUri == null) {
            return ArtifactReference.missing();
        }
        return ArtifactReference.remote(downloadUri, artifactFileName);
    }

    private boolean looksLikeAbsoluteHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.startsWith("https://") || normalized.startsWith("http://");
    }

    private URI remoteRepositoryUrl() {
        String repositoryUrl = pluginRuntimeProperties.getMarketplace().getRepositoryUrl();
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new IllegalStateException("bot.plugins.marketplace.repository-url must not be blank");
        }
        return URI.create(repositoryUrl.endsWith("/") ? repositoryUrl : repositoryUrl + "/");
    }

    private URI remoteTreeApiUri() {
        PluginRuntimeProperties.MarketplaceProperties marketplace = pluginRuntimeProperties.getMarketplace();
        GitHubRepository repository = parseRemoteRepository();
        String encodedBranch = encodePathSegment(marketplace.getBranch());
        String relativePath = "repos/" + repository.owner() + "/" + repository.name()
                + "/git/trees/" + encodedBranch + "?recursive=1";
        return buildUri(marketplace.getApiBaseUrl(), relativePath);
    }

    private URI remoteRawFileUri(String filePath) {
        return remoteRawFileUri(remoteBranchRef(), filePath);
    }

    private URI remoteRawFileUri(String ref, String filePath) {
        PluginRuntimeProperties.MarketplaceProperties marketplace = pluginRuntimeProperties.getMarketplace();
        GitHubRepository repository = parseRemoteRepository();
        String relativePath = repository.owner() + "/" + repository.name() + "/" + ref + "/" + filePath;
        return buildUri(marketplace.getRawBaseUrl(), relativePath);
    }

    private URI remotePackagesPageUri() {
        return remoteRepositoryUrl().resolve("packages");
    }

    private String remoteRegistryIndexPath(PluginCoordinates coordinates) {
        return "registry/" + coordinates.owner() + "/" + coordinates.name() + "/index.yaml";
    }

    private String remoteRegistryVersionPath(PluginCoordinates coordinates, String version) {
        return "registry/" + coordinates.owner() + "/" + coordinates.name() + "/versions/" + version + ".yaml";
    }

    private Map<String, URI> fetchRemotePackagePageUris() {
        URI packagesUri = remotePackagesPageUri();
        String responseBody = fetchText(packagesUri, METADATA_REQUEST_TIMEOUT);
        Matcher matcher = GITHUB_PACKAGE_ENTRY_PATTERN.matcher(responseBody);
        Map<String, URI> packagePageUris = new LinkedHashMap<>();
        while (matcher.find()) {
            String packageName = matcher.group(1);
            String artifactId = artifactIdFromPackageName(packageName);
            if (artifactId == null) {
                continue;
            }
            URI packagePageUri = remoteRepositoryUrl().resolve(matcher.group(2));
            packagePageUris.putIfAbsent(artifactId, packagePageUri);
        }
        return Map.copyOf(packagePageUris);
    }

    private String artifactIdFromPackageName(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return null;
        }
        int separatorIndex = packageName.lastIndexOf('.');
        String artifactId = separatorIndex >= 0 ? packageName.substring(separatorIndex + 1) : packageName;
        return artifactId.isBlank() ? null : artifactId;
    }

    private URI findRemotePackagePageUri(
            Map<String, URI> packagePageUrisByArtifactId,
            String artifactFileName,
            String version) {
        String artifactId = parseArtifactId(artifactFileName, version);
        return packagePageUrisByArtifactId.get(artifactId);
    }

    private URI resolveRemotePackageDownloadUri(URI packagePageUri, String version, String artifactFileName) {
        URI versionPageUri = appendQueryParameter(packagePageUri, "version", version);
        String responseBody = fetchText(versionPageUri, METADATA_REQUEST_TIMEOUT);
        Matcher matcher = GITHUB_PACKAGE_DOWNLOAD_PATTERN.matcher(responseBody);
        while (matcher.find()) {
            String href = decodeHtmlHref(matcher.group(1));
            URI candidate = URI.create(href);
            String fileName = resolveRemoteArtifactFileName(candidate);
            if (artifactFileName.equals(fileName)) {
                return candidate;
            }
        }
        return null;
    }

    private URI appendQueryParameter(URI uri, String name, String value) {
        String separator = uri.getQuery() == null || uri.getQuery().isBlank() ? "?" : "&";
        return URI.create(uri + separator + encodePathSegment(name) + "=" + encodePathSegment(value));
    }

    private String decodeHtmlHref(String href) {
        return href.replace("&amp;", "&");
    }

    private String resolveRemoteArtifactFileName(URI artifactUri) {
        String dispositionFileName = extractDispositionFileName(artifactUri);
        if (dispositionFileName != null) {
            return dispositionFileName;
        }
        String path = artifactUri.getPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        int separatorIndex = path.lastIndexOf('/');
        String fileName = separatorIndex >= 0 ? path.substring(separatorIndex + 1) : path;
        return fileName.isBlank() ? null : fileName;
    }

    private String extractDispositionFileName(URI artifactUri) {
        String query = artifactUri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String segment : query.split("&")) {
            int separatorIndex = segment.indexOf('=');
            String name = separatorIndex >= 0 ? segment.substring(0, separatorIndex) : segment;
            if (!"response-content-disposition".equals(name)) {
                continue;
            }
            String value = separatorIndex >= 0 ? segment.substring(separatorIndex + 1) : "";
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (decoded.startsWith("filename=") && decoded.length() > "filename=".length()) {
                return decoded.substring("filename=".length());
            }
        }
        return null;
    }

    private GitHubRepository parseRemoteRepository() {
        URI repositoryUrl = remoteRepositoryUrl();
        String path = repositoryUrl.getPath();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Marketplace repository URL is invalid: " + repositoryUrl);
        }
        String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (normalizedPath.endsWith(".git")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 4);
        }
        String[] segments = normalizedPath.split("/");
        if (segments.length < 3) {
            throw new IllegalStateException("Marketplace repository URL must match <host>/<owner>/<repo>");
        }
        String owner = segments[segments.length - 2];
        String name = segments[segments.length - 1];
        if (owner.isBlank() || name.isBlank()) {
            throw new IllegalStateException("Marketplace repository URL must match <host>/<owner>/<repo>");
        }
        return new GitHubRepository(owner, name);
    }

    private URI buildUri(String baseUrl, String relativePath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Marketplace base URL must not be blank");
        }
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return URI.create(normalizedBaseUrl).resolve(relativePath);
    }

    private String encodePathSegment(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return encoded.replace("+", "%20");
    }

    private String remoteBranchRef() {
        String branch = pluginRuntimeProperties.getMarketplace().getBranch();
        if (branch == null || branch.isBlank()) {
            throw new IllegalStateException("bot.plugins.marketplace.branch must not be blank");
        }
        return branch;
    }

    private Path requireDestinationParent(Path pluginsRoot, Path destination) {
        Path destinationParent = destination.getParent();
        if (destinationParent == null) {
            throw new IllegalStateException("Invalid plugin install destination: " + destination);
        }
        return ensureContainedInRoot(pluginsRoot, destinationParent, "plugin install destination");
    }

    private String findInstalledVersion(PluginCoordinates coordinates) {
        Path pluginsRoot = pluginDirectoryRoot();
        Path pluginDir = installedPluginDirectory(pluginsRoot, coordinates);
        if (!Files.isDirectory(pluginDir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(pluginDir)) {
            return stream.filter(Files::isDirectory)
                    .map(this::fileNameOrNull)
                    .filter(version -> version != null && !version.isBlank())
                    .filter(version -> {
                        try {
                            String normalizedVersion = normalizeVersion(version);
                            return hasJarFile(ensureContainedInRoot(pluginDir, pluginDir.resolve(normalizedVersion),
                                    "installed plugin version directory"));
                        } catch (IllegalArgumentException ex) {
                            return false;
                        }
                    })
                    .max(PluginVersionSupport::compareVersions)
                    .orElse(null);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect installed plugin directory for " + coordinates.id(), ex);
        }
    }

    private Path installedPluginDirectory(Path pluginsRoot, PluginCoordinates coordinates) {
        return ensureContainedInRoot(pluginsRoot,
                pluginsRoot.resolve(coordinates.owner()).resolve(coordinates.name()),
                "installed plugin directory");
    }

    private boolean hasJarFile(Path versionDir) {
        try (Stream<Path> stream = Files.list(versionDir)) {
            return stream.anyMatch(path -> Files.isRegularFile(path) && fileNameOrEmpty(path).endsWith(".jar"));
        } catch (IOException ex) {
            return false;
        }
    }

    private void deleteRecursively(Path root, Path directory, String label) {
        Path containedDirectory = ensureContainedInRoot(root, directory, label);
        if (!Files.exists(containedDirectory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(containedDirectory)) {
            List<Path> pathsToDelete = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path current : pathsToDelete) {
                Files.deleteIfExists(current);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete " + label + " " + containedDirectory, ex);
        }
    }

    private String resolveEngineVersion() {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        String version = buildProperties != null ? buildProperties.getVersion() : null;
        return PluginVersionSupport.normalizeHostVersion(version != null ? version : "0.0.0-SNAPSHOT");
    }

    private PluginMarketplaceCatalog unavailable(String message) {
        return PluginMarketplaceCatalog.builder()
                .available(false)
                .message(message)
                .items(List.of())
                .build();
    }

    private String resolveInstallStatus(String previousVersion, String targetVersion) {
        if (previousVersion == null || previousVersion.isBlank()) {
            return "installed";
        }
        if (PluginVersionSupport.compareVersions(targetVersion, previousVersion) > 0) {
            return "updated";
        }
        if (PluginVersionSupport.compareVersions(targetVersion, previousVersion) == 0) {
            return "already-installed";
        }
        return "installed";
    }

    private String buildInstallMessage(PluginMarketplaceItem plugin, String status, boolean loaded) {
        String prefix = switch (status) {
        case "updated" -> plugin.getName() + " updated to " + plugin.getVersion();
        case "already-installed" -> plugin.getName() + " is already installed";
        default -> plugin.getName() + " installed";
        };
        return loaded ? prefix + " and reloaded." : prefix + ". Artifact copied, but runtime load should be checked.";
    }

    private String buildUninstallMessage(String pluginName, boolean unloaded, boolean reloaded) {
        if (reloaded) {
            return pluginName + " marketplace files removed. Another artifact remains loaded.";
        }
        if (unloaded) {
            return pluginName + " uninstalled and unloaded.";
        }
        return pluginName + " uninstalled.";
    }

    private String normalizePluginId(String pluginId) {
        PluginCoordinates coordinates = normalizePluginCoordinates(pluginId);
        return coordinates != null ? coordinates.id() : null;
    }

    private PluginCoordinates normalizePluginCoordinates(String pluginId) {
        if (pluginId == null) {
            return null;
        }
        String normalized = pluginId.trim().toLowerCase(Locale.ROOT);
        return parsePluginId(normalized);
    }

    private String ownerFromPluginId(String pluginId) {
        if (pluginId == null) {
            return "";
        }
        try {
            return parsePluginId(pluginId).owner();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private String humanizePluginName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "Plugin";
        }
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);
        String override = DISPLAY_NAME_OVERRIDES.get(normalized);
        if (override != null) {
            return override;
        }
        StringBuilder humanized = new StringBuilder(normalized.length());
        boolean capitalizeNext = true;
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (current == '-') {
                if (!humanized.isEmpty() && humanized.charAt(humanized.length() - 1) != ' ') {
                    humanized.append(' ');
                }
                capitalizeNext = true;
                continue;
            }
            if (capitalizeNext) {
                humanized.append(Character.toUpperCase(current));
                capitalizeNext = false;
            } else {
                humanized.append(current);
            }
        }
        int length = humanized.length();
        if (length > 0 && humanized.charAt(length - 1) == ' ') {
            humanized.setLength(length - 1);
        }
        return humanized.isEmpty() ? "Plugin" : humanized.toString();
    }

    @SafeVarargs
    private static <T> T coalesce(T... values) {
        for (T value : values) {
            if (value instanceof String stringValue) {
                if (!stringValue.isBlank()) {
                    return value;
                }
                continue;
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String normalized = version.trim();
        PluginVersionSupport.compareVersions(normalized, normalized);
        return normalized;
    }

    private PluginCoordinates parsePluginId(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("Plugin id must match <owner>/<plugin>");
        }
        int separatorIndex = pluginId.indexOf('/');
        if (separatorIndex <= 0 || separatorIndex != pluginId.lastIndexOf('/')
                || separatorIndex == pluginId.length() - 1) {
            throw new IllegalArgumentException("Plugin id must match <owner>/<plugin>");
        }
        String owner = pluginId.substring(0, separatorIndex);
        String name = pluginId.substring(separatorIndex + 1);
        validateIdentifier(owner, "Plugin owner");
        validateIdentifier(name, "Plugin name");
        return new PluginCoordinates(owner, name);
    }

    private void validateIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean alphanumeric = (current >= 'a' && current <= 'z') || (current >= '0' && current <= '9');
            if (index == 0 && !alphanumeric) {
                throw new IllegalArgumentException(label + " must start with a lowercase letter or digit");
            }
            if (!alphanumeric && current != '-') {
                throw new IllegalArgumentException(label + " must contain only lowercase letters, digits, and '-'");
            }
        }
    }

    private void validateRelativePath(Path relativePath, boolean allowDots) {
        if (relativePath.getNameCount() == 0) {
            throw new IllegalArgumentException("Relative path must not be empty");
        }
        for (Path segment : relativePath) {
            String value = segment.toString();
            validatePathSegment(value, allowDots);
        }
    }

    private void validatePathSegment(String value, boolean allowDots) {
        if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("Path segment must not be blank or relative");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean alphanumeric = (current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9');
            boolean punctuation = current == '-' || current == '_';
            boolean dot = allowDots && current == '.';
            if (!alphanumeric && !punctuation && !dot) {
                throw new IllegalArgumentException("Path segment contains unsupported characters");
            }
        }
    }

    private String validateFileName(String fileName, String suffix, String label) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (!fileName.endsWith(suffix)) {
            throw new IllegalArgumentException(label + " must end with " + suffix);
        }
        validatePathSegment(fileName, true);
        return fileName;
    }

    private Path requireDirectory(Path path, String label) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(label + " was not found: " + normalized);
        }
        return normalized;
    }

    private Path ensureContainedInRoot(Path root, Path candidate, String label) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(label + " must stay within " + normalizedRoot);
        }
        return normalizedCandidate;
    }

    private String fileNameOrEmpty(Path path) {
        Path fileName = path != null ? path.getFileName() : null;
        return fileName != null ? fileName.toString() : "";
    }

    private String fileNameOrNull(Path path) {
        Path fileName = path != null ? path.getFileName() : null;
        return fileName != null ? fileName.toString() : null;
    }

    private String parseArtifactId(String artifactFileName, String version) {
        String normalizedVersion = normalizeVersion(version);
        String validatedFileName = validateFileName(artifactFileName, ".jar", "artifact file name");
        String versionSuffix = "-" + normalizedVersion + ".jar";
        if (validatedFileName.endsWith(versionSuffix) && validatedFileName.length() > versionSuffix.length()) {
            return validatedFileName.substring(0, validatedFileName.length() - versionSuffix.length());
        }
        return validatedFileName.substring(0, validatedFileName.length() - ".jar".length());
    }

    private String parseArtifactFileName(String artifactUrl, PluginCoordinates coordinates, String version) {
        Path artifactPath = parseArtifactRelativePath(artifactUrl, coordinates, version);
        if (artifactPath == null) {
            return null;
        }
        Path fileNamePath = artifactPath.getFileName();
        if (fileNamePath == null) {
            throw new IllegalArgumentException("Marketplace artifact path must include a file name");
        }
        return fileNamePath.toString();
    }

    private Path parseArtifactRelativePath(String artifactUrl, PluginCoordinates coordinates, String version) {
        if (artifactUrl == null || artifactUrl.isBlank()) {
            return null;
        }
        Path candidate = Path.of(artifactUrl);
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException("Marketplace artifact path must be relative");
        }
        validateRelativePath(candidate, true);
        if (candidate.getNameCount() != 5) {
            throw new IllegalArgumentException(
                    "Marketplace artifact path must match dist/<owner>/<plugin>/<version>/<file>");
        }
        if (!"dist".equals(candidate.getName(0).toString())) {
            throw new IllegalArgumentException("Marketplace artifact path must stay under dist/");
        }
        if (!coordinates.owner().equals(candidate.getName(1).toString())
                || !coordinates.name().equals(candidate.getName(2).toString())) {
            throw new IllegalArgumentException("Marketplace artifact path must match plugin id");
        }
        String safeVersion = normalizeVersion(version);
        if (!safeVersion.equals(candidate.getName(3).toString())) {
            throw new IllegalArgumentException("Marketplace artifact path must match plugin version");
        }
        validateFileName(candidate.getName(4).toString(), ".jar", "artifact file name");
        return candidate;
    }

    private Path artifactDirectory(Path repositoryRoot, PluginCoordinates coordinates, String version) {
        Path distRoot = ensureContainedInRoot(repositoryRoot, repositoryRoot.resolve("dist"), "plugin artifact root");
        String safeVersion = normalizeVersion(version);
        return ensureContainedInRoot(distRoot,
                distRoot.resolve(coordinates.owner()).resolve(coordinates.name()).resolve(safeVersion),
                "plugin artifact directory");
    }

    private Path missingArtifactPath(Path repositoryRoot) {
        return ensureContainedInRoot(repositoryRoot, repositoryRoot.resolve("missing-artifact"), "plugin artifact");
    }

    private Path pluginDirectoryRoot() {
        return Path.of(pluginRuntimeProperties.getDirectory()).toAbsolutePath().normalize();
    }

    private record TrustedPluginRecord(
            PluginCoordinates coordinates,
            RegistryIndexMetadata indexMetadata,
            Map<String, TrustedVersionRecord> versions,
            String latestVersion) {
    }

    private record TrustedVersionRecord(
            RegistryVersionMetadata versionMetadata,
            PluginDescriptor descriptor,
            ArtifactReference artifactReference,
            boolean compatible) {
    }

    private record PluginCoordinates(String owner, String name) {

        private String id() {
            return owner + "/" + name;
        }
    }

    private record ArtifactReference(Path localPath, URI remoteUri, String artifactFileName) {

        private static ArtifactReference local(Path path) {
            Path fileNamePath = path != null ? path.getFileName() : null;
            return new ArtifactReference(path, null, fileNamePath != null ? fileNamePath.toString() : null);
        }

        private static ArtifactReference remote(URI uri, String fileName) {
            return new ArtifactReference(null, uri, fileName);
        }

        private static ArtifactReference missing() {
            return new ArtifactReference(null, null, null);
        }

        private boolean isAvailable() {
            if (localPath != null) {
                return Files.isRegularFile(localPath);
            }
            return remoteUri != null && artifactFileName != null && !artifactFileName.isBlank();
        }

        private String fileName() {
            if (artifactFileName != null && !artifactFileName.isBlank()) {
                return artifactFileName;
            }
            throw new IllegalStateException("Artifact source has no file name");
        }

        @Override
        public String toString() {
            if (localPath != null) {
                return localPath.toString();
            }
            return remoteUri != null ? remoteUri.toString() : "unavailable";
        }
    }

    private record RemoteCatalogCache(Instant loadedAt, Map<String, TrustedPluginRecord> trustedPlugins) {
    }

    private record RemoteRepositoryTree(List<String> indexPaths, Set<String> blobPaths) {
    }

    private record GitHubRepository(String owner, String name) {
    }

    @Data
    private static class RegistryIndexMetadata {
        private String id;
        private String owner;
        private String name;
        private String latest;
        private List<String> versions = List.of();
        private String source;
    }

    @Data
    private static class RegistryVersionMetadata {
        private String id;
        private String version;
        private Integer pluginApiVersion;
        private String engineVersion;
        private String artifactUrl;
        private String checksumSha256;
        private String publishedAt;
        private String sourceCommit;
        private String entrypoint;
        private String sourceUrl;
        private String license;
        private List<String> maintainers = List.of();
    }
}

package me.golemcore.bot.plugin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.plugin.api.extension.spi.PluginDescriptor;
import me.golemcore.plugin.api.extension.spi.PluginSettingsCatalogItem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Reads marketplace metadata from the plugins repository and installs plugin
 * artifacts into the runtime plugin directory.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PluginMarketplaceService {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String PLUGIN_MANIFEST_PATH = "META-INF/golemcore/plugin.yaml";
    private static final Map<String, String> DISPLAY_NAME_OVERRIDES = Map.of(
            "lightrag", "LightRAG",
            "elevenlabs", "ElevenLabs");

    private final BotProperties botProperties;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final PluginManager pluginManager;
    private final PluginSettingsRegistry pluginSettingsRegistry;

    public PluginMarketplaceCatalog getCatalog() {
        if (!botProperties.getPlugins().getMarketplace().isEnabled()) {
            return unavailable("Plugin marketplace is disabled by backend configuration.");
        }

        Optional<Path> repositoryRoot = resolveRepositoryRoot();
        if (repositoryRoot.isEmpty()) {
            return unavailable(
                    "Marketplace repository was not found. Configure bot.plugins.marketplace.repository-directory.");
        }

        Path repoRoot = repositoryRoot.get();
        Path registryRoot = repoRoot.resolve("registry");
        if (!Files.isDirectory(registryRoot)) {
            return unavailable("Marketplace registry directory is missing under " + repoRoot);
        }

        String engineVersion = resolveEngineVersion();
        Map<String, TrustedPluginRecord> trustedPlugins = loadTrustedPlugins(repoRoot, engineVersion);
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
                .sourceDirectory(repoRoot.toString())
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
        if (!botProperties.getPlugins().getMarketplace().isEnabled()) {
            throw new IllegalStateException("Plugin marketplace is disabled");
        }

        Path repoRoot = resolveRepositoryRoot()
                .orElseThrow(() -> new IllegalStateException("Marketplace repository was not found"));
        String engineVersion = resolveEngineVersion();
        Map<String, TrustedPluginRecord> trustedPlugins = loadTrustedPlugins(repoRoot, engineVersion);
        TrustedPluginRecord trustedPlugin = trustedPlugins.get(normalizedPluginId);
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
        if (!Files.isRegularFile(selectedVersion.artifactPath())) {
            throw new IllegalStateException(
                    "Artifact not found for " + normalizedPluginId + " at " + selectedVersion.artifactPath());
        }

        verifyChecksum(selectedVersion.artifactPath(), selectedVersion.versionMetadata().getChecksumSha256());
        Path artifactFileName = selectedVersion.artifactPath().getFileName();
        if (artifactFileName == null) {
            throw new IllegalStateException("Artifact path has no file name: " + selectedVersion.artifactPath());
        }
        Path pluginsRoot = pluginDirectoryRoot();
        Path destination = installDestination(pluginsRoot, trustedPlugin.coordinates(), trustedVersion,
                artifactFileName.toString());
        String previouslyInstalledVersion = findInstalledVersion(trustedPlugin.coordinates());
        try {
            Path destinationParent = requireDestinationParent(pluginsRoot, destination);
            Files.createDirectories(destinationParent);
            Path tempFile = ensureContainedInRoot(pluginsRoot,
                    Files.createTempFile(destinationParent, "plugin-", ".tmp"),
                    "plugin temp artifact");
            try {
                Files.copy(selectedVersion.artifactPath(), tempFile, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to install plugin artifact for " + normalizedPluginId, ex);
        }
        verifyChecksum(destination, selectedVersion.versionMetadata().getChecksumSha256());

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
                        selectedVersion.artifactPath(),
                        selectedVersion.compatible()));

        String status = resolveInstallStatus(previouslyInstalledVersion, trustedVersion);
        String message = buildInstallMessage(plugin, status, loaded);
        return PluginInstallResult.builder()
                .status(status)
                .message(message)
                .plugin(plugin)
                .build();
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
                            latestVersion.artifactPath(),
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
            indexPaths = stream.filter(path -> "index.yaml".equals(path.getFileName().toString()))
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
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
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
            Path artifactPath = resolveArtifactPath(repoRoot, coordinates, versionMetadata);
            PluginDescriptor descriptor = readDescriptor(artifactPath, versionMetadata, index);
            boolean compatible = PluginVersionSupport.matchesVersionConstraint(
                    engineVersion,
                    versionMetadata.getEngineVersion());
            versions.put(normalizedVersion,
                    new TrustedVersionRecord(versionMetadata, descriptor, artifactPath, compatible));
        }
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("No marketplace versions found for " + coordinates.id());
        }
        return versions;
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
            Path artifactPath,
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
                .artifactAvailable(Files.isRegularFile(artifactPath))
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

    private Optional<Path> resolveRepositoryRoot() {
        String configured = botProperties.getPlugins().getMarketplace().getRepositoryDirectory();
        if (configured != null && !configured.isBlank()) {
            Path configuredPath = Path.of(configured).toAbsolutePath().normalize();
            return Files.isDirectory(configuredPath) ? Optional.of(configuredPath) : Optional.empty();
        }

        Path cwd = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                cwd.resolve("golemcore-plugins"),
                cwd.resolve("../golemcore-plugins").normalize());
        return candidates.stream()
                .filter(Files::isDirectory)
                .findFirst();
    }

    private Path resolveArtifactPath(
            Path repositoryRoot,
            PluginCoordinates coordinates,
            RegistryVersionMetadata versionMetadata) {
        Path artifactDirectory = artifactDirectory(repositoryRoot, coordinates, versionMetadata.getVersion());
        if (!Files.isDirectory(artifactDirectory)) {
            return missingArtifactPath(repositoryRoot);
        }
        String expectedFileName = parseArtifactFileName(versionMetadata.getArtifactUrl(), coordinates,
                versionMetadata.getVersion());
        try (Stream<Path> stream = Files.list(artifactDirectory)) {
            List<Path> jarFiles = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
            if (expectedFileName != null) {
                return jarFiles.stream()
                        .filter(path -> expectedFileName.equals(path.getFileName().toString()))
                        .findFirst()
                        .orElseGet(() -> missingArtifactPath(repositoryRoot));
            }
            return jarFiles.isEmpty() ? missingArtifactPath(repositoryRoot) : jarFiles.getFirst();
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

    private Path requireDestinationParent(Path pluginsRoot, Path destination) {
        Path destinationParent = destination.getParent();
        if (destinationParent == null) {
            throw new IllegalStateException("Invalid plugin install destination: " + destination);
        }
        return ensureContainedInRoot(pluginsRoot, destinationParent, "plugin install destination");
    }

    private String findInstalledVersion(PluginCoordinates coordinates) {
        Path pluginsRoot = pluginDirectoryRoot();
        Path pluginDir = ensureContainedInRoot(pluginsRoot,
                pluginsRoot.resolve(coordinates.owner()).resolve(coordinates.name()),
                "installed plugin directory");
        if (!Files.isDirectory(pluginDir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(pluginDir)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
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

    private boolean hasJarFile(Path versionDir) {
        try (Stream<Path> stream = Files.list(versionDir)) {
            return stream.anyMatch(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"));
        } catch (IOException ex) {
            return false;
        }
    }

    private void verifyChecksum(Path artifactPath, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isBlank()) {
            return;
        }
        String actual = sha256(artifactPath);
        if (!expectedChecksum.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("Checksum mismatch for " + artifactPath.getFileName()
                    + ": expected " + expectedChecksum + ", actual " + actual);
        }
    }

    private String sha256(Path path) {
        try {
            if (path == null) {
                throw new IllegalArgumentException("Checksum path is required");
            }
            Path safePath = path.toAbsolutePath().normalize();
            Path checksumRoot = safePath.resolve("..").normalize();
            Path containedPath = ensureContainedInRoot(checksumRoot, safePath, "checksum input");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(containedPath));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + path + " for checksum verification", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is unavailable", ex);
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

    private String parseArtifactFileName(String artifactUrl, PluginCoordinates coordinates, String version) {
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
        return validateFileName(candidate.getName(4).toString(), ".jar", "artifact file name");
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
        return Path.of(botProperties.getPlugins().getDirectory()).toAbsolutePath().normalize();
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
            Path artifactPath,
            boolean compatible) {
    }

    private record PluginCoordinates(String owner, String name) {

        private String id() {
            return owner + "/" + name;
        }
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

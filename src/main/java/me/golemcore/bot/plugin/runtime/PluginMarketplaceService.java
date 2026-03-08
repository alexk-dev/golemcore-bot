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

        List<PluginMarketplaceItem> items = loadRegistryItems(repoRoot, registryRoot, loadedById,
                settingsRouteByPlugin);
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
        RegistrySelection selection = resolveSelection(repoRoot, requestedPlugin, normalizedVersion);
        String trustedPluginId = selection.coordinates().id();
        String trustedVersion = selection.versionMetadata().getVersion();
        if (!selection.compatible()) {
            throw new IllegalStateException("Plugin " + normalizedPluginId + " is not compatible with engine "
                    + resolveEngineVersion());
        }
        if (!Files.isRegularFile(selection.artifactPath())) {
            throw new IllegalStateException(
                    "Artifact not found for " + normalizedPluginId + " at " + selection.artifactPath());
        }

        verifyChecksum(selection.artifactPath(), selection.versionMetadata().getChecksumSha256());
        Path artifactFileName = selection.artifactPath().getFileName();
        if (artifactFileName == null) {
            throw new IllegalStateException("Artifact path has no file name: " + selection.artifactPath());
        }
        Path pluginsRoot = pluginDirectoryRoot();
        Path destination = installDestination(pluginsRoot, selection.coordinates(), trustedVersion,
                artifactFileName.toString());
        String previouslyInstalledVersion = findInstalledVersion(selection.coordinates());
        try {
            Path destinationParent = destination.getParent();
            if (destinationParent == null || !destinationParent.startsWith(pluginsRoot)) {
                throw new IllegalStateException("Invalid plugin install destination: " + destination);
            }
            Files.createDirectories(destinationParent);
            Path tempFile = destination.resolveSibling(destination.getFileName() + ".tmp");
            ensureContainedInRoot(pluginsRoot, tempFile, "plugin temp artifact");
            Files.copy(selection.artifactPath(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to install plugin artifact for " + normalizedPluginId, ex);
        }
        verifyChecksum(destination, selection.versionMetadata().getChecksumSha256());

        boolean loaded = pluginManager.reloadPlugin(trustedPluginId);
        PluginMarketplaceItem plugin = getCatalog().getItems().stream()
                .filter(item -> trustedPluginId.equals(item.getId()))
                .findFirst()
                .orElseGet(() -> toMarketplaceItem(
                        selection.indexMetadata(),
                        selection.versionMetadata(),
                        selection.descriptor(),
                        previouslyInstalledVersion,
                        null,
                        null,
                        selection.artifactPath(),
                        selection.compatible()));

        String status = resolveInstallStatus(previouslyInstalledVersion, trustedVersion);
        String message = buildInstallMessage(plugin, status, loaded);
        return PluginInstallResult.builder()
                .status(status)
                .message(message)
                .plugin(plugin)
                .build();
    }

    private List<PluginMarketplaceItem> loadRegistryItems(
            Path repoRoot,
            Path registryRoot,
            Map<String, PluginRuntimeInfo> loadedById,
            Map<String, String> settingsRouteByPlugin) {
        List<PluginMarketplaceItem> items = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(registryRoot, 3)) {
            stream.filter(path -> path.getFileName().toString().equals("index.yaml"))
                    .sorted()
                    .forEach(indexPath -> {
                        try {
                            RegistryIndexMetadata index = YAML_MAPPER.readValue(indexPath.toFile(),
                                    RegistryIndexMetadata.class);
                            if (index == null || index.getId() == null || index.getLatest() == null) {
                                return;
                            }
                            String normalizedIndexPluginId = normalizePluginId(index.getId());
                            if (normalizedIndexPluginId == null) {
                                return;
                            }
                            PluginCoordinates coordinates = parsePluginId(normalizedIndexPluginId);
                            index.setId(coordinates.id());
                            RegistryVersionMetadata version = resolveVersionMetadata(
                                    requireDirectory(indexPath.getParent(), "plugin registry directory"),
                                    null,
                                    index.getVersions(),
                                    index.getLatest());
                            Path artifactPath = resolveArtifactPath(repoRoot, coordinates, version);
                            PluginDescriptor descriptor = readDescriptor(artifactPath, version, index);
                            String installedVersion = findInstalledVersion(coordinates);
                            PluginRuntimeInfo loaded = loadedById.get(coordinates.id());
                            String settingsRouteKey = settingsRouteByPlugin.get(coordinates.id());
                            boolean compatible = PluginVersionSupport.matchesVersionConstraint(
                                    resolveEngineVersion(),
                                    version.getEngineVersion());
                            items.add(toMarketplaceItem(
                                    index,
                                    version,
                                    descriptor,
                                    installedVersion,
                                    loaded,
                                    settingsRouteKey,
                                    artifactPath,
                                    compatible));
                        } catch (RuntimeException | IOException ex) {
                            log.warn("[Plugins] Failed to read marketplace entry {}: {}", indexPath, ex.getMessage());
                        }
                    });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan marketplace registry " + registryRoot, ex);
        }
        return items.stream()
                .sorted(Comparator
                        .comparing(PluginMarketplaceItem::isUpdateAvailable).reversed()
                        .thenComparing(PluginMarketplaceItem::isInstalled).reversed()
                        .thenComparing(PluginMarketplaceItem::getName))
                .toList();
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

    private RegistrySelection resolveSelection(Path repoRoot, PluginCoordinates pluginId, String requestedVersion) {
        Path registryRoot = requireDirectory(repoRoot.resolve("registry"), "marketplace registry");
        try (Stream<Path> stream = Files.walk(registryRoot, 3)) {
            List<Path> indexPaths = stream.filter(path -> "index.yaml".equals(path.getFileName().toString()))
                    .sorted()
                    .toList();
            for (Path indexPath : indexPaths) {
                RegistryIndexMetadata index = YAML_MAPPER.readValue(indexPath.toFile(), RegistryIndexMetadata.class);
                if (index == null || index.getId() == null || index.getLatest() == null) {
                    continue;
                }
                String normalizedIndexPluginId = normalizePluginId(index.getId());
                if (!pluginId.id().equals(normalizedIndexPluginId)) {
                    continue;
                }
                PluginCoordinates coordinates = parsePluginId(normalizedIndexPluginId);
                index.setId(coordinates.id());
                Path pluginRegistryDir = requireDirectory(indexPath.getParent(), "plugin registry directory");
                RegistryVersionMetadata versionMetadata = resolveVersionMetadata(
                        pluginRegistryDir,
                        requestedVersion,
                        index.getVersions(),
                        index.getLatest());
                Path artifactPath = resolveArtifactPath(repoRoot, coordinates, versionMetadata);
                PluginDescriptor descriptor = readDescriptor(artifactPath, versionMetadata, index);
                boolean compatible = PluginVersionSupport.matchesVersionConstraint(
                        resolveEngineVersion(),
                        versionMetadata.getEngineVersion());
                return new RegistrySelection(coordinates, index, versionMetadata, descriptor, artifactPath, compatible);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load marketplace metadata for " + pluginId.id(), ex);
        }
        throw new IllegalArgumentException("Unknown plugin: " + pluginId.id());
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
            Path safePath = ensureContainedInRoot(path.getParent() != null ? path.getParent() : path, path,
                    "checksum input");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(safePath));
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
        return java.util.Arrays.stream(normalized.split("-"))
                .filter(part -> !part.isBlank())
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .reduce((left, right) -> left + " " + right)
                .orElse("Plugin");
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

    private String resolveRequestedVersion(String requestedVersion, List<String> availableVersions,
            String latestVersion) {
        String candidate = requestedVersion != null ? requestedVersion : latestVersion;
        String normalizedVersion = normalizeVersion(candidate);
        if (normalizedVersion == null) {
            throw new IllegalArgumentException("Plugin version is required");
        }
        if (availableVersions == null || availableVersions.isEmpty()) {
            return normalizedVersion;
        }
        for (String version : availableVersions) {
            String normalizedAvailableVersion = normalizeVersion(version);
            if (normalizedVersion.equals(normalizedAvailableVersion)) {
                return normalizedAvailableVersion;
            }
        }
        throw new IllegalArgumentException("Unknown plugin version: " + normalizedVersion);
    }

    private RegistryVersionMetadata resolveVersionMetadata(
            Path pluginRegistryDir,
            String requestedVersion,
            List<String> availableVersions,
            String latestVersion) throws IOException {
        String selectedVersion = resolveRequestedVersion(requestedVersion, availableVersions, latestVersion);
        Path versionsDir = requireDirectory(pluginRegistryDir.resolve("versions"), "plugin versions directory");
        try (Stream<Path> stream = Files.list(versionsDir)) {
            List<Path> versionPaths = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
            for (Path versionPath : versionPaths) {
                RegistryVersionMetadata candidate = readVersionMetadata(versionPath);
                if (candidate == null) {
                    continue;
                }
                String candidateVersion = normalizeVersion(candidate.getVersion());
                if (candidate.getId() != null) {
                    candidate.setId(normalizePluginId(candidate.getId()));
                }
                if (selectedVersion.equals(candidateVersion)) {
                    candidate.setVersion(candidateVersion);
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown plugin version: " + selectedVersion);
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
            boolean alphanumeric = current >= 'a' && current <= 'z' || current >= '0' && current <= '9';
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
            boolean alphanumeric = current >= 'a' && current <= 'z'
                    || current >= 'A' && current <= 'Z'
                    || current >= '0' && current <= '9';
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

    private record RegistrySelection(
            PluginCoordinates coordinates,
            RegistryIndexMetadata indexMetadata,
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

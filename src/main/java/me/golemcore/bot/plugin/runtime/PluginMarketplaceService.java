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
        String normalizedPluginId = normalizePluginId(pluginId);
        if (normalizedPluginId == null || normalizedPluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId is required");
        }
        if (!botProperties.getPlugins().getMarketplace().isEnabled()) {
            throw new IllegalStateException("Plugin marketplace is disabled");
        }

        Path repoRoot = resolveRepositoryRoot()
                .orElseThrow(() -> new IllegalStateException("Marketplace repository was not found"));
        RegistrySelection selection = resolveSelection(repoRoot, normalizedPluginId, version);
        if (!selection.compatible()) {
            throw new IllegalStateException("Plugin " + normalizedPluginId + " is not compatible with engine "
                    + resolveEngineVersion());
        }
        if (!selection.artifactPath().toFile().isFile()) {
            throw new IllegalStateException(
                    "Artifact not found for " + normalizedPluginId + " at " + selection.artifactPath());
        }

        verifyChecksum(selection.artifactPath(), selection.versionMetadata().getChecksumSha256());
        Path destination = installDestination(normalizedPluginId, selection.versionMetadata().getVersion(),
                selection.artifactPath().getFileName().toString());
        String previouslyInstalledVersion = findInstalledVersion(normalizedPluginId);
        try {
            Files.createDirectories(destination.getParent());
            Path tempFile = destination.resolveSibling(destination.getFileName() + ".tmp");
            Files.copy(selection.artifactPath(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to install plugin artifact for " + normalizedPluginId, ex);
        }
        verifyChecksum(destination, selection.versionMetadata().getChecksumSha256());

        boolean loaded = pluginManager.reloadPlugin(normalizedPluginId);
        PluginMarketplaceItem plugin = getCatalog().getItems().stream()
                .filter(item -> normalizedPluginId.equals(item.getId()))
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

        String status = resolveInstallStatus(previouslyInstalledVersion, selection.versionMetadata().getVersion());
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
                            RegistryVersionMetadata version = readVersionMetadata(indexPath.getParent(),
                                    index.getLatest());
                            Path artifactPath = resolveArtifactPath(repoRoot, version.getArtifactUrl());
                            PluginDescriptor descriptor = readDescriptor(artifactPath, version, index);
                            String installedVersion = findInstalledVersion(index.getId());
                            PluginRuntimeInfo loaded = loadedById.get(index.getId());
                            String settingsRouteKey = settingsRouteByPlugin.get(index.getId());
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

    private RegistrySelection resolveSelection(Path repoRoot, String pluginId, String requestedVersion) {
        Path pluginRegistryDir = repoRoot.resolve("registry").resolve(pluginId);
        Path indexPath = pluginRegistryDir.resolve("index.yaml");
        if (!Files.isRegularFile(indexPath)) {
            throw new IllegalArgumentException("Unknown plugin: " + pluginId);
        }
        try {
            RegistryIndexMetadata index = YAML_MAPPER.readValue(indexPath.toFile(), RegistryIndexMetadata.class);
            String version = requestedVersion != null && !requestedVersion.isBlank()
                    ? requestedVersion.trim()
                    : index.getLatest();
            RegistryVersionMetadata versionMetadata = readVersionMetadata(pluginRegistryDir, version);
            Path artifactPath = resolveArtifactPath(repoRoot, versionMetadata.getArtifactUrl());
            PluginDescriptor descriptor = readDescriptor(artifactPath, versionMetadata, index);
            boolean compatible = PluginVersionSupport.matchesVersionConstraint(
                    resolveEngineVersion(),
                    versionMetadata.getEngineVersion());
            return new RegistrySelection(index, versionMetadata, descriptor, artifactPath, compatible);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load marketplace metadata for " + pluginId, ex);
        }
    }

    private RegistryVersionMetadata readVersionMetadata(Path pluginRegistryDir, String version) throws IOException {
        Path versionPath = pluginRegistryDir.resolve("versions").resolve(version + ".yaml");
        if (!Files.isRegularFile(versionPath)) {
            throw new IllegalArgumentException("Unknown plugin version: " + version);
        }
        return YAML_MAPPER.readValue(versionPath.toFile(), RegistryVersionMetadata.class);
    }

    private PluginDescriptor readDescriptor(
            Path artifactPath,
            RegistryVersionMetadata versionMetadata,
            RegistryIndexMetadata indexMetadata) throws IOException {
        PluginDescriptor descriptor = artifactPath.toFile().isFile()
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

    private Path resolveArtifactPath(Path repositoryRoot, String artifactUrl) {
        if (artifactUrl == null || artifactUrl.isBlank()) {
            return repositoryRoot.resolve("missing-artifact");
        }
        Path candidate = Path.of(artifactUrl);
        return candidate.isAbsolute()
                ? candidate.normalize()
                : repositoryRoot.resolve(candidate).normalize();
    }

    private Path installDestination(String pluginId, String version, String artifactFileName) {
        String[] parts = pluginId.split("/", 2);
        Path pluginsRoot = Path.of(botProperties.getPlugins().getDirectory()).toAbsolutePath().normalize();
        return pluginsRoot.resolve(parts[0]).resolve(parts[1]).resolve(version).resolve(artifactFileName);
    }

    private String findInstalledVersion(String pluginId) {
        String[] parts = pluginId.split("/", 2);
        Path pluginDir = Path.of(botProperties.getPlugins().getDirectory()).toAbsolutePath().normalize()
                .resolve(parts[0])
                .resolve(parts[1]);
        if (!Files.isDirectory(pluginDir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(pluginDir)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(version -> {
                        try {
                            PluginVersionSupport.compareVersions(version, version);
                            return hasJarFile(pluginDir.resolve(version));
                        } catch (IllegalArgumentException ex) {
                            return false;
                        }
                    })
                    .max(PluginVersionSupport::compareVersions)
                    .orElse(null);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect installed plugin directory for " + pluginId, ex);
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
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
        return pluginId == null ? null : pluginId.trim().toLowerCase(Locale.ROOT);
    }

    private String ownerFromPluginId(String pluginId) {
        if (pluginId == null || !pluginId.contains("/")) {
            return "";
        }
        return pluginId.substring(0, pluginId.indexOf('/'));
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

    private record RegistrySelection(
            RegistryIndexMetadata indexMetadata,
            RegistryVersionMetadata versionMetadata,
            PluginDescriptor descriptor,
            Path artifactPath,
            boolean compatible) {
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

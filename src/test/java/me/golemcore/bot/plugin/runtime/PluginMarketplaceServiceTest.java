package me.golemcore.bot.plugin.runtime;

import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.plugin.api.extension.spi.PluginSettingsCatalogItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginMarketplaceServiceTest {

    @Test
    void shouldListMarketplaceItemsWithInstalledAndUpdateState(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath);

        Path installedOldVersion = installRoot.resolve("golemcore/browser/0.9.0/browser-old.jar");
        Files.createDirectories(installedOldVersion.getParent());
        Files.writeString(installedOldVersion, "old-version", StandardCharsets.UTF_8);

        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.listPlugins()).thenReturn(List.of(PluginRuntimeInfo.builder()
                .id("golemcore/browser")
                .name("browser")
                .provider("golemcore")
                .version("0.9.0")
                .loaded(true)
                .build()));

        PluginSettingsRegistry settingsRegistry = mock(PluginSettingsRegistry.class);
        when(settingsRegistry.listCatalogItems()).thenReturn(List.of(PluginSettingsCatalogItem.builder()
                .pluginId("golemcore/browser")
                .routeKey("plugin-golemcore-browser")
                .title("Browser")
                .build()));

        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                settingsRegistry);

        PluginMarketplaceCatalog catalog = service.getCatalog();

        assertTrue(catalog.isAvailable());
        assertEquals(repositoryRoot.toString(), catalog.getSourceDirectory());
        assertEquals(1, catalog.getItems().size());

        PluginMarketplaceItem item = catalog.getItems().getFirst();
        assertEquals("golemcore/browser", item.getId());
        assertEquals("Browser", item.getName());
        assertTrue(item.isOfficial());
        assertTrue(item.isCompatible());
        assertTrue(item.isInstalled());
        assertTrue(item.isLoaded());
        assertTrue(item.isUpdateAvailable());
        assertEquals("0.9.0", item.getInstalledVersion());
        assertEquals("0.9.0", item.getLoadedVersion());
        assertEquals("plugin-golemcore-browser", item.getSettingsRouteKey());
    }

    @Test
    void shouldInstallPluginArtifactAndReloadRuntime(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath);

        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.reloadPlugin("golemcore/browser")).thenReturn(true);
        when(pluginManager.listPlugins()).thenReturn(List.of(PluginRuntimeInfo.builder()
                .id("golemcore/browser")
                .name("browser")
                .provider("golemcore")
                .version("1.0.0")
                .loaded(true)
                .build()));

        PluginSettingsRegistry settingsRegistry = mock(PluginSettingsRegistry.class);
        when(settingsRegistry.listCatalogItems()).thenReturn(List.of(PluginSettingsCatalogItem.builder()
                .pluginId("golemcore/browser")
                .routeKey("plugin-golemcore-browser")
                .title("Browser")
                .build()));

        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                settingsRegistry);

        PluginInstallResult result = service.install("golemcore/browser", null);

        Path installedJar = installRoot.resolve("golemcore/browser/1.0.0/golemcore-browser-plugin-1.0.0.jar");
        assertTrue(Files.isRegularFile(installedJar));
        assertEquals("installed", result.getStatus());
        assertTrue(result.getMessage().contains("installed"));
        assertNotNull(result.getPlugin());
        assertEquals("1.0.0", result.getPlugin().getVersion());
        assertTrue(result.getPlugin().isInstalled());
        assertTrue(result.getPlugin().isLoaded());
        verify(pluginManager).reloadPlugin("golemcore/browser");
    }

    @Test
    void shouldReturnUnavailableCatalogWhenRepositoryIsMissing(@TempDir Path tempDir) {
        Path installRoot = tempDir.resolve("installed-plugins");

        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(tempDir.resolve("missing-repository"), installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                mock(PluginManager.class),
                mock(PluginSettingsRegistry.class));

        PluginMarketplaceCatalog catalog = service.getCatalog();

        assertFalse(catalog.isAvailable());
        assertNotNull(catalog.getMessage());
        assertTrue(catalog.getItems().isEmpty());
    }

    private BotProperties botProperties(Path repositoryRoot, Path installRoot) {
        BotProperties properties = new BotProperties();
        properties.getPlugins().setDirectory(installRoot.toString());
        properties.getPlugins().getMarketplace().setRepositoryDirectory(repositoryRoot.toString());
        return properties;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<BuildProperties> buildPropertiesProvider(String version) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        Properties properties = new Properties();
        properties.setProperty("version", version);
        when(provider.getIfAvailable()).thenReturn(new BuildProperties(properties));
        return provider;
    }

    private Path createPluginArtifact(Path repositoryRoot, String pluginId, String version, String description)
            throws IOException {
        String[] parts = pluginId.split("/", 2);
        Path artifactPath = repositoryRoot.resolve("dist")
                .resolve(parts[0])
                .resolve(parts[1])
                .resolve(version)
                .resolve("golemcore-" + parts[1] + "-plugin-" + version + ".jar");
        Files.createDirectories(artifactPath.getParent());
        try (OutputStream outputStream = Files.newOutputStream(artifactPath);
                JarOutputStream jarOutputStream = new JarOutputStream(outputStream)) {
            jarOutputStream.putNextEntry(new JarEntry("META-INF/golemcore/plugin.yaml"));
            jarOutputStream.write(pluginYaml(pluginId, version, description).getBytes(StandardCharsets.UTF_8));
            jarOutputStream.closeEntry();
        }
        return artifactPath;
    }

    private void writeRegistryEntry(Path repositoryRoot, String pluginId, String version, Path artifactPath)
            throws IOException {
        String[] parts = pluginId.split("/", 2);
        Path pluginRegistryDir = repositoryRoot.resolve("registry").resolve(parts[0]).resolve(parts[1]);
        Files.createDirectories(pluginRegistryDir.resolve("versions"));
        Files.writeString(pluginRegistryDir.resolve("index.yaml"), """
                id: %s
                owner: %s
                name: %s
                latest: %s
                versions:
                  - %s
                source: https://github.com/alexk-dev/golemcore-plugins/tree/main/%s/%s
                """.formatted(pluginId, parts[0], parts[1], version, version, parts[0], parts[1]),
                StandardCharsets.UTF_8);
        Files.writeString(pluginRegistryDir.resolve("versions").resolve(version + ".yaml"), """
                id: %s
                version: %s
                pluginApiVersion: 1
                engineVersion: ">=0.0.0 <1.0.0"
                artifactUrl: "dist/%s/%s/%s/%s"
                checksumSha256: "%s"
                publishedAt: "2026-03-07T00:00:00Z"
                sourceCommit: "abc123"
                entrypoint: me.golemcore.plugins.%s.%s.PluginBootstrap
                sourceUrl: https://github.com/alexk-dev/golemcore-plugins/tree/main/%s/%s
                license: Apache-2.0
                maintainers:
                  - alexk-dev
                """.formatted(
                pluginId,
                version,
                parts[0],
                parts[1],
                version,
                artifactPath.getFileName(),
                sha256(artifactPath),
                parts[0],
                parts[1],
                parts[0],
                parts[1]), StandardCharsets.UTF_8);
    }

    private String pluginYaml(String pluginId, String version, String description) {
        String[] parts = pluginId.split("/", 2);
        return """
                id: %s
                provider: %s
                name: %s
                version: %s
                pluginApiVersion: 1
                engineVersion: ">=0.0.0 <1.0.0"
                entrypoint: me.golemcore.plugins.%s.%s.PluginBootstrap
                description: %s
                sourceUrl: https://github.com/alexk-dev/golemcore-plugins/tree/main/%s/%s
                license: Apache-2.0
                maintainers:
                  - alexk-dev
                """.formatted(pluginId, parts[0], parts[1], version, parts[0], parts[1], description, parts[0],
                parts[1]);
    }

    private String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

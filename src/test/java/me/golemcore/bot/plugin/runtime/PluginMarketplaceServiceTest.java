package me.golemcore.bot.plugin.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.plugin.api.extension.spi.PluginSettingsCatalogItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void shouldUninstallPluginArtifactsAndUnloadRuntime(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath);

        Path installedOldVersion = installRoot.resolve("golemcore/browser/0.9.0/browser-old.jar");
        Path installedCurrentVersion = installRoot.resolve("golemcore/browser/1.0.0/browser-current.jar");
        Files.createDirectories(installedOldVersion.getParent());
        Files.createDirectories(installedCurrentVersion.getParent());
        Files.writeString(installedOldVersion, "old-version", StandardCharsets.UTF_8);
        Files.writeString(installedCurrentVersion, "current-version", StandardCharsets.UTF_8);

        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.unloadPlugin("golemcore/browser")).thenReturn(true);
        when(pluginManager.reloadPlugin("golemcore/browser")).thenReturn(false);

        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                mock(PluginSettingsRegistry.class));

        PluginUninstallResult result = service.uninstall("golemcore/browser");

        assertEquals("uninstalled", result.getStatus());
        assertTrue(result.getMessage().contains("uninstalled and unloaded"));
        assertFalse(Files.exists(installRoot.resolve("golemcore/browser")));
        verify(pluginManager).unloadPlugin("golemcore/browser");
        verify(pluginManager).reloadPlugin("golemcore/browser");
    }

    @Test
    void shouldReturnNotInstalledWhenPluginArtifactsAreMissing(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath);

        PluginManager pluginManager = mock(PluginManager.class);
        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                mock(PluginSettingsRegistry.class));

        PluginUninstallResult result = service.uninstall("golemcore/browser");

        assertEquals("not-installed", result.getStatus());
        assertTrue(result.getMessage().contains("not installed"));
        verify(pluginManager, never()).unloadPlugin("golemcore/browser");
        verify(pluginManager, never()).reloadPlugin("golemcore/browser");
    }

    @Test
    void shouldReportRemainingArtifactWhenPluginReloadsAfterUninstall(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath);

        Path installedVersion = installRoot.resolve("golemcore/browser/1.0.0/browser-current.jar");
        Files.createDirectories(installedVersion.getParent());
        Files.writeString(installedVersion, "current-version", StandardCharsets.UTF_8);

        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.unloadPlugin("golemcore/browser")).thenReturn(true);
        when(pluginManager.reloadPlugin("golemcore/browser")).thenReturn(true);

        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                mock(PluginSettingsRegistry.class));

        PluginUninstallResult result = service.uninstall("golemcore/browser");

        assertEquals("uninstalled", result.getStatus());
        assertTrue(result.getMessage().contains("Another artifact remains loaded"));
        verify(pluginManager).unloadPlugin("golemcore/browser");
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

    @Test
    void shouldListMarketplaceItemsFromRemoteRepositoryWhenRepositoryDirectoryIsBlank(@TempDir Path tempDir)
            throws Exception {
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactRoot = tempDir.resolve("remote-artifacts");
        Path artifactPath = createPluginArtifact(artifactRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");

        try (RemoteMarketplaceServer remoteServer = startRemoteMarketplaceServer(
                artifactPath,
                "golemcore/browser",
                "1.0.0",
                true)) {
            PluginManager pluginManager = mock(PluginManager.class);
            when(pluginManager.listPlugins()).thenReturn(List.of());

            PluginMarketplaceService service = new PluginMarketplaceService(
                    remoteBotProperties(remoteServer.baseUrl(), installRoot),
                    buildPropertiesProvider("0.0.0-SNAPSHOT"),
                    pluginManager,
                    mock(PluginSettingsRegistry.class));

            PluginMarketplaceCatalog catalog = service.getCatalog();

            assertTrue(catalog.isAvailable());
            assertEquals(remoteServer.repositoryUrl(), catalog.getSourceDirectory());
            assertEquals(1, catalog.getItems().size());
            assertEquals("golemcore/browser", catalog.getItems().getFirst().getId());
            assertEquals("Browser", catalog.getItems().getFirst().getName());
            assertTrue(catalog.getItems().getFirst().isArtifactAvailable());
        }
    }

    @Test
    void shouldInstallPluginArtifactFromRemoteRepositoryWhenRepositoryDirectoryIsBlank(@TempDir Path tempDir)
            throws Exception {
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactRoot = tempDir.resolve("remote-artifacts");
        Path artifactPath = createPluginArtifact(artifactRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");

        try (RemoteMarketplaceServer remoteServer = startRemoteMarketplaceServer(
                artifactPath,
                "golemcore/browser",
                "1.0.0",
                true)) {
            PluginManager pluginManager = mock(PluginManager.class);
            when(pluginManager.reloadPlugin("golemcore/browser")).thenReturn(true);
            when(pluginManager.listPlugins()).thenReturn(List.of(PluginRuntimeInfo.builder()
                    .id("golemcore/browser")
                    .name("browser")
                    .provider("golemcore")
                    .version("1.0.0")
                    .loaded(true)
                    .build()));

            PluginMarketplaceService service = new PluginMarketplaceService(
                    remoteBotProperties(remoteServer.baseUrl(), installRoot),
                    buildPropertiesProvider("0.0.0-SNAPSHOT"),
                    pluginManager,
                    mock(PluginSettingsRegistry.class));

            PluginInstallResult result = service.install("golemcore/browser", null);

            Path installedJar = installRoot.resolve("golemcore/browser/1.0.0/golemcore-browser-plugin-1.0.0.jar");
            assertTrue(Files.isRegularFile(installedJar));
            assertEquals("installed", result.getStatus());
            assertEquals("1.0.0", result.getPlugin().getVersion());
            assertTrue(result.getPlugin().isInstalled());
            assertTrue(result.getPlugin().isLoaded());
            verify(pluginManager).reloadPlugin("golemcore/browser");
        }
    }

    @Test
    void shouldMarkRemoteCatalogItemArtifactUnavailableWhenJarIsMissing(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactRoot = tempDir.resolve("remote-artifacts");
        Path artifactPath = createPluginArtifact(artifactRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");

        try (RemoteMarketplaceServer remoteServer = startRemoteMarketplaceServer(
                artifactPath,
                "golemcore/browser",
                "1.0.0",
                false)) {
            PluginManager pluginManager = mock(PluginManager.class);
            when(pluginManager.listPlugins()).thenReturn(List.of());

            PluginMarketplaceService service = new PluginMarketplaceService(
                    remoteBotProperties(remoteServer.baseUrl(), installRoot),
                    buildPropertiesProvider("0.0.0-SNAPSHOT"),
                    pluginManager,
                    mock(PluginSettingsRegistry.class));

            PluginMarketplaceCatalog catalog = service.getCatalog();
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> service.install("golemcore/browser", null));

            assertEquals(1, catalog.getItems().size());
            assertFalse(catalog.getItems().getFirst().isArtifactAvailable());
            assertTrue(exception.getMessage().contains("Artifact not found"));
        }
    }

    @Test
    void shouldRejectInvalidPluginIdDuringInstall(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath);

        PluginManager pluginManager = mock(PluginManager.class);
        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                mock(PluginSettingsRegistry.class));

        assertThrows(IllegalArgumentException.class, () -> service.install("../browser", null));
        verify(pluginManager, never()).reloadPlugin("golemcore/browser");
    }

    @Test
    void shouldRejectArtifactPathThatEscapesRepositoryRoot(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath,
                "../outside/golemcore-browser-plugin-1.0.0.jar");

        PluginManager pluginManager = mock(PluginManager.class);
        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                mock(PluginSettingsRegistry.class));

        assertThrows(IllegalArgumentException.class, () -> service.install("golemcore/browser", null));
        verify(pluginManager, never()).reloadPlugin("golemcore/browser");
    }

    @Test
    void shouldRejectArtifactPathThatDoesNotMatchPluginCoordinates(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath,
                "dist/golemcore/other-plugin/1.0.0/golemcore-browser-plugin-1.0.0.jar");

        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                mock(PluginManager.class),
                mock(PluginSettingsRegistry.class));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.install("golemcore/browser", null));

        assertTrue(exception.getMessage().contains("plugin"));
    }

    @Test
    void shouldRejectRequestedVersionThatIsNotPublished(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath);

        PluginManager pluginManager = mock(PluginManager.class);
        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                mock(PluginSettingsRegistry.class));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.install("golemcore/browser", "2.0.0"));

        assertTrue(exception.getMessage().contains("Unknown plugin version"));
        verify(pluginManager, never()).reloadPlugin("golemcore/browser");
    }

    @Test
    void shouldRejectIncompatiblePluginVersion(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath);

        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("1.0.0"),
                mock(PluginManager.class),
                mock(PluginSettingsRegistry.class));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.install("golemcore/browser", null));

        assertTrue(exception.getMessage().contains("not compatible"));
    }

    @Test
    void shouldResolveVersionMetadataByContentsInsteadOfRequestedPath(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath,
                "release.yaml",
                "dist/golemcore/browser/1.0.0/golemcore-browser-plugin-1.0.0.jar");

        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.reloadPlugin("golemcore/browser")).thenReturn(true);
        when(pluginManager.listPlugins()).thenReturn(List.of());

        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                mock(PluginSettingsRegistry.class));

        PluginInstallResult result = service.install("golemcore/browser", "1.0.0");

        assertEquals("installed", result.getStatus());
        assertTrue(Files.isRegularFile(
                installRoot.resolve("golemcore/browser/1.0.0/golemcore-browser-plugin-1.0.0.jar")));
        verify(pluginManager).reloadPlugin("golemcore/browser");
    }

    @Test
    void shouldInstallWhenArtifactUrlIsMissingButDirectoryContainsSingleJar(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath, "");

        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.reloadPlugin("golemcore/browser")).thenReturn(true);
        when(pluginManager.listPlugins()).thenReturn(List.of());

        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                mock(PluginSettingsRegistry.class));

        PluginInstallResult result = service.install("golemcore/browser", null);

        assertEquals("installed", result.getStatus());
        assertTrue(Files.isRegularFile(
                installRoot.resolve("golemcore/browser/1.0.0/golemcore-browser-plugin-1.0.0.jar")));
    }

    @Test
    void shouldMarkCatalogItemArtifactUnavailableWhenJarIsMissing(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = tempDir.resolve("golemcore-plugins");
        Path installRoot = tempDir.resolve("installed-plugins");
        Path artifactPath = createPluginArtifact(repositoryRoot, "golemcore/browser", "1.0.0",
                "Playwright-backed browser automation tool with screenshot support.");
        writeRegistryEntry(repositoryRoot, "golemcore/browser", "1.0.0", artifactPath);
        Files.delete(artifactPath);

        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.listPlugins()).thenReturn(List.of());

        PluginMarketplaceService service = new PluginMarketplaceService(
                botProperties(repositoryRoot, installRoot),
                buildPropertiesProvider("0.0.0-SNAPSHOT"),
                pluginManager,
                mock(PluginSettingsRegistry.class));

        PluginMarketplaceCatalog catalog = service.getCatalog();

        assertEquals(1, catalog.getItems().size());
        assertFalse(catalog.getItems().getFirst().isArtifactAvailable());
    }

    private BotProperties remoteBotProperties(String baseUrl, Path installRoot) {
        BotProperties properties = new BotProperties();
        properties.getPlugins().setDirectory(installRoot.toString());
        properties.getPlugins().getMarketplace().setRepositoryDirectory("");
        properties.getPlugins().getMarketplace().setRepositoryUrl(baseUrl + "/alexk-dev/golemcore-plugins");
        properties.getPlugins().getMarketplace().setApiBaseUrl(baseUrl + "/api");
        properties.getPlugins().getMarketplace().setRawBaseUrl(baseUrl + "/raw");
        properties.getPlugins().getMarketplace().setBranch("main");
        properties.getPlugins().getMarketplace().setRemoteCacheTtl(Duration.ofSeconds(30));
        return properties;
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
        writeRegistryEntry(repositoryRoot, pluginId, version, artifactPath,
                version + ".yaml",
                "dist/%s/%s/%s/%s".formatted(
                        pluginId.split("/", 2)[0],
                        pluginId.split("/", 2)[1],
                        version,
                        artifactPath.getFileName()));
    }

    private void writeRegistryEntry(Path repositoryRoot, String pluginId, String version, Path artifactPath,
            String artifactUrl) throws IOException {
        writeRegistryEntry(repositoryRoot, pluginId, version, artifactPath, version + ".yaml", artifactUrl);
    }

    private void writeRegistryEntry(
            Path repositoryRoot,
            String pluginId,
            String version,
            Path artifactPath,
            String versionMetadataFileName,
            String artifactUrl) throws IOException {
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
        Files.writeString(pluginRegistryDir.resolve("versions").resolve(versionMetadataFileName), """
                id: %s
                version: %s
                pluginApiVersion: 1
                engineVersion: ">=0.0.0 <1.0.0"
                artifactUrl: "%s"
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
                artifactUrl,
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

    private RemoteMarketplaceServer startRemoteMarketplaceServer(
            Path artifactPath,
            String pluginId,
            String version,
            boolean artifactAvailable)
            throws IOException {
        String[] parts = pluginId.split("/", 2);
        String artifactFileName = artifactPath.getFileName().toString();
        String checksum = sha256(artifactPath);
        String artifactRepositoryPath = "dist/" + parts[0] + "/" + parts[1] + "/" + version + "/" + artifactFileName;
        String artifactId = artifactFileName.substring(0,
                artifactFileName.length() - ("-" + version + ".jar").length());
        String packageName = "me.golemcore.plugins." + artifactId;
        String packagePagePath = "/alexk-dev/golemcore-plugins/packages/2891620";
        String indexYaml = """
                id: %s
                owner: %s
                name: %s
                latest: %s
                versions:
                  - %s
                source: https://github.com/alexk-dev/golemcore-plugins/tree/main/%s/%s
                """.formatted(pluginId, parts[0], parts[1], version, version, parts[0], parts[1]);
        String versionYaml = """
                id: %s
                version: %s
                pluginApiVersion: 1
                engineVersion: ">=0.0.0 <1.0.0"
                artifactUrl: "%s"
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
                artifactRepositoryPath,
                checksum,
                parts[0],
                parts[1],
                parts[0],
                parts[1]);

        String treeJson = """
                {
                  "tree": [
                    {
                      "path": "registry/%s/%s/index.yaml",
                      "type": "blob"
                    }
                  ]
                }
                """.formatted(parts[0], parts[1]);
        String packagesHtml = artifactAvailable
                ? """
                        <html>
                          <body>
                            <a class="text-bold f4 Link--primary" title="%s" href="%s">%s</a>
                          </body>
                        </html>
                        """.formatted(packageName, packagePagePath, packageName)
                : "<html><body></body></html>";

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/repos/alexk-dev/golemcore-plugins/git/trees/main",
                exchange -> respond(exchange, 200, "application/json", treeJson));
        server.createContext("/raw/alexk-dev/golemcore-plugins/main/registry/" + parts[0] + "/" + parts[1]
                + "/index.yaml", exchange -> respond(exchange, 200, "text/plain; charset=utf-8", indexYaml));
        server.createContext("/raw/alexk-dev/golemcore-plugins/main/registry/" + parts[0] + "/" + parts[1]
                + "/versions/" + version + ".yaml",
                exchange -> respond(exchange, 200, "text/plain; charset=utf-8", versionYaml));
        server.createContext("/alexk-dev/golemcore-plugins/packages",
                exchange -> respond(exchange, 200, "text/html; charset=utf-8", packagesHtml));
        if (artifactAvailable) {
            server.createContext(packagePagePath, exchange -> {
                int port = exchange.getLocalAddress().getPort();
                String detailHtml = """
                        <html>
                          <body>
                            <a href="http://127.0.0.1:%d/downloads/%s?response-content-disposition=filename%%3D%s&amp;response-content-type=application%%2Foctet-stream" class="d-flex">
                              %s
                            </a>
                          </body>
                        </html>
                        """
                        .formatted(port, artifactId, artifactFileName, artifactFileName);
                respond(exchange, 200, "text/html; charset=utf-8", detailHtml);
            });
            server.createContext("/downloads/" + artifactId,
                    exchange -> respond(exchange, 200, "application/java-archive", Files.readAllBytes(artifactPath)));
        }
        server.start();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        return new RemoteMarketplaceServer(server, baseUrl);
    }

    private void respond(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
        respond(exchange, statusCode, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int statusCode, String contentType, byte[] body) throws IOException {
        try (HttpExchange closableExchange = exchange;
                OutputStream outputStream = closableExchange.getResponseBody()) {
            closableExchange.getResponseHeaders().set("Content-Type", contentType);
            closableExchange.sendResponseHeaders(statusCode, body.length);
            outputStream.write(body);
        }
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

    private record RemoteMarketplaceServer(HttpServer server, String baseUrl) implements AutoCloseable {

    private String repositoryUrl() {
        return baseUrl + "/alexk-dev/golemcore-plugins/";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}}

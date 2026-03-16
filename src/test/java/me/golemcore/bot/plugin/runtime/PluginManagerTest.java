package me.golemcore.bot.plugin.runtime;

import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.runtime.extension.PluginExtensionApiMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginManagerTest {

    private AnnotationConfigApplicationContext applicationContext;
    private PluginManager manager;
    private PluginSettingsRegistry pluginSettingsRegistry;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
        if (applicationContext != null) {
            applicationContext.close();
        }
    }

    @Test
    void shouldLoadPluginFromValidJarAndListIt(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        createPluginJar(pluginsDir, "golemcore/browser", "1.0.0", ">=1.0.0 <2.0.0");
        manager = createManager(pluginsDir, "1.0.0-SNAPSHOT");

        manager.reloadAll();

        List<PluginRuntimeInfo> plugins = manager.listPlugins();
        assertEquals(1, plugins.size());
        assertEquals("golemcore/browser", plugins.getFirst().getId());
        assertEquals("1.0.0", plugins.getFirst().getVersion());
        assertTrue(plugins.getFirst().isLoaded());
    }

    @Test
    void shouldUnloadPluginWhenArtifactIsRemoved(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Path pluginJar = createPluginJar(pluginsDir, "golemcore/browser", "1.0.0", ">=1.0.0 <2.0.0");
        manager = createManager(pluginsDir, "1.0.0");

        manager.reloadAll();
        Files.delete(pluginJar);

        manager.reloadAll();

        assertTrue(manager.listPlugins().isEmpty());
    }

    @Test
    void shouldReturnFalseWhenReloadingMissingPlugin(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Path pluginJar = createPluginJar(pluginsDir, "golemcore/browser", "1.0.0", ">=1.0.0 <2.0.0");
        manager = createManager(pluginsDir, "1.0.0");
        manager.reloadAll();
        Files.delete(pluginJar);

        boolean reloaded = manager.reloadPlugin("  GOLEMCORE/BROWSER  ");

        assertFalse(reloaded);
        assertTrue(manager.listPlugins().isEmpty());
    }

    @Test
    void shouldUnloadPluginById(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Path pluginJar = createPluginJar(pluginsDir, "golemcore/browser", "1.0.0", ">=1.0.0 <2.0.0");
        manager = createManager(pluginsDir, "1.0.0");
        manager.reloadAll();

        boolean unloaded = manager.unloadPlugin("  GOLEMCORE/BROWSER  ");

        assertTrue(unloaded);
        assertTrue(manager.listPlugins().isEmpty());
        assertTrue(Files.isRegularFile(pluginJar));
    }

    @Test
    void shouldPreferNewestPluginArtifactAcrossMultipleVersions(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        createPluginJar(pluginsDir, "golemcore/browser", "1.0.0", ">=1.0.0 <2.0.0");
        createPluginJar(pluginsDir, "golemcore/browser", "1.1.0", ">=1.0.0 <2.0.0");
        manager = createManager(pluginsDir, "1.0.0");

        manager.reloadAll();

        List<PluginRuntimeInfo> plugins = manager.listPlugins();
        assertEquals(1, plugins.size());
        assertEquals("1.1.0", plugins.getFirst().getVersion());
    }

    @Test
    void shouldIgnoreIncompatiblePluginArtifact(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        createPluginJar(pluginsDir, "golemcore/browser", "1.0.0", ">=2.0.0");
        manager = createManager(pluginsDir, "1.0.0");

        manager.reloadAll();

        assertTrue(manager.listPlugins().isEmpty());
    }

    @Test
    void shouldLoadPluginWhenHostVersionUsesBranchSuffix(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        createPluginJar(pluginsDir, "golemcore/browser", "1.0.0", ">=0.12.0 <1.0.0");
        manager = createManager(pluginsDir, "0.12.0-feat_plugin_runtime_marketplace");

        manager.reloadAll();

        List<PluginRuntimeInfo> plugins = manager.listPlugins();
        assertEquals(1, plugins.size());
        assertEquals("golemcore/browser", plugins.getFirst().getId());
    }

    @Test
    void shouldLoadPluginSettingsContributorThatInjectsHostOkHttpClient(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        createPluginJarWithOkHttpBackedSettings(pluginsDir, "golemcore/browser", "1.0.0", ">=1.0.0 <2.0.0");
        manager = createManager(pluginsDir, "1.0.0");

        manager.reloadAll();

        List<PluginRuntimeInfo> plugins = manager.listPlugins();
        assertEquals(1, plugins.size());
        assertEquals("golemcore/browser", plugins.getFirst().getId());
        assertTrue(plugins.getFirst().isLoaded());
        assertEquals(1, pluginSettingsRegistry.listCatalogItems().size());
        assertEquals("plugin-golemcore-browser",
                pluginSettingsRegistry.listCatalogItems().getFirst().getRouteKey());
    }

    private PluginManager createManager(Path pluginsDir, String hostVersion) {
        BotProperties properties = new BotProperties();
        properties.getPlugins().setEnabled(true);
        properties.getPlugins().setAutoReload(false);
        properties.getPlugins().setAutoStart(false);
        properties.getPlugins().setDirectory(pluginsDir.toString());

        applicationContext = new AnnotationConfigApplicationContext();
        applicationContext.registerBean(OkHttpClient.class, () -> new OkHttpClient());
        applicationContext.refresh();

        pluginSettingsRegistry = new PluginSettingsRegistry();

        return new PluginManager(
                properties,
                applicationContext,
                buildPropertiesProvider(hostVersion),
                new ChannelRegistry(List.of()),
                new PluginBackedConfirmationPort(),
                new SttProviderRegistry(),
                new TtsProviderRegistry(),
                new RagProviderRegistry(),
                pluginSettingsRegistry,
                mock(ToolCallExecutionService.class),
                new PluginExtensionApiMapper());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<BuildProperties> buildPropertiesProvider(String version) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        Properties properties = new Properties();
        properties.setProperty("version", version);
        when(provider.getIfAvailable()).thenReturn(new BuildProperties(properties));
        return provider;
    }

    private Path createPluginJar(Path pluginsDir, String pluginId, String version, String engineVersion)
            throws Exception {
        String[] coordinates = pluginId.split("/", 2);
        String packageSuffix = pluginId.replace('/', '.').replace('-', '_') + ".v" + version.replace('.', '_')
                .replace('-', '_');
        String packageName = "testplugins." + packageSuffix;
        String bootstrapClassName = "GeneratedPluginBootstrap";
        String configurationClassName = "GeneratedPluginConfiguration";
        String bootstrapFqcn = packageName + "." + bootstrapClassName;

        Path workDir = pluginsDir.resolve("build-" + version.replace('.', '_'));
        Path sourceRoot = workDir.resolve("src");
        Path classesRoot = workDir.resolve("classes");
        Path packageDir = sourceRoot.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve(configurationClassName + ".java"),
                configurationSource(packageName, configurationClassName), StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve(bootstrapClassName + ".java"),
                bootstrapSource(packageName, bootstrapClassName, configurationClassName, pluginId, coordinates[0],
                        coordinates[1], version, engineVersion),
                StandardCharsets.UTF_8);

        compileSources(sourceRoot, classesRoot);

        Path jarPath = pluginsDir.resolve(coordinates[0] + "-" + coordinates[1] + "-" + version + ".jar");
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            addCompiledClasses(output, classesRoot);
            writeJarEntry(output, "META-INF/services/me.golemcore.plugin.api.extension.spi.PluginBootstrap",
                    bootstrapFqcn);
            writeJarEntry(output, "META-INF/golemcore/plugin.yaml",
                    pluginYaml(pluginId, coordinates[0], coordinates[1], version, engineVersion, bootstrapFqcn));
        }
        return jarPath;
    }

    private Path createPluginJarWithOkHttpBackedSettings(
            Path pluginsDir,
            String pluginId,
            String version,
            String engineVersion) throws Exception {
        String[] coordinates = pluginId.split("/", 2);
        String packageSuffix = pluginId.replace('/', '.').replace('-', '_') + ".v" + version.replace('.', '_')
                .replace('-', '_');
        String packageName = "testplugins." + packageSuffix;
        String bootstrapClassName = "GeneratedPluginBootstrap";
        String configurationClassName = "GeneratedPluginConfiguration";
        String settingsContributorClassName = "GeneratedPluginSettingsContributor";
        String bootstrapFqcn = packageName + "." + bootstrapClassName;

        Path workDir = pluginsDir.resolve("build-" + version.replace('.', '_') + "-okhttp");
        Path sourceRoot = workDir.resolve("src");
        Path classesRoot = workDir.resolve("classes");
        Path packageDir = sourceRoot.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve(configurationClassName + ".java"),
                okHttpConfigurationSource(packageName, configurationClassName, settingsContributorClassName),
                StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve(settingsContributorClassName + ".java"),
                okHttpSettingsContributorSource(packageName, settingsContributorClassName, pluginId),
                StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve(bootstrapClassName + ".java"),
                bootstrapSource(packageName, bootstrapClassName, configurationClassName, pluginId, coordinates[0],
                        coordinates[1], version, engineVersion),
                StandardCharsets.UTF_8);

        compileSources(sourceRoot, classesRoot);

        Path jarPath = pluginsDir.resolve(coordinates[0] + "-" + coordinates[1] + "-" + version + ".jar");
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            addCompiledClasses(output, classesRoot);
            writeClassCopy(output, OkHttpClient.class);
            writeJarEntry(output, "META-INF/services/me.golemcore.plugin.api.extension.spi.PluginBootstrap",
                    bootstrapFqcn);
            writeJarEntry(output, "META-INF/golemcore/plugin.yaml",
                    pluginYaml(pluginId, coordinates[0], coordinates[1], version, engineVersion, bootstrapFqcn));
        }
        return jarPath;
    }

    private void compileSources(Path sourceRoot, Path classesRoot) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for plugin runtime tests");
        Files.createDirectories(classesRoot);

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null,
                StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesRoot.toFile()));
            List<Path> sourceFiles;
            try (Stream<Path> stream = Files.walk(sourceRoot)) {
                sourceFiles = stream.filter(path -> path.toString().endsWith(".java")).toList();
            }
            boolean success = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    List.of("-classpath", System.getProperty("java.class.path")),
                    null,
                    fileManager.getJavaFileObjectsFromPaths(sourceFiles)).call();
            assertTrue(success, "Generated plugin sources must compile");
        }
    }

    private void addCompiledClasses(JarOutputStream output, Path classesRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(classesRoot)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        String entryName = classesRoot.relativize(path).toString().replace('\\', '/');
                        try {
                            output.putNextEntry(new JarEntry(entryName));
                            output.write(Files.readAllBytes(path));
                            output.closeEntry();
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    private void writeJarEntry(JarOutputStream output, String name, String content) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private void writeClassCopy(JarOutputStream output, Class<?> type) throws IOException {
        String entryName = type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getClassLoader().getResourceAsStream(entryName)) {
            assertNotNull(input, "Class bytes must be available for " + type.getName());
            output.putNextEntry(new JarEntry(entryName));
            input.transferTo(output);
            output.closeEntry();
        }
    }

    private String configurationSource(String packageName, String configurationClassName) {
        return """
                package %s;

                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class %s {
                }
                """.formatted(packageName, configurationClassName);
    }

    private String okHttpConfigurationSource(
            String packageName,
            String configurationClassName,
            String settingsContributorClassName) {
        return """
                package %s;

                import okhttp3.OkHttpClient;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class %s {

                    @Bean
                    public %s generatedPluginSettingsContributor(OkHttpClient okHttpClient) {
                        return new %s(okHttpClient);
                    }
                }
                """.formatted(packageName, configurationClassName, settingsContributorClassName,
                settingsContributorClassName);
    }

    private String okHttpSettingsContributorSource(String packageName, String className, String pluginId) {
        return """
                package %s;

                import java.util.List;
                import java.util.Map;

                import me.golemcore.plugin.api.extension.spi.PluginActionResult;
                import me.golemcore.plugin.api.extension.spi.PluginSettingsCatalogItem;
                import me.golemcore.plugin.api.extension.spi.PluginSettingsContributor;
                import me.golemcore.plugin.api.extension.spi.PluginSettingsSection;
                import okhttp3.OkHttpClient;

                public class %s implements PluginSettingsContributor {

                    private final OkHttpClient okHttpClient;

                    public %s(OkHttpClient okHttpClient) {
                        this.okHttpClient = okHttpClient;
                    }

                    @Override
                    public String getPluginId() {
                        return "%s";
                    }

                    @Override
                    public List<PluginSettingsCatalogItem> getCatalogItems() {
                        return List.of(PluginSettingsCatalogItem.builder()
                                .sectionKey("main")
                                .title("Test Settings")
                                .description(okHttpClient.getClass().getName())
                                .build());
                    }

                    @Override
                    public PluginSettingsSection getSection(String sectionKey) {
                        return PluginSettingsSection.builder()
                                .sectionKey(sectionKey)
                                .title("Test Settings")
                                .description(okHttpClient.getClass().getName())
                                .build();
                    }

                    @Override
                    public PluginSettingsSection saveSection(String sectionKey, Map<String, Object> values) {
                        return getSection(sectionKey);
                    }

                    @Override
                    public PluginActionResult executeAction(String sectionKey, String actionId, Map<String, Object> payload) {
                        return PluginActionResult.builder()
                                .status("ok")
                                .message(actionId)
                                .build();
                    }
                }
                """
                .formatted(packageName, className, className, pluginId);
    }

    private String bootstrapSource(String packageName, String bootstrapClassName, String configurationClassName,
            String pluginId, String provider, String name, String version, String engineVersion) {
        return """
                package %s;

                import me.golemcore.plugin.api.extension.spi.PluginBootstrap;
                import me.golemcore.plugin.api.extension.spi.PluginDescriptor;

                public class %s implements PluginBootstrap {
                    @Override
                    public PluginDescriptor descriptor() {
                        return PluginDescriptor.builder()
                                .id("%s")
                                .provider("%s")
                                .name("%s")
                                .version("%s")
                                .pluginApiVersion(1)
                                .engineVersion("%s")
                                .build();
                    }

                    @Override
                    public Class<?> configurationClass() {
                        return %s.class;
                    }
                }
                """.formatted(packageName, bootstrapClassName, pluginId, provider, name, version, engineVersion,
                configurationClassName);
    }

    private String pluginYaml(String pluginId, String provider, String name, String version, String engineVersion,
            String bootstrapFqcn) {
        return """
                id: "%s"
                provider: "%s"
                name: "%s"
                version: "%s"
                pluginApiVersion: 1
                engineVersion: "%s"
                entrypoint: "%s"
                """.formatted(pluginId, provider, name, version, engineVersion, bootstrapFqcn);
    }
}

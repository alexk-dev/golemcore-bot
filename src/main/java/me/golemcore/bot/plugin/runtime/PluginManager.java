package me.golemcore.bot.plugin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.runtime.extension.PluginToolAdapter;
import me.golemcore.bot.plugin.runtime.extension.PluginExtensionApiMapper;
import me.golemcore.bot.plugin.runtime.extension.PluginChannelPortAdapter;
import me.golemcore.bot.plugin.runtime.extension.PluginConfirmationPortAdapter;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import me.golemcore.plugin.api.extension.spi.PluginBootstrap;
import me.golemcore.plugin.api.extension.spi.PluginDescriptor;
import me.golemcore.plugin.api.extension.spi.PluginSettingsContributor;
import me.golemcore.plugin.api.extension.spi.RagProvider;
import me.golemcore.plugin.api.extension.spi.SttProvider;
import me.golemcore.plugin.api.extension.spi.TtsProvider;
import me.golemcore.plugin.api.extension.spi.ToolProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads plugin JARs into isolated child Spring contexts and registers exposed
 * extension beans into host registries.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PluginManager {

    private static final ObjectMapper PLUGIN_MANIFEST_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String PLUGIN_MANIFEST_PATH = "META-INF/golemcore/plugin.yaml";
    private static final int HOST_PLUGIN_API_VERSION = 1;
    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?$");

    private final BotProperties botProperties;
    private final ConfigurableApplicationContext applicationContext;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ChannelRegistry channelRegistry;
    private final PluginBackedConfirmationPort confirmationPort;
    private final SttProviderRegistry sttProviderRegistry;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final RagProviderRegistry ragProviderRegistry;
    private final PluginSettingsRegistry pluginSettingsRegistry;
    private final ToolCallExecutionService toolCallExecutionService;
    private final PluginExtensionApiMapper pluginApiMapper;

    private final Map<Path, LoadedPlugin> pluginsByJar = new LinkedHashMap<>();
    private final Map<String, LoadedPlugin> pluginsById = new LinkedHashMap<>();
    private final Map<Path, JarFingerprint> fingerprintsByJar = new LinkedHashMap<>();

    private ScheduledExecutorService pollExecutor;

    @PostConstruct
    public synchronized void startPolling() {
        if (!botProperties.getPlugins().isEnabled() || !botProperties.getPlugins().isAutoReload()) {
            return;
        }
        Duration interval = botProperties.getPlugins().getPollInterval();
        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "plugin-runtime-poller");
            t.setDaemon(true);
            return t;
        });
        pollExecutor.scheduleWithFixedDelay(this::safeReloadAll,
                interval.toSeconds(),
                interval.toSeconds(),
                TimeUnit.SECONDS);
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
        }
        List<LoadedPlugin> loaded = List.copyOf(pluginsByJar.values());
        loaded.forEach(this::unloadPlugin);
        pluginsByJar.clear();
        pluginsById.clear();
        fingerprintsByJar.clear();
    }

    public synchronized void reloadAll() {
        if (!botProperties.getPlugins().isEnabled()) {
            return;
        }
        synchronizeActiveArtifacts(discoverActiveArtifacts());
    }

    public synchronized boolean reloadPlugin(String pluginId) {
        String normalizedPluginId = normalizePluginId(pluginId);
        Map<String, PluginArtifactCandidate> activeArtifacts = discoverActiveArtifacts();
        PluginArtifactCandidate candidate = activeArtifacts.get(normalizedPluginId);
        if (candidate == null) {
            removeLoadedPlugin(pluginsById.get(normalizedPluginId));
            return false;
        }
        reloadCandidate(candidate);
        return pluginsById.containsKey(normalizedPluginId);
    }

    public synchronized List<PluginRuntimeInfo> listPlugins() {
        return pluginsById.values().stream()
                .map(plugin -> PluginRuntimeInfo.builder()
                        .id(plugin.descriptor().getId())
                        .name(plugin.descriptor().getName())
                        .provider(plugin.descriptor().getProvider())
                        .version(plugin.descriptor().getVersion())
                        .pluginApiVersion(plugin.descriptor().getPluginApiVersion())
                        .engineVersion(plugin.descriptor().getEngineVersion())
                        .jarPath(plugin.jarPath().toString())
                        .loaded(true)
                        .build())
                .sorted(java.util.Comparator.comparing(PluginRuntimeInfo::getId))
                .toList();
    }

    public synchronized void publishToPlugins(Object event) {
        for (LoadedPlugin plugin : pluginsByJar.values()) {
            try {
                plugin.applicationContext().publishEvent(event);
            } catch (RuntimeException ex) {
                log.warn("[Plugins] Failed to republish event {} to plugin {}: {}",
                        event.getClass().getName(), plugin.descriptor().getId(), ex.getMessage());
            }
        }
    }

    private void safeReloadAll() {
        try {
            reloadAll();
        } catch (RuntimeException ex) {
            log.error("[Plugins] Background plugin reload failed", ex);
        }
    }

    private void synchronizeActiveArtifacts(Map<String, PluginArtifactCandidate> activeArtifacts) {
        for (LoadedPlugin loadedPlugin : List.copyOf(pluginsById.values())) {
            PluginArtifactCandidate candidate = activeArtifacts.get(loadedPlugin.descriptor().getId());
            if (candidate == null) {
                removeLoadedPlugin(loadedPlugin);
            }
        }

        for (PluginArtifactCandidate candidate : activeArtifacts.values()) {
            LoadedPlugin current = pluginsById.get(candidate.descriptor().getId());
            JarFingerprint currentFingerprint = current != null ? fingerprintsByJar.get(current.jarPath()) : null;
            if (current == null
                    || !candidate.jarPath().equals(current.jarPath())
                    || !candidate.fingerprint().equals(currentFingerprint)) {
                reloadCandidate(candidate);
            }
        }
    }

    private void reloadCandidate(PluginArtifactCandidate candidate) {
        LoadedPlugin replacement = loadPlugin(candidate);
        if (replacement == null) {
            return;
        }

        LoadedPlugin existingById = pluginsById.get(replacement.descriptor().getId());
        LoadedPlugin existingByPath = pluginsByJar.get(candidate.jarPath());
        if (existingById != null) {
            removeLoadedPlugin(existingById);
        }
        if (existingByPath != null && existingByPath != existingById) {
            removeLoadedPlugin(existingByPath);
        }

        registerPlugin(replacement);
        pluginsByJar.put(candidate.jarPath(), replacement);
        pluginsById.put(replacement.descriptor().getId(), replacement);
        fingerprintsByJar.put(candidate.jarPath(), candidate.fingerprint());
    }

    private LoadedPlugin loadPlugin(PluginArtifactCandidate candidate) {
        Path jarPath = candidate.jarPath();
        ChildFirstPluginClassLoader classLoader = null;
        AnnotationConfigApplicationContext pluginContext = null;
        try {
            classLoader = new ChildFirstPluginClassLoader(
                    new URL[] { jarPath.toUri().toURL() },
                    applicationContext.getClassLoader());
            ServiceLoader<PluginBootstrap> loader = ServiceLoader.load(PluginBootstrap.class, classLoader);
            PluginBootstrap bootstrap = loader.findFirst()
                    .orElseThrow(() -> new IllegalStateException("No PluginBootstrap found in " + jarPath));
            PluginDescriptor descriptor = resolveDescriptor(classLoader, bootstrap);
            validateDescriptor(descriptor);
            validateCompatibility(descriptor);

            pluginContext = new AnnotationConfigApplicationContext();
            pluginContext.setClassLoader(classLoader);
            pluginContext.setParent(applicationContext);
            pluginContext.getBeanFactory().registerSingleton("pluginDescriptor", descriptor);
            pluginContext.register(bootstrap.configurationClass());
            pluginContext.refresh();

            Collection<me.golemcore.plugin.api.extension.port.inbound.ChannelPort> pluginChannelPorts = pluginContext
                    .getBeansOfType(me.golemcore.plugin.api.extension.port.inbound.ChannelPort.class).values();
            Collection<me.golemcore.plugin.api.extension.port.outbound.ConfirmationPort> pluginConfirmationPorts = pluginContext
                    .getBeansOfType(me.golemcore.plugin.api.extension.port.outbound.ConfirmationPort.class).values();
            Collection<SttProvider> sttProviders = pluginContext.getBeansOfType(SttProvider.class).values();
            Collection<TtsProvider> ttsProviders = pluginContext.getBeansOfType(TtsProvider.class).values();
            Collection<RagProvider> ragProviders = pluginContext.getBeansOfType(RagProvider.class).values();
            Collection<ToolProvider> toolProviders = pluginContext.getBeansOfType(ToolProvider.class).values();
            Collection<PluginSettingsContributor> settingsContributors = pluginContext
                    .getBeansOfType(PluginSettingsContributor.class).values();
            List<ChannelPort> channelPorts = pluginChannelPorts.stream()
                    .map(port -> new PluginChannelPortAdapter(port, pluginApiMapper))
                    .map(ChannelPort.class::cast)
                    .toList();
            List<ConfirmationPort> confirmationPorts = pluginConfirmationPorts.stream()
                    .map(PluginConfirmationPortAdapter::new)
                    .map(ConfirmationPort.class::cast)
                    .toList();
            List<ToolComponent> tools = toolProviders.stream()
                    .map(provider -> new PluginToolAdapter(provider, pluginApiMapper))
                    .map(ToolComponent.class::cast)
                    .toList();

            log.info(
                    "[Plugins] Loaded {} v{} from {} (channels={}, confirmations={}, stt={}, tts={}, rag={}, tools={}, settings={})",
                    descriptor.getId(), descriptor.getVersion(), jarPath,
                    channelPorts.size(), confirmationPorts.size(), sttProviders.size(), ttsProviders.size(),
                    ragProviders.size(), tools.size(),
                    settingsContributors.size());

            return new LoadedPlugin(descriptor, jarPath, classLoader, pluginContext,
                    channelPorts, confirmationPorts, List.copyOf(sttProviders),
                    List.copyOf(ttsProviders), List.copyOf(ragProviders), tools, List.copyOf(settingsContributors));
        } catch (IOException | RuntimeException | ServiceConfigurationError | LinkageError ex) {
            closeFailedPluginContext(jarPath, pluginContext);
            closeFailedPluginClassLoader(jarPath, classLoader);
            log.error("[Plugins] Failed to load plugin from {}", jarPath, ex);
            return null;
        }
    }

    private void closeFailedPluginContext(Path jarPath, AnnotationConfigApplicationContext pluginContext) {
        if (pluginContext == null) {
            return;
        }
        try {
            pluginContext.close();
        } catch (RuntimeException ex) {
            log.warn("[Plugins] Failed to close incomplete child context for {}: {}", jarPath, ex.getMessage());
        }
    }

    private void closeFailedPluginClassLoader(Path jarPath, ChildFirstPluginClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException ex) {
            log.warn("[Plugins] Failed to close incomplete classloader for {}: {}", jarPath, ex.getMessage());
        }
    }

    private void registerPlugin(LoadedPlugin plugin) {
        String pluginId = plugin.descriptor().getId();
        channelRegistry.replacePluginChannels(pluginId, plugin.channels());
        confirmationPort.replacePluginPorts(pluginId, plugin.confirmationPorts());
        sttProviderRegistry.replaceProviders(pluginId, plugin.sttProviders());
        ttsProviderRegistry.replaceProviders(pluginId, plugin.ttsProviders());
        ragProviderRegistry.replaceProviders(pluginId, plugin.ragProviders());
        plugin.tools().forEach(toolCallExecutionService::registerTool);
        pluginSettingsRegistry.replaceContributors(plugin.descriptor(), plugin.settingsContributors());
        if (botProperties.getPlugins().isAutoStart()) {
            plugin.channels().forEach(this::safeStartChannel);
        }
    }

    private void unloadPlugin(LoadedPlugin plugin) {
        if (plugin == null) {
            return;
        }
        String pluginId = plugin.descriptor().getId();
        plugin.channels().forEach(this::safeStopChannel);
        channelRegistry.removePluginChannels(pluginId);
        confirmationPort.removePluginPorts(pluginId);
        sttProviderRegistry.removeProviders(pluginId);
        ttsProviderRegistry.removeProviders(pluginId);
        ragProviderRegistry.removeProviders(pluginId);
        toolCallExecutionService.unregisterTools(plugin.tools().stream()
                .map(ToolComponent::getToolName)
                .toList());
        pluginSettingsRegistry.removePlugin(pluginId);
        pluginsById.remove(pluginId);
        try {
            plugin.applicationContext().close();
        } catch (RuntimeException ex) {
            log.warn("[Plugins] Failed to close child context for {}: {}", pluginId, ex.getMessage());
        }
        try {
            plugin.classLoader().close();
        } catch (IOException ex) {
            log.warn("[Plugins] Failed to close classloader for {}: {}", pluginId, ex.getMessage());
        }
        log.info("[Plugins] Unloaded {}", pluginId);
    }

    private void removeLoadedPlugin(LoadedPlugin plugin) {
        if (plugin == null) {
            return;
        }
        pluginsByJar.remove(plugin.jarPath());
        fingerprintsByJar.remove(plugin.jarPath());
        unloadPlugin(plugin);
    }

    private Map<String, PluginArtifactCandidate> discoverActiveArtifacts() {
        Map<String, PluginArtifactCandidate> result = new LinkedHashMap<>();
        Path pluginsRoot = Path.of(botProperties.getPlugins().getDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(pluginsRoot)) {
            return result;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(pluginsRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(path -> registerCandidate(result, path.toAbsolutePath().normalize()));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan plugin directory " + pluginsRoot, ex);
        }
        return result;
    }

    private void registerCandidate(Map<String, PluginArtifactCandidate> selectedByPluginId, Path jarPath) {
        try {
            PluginDescriptor descriptor = probeDescriptor(jarPath);
            validateDescriptor(descriptor);
            validateCompatibility(descriptor);
            PluginArtifactCandidate candidate = new PluginArtifactCandidate(descriptor, jarPath, fingerprint(jarPath));
            PluginArtifactCandidate existing = selectedByPluginId.get(descriptor.getId());
            if (existing == null) {
                selectedByPluginId.put(descriptor.getId(), candidate);
                return;
            }

            int versionComparison = compareVersions(descriptor.getVersion(), existing.descriptor().getVersion());
            if (versionComparison > 0) {
                log.info("[Plugins] Selecting newer artifact for {}: {} -> {}",
                        descriptor.getId(), existing.jarPath(), jarPath);
                selectedByPluginId.put(descriptor.getId(), candidate);
                return;
            }
            if (versionComparison == 0 && !existing.jarPath().equals(jarPath)) {
                log.error("[Plugins] Duplicate plugin artifact for {} v{} in {} and {}. Keeping {}.",
                        descriptor.getId(), descriptor.getVersion(), existing.jarPath(), jarPath, existing.jarPath());
                return;
            }
            log.debug("[Plugins] Ignoring older plugin artifact for {}: {} (active={})",
                    descriptor.getId(), jarPath, existing.jarPath());
        } catch (RuntimeException ex) {
            log.warn("[Plugins] Skipping plugin artifact {}: {}", jarPath, ex.getMessage());
        }
    }

    private PluginDescriptor probeDescriptor(Path jarPath) {
        PluginDescriptor descriptor = readDescriptorFromManifest(jarPath);
        if (descriptor != null) {
            normalizeManifestDescriptor(descriptor);
            return descriptor;
        }

        try (ChildFirstPluginClassLoader classLoader = new ChildFirstPluginClassLoader(
                new URL[] { jarPath.toUri().toURL() },
                applicationContext.getClassLoader())) {
            PluginBootstrap bootstrap = ServiceLoader.load(PluginBootstrap.class, classLoader)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No PluginBootstrap found in " + jarPath));
            PluginDescriptor bootstrapDescriptor = bootstrap.descriptor();
            if (bootstrapDescriptor == null) {
                throw new IllegalStateException("Plugin descriptor is missing from bootstrap in " + jarPath);
            }
            normalizeDescriptor(bootstrapDescriptor, bootstrap);
            return bootstrapDescriptor;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect plugin descriptor for " + jarPath, ex);
        }
    }

    private PluginDescriptor readDescriptorFromManifest(Path jarPath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            java.util.jar.JarEntry entry = jarFile.getJarEntry(PLUGIN_MANIFEST_PATH);
            if (entry == null) {
                return null;
            }
            try (InputStream stream = jarFile.getInputStream(entry)) {
                return PLUGIN_MANIFEST_MAPPER.readValue(stream, PluginDescriptor.class);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read plugin manifest from " + jarPath, ex);
        }
    }

    private JarFingerprint fingerprint(Path jarPath) {
        try {
            return new JarFingerprint(Files.getLastModifiedTime(jarPath).toMillis(), Files.size(jarPath));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to fingerprint plugin jar " + jarPath, ex);
        }
    }

    private void normalizeDescriptor(PluginDescriptor descriptor, PluginBootstrap bootstrap) {
        if (descriptor.getEntrypoint() == null || descriptor.getEntrypoint().isBlank()) {
            descriptor.setEntrypoint(bootstrap.getClass().getName());
        }
        normalizeManifestDescriptor(descriptor);
    }

    private void normalizeManifestDescriptor(PluginDescriptor descriptor) {
        if ((descriptor.getProvider() == null || descriptor.getProvider().isBlank())
                && descriptor.getId() != null
                && descriptor.getId().contains("/")) {
            descriptor.setProvider(descriptor.getId().substring(0, descriptor.getId().indexOf('/')));
        }
        if ((descriptor.getName() == null || descriptor.getName().isBlank())
                && descriptor.getId() != null
                && descriptor.getId().contains("/")) {
            descriptor.setName(descriptor.getId().substring(descriptor.getId().indexOf('/') + 1));
        }
    }

    private PluginDescriptor resolveDescriptor(ClassLoader classLoader, PluginBootstrap bootstrap) throws IOException {
        PluginDescriptor bootstrapDescriptor = bootstrap.descriptor();
        PluginDescriptor manifestDescriptor = null;
        try (InputStream stream = classLoader.getResourceAsStream(PLUGIN_MANIFEST_PATH)) {
            if (stream != null) {
                manifestDescriptor = PLUGIN_MANIFEST_MAPPER.readValue(stream, PluginDescriptor.class);
            }
        }
        if (manifestDescriptor == null && bootstrapDescriptor == null) {
            throw new IllegalStateException("Plugin descriptor is missing from both plugin.yaml and bootstrap");
        }
        if (manifestDescriptor != null && bootstrapDescriptor != null) {
            validateDescriptorConsistency(manifestDescriptor, bootstrapDescriptor);
            applyDescriptorFallbacks(manifestDescriptor, bootstrapDescriptor);
        }
        PluginDescriptor resolved = manifestDescriptor != null ? manifestDescriptor : bootstrapDescriptor;
        normalizeDescriptor(resolved, bootstrap);
        return resolved;
    }

    private void applyDescriptorFallbacks(PluginDescriptor target, PluginDescriptor fallback) {
        if ((target.getProvider() == null || target.getProvider().isBlank())
                && fallback.getProvider() != null
                && !fallback.getProvider().isBlank()) {
            target.setProvider(fallback.getProvider());
        }
        if ((target.getName() == null || target.getName().isBlank())
                && fallback.getName() != null
                && !fallback.getName().isBlank()) {
            target.setName(fallback.getName());
        }
        if ((target.getVersion() == null || target.getVersion().isBlank())
                && fallback.getVersion() != null
                && !fallback.getVersion().isBlank()) {
            target.setVersion(fallback.getVersion());
        }
        if (target.getPluginApiVersion() == null && fallback.getPluginApiVersion() != null) {
            target.setPluginApiVersion(fallback.getPluginApiVersion());
        }
        if ((target.getEngineVersion() == null || target.getEngineVersion().isBlank())
                && fallback.getEngineVersion() != null
                && !fallback.getEngineVersion().isBlank()) {
            target.setEngineVersion(fallback.getEngineVersion());
        }
        if ((target.getDescription() == null || target.getDescription().isBlank())
                && fallback.getDescription() != null
                && !fallback.getDescription().isBlank()) {
            target.setDescription(fallback.getDescription());
        }
        if ((target.getSourceUrl() == null || target.getSourceUrl().isBlank())
                && fallback.getSourceUrl() != null
                && !fallback.getSourceUrl().isBlank()) {
            target.setSourceUrl(fallback.getSourceUrl());
        }
        if ((target.getLicense() == null || target.getLicense().isBlank())
                && fallback.getLicense() != null
                && !fallback.getLicense().isBlank()) {
            target.setLicense(fallback.getLicense());
        }
        if ((target.getMaintainers() == null || target.getMaintainers().isEmpty())
                && fallback.getMaintainers() != null
                && !fallback.getMaintainers().isEmpty()) {
            target.setMaintainers(fallback.getMaintainers());
        }
    }

    private void validateDescriptorConsistency(PluginDescriptor manifestDescriptor,
            PluginDescriptor bootstrapDescriptor) {
        if (manifestDescriptor.getId() != null
                && bootstrapDescriptor.getId() != null
                && !manifestDescriptor.getId().equals(bootstrapDescriptor.getId())) {
            throw new IllegalStateException("plugin.yaml id does not match bootstrap descriptor id");
        }
        if (manifestDescriptor.getVersion() != null
                && bootstrapDescriptor.getVersion() != null
                && !manifestDescriptor.getVersion().equals(bootstrapDescriptor.getVersion())) {
            throw new IllegalStateException("plugin.yaml version does not match bootstrap descriptor version");
        }
    }

    private void validateDescriptor(PluginDescriptor descriptor) {
        if (descriptor.getId() == null
                || !descriptor.getId().matches("[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("Plugin id must match <owner>/<plugin> in lowercase kebab-case");
        }
        if (descriptor.getVersion() == null
                || !descriptor.getVersion().matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.-]+)?")) {
            throw new IllegalArgumentException("Plugin version must be SemVer");
        }
        if (descriptor.getPluginApiVersion() == null || descriptor.getPluginApiVersion() != HOST_PLUGIN_API_VERSION) {
            throw new IllegalArgumentException("pluginApiVersion must be " + HOST_PLUGIN_API_VERSION);
        }
    }

    private void validateCompatibility(PluginDescriptor descriptor) {
        String engineConstraint = descriptor.getEngineVersion();
        if (engineConstraint == null || engineConstraint.isBlank()) {
            return;
        }
        String engineVersion = resolveEngineVersion();
        if (!matchesVersionConstraint(engineVersion, engineConstraint)) {
            throw new IllegalArgumentException(
                    "Plugin " + descriptor.getId() + " requires engineVersion " + engineConstraint
                            + ", current engine is " + engineVersion);
        }
    }

    private String resolveEngineVersion() {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        String version = buildProperties != null ? buildProperties.getVersion() : null;
        return PluginVersionSupport.normalizeHostVersion(version != null ? version : "0.0.0-SNAPSHOT");
    }

    private boolean matchesVersionConstraint(String version, String constraint) {
        if (constraint == null || constraint.isBlank()) {
            return true;
        }
        for (String token : constraint.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            String normalizedToken = token;
            if (normalizedToken.endsWith(">")) {
                normalizedToken = normalizedToken.substring(0, normalizedToken.length() - 1);
            }
            String operator = extractOperator(normalizedToken);
            String expectedVersion = normalizedToken.substring(operator.length());
            int comparison = compareVersions(version, expectedVersion);
            boolean matches = switch (operator) {
            case ">=" -> comparison >= 0;
            case ">" -> comparison > 0;
            case "<=" -> comparison <= 0;
            case "<" -> comparison < 0;
            case "=" -> comparison == 0;
            default -> throw new IllegalArgumentException("Unsupported engineVersion constraint: " + token);
            };
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    private String extractOperator(String token) {
        if (token.startsWith(">=") || token.startsWith("<=")) {
            return token.substring(0, 2);
        }
        if (token.startsWith(">") || token.startsWith("<") || token.startsWith("=")) {
            return token.substring(0, 1);
        }
        throw new IllegalArgumentException("Constraint token must start with comparison operator: " + token);
    }

    private int compareVersions(String left, String right) {
        SemVer leftVersion = parseSemVer(left);
        SemVer rightVersion = parseSemVer(right);
        int mainComparison = Integer.compare(leftVersion.major(), rightVersion.major());
        if (mainComparison != 0) {
            return mainComparison;
        }
        int minorComparison = Integer.compare(leftVersion.minor(), rightVersion.minor());
        if (minorComparison != 0) {
            return minorComparison;
        }
        int patchComparison = Integer.compare(leftVersion.patch(), rightVersion.patch());
        if (patchComparison != 0) {
            return patchComparison;
        }
        if (leftVersion.preRelease() == null && rightVersion.preRelease() == null) {
            return 0;
        }
        if (leftVersion.preRelease() == null) {
            return 1;
        }
        if (rightVersion.preRelease() == null) {
            return -1;
        }
        return comparePreRelease(leftVersion.preRelease(), rightVersion.preRelease());
    }

    private int comparePreRelease(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            if (i >= leftParts.length) {
                return -1;
            }
            if (i >= rightParts.length) {
                return 1;
            }
            String leftPart = leftParts[i];
            String rightPart = rightParts[i];
            boolean leftNumeric = leftPart.chars().allMatch(Character::isDigit);
            boolean rightNumeric = rightPart.chars().allMatch(Character::isDigit);
            if (leftNumeric && rightNumeric) {
                int comparison = Integer.compare(Integer.parseInt(leftPart), Integer.parseInt(rightPart));
                if (comparison != 0) {
                    return comparison;
                }
                continue;
            }
            if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            }
            int comparison = leftPart.compareTo(rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private SemVer parseSemVer(String version) {
        Matcher matcher = SEMVER_PATTERN.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid SemVer: " + version);
        }
        return new SemVer(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0,
                matcher.group(4));
    }

    private String normalizePluginId(String pluginId) {
        return pluginId == null ? null : pluginId.trim().toLowerCase(Locale.ROOT);
    }

    private void safeStartChannel(ChannelPort channel) {
        try {
            channel.start();
        } catch (RuntimeException ex) {
            log.warn("[Plugins] Failed to start channel {}: {}", channel.getChannelType(), ex.getMessage());
        }
    }

    private void safeStopChannel(ChannelPort channel) {
        try {
            channel.stop();
        } catch (RuntimeException ex) {
            log.warn("[Plugins] Failed to stop channel {}: {}", channel.getChannelType(), ex.getMessage());
        }
    }

    private record JarFingerprint(long lastModifiedMillis, long sizeBytes) {
    }

    private record PluginArtifactCandidate(
            PluginDescriptor descriptor,
            Path jarPath,
            JarFingerprint fingerprint) {
    }

    private record LoadedPlugin(
            PluginDescriptor descriptor,
            Path jarPath,
            ChildFirstPluginClassLoader classLoader,
            AnnotationConfigApplicationContext applicationContext,
            List<ChannelPort> channels,
            List<ConfirmationPort> confirmationPorts,
            List<SttProvider> sttProviders,
            List<TtsProvider> ttsProviders,
            List<RagProvider> ragProviders,
            List<ToolComponent> tools,
            List<PluginSettingsContributor> settingsContributors) {
    }

    private record SemVer(int major, int minor, int patch, String preRelease) {
    }

}

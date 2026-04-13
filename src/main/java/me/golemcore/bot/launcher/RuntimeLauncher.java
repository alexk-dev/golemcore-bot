package me.golemcore.bot.launcher;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Supervises the actual bot runtime so self-update can restart into a staged
 * jar instead of relaunching the immutable container classpath.
 */
public final class RuntimeLauncher {

    static final int RESTART_EXIT_CODE = 42;
    static final String APPLICATION_MAIN_CLASS = "me.golemcore.bot.BotApplication";
    static final String CURRENT_MARKER_NAME = "current.txt";
    static final String JARS_DIR_NAME = "jars";
    static final String JIB_CLASSPATH_FILE = "/app/jib-classpath-file";
    static final String STORAGE_PATH_ENV = "STORAGE_PATH";
    static final String STORAGE_PATH_PROPERTY = "bot.storage.local.base-path";
    static final String UPDATE_PATH_ENV = "UPDATE_PATH";
    static final String UPDATE_PATH_PROPERTY = "bot.update.updates-path";
    static final String BUNDLED_JAR_ENV = "GOLEMCORE_BUNDLED_JAR";
    static final String BUNDLED_JAR_PROPERTY = "golemcore.launcher.bundled-jar";
    static final String SERVER_PORT_PROPERTY = "server.port";
    private static final String LIB_DIR_NAME = "lib";
    private static final String RUNTIME_DIR_NAME = "runtime";

    private static final List<String> FORWARDED_SYSTEM_PROPERTIES = List.of(
            STORAGE_PATH_PROPERTY,
            UPDATE_PATH_PROPERTY,
            SERVER_PORT_PROPERTY);

    private final String javaCommand;
    private final ProcessStarter processStarter;
    private final EnvironmentReader environmentReader;
    private final PropertyReader propertyReader;
    private final LauncherOutput output;
    private final BundledRuntimeResolver bundledRuntimeResolver;

    public RuntimeLauncher() {
        this(resolveJavaCommand(), new DefaultProcessStarter(), new SystemEnvironmentReader(),
                new SystemPropertyReader(), new ConsoleLauncherOutput(), new DefaultBundledRuntimeResolver());
    }

    RuntimeLauncher(
            String javaCommand,
            ProcessStarter processStarter,
            EnvironmentReader environmentReader,
            PropertyReader propertyReader,
            LauncherOutput output,
            BundledRuntimeResolver bundledRuntimeResolver) {
        this.javaCommand = javaCommand;
        this.processStarter = processStarter;
        this.environmentReader = environmentReader;
        this.propertyReader = propertyReader;
        this.output = output;
        this.bundledRuntimeResolver = bundledRuntimeResolver;
    }

    public static void main(String[] args) {
        RuntimeLauncher launcher = new RuntimeLauncher();
        int exitCode = launcher.run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        while (true) {
            LaunchCommand launchCommand = resolveLaunchCommand(args);
            ChildProcess childProcess;
            try {
                output.info("Starting " + launchCommand.description());
                childProcess = processStarter.start(launchCommand.command());
            } catch (IOException e) {
                output.error("Failed to start runtime: " + safeMessage(e));
                return 1;
            }

            Thread shutdownHook = new Thread(childProcess::destroy, "runtime-launcher-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            int exitCode;
            try {
                exitCode = childProcess.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                childProcess.destroy();
                output.error("Runtime launcher interrupted while waiting for child process");
                return 1;
            } finally {
                removeShutdownHook(shutdownHook);
            }

            if (exitCode != RESTART_EXIT_CODE) {
                output.info("Runtime exited with code " + exitCode);
                return exitCode;
            }

            output.info("Runtime requested restart via exit code " + RESTART_EXIT_CODE);
        }
    }

    LaunchCommand resolveLaunchCommand(String[] args) {
        Path currentJar = resolveCurrentJar(args);
        List<String> applicationArgs = resolveApplicationArgs(args);
        List<String> command = new ArrayList<>();
        command.add(javaCommand);
        command.addAll(resolveForwardedJavaOptions(args));

        if (currentJar != null) {
            command.add("-jar");
            command.add(currentJar.toString());
            command.addAll(applicationArgs);
            return new LaunchCommand(List.copyOf(command), "updated runtime " + currentJar);
        }

        Path bundledJar = resolveBundledJar();
        if (bundledJar != null) {
            command.add("-jar");
            command.add(bundledJar.toString());
            command.addAll(applicationArgs);
            return new LaunchCommand(List.copyOf(command), "bundled runtime jar " + bundledJar);
        }

        command.add("-cp");
        command.add("@" + JIB_CLASSPATH_FILE);
        command.add(APPLICATION_MAIN_CLASS);
        command.addAll(applicationArgs);
        return new LaunchCommand(List.copyOf(command), "bundled runtime from image classpath");
    }

    Path resolveCurrentJar(String[] args) {
        Path updatesDir = resolveUpdatesDir(args);
        Path markerPath = updatesDir.resolve(CURRENT_MARKER_NAME);
        if (!Files.isRegularFile(markerPath)) {
            return null;
        }

        try {
            String assetName = Files.readString(markerPath, StandardCharsets.UTF_8).trim();
            if (assetName.isBlank()) {
                return null;
            }
            if (assetName.contains("/") || assetName.contains("\\") || assetName.contains("..")) {
                output.error("Ignoring invalid current marker asset name: " + assetName);
                return null;
            }

            Path jarsDir = updatesDir.resolve(JARS_DIR_NAME).toAbsolutePath().normalize();
            Path jarPath = jarsDir.resolve(assetName).toAbsolutePath().normalize();
            if (!jarPath.startsWith(jarsDir)) {
                output.error("Ignoring current marker outside updates directory: " + jarPath);
                return null;
            }
            if (!Files.isRegularFile(jarPath)) {
                output.error("Current marker points to a missing jar: " + jarPath);
                return null;
            }
            return jarPath;
        } catch (IOException e) {
            output.error("Failed to read current marker: " + safeMessage(e));
            return null;
        }
    }

    Path resolveUpdatesDir(String[] args) {
        String configuredUpdatesPath = firstNonBlank(
                extractPropertyArg(args, UPDATE_PATH_PROPERTY),
                extractSystemPropertyArg(args, UPDATE_PATH_PROPERTY),
                propertyReader.get(UPDATE_PATH_PROPERTY),
                environmentReader.get(UPDATE_PATH_ENV));
        if (configuredUpdatesPath != null) {
            return normalizePath(configuredUpdatesPath);
        }

        String storageBasePath = firstNonBlank(
                extractPropertyArg(args, STORAGE_PATH_PROPERTY),
                extractSystemPropertyArg(args, STORAGE_PATH_PROPERTY),
                propertyReader.get(STORAGE_PATH_PROPERTY),
                environmentReader.get(STORAGE_PATH_ENV));
        if (storageBasePath != null) {
            return normalizePath(storageBasePath).resolve("updates").normalize();
        }

        return Path.of(System.getProperty("user.home"), ".golemcore", "workspace", "updates")
                .toAbsolutePath()
                .normalize();
    }

    Path resolveBundledJar() {
        String configuredBundledJar = firstNonBlank(
                propertyReader.get(BUNDLED_JAR_PROPERTY),
                environmentReader.get(BUNDLED_JAR_ENV));
        if (configuredBundledJar != null) {
            Path configuredPath = normalizePath(configuredBundledJar);
            if (isRuntimeJar(configuredPath)) {
                return configuredPath;
            }
            output.error("Ignoring missing bundled runtime jar: " + configuredPath);
            return null;
        }

        Path resolvedLocation = bundledRuntimeResolver.resolve();
        if (resolvedLocation == null) {
            return null;
        }

        Path normalizedLocation = resolvedLocation.toAbsolutePath().normalize();
        if (isBundledRuntimeJar(normalizedLocation)) {
            return normalizedLocation;
        }

        Path siblingJar = findBundledRuntimeJar(normalizedLocation);
        if (siblingJar != null) {
            return siblingJar;
        }

        Path nearbyJar = findBundledRuntimeJarInCommonRuntimeDirs(normalizedLocation);
        if (nearbyJar != null) {
            return nearbyJar;
        }
        return null;
    }

    private List<String> resolveForwardedJavaOptions(String[] args) {
        List<String> forwardedOptions = new ArrayList<>();
        Set<String> explicitSystemPropertyNames = new LinkedHashSet<>();
        for (String arg : args) {
            if (isSystemPropertyArg(arg)) {
                forwardedOptions.add(arg);
                explicitSystemPropertyNames.add(extractSystemPropertyName(arg));
            }
        }

        for (String propertyName : FORWARDED_SYSTEM_PROPERTIES) {
            boolean propertyExplicitlyConfigured = explicitSystemPropertyNames.contains(propertyName)
                    || extractPropertyArg(args, propertyName) != null;
            if (!propertyExplicitlyConfigured) {
                addForwardedSystemProperty(forwardedOptions, propertyName);
            }
        }
        return forwardedOptions;
    }

    private void addForwardedSystemProperty(List<String> forwardedOptions, String propertyName) {
        String propertyValue = propertyReader.get(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            forwardedOptions.add(buildSystemPropertyOption(propertyName, propertyValue));
        }
    }

    private List<String> resolveApplicationArgs(String[] args) {
        List<String> applicationArgs = new ArrayList<>();
        for (String arg : args) {
            if (isSystemPropertyArg(arg)) {
                continue;
            }
            applicationArgs.add(arg);
        }
        return applicationArgs;
    }

    private static boolean isSystemPropertyArg(String arg) {
        return arg != null && arg.startsWith("-D") && arg.indexOf('=') > 2;
    }

    private static String extractSystemPropertyName(String arg) {
        int equalsIndex = arg.indexOf('=');
        return arg.substring(2, equalsIndex);
    }

    private static String buildSystemPropertyOption(String propertyName, String value) {
        return "-D" + propertyName + "=" + value;
    }

    private Path findBundledRuntimeJarInCommonRuntimeDirs(Path resolvedLocation) {
        Path currentDirectory = Files.isDirectory(resolvedLocation)
                ? resolvedLocation
                : resolvedLocation.getParent();
        if (currentDirectory == null) {
            return null;
        }

        Set<Path> searchDirectories = new LinkedHashSet<>();
        addSearchDirectory(searchDirectories, currentDirectory.resolve(RUNTIME_DIR_NAME));
        addSearchDirectory(searchDirectories, currentDirectory.resolve(LIB_DIR_NAME).resolve(RUNTIME_DIR_NAME));

        Path parentDirectory = currentDirectory.getParent();
        if (parentDirectory != null) {
            addSearchDirectory(searchDirectories, parentDirectory);
            addSearchDirectory(searchDirectories, parentDirectory.resolve(RUNTIME_DIR_NAME));
            addSearchDirectory(searchDirectories, parentDirectory.resolve(LIB_DIR_NAME).resolve(RUNTIME_DIR_NAME));
        }

        for (Path searchDirectory : searchDirectories) {
            Path bundledJar = findBundledRuntimeJar(searchDirectory);
            if (bundledJar != null) {
                return bundledJar;
            }
        }
        return null;
    }

    private static void addSearchDirectory(Set<Path> searchDirectories, Path directory) {
        if (directory != null) {
            searchDirectories.add(directory.toAbsolutePath().normalize());
        }
    }

    private Path findBundledRuntimeJar(Path resolvedLocation) {
        Path searchDirectory = Files.isDirectory(resolvedLocation)
                ? resolvedLocation
                : resolvedLocation.getParent();
        if (searchDirectory == null || !Files.isDirectory(searchDirectory)) {
            return null;
        }

        try (Stream<Path> stream = Files.list(searchDirectory)) {
            return stream
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .filter(this::isBundledRuntimeJar)
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            output.error("Failed to inspect bundled runtime directory: " + safeMessage(e));
            return null;
        }
    }

    private boolean isBundledRuntimeJar(Path path) {
        if (!isRuntimeJar(path)) {
            return false;
        }
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return fileName.startsWith("bot-") && !fileName.endsWith(".jar.original");
    }

    private boolean isRuntimeJar(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".jar");
    }

    private static Path normalizePath(String value) {
        String expanded = value;
        if (expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        expanded = expanded.replace("${user.home}", System.getProperty("user.home"));
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    private static String extractPropertyArg(String[] args, String propertyName) {
        String prefix = "--" + propertyName + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length()).trim();
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private static String extractSystemPropertyArg(String[] args, String propertyName) {
        for (String arg : args) {
            if (!isSystemPropertyArg(arg)) {
                continue;
            }
            if (extractSystemPropertyName(arg).equals(propertyName)) {
                int equalsIndex = arg.indexOf('=');
                String value = arg.substring(equalsIndex + 1).trim();
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String resolveJavaCommand() {
        String executableName = isWindows() ? "java.exe" : "java";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", executableName);
        if (Files.isRegularFile(candidate)) {
            return candidate.toAbsolutePath().normalize().toString();
        }
        return executableName;
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unknown error";
        }
        return throwable.getMessage();
    }

    private void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown already started, hook removal is no longer possible.
        }
    }

    record LaunchCommand(List<String> command, String description) {
    }

    interface ProcessStarter {
        ChildProcess start(List<String> command) throws IOException;
    }

    interface ChildProcess {
        int waitFor() throws InterruptedException;

        void destroy();
    }

    interface EnvironmentReader {
        String get(String name);
    }

    interface PropertyReader {
        String get(String name);
    }

    interface LauncherOutput {
        void info(String message);

        void error(String message);
    }

    interface BundledRuntimeResolver {
        Path resolve();
    }

    private static final class DefaultProcessStarter implements ProcessStarter {

        @Override
        public ChildProcess start(List<String> command) throws IOException {
            Process process = new ProcessBuilder(command)
                    .inheritIO()
                    .start();
            return new ProcessChildProcess(process);
        }
    }

    private static final class ProcessChildProcess implements ChildProcess {

        private final Process process;

        private ProcessChildProcess(Process process) {
            this.process = process;
        }

        @Override
        public int waitFor() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public void destroy() {
            if (process.isAlive()) {
                process.destroy();
            }
        }
    }

    private static final class SystemEnvironmentReader implements EnvironmentReader {

        @Override
        public String get(String name) {
            return System.getenv(name);
        }
    }

    private static final class SystemPropertyReader implements PropertyReader {

        @Override
        public String get(String name) {
            return System.getProperty(name);
        }
    }

    private static final class ConsoleLauncherOutput implements LauncherOutput {

        @Override
        public void info(String message) {
            System.out.println("[launcher] " + message);
        }

        @Override
        public void error(String message) {
            System.err.println("[launcher] " + message);
        }
    }

    private static final class DefaultBundledRuntimeResolver implements BundledRuntimeResolver {

        @Override
        public Path resolve() {
            try {
                CodeSource codeSource = RuntimeLauncher.class.getProtectionDomain().getCodeSource();
                if (codeSource == null || codeSource.getLocation() == null) {
                    return null;
                }
                return Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            } catch (URISyntaxException | IllegalArgumentException _) {
                return null;
            }
        }
    }
}

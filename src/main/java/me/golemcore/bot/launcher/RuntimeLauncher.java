package me.golemcore.bot.launcher;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.Unmatched;

/**
 * Supervises the actual bot runtime so self-update can restart into a staged
 * jar instead of relaunching the immutable container classpath.
 *
 * <p>
 * The launcher always evaluates the best available runtime source in this
 * order:
 * <ol>
 * <li>a staged jar selected by {@code updates/current.txt}</li>
 * <li>a bundled runtime jar shipped next to the launcher</li>
 * <li>the image classpath produced by the container build</li>
 * </ol>
 *
 * <p>
 * The native app-image entry point uses picocli so the local launcher can
 * document its own options while forwarding unknown arguments to the spawned
 * Spring Boot runtime.
 */
public final class RuntimeLauncher {

    static final int RESTART_EXIT_CODE = 42;
    static final int CLI_ERROR_EXIT_CODE = 2;
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

    /**
     * System properties that must follow the child runtime even when the new
     * process is launched from a different entry point.
     */
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

    /**
     * Runs the launcher loop until the child runtime exits normally.
     *
     * <p>
     * When the child returns {@link #RESTART_EXIT_CODE}, the loop resolves the
     * runtime source again so newly staged jars become active immediately.
     *
     * @param args
     *            original launcher arguments
     * @return final child exit code or {@code 1} when the launcher itself fails
     */
    int run(String[] args) {
        ParseOutcome parseOutcome = parseArguments(args);
        if (parseOutcome.shouldExit()) {
            return parseOutcome.exitCode();
        }

        LauncherArguments launcherArguments = parseOutcome.launcherArguments();
        while (true) {
            LaunchCommand launchCommand = resolveLaunchCommand(launcherArguments);
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

    /**
     * Parses launcher-specific command-line options with picocli while leaving
     * unknown arguments untouched for Spring Boot.
     *
     * @param args
     *            original launcher arguments
     * @return parse outcome containing either normalized launcher arguments or an
     *         exit decision for help / parse failures
     */
    ParseOutcome parseArguments(String[] args) {
        LauncherCliArguments cliArguments = new LauncherCliArguments();
        CommandLine commandLine = createCommandLine(cliArguments);
        CommandLine.ParseResult parseResult;
        try {
            parseResult = commandLine.parseArgs(args);
        } catch (CommandLine.ParameterException e) {
            output.error(e.getMessage());
            emitUsage(e.getCommandLine(), true);
            return ParseOutcome.exit(CLI_ERROR_EXIT_CODE);
        }

        if (parseResult.isUsageHelpRequested()) {
            emitUsage(commandLine, false);
            return ParseOutcome.exit(0);
        }
        if (parseResult.isVersionHelpRequested()) {
            emitVersion();
            return ParseOutcome.exit(0);
        }
        return ParseOutcome.success(cliArguments.toLauncherArguments());
    }

    /**
     * Builds the exact child-process command for the current launcher cycle.
     *
     * <p>
     * The launcher prefers a staged update, then an explicitly or implicitly
     * discovered bundled jar, and only falls back to the image classpath when no
     * jar-based runtime is available.
     *
     * @param args
     *            original launcher arguments
     * @return immutable launch command description
     */
    LaunchCommand resolveLaunchCommand(String[] args) {
        return resolveLaunchCommand(parseArguments(args).launcherArguments());
    }

    private LaunchCommand resolveLaunchCommand(LauncherArguments launcherArguments) {
        Path currentJar = resolveCurrentJar(launcherArguments);
        List<String> command = new ArrayList<>();
        command.add(javaCommand);
        command.addAll(resolveForwardedJavaOptions(launcherArguments));

        if (currentJar != null) {
            command.add("-jar");
            command.add(currentJar.toString());
            command.addAll(launcherArguments.applicationArguments());
            return new LaunchCommand(List.copyOf(command), "updated runtime " + currentJar);
        }

        Path bundledJar = resolveBundledJar(launcherArguments);
        if (bundledJar != null) {
            command.add("-jar");
            command.add(bundledJar.toString());
            command.addAll(launcherArguments.applicationArguments());
            return new LaunchCommand(List.copyOf(command), "bundled runtime jar " + bundledJar);
        }

        command.add("-cp");
        command.add("@" + JIB_CLASSPATH_FILE);
        command.add(APPLICATION_MAIN_CLASS);
        command.addAll(launcherArguments.applicationArguments());
        return new LaunchCommand(List.copyOf(command), "bundled runtime from image classpath");
    }

    /**
     * Resolves the staged runtime jar pointed to by {@code current.txt}.
     *
     * <p>
     * The marker content is validated aggressively so a corrupted or hostile marker
     * cannot escape the updates directory via path traversal.
     *
     * @param args
     *            original launcher arguments
     * @return staged runtime jar path, or {@code null} when no valid staged jar
     *         exists
     */
    Path resolveCurrentJar(String[] args) {
        return resolveCurrentJar(parseArguments(args).launcherArguments());
    }

    private Path resolveCurrentJar(LauncherArguments launcherArguments) {
        Path updatesDir = resolveUpdatesDir(launcherArguments);
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

    /**
     * Resolves the updates directory using the same precedence as the runtime.
     *
     * <p>
     * Explicit launcher options win over JVM properties, which win over the ambient
     * environment. When no explicit updates path exists, the launcher derives it
     * from the storage root and finally falls back to the default workspace
     * location under the user home directory.
     *
     * @param args
     *            original launcher arguments
     * @return normalized updates directory path
     */
    Path resolveUpdatesDir(String[] args) {
        return resolveUpdatesDir(parseArguments(args).launcherArguments());
    }

    private Path resolveUpdatesDir(LauncherArguments launcherArguments) {
        String configuredUpdatesPath = firstNonBlank(
                launcherArguments.updatesPath(),
                propertyReader.get(UPDATE_PATH_PROPERTY),
                environmentReader.get(UPDATE_PATH_ENV));
        if (configuredUpdatesPath != null) {
            return normalizePath(configuredUpdatesPath);
        }

        String storageBasePath = firstNonBlank(
                launcherArguments.storagePath(),
                propertyReader.get(STORAGE_PATH_PROPERTY),
                environmentReader.get(STORAGE_PATH_ENV));
        if (storageBasePath != null) {
            return normalizePath(storageBasePath).resolve("updates").normalize();
        }

        return Path.of(System.getProperty("user.home"), ".golemcore", "workspace", "updates")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Resolves a bundled runtime jar shipped with the launcher itself.
     *
     * <p>
     * An explicit launcher option or system property wins first. Otherwise the
     * launcher inspects the code-source location and common app-image directories
     * to support both direct jars and native distributions produced by
     * {@code jpackage}.
     *
     * @return bundled runtime jar path, or {@code null} when the launcher should
     *         use classpath mode
     */
    Path resolveBundledJar() {
        return resolveBundledJar(LauncherArguments.empty());
    }

    private Path resolveBundledJar(LauncherArguments launcherArguments) {
        String configuredBundledJar = firstNonBlank(
                launcherArguments.bundledJar(),
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

    /**
     * Collects JVM options that must be preserved across launcher restarts.
     *
     * <p>
     * Explicit JVM options from picocli's {@code -J/--java-option} and pass-through
     * {@code -D...} arguments are forwarded verbatim. For a small set of critical
     * properties the launcher also injects values from the parsed launcher options
     * or the current JVM when they were not supplied explicitly.
     *
     * @param launcherArguments
     *            normalized launcher arguments
     * @return JVM options for the child process
     */
    private List<String> resolveForwardedJavaOptions(LauncherArguments launcherArguments) {
        List<String> forwardedOptions = new ArrayList<>(launcherArguments.explicitJavaOptions());
        Set<String> explicitSystemPropertyNames = extractSystemPropertyNames(forwardedOptions);

        addExplicitSystemProperty(forwardedOptions, explicitSystemPropertyNames,
                STORAGE_PATH_PROPERTY, launcherArguments.storagePath());
        addExplicitSystemProperty(forwardedOptions, explicitSystemPropertyNames,
                UPDATE_PATH_PROPERTY, launcherArguments.updatesPath());
        addExplicitSystemProperty(forwardedOptions, explicitSystemPropertyNames,
                SERVER_PORT_PROPERTY, launcherArguments.serverPort());

        for (String propertyName : FORWARDED_SYSTEM_PROPERTIES) {
            if (!explicitSystemPropertyNames.contains(propertyName)) {
                addForwardedSystemProperty(forwardedOptions, propertyName);
            }
        }
        return List.copyOf(forwardedOptions);
    }

    private Set<String> extractSystemPropertyNames(List<String> explicitJavaOptions) {
        Set<String> explicitSystemPropertyNames = new LinkedHashSet<>();
        for (String explicitJavaOption : explicitJavaOptions) {
            if (isSystemPropertyArg(explicitJavaOption)) {
                explicitSystemPropertyNames.add(extractSystemPropertyName(explicitJavaOption));
            }
        }
        return explicitSystemPropertyNames;
    }

    private void addExplicitSystemProperty(
            List<String> forwardedOptions,
            Set<String> explicitSystemPropertyNames,
            String propertyName,
            String propertyValue) {
        String normalizedValue = trimToNull(propertyValue);
        if (normalizedValue == null || explicitSystemPropertyNames.contains(propertyName)) {
            return;
        }
        forwardedOptions.add(buildSystemPropertyOption(propertyName, normalizedValue));
        explicitSystemPropertyNames.add(propertyName);
    }

    private void addForwardedSystemProperty(List<String> forwardedOptions, String propertyName) {
        String propertyValue = propertyReader.get(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            forwardedOptions.add(buildSystemPropertyOption(propertyName, propertyValue));
        }
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

    /**
     * Searches app-image layouts that place the runtime jar in a nearby
     * {@code runtime} directory instead of next to the launcher artifact.
     *
     * <p>
     * This covers the directory shapes produced by local native bundles on Linux
     * and macOS.
     *
     * @param resolvedLocation
     *            launcher code-source location
     * @return discovered bundled runtime jar, or {@code null} when none exists
     *         nearby
     */
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

    /**
     * Scans a single directory for a bundled runtime jar.
     *
     * @param resolvedLocation
     *            directory or file near the bundled runtime
     * @return matching runtime jar, or {@code null} when no candidate is present
     */
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

    /**
     * Normalizes user-provided paths and expands the home placeholders commonly
     * produced by local launchers and shell invocations.
     *
     * @param value
     *            raw path-like value
     * @return normalized absolute path
     */
    private static Path normalizePath(String value) {
        String expanded = value;
        if (expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        expanded = expanded.replace("${user.home}", System.getProperty("user.home"));
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    /**
     * Removes the per-run shutdown hook once the child process has finished so
     * repeated restarts do not accumulate stale hooks.
     *
     * @param shutdownHook
     *            hook installed for the current child process
     */
    private void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown already started, hook removal is no longer possible.
        }
    }

    private CommandLine createCommandLine(LauncherCliArguments cliArguments) {
        CommandLine commandLine = new CommandLine(cliArguments);
        commandLine.setUnmatchedArgumentsAllowed(true);
        return commandLine;
    }

    private void emitUsage(CommandLine commandLine, boolean error) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        commandLine.usage(printWriter);
        printWriter.flush();
        emitCommandLineOutput(stringWriter.toString(), error);
    }

    private void emitVersion() {
        RuntimeLauncherVersionProvider versionProvider = new RuntimeLauncherVersionProvider();
        try {
            for (String line : versionProvider.getVersion()) {
                output.info(line);
            }
        } catch (Exception e) {
            output.info("golemcore-bot native launcher");
        }
    }

    private void emitCommandLineOutput(String text, boolean error) {
        String normalizedText = text.replace("\r\n", "\n");
        String[] lines = normalizedText.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index == lines.length - 1 && lines[index].isEmpty()) {
                continue;
            }
            if (error) {
                output.error(lines[index]);
            } else {
                output.info(lines[index]);
            }
        }
    }

    /**
     * Immutable child-process launch descriptor.
     *
     * @param command
     *            exact process command line
     * @param description
     *            human-readable command source description for logs
     */
    record LaunchCommand(List<String> command, String description) {
    }

    record LauncherArguments(
            String storagePath,
            String updatesPath,
            String bundledJar,
            String serverPort,
            List<String> explicitJavaOptions,
            List<String> applicationArguments) {

        private static LauncherArguments empty() {
            return new LauncherArguments(null, null, null, null, List.of(), List.of());
        }
    }

    record ParseOutcome(LauncherArguments launcherArguments, int exitCode, boolean shouldExit) {

        private static ParseOutcome success(LauncherArguments launcherArguments) {
            return new ParseOutcome(launcherArguments, 0, false);
        }

        private static ParseOutcome exit(int exitCode) {
            return new ParseOutcome(LauncherArguments.empty(), exitCode, true);
        }
    }

    /**
     * Picocli-backed argument model for the native app-image launcher.
     */
    @Command(name = "golemcore-bot", mixinStandardHelpOptions = true, versionProvider = RuntimeLauncherVersionProvider.class, sortOptions = false, description = {
            "Restart-aware native launcher for the GolemCore Bot app-image.",
            "Launcher-specific options are handled locally; all unknown arguments",
            "are forwarded to the spawned Spring Boot runtime."
    }, footer = {
            "",
            "Examples:",
            "  golemcore-bot --server-port=9090",
            "  golemcore-bot -J=-Xmx1g --server-port=9090",
            "  golemcore-bot --storage-path=/srv/golemcore/workspace --updates-path=/srv/golemcore/updates",
            "  golemcore-bot -- --spring.profiles.active=prod"
    })
    private static final class LauncherCliArguments {

        @Option(names = { "--storage-path",
                "--bot.storage.local.base-path" }, description = "Workspace storage root. Also forwarded to the runtime as -D"
                        + STORAGE_PATH_PROPERTY + ".")
        private String storagePath;

        @Option(names = { "--updates-path",
                "--bot.update.updates-path" }, description = "Updates directory. Also forwarded to the runtime as -D"
                        + UPDATE_PATH_PROPERTY + ".")
        private String updatesPath;

        @Option(names = { "--bundled-jar",
                "--golemcore.launcher.bundled-jar" }, description = "Bundled runtime jar to prefer before the classpath fallback.")
        private String bundledJar;

        @Option(names = { "--server-port",
                "--server.port" }, description = "HTTP port for the spawned runtime. Also forwarded as -D"
                        + SERVER_PORT_PROPERTY + ".")
        private String serverPort;

        @Option(names = { "-J",
                "--java-option" }, paramLabel = "<jvm-option>", description = "Additional JVM option forwarded to the spawned runtime. Repeatable.")
        private List<String> javaOptions = new ArrayList<>();

        @Unmatched
        private List<String> passThroughArguments = new ArrayList<>();

        private LauncherArguments toLauncherArguments() {
            List<String> explicitJavaOptions = new ArrayList<>(javaOptions);
            List<String> applicationArguments = new ArrayList<>();
            for (String passThroughArgument : passThroughArguments) {
                if (isSystemPropertyArg(passThroughArgument)) {
                    explicitJavaOptions.add(passThroughArgument);
                } else {
                    applicationArguments.add(passThroughArgument);
                }
            }
            return new LauncherArguments(
                    trimToNull(storagePath),
                    trimToNull(updatesPath),
                    trimToNull(bundledJar),
                    trimToNull(serverPort),
                    List.copyOf(explicitJavaOptions),
                    List.copyOf(applicationArguments));
        }
    }

    private static final class RuntimeLauncherVersionProvider implements IVersionProvider {

        @Override
        public String[] getVersion() {
            String implementationVersion = RuntimeLauncher.class.getPackage().getImplementationVersion();
            if (implementationVersion == null || implementationVersion.isBlank()) {
                implementationVersion = "development";
            }
            return new String[] { "golemcore-bot native launcher " + implementationVersion };
        }
    }

    /**
     * Starts the child runtime process.
     */
    interface ProcessStarter {
        ChildProcess start(List<String> command) throws IOException;
    }

    /**
     * Minimal process abstraction that keeps launcher tests deterministic.
     */
    interface ChildProcess {
        int waitFor() throws InterruptedException;

        void destroy();
    }

    /**
     * Reads environment variables from the current execution context.
     */
    interface EnvironmentReader {
        String get(String name);
    }

    /**
     * Reads JVM system properties from the current execution context.
     */
    interface PropertyReader {
        String get(String name);
    }

    /**
     * Writes launcher diagnostics for operators.
     */
    interface LauncherOutput {
        void info(String message);

        void error(String message);
    }

    /**
     * Resolves the launcher code-source or equivalent packaged runtime origin.
     */
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
            } catch (URISyntaxException | IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}

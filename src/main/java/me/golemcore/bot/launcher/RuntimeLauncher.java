package me.golemcore.bot.launcher;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.golemcore.bot.domain.service.RuntimeVersionSupport;
import picocli.CommandLine;

/**
 * Supervises the actual bot runtime so self-update can restart into a staged
 * jar instead of relaunching the immutable container classpath.
 *
 * <p>
 * The launcher always evaluates the best available runtime source in this
 * order:
 * <ol>
 * <li>a staged jar selected by {@code updates/current.txt}, unless the bundled
 * runtime is strictly newer</li>
 * <li>a bundled runtime jar shipped next to the launcher or image</li>
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
    static final String BUILD_INFO_RESOURCE = "META-INF/build-info.properties";

    private final String javaCommand;
    private final ProcessStarter processStarter;
    private final LauncherOutput output;
    private final CurrentRuntimeResolver currentRuntimeResolver;
    private final BundledRuntimeLocator bundledRuntimeLocator;
    private final ForwardedJavaOptionsResolver javaOptionsResolver;

    public RuntimeLauncher() {
        this(resolveJavaCommand(), new DefaultProcessStarter(), new SystemEnvironmentReader(),
                new SystemPropertyReader(), new ConsoleLauncherOutput(), new DefaultBundledRuntimeResolver(),
                new ClasspathRuntimeVersionReader(), new RuntimeVersionSupport());
    }

    RuntimeLauncher(
            String javaCommand,
            ProcessStarter processStarter,
            EnvironmentReader environmentReader,
            PropertyReader propertyReader,
            LauncherOutput output,
            BundledRuntimeResolver bundledRuntimeResolver) {
        this(javaCommand, processStarter, environmentReader, propertyReader, output, bundledRuntimeResolver,
                new ClasspathRuntimeVersionReader(), new RuntimeVersionSupport());
    }

    RuntimeLauncher(
            String javaCommand,
            ProcessStarter processStarter,
            EnvironmentReader environmentReader,
            PropertyReader propertyReader,
            LauncherOutput output,
            BundledRuntimeResolver bundledRuntimeResolver,
            RuntimeVersionReader runtimeVersionReader) {
        this(javaCommand, processStarter, environmentReader, propertyReader, output, bundledRuntimeResolver,
                runtimeVersionReader, new RuntimeVersionSupport());
    }

    RuntimeLauncher(
            String javaCommand,
            ProcessStarter processStarter,
            EnvironmentReader environmentReader,
            PropertyReader propertyReader,
            LauncherOutput output,
            RuntimeVersionReader runtimeVersionReader) {
        this(javaCommand, processStarter, environmentReader, propertyReader, output,
                new DefaultBundledRuntimeResolver(), runtimeVersionReader, new RuntimeVersionSupport());
    }

    RuntimeLauncher(
            String javaCommand,
            ProcessStarter processStarter,
            EnvironmentReader environmentReader,
            PropertyReader propertyReader,
            LauncherOutput output,
            BundledRuntimeResolver bundledRuntimeResolver,
            RuntimeVersionReader runtimeVersionReader,
            RuntimeVersionSupport runtimeVersionSupport) {
        this.javaCommand = javaCommand;
        this.processStarter = processStarter;
        this.output = output;
        this.currentRuntimeResolver = new CurrentRuntimeResolver(
                environmentReader,
                propertyReader,
                output,
                runtimeVersionReader,
                runtimeVersionSupport);
        this.bundledRuntimeLocator = new BundledRuntimeLocator(
                environmentReader,
                propertyReader,
                output,
                bundledRuntimeResolver);
        this.javaOptionsResolver = new ForwardedJavaOptionsResolver(propertyReader);
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
                output.error("Failed to start runtime: " + LauncherText.safeMessage(e));
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
        Path currentJar = currentRuntimeResolver.resolveCurrentJar(launcherArguments);
        List<String> command = new ArrayList<>();
        command.add(javaCommand);
        command.addAll(javaOptionsResolver.resolve(launcherArguments));

        if (currentJar != null) {
            command.add("-jar");
            command.add(currentJar.toString());
            command.addAll(launcherArguments.applicationArguments());
            return new LaunchCommand(List.copyOf(command), "updated runtime " + currentJar);
        }

        Path bundledJar = bundledRuntimeLocator.resolve(launcherArguments);
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
     * @param args
     *            original launcher arguments
     * @return staged runtime jar path, or {@code null} when no valid staged jar
     *         exists
     */
    Path resolveCurrentJar(String[] args) {
        return currentRuntimeResolver.resolveCurrentJar(parseArguments(args).launcherArguments());
    }

    /**
     * Resolves the updates directory using the same precedence as the runtime.
     *
     * @param args
     *            original launcher arguments
     * @return normalized updates directory path
     */
    Path resolveUpdatesDir(String[] args) {
        return currentRuntimeResolver.resolveUpdatesDir(parseArguments(args).launcherArguments());
    }

    /**
     * Resolves a bundled runtime jar shipped with the launcher itself.
     *
     * @return bundled runtime jar path, or {@code null} when the launcher should
     *         use classpath mode
     */
    Path resolveBundledJar() {
        return bundledRuntimeLocator.resolve(LauncherArguments.empty());
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
        for (String line : versionProvider.getVersion()) {
            output.info(line);
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
}

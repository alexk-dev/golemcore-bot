package me.golemcore.bot.launcher;

import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Unmatched;

/**
 * Picocli-backed argument model for the native app-image launcher.
 */
@Command(name = "golemcore-bot", mixinStandardHelpOptions = true, versionProvider = RuntimeLauncherVersionProvider.class, sortOptions = false, description = {
        "Restart-aware native launcher for the GolemCore Bot app-image.",
        "A command is required. Use `web` to start the bundled web runtime."
}, footer = {
        "",
        "Examples:",
        "  golemcore-bot web --port=8080 --hostname=0.0.0.0",
        "  golemcore-bot web -J=-Xmx1g --port=9090",
        "  golemcore-bot --storage-path=/srv/golemcore/workspace web --updates-path=/srv/golemcore/updates",
        "  golemcore-bot web -- --spring.profiles.active=prod"
})
final class LauncherCliArguments {

    private final WebCommand webSubcommand = new WebCommand();

    @Option(names = { "--storage-path",
            "--bot.storage.local.base-path" }, description = "Workspace storage root. Also forwarded to the runtime as -D"
                    + RuntimeLauncher.STORAGE_PATH_PROPERTY + ".")
    private String storagePath;

    @Option(names = { "--updates-path",
            "--bot.update.updates-path" }, description = "Updates directory. Also forwarded to the runtime as -D"
                    + RuntimeLauncher.UPDATE_PATH_PROPERTY + ".")
    private String updatesPath;

    @Option(names = { "--bundled-jar",
            "--golemcore.launcher.bundled-jar" }, description = "Bundled runtime jar to prefer before the classpath fallback.")
    private String bundledJar;

    @Option(names = { "-J",
            "--java-option" }, paramLabel = "<jvm-option>", description = "Additional JVM option forwarded to the spawned runtime. Repeatable.")
    private List<String> javaOptions = new ArrayList<>();

    WebCommand webCommand() {
        return webSubcommand;
    }

    LauncherArguments toLauncherArguments() {
        List<String> explicitJavaOptions = new ArrayList<>(javaOptions);
        explicitJavaOptions.addAll(webSubcommand.javaOptions);
        List<String> applicationArguments = new ArrayList<>();
        for (String passThroughArgument : webSubcommand.passThroughArguments) {
            if (SystemPropertyOption.isSystemPropertyArg(passThroughArgument)) {
                explicitJavaOptions.add(passThroughArgument);
            } else {
                applicationArguments.add(passThroughArgument);
            }
        }
        return new LauncherArguments(
                LauncherText.trimToNull(LauncherText.firstNonBlank(webSubcommand.storagePath, storagePath)),
                LauncherText.trimToNull(LauncherText.firstNonBlank(webSubcommand.updatesPath, updatesPath)),
                LauncherText.trimToNull(LauncherText.firstNonBlank(webSubcommand.bundledJar, bundledJar)),
                LauncherText.trimToNull(webSubcommand.serverPort),
                LauncherText.trimToNull(webSubcommand.serverAddress),
                List.copyOf(explicitJavaOptions),
                List.copyOf(applicationArguments));
    }

    @Command(name = "web", mixinStandardHelpOptions = true, sortOptions = false, description = {
            "Start the bundled Spring Boot web runtime.",
            "Launcher-specific options are handled locally; all unknown arguments",
            "are forwarded to the spawned Spring Boot runtime."
    })
    static final class WebCommand {

        @Option(names = { "--storage-path",
                "--bot.storage.local.base-path" }, description = "Workspace storage root. Also forwarded to the runtime as -D"
                        + RuntimeLauncher.STORAGE_PATH_PROPERTY + ".")
        private String storagePath;

        @Option(names = { "--updates-path",
                "--bot.update.updates-path" }, description = "Updates directory. Also forwarded to the runtime as -D"
                        + RuntimeLauncher.UPDATE_PATH_PROPERTY + ".")
        private String updatesPath;

        @Option(names = { "--bundled-jar",
                "--golemcore.launcher.bundled-jar" }, description = "Bundled runtime jar to prefer before the classpath fallback.")
        private String bundledJar;

        @Option(names = { "--port",
                "--server-port",
                "--server.port" }, description = "HTTP port for the spawned runtime. Also forwarded as -D"
                        + RuntimeLauncher.SERVER_PORT_PROPERTY + ".")
        private String serverPort;

        @Option(names = { "--hostname",
                "--server-address",
                "--server.address" }, description = "HTTP bind address for the spawned runtime. Also forwarded as -D"
                        + RuntimeLauncher.SERVER_ADDRESS_PROPERTY + ".")
        private String serverAddress;

        @Option(names = { "-J",
                "--java-option" }, paramLabel = "<jvm-option>", description = "Additional JVM option forwarded to the spawned runtime. Repeatable.")
        private List<String> javaOptions = new ArrayList<>();

        @Unmatched
        private List<String> passThroughArguments = new ArrayList<>();
    }
}

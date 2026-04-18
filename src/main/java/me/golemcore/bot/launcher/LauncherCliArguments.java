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
final class LauncherCliArguments {

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

    @Option(names = { "--server-port",
            "--server.port" }, description = "HTTP port for the spawned runtime. Also forwarded as -D"
                    + RuntimeLauncher.SERVER_PORT_PROPERTY + ".")
    private String serverPort;

    @Option(names = { "-J",
            "--java-option" }, paramLabel = "<jvm-option>", description = "Additional JVM option forwarded to the spawned runtime. Repeatable.")
    private List<String> javaOptions = new ArrayList<>();

    @Unmatched
    private List<String> passThroughArguments = new ArrayList<>();

    LauncherArguments toLauncherArguments() {
        List<String> explicitJavaOptions = new ArrayList<>(javaOptions);
        List<String> applicationArguments = new ArrayList<>();
        for (String passThroughArgument : passThroughArguments) {
            if (SystemPropertyOption.isSystemPropertyArg(passThroughArgument)) {
                explicitJavaOptions.add(passThroughArgument);
            } else {
                applicationArguments.add(passThroughArgument);
            }
        }
        return new LauncherArguments(
                LauncherText.trimToNull(storagePath),
                LauncherText.trimToNull(updatesPath),
                LauncherText.trimToNull(bundledJar),
                LauncherText.trimToNull(serverPort),
                List.copyOf(explicitJavaOptions),
                List.copyOf(applicationArguments));
    }
}

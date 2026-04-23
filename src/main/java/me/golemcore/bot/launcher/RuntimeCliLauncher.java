package me.golemcore.bot.launcher;

/**
 * Strict CLI entrypoint for native app-image and new Docker images.
 */
public final class RuntimeCliLauncher {

    private RuntimeCliLauncher() {
    }

    public static void main(String[] args) {
        RuntimeLauncher launcher = new RuntimeLauncher();
        int exitCode = launcher.run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}

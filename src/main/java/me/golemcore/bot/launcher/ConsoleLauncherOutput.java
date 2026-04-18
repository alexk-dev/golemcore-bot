package me.golemcore.bot.launcher;

final class ConsoleLauncherOutput implements LauncherOutput {

    @Override
    public void info(String message) {
        System.out.println("[launcher] " + message);
    }

    @Override
    public void error(String message) {
        System.err.println("[launcher] " + message);
    }
}

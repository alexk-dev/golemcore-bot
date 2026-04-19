package me.golemcore.bot.launcher;

final class SystemEnvironmentReader implements EnvironmentReader {

    @Override
    public String get(String name) {
        return System.getenv(name);
    }
}

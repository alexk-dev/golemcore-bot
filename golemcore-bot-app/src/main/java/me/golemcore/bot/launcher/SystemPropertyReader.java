package me.golemcore.bot.launcher;

final class SystemPropertyReader implements PropertyReader {

    @Override
    public String get(String name) {
        return System.getProperty(name);
    }
}

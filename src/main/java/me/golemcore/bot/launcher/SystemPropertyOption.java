package me.golemcore.bot.launcher;

final class SystemPropertyOption {

    private SystemPropertyOption() {
    }

    static boolean isSystemPropertyArg(String arg) {
        return arg != null && arg.startsWith("-D") && arg.indexOf('=') > 2;
    }

    static String extractSystemPropertyName(String arg) {
        int equalsIndex = arg.indexOf('=');
        return arg.substring(2, equalsIndex);
    }

    static String build(String propertyName, String value) {
        return "-D" + propertyName + "=" + value;
    }
}

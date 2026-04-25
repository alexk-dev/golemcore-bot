package me.golemcore.bot.launcher;

final class SystemPropertyOption {

    private SystemPropertyOption() {
    }

    static boolean isSystemPropertyArg(String arg) {
        if (arg == null || !arg.startsWith("-D") || arg.length() <= 2) {
            return false;
        }
        int equalsIndex = arg.indexOf('=');
        return equalsIndex == -1 || equalsIndex > 2;
    }

    static String extractSystemPropertyName(String arg) {
        int equalsIndex = arg.indexOf('=');
        if (equalsIndex < 0) {
            return arg.substring(2);
        }
        return arg.substring(2, equalsIndex);
    }

    static String build(String propertyName, String value) {
        return "-D" + propertyName + "=" + value;
    }
}

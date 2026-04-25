package me.golemcore.bot.launcher;

import java.nio.file.Path;

final class LauncherPaths {

    private LauncherPaths() {
    }

    static Path normalizePath(String value) {
        String expanded = value;
        if (expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        expanded = expanded.replace("${user.home}", System.getProperty("user.home"));
        return Path.of(expanded).toAbsolutePath().normalize();
    }
}

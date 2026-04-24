package me.golemcore.bot.launcher;

/**
 * Reads the version baked into the bundled image runtime.
 */
interface RuntimeVersionReader {

    String currentVersion();
}

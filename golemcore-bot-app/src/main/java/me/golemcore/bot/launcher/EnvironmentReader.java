package me.golemcore.bot.launcher;

/**
 * Reads environment variables from the current execution context.
 */
interface EnvironmentReader {

    String get(String name);
}

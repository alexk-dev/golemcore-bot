package me.golemcore.bot.launcher;

/**
 * Reads JVM system properties from the current execution context.
 */
interface PropertyReader {

    String get(String name);
}

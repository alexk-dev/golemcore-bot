package me.golemcore.bot.launcher;

/**
 * Writes launcher diagnostics for operators.
 */
interface LauncherOutput {

    void info(String message);

    void error(String message);
}

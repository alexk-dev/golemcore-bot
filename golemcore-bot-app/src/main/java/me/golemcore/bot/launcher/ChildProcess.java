package me.golemcore.bot.launcher;

/**
 * Minimal process abstraction that keeps launcher tests deterministic.
 */
interface ChildProcess {

    int waitFor() throws InterruptedException;

    void destroy();
}

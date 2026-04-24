package me.golemcore.bot.launcher;

import java.nio.file.Path;

/**
 * Resolves the launcher code-source or equivalent packaged runtime origin.
 */
interface BundledRuntimeResolver {

    Path resolve();
}
